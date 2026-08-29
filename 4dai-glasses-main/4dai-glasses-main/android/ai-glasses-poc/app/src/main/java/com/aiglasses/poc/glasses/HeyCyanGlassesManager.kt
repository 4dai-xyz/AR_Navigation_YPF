package com.aiglasses.poc.glasses

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.oudmon.wifi.GlassesControl
import com.oudmon.wifi.bean.GlassAlbumEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GlassesDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

data class GlassesMediaItem(
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val lastModified: Long,
)

data class GlassesDebugState(
    val initialized: Boolean = false,
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val selectedDevice: GlassesDevice? = null,
    val devices: List<GlassesDevice> = emptyList(),
    val localMedia: List<GlassesMediaItem> = emptyList(),
    val latestPreviewPath: String? = null,
    val latestThumbnailPath: String? = null,
    val status: String = "HeyCyan SDK 未初始化",
    val batterySummary: String = "电量：未同步",
    val deviceInfoSummary: String = "设备信息：未同步",
    val mediaCountSummary: String = "眼镜媒体：未同步",
    val rememberedDeviceSummary: String = "上次设备：未记录",
    val videoRecording: Boolean = false,
    val logs: List<String> = emptyList(),
)

object HeyCyanGlassesManager {
    private const val TAG = "HeyCyanDebug"
    private const val SCAN_TIMEOUT_MS = 15_000L
    private const val AUTO_CONNECT_RETRY_DELAY_MS = 5_000L
    private const val THUMBNAIL_TIMEOUT_MS = 8_000L
    private const val ALBUM_SYNC_TIMEOUT_MS = 60_000L
    private const val ALBUM_CONFIG_RETRY_DELAY_MS = 2_500L
    private const val MAX_ALBUM_CONFIG_RETRY_COUNT = 2
    private const val QUEUED_THUMBNAIL_DELAY_MS = 1_200L
    private const val POST_CONNECT_SYNC_DELAY_MS = 500L
    private const val POST_CONNECT_SYNC_STEP_MS = 450L
    private const val NOTIFY_TYPE_THUMBNAIL_READY = 0x02
    private const val NOTIFY_TYPE_MEDIA_READY = 0x0B
    private const val GLASS_WORK_RECORDING = 2
    private const val GLASS_WORK_TRANSFER = 4
    private const val FILE_ERROR_TYPE_ALBUM_CONFIG = 1
    private const val DOWNLOAD_ERROR_UNKNOWN = 0
    private const val MAX_LOG_LINES = 80
    private const val PREFS_NAME = "heycyan_glasses_debug"
    private const val PREF_LAST_DEVICE_NAME = "last_device_name"
    private const val PREF_LAST_DEVICE_ADDRESS = "last_device_address"
    private val HEYCYAN_DEVICE_PREFIXES = listOf("W630_", "W631_", "W632_", "AM01", "O_", "Q_")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(GlassesDebugState())
    val state: StateFlow<GlassesDebugState> = _state.asStateFlow()

    private var application: Application? = null
    private var localReceiver: QCBluetoothCallbackCloneReceiver? = null
    private var deviceReceiver: BroadcastReceiver? = null
    private var deviceNotifyListenerRegistered = false
    private var thumbnailBuffer = ByteArrayOutputStream()
    private var thumbnailRequestActive = false
    private var thumbnailNotifyPending = false
    private var albumSyncActive = false
    private var albumDownloadedCount = 0
    private var albumConfigRetryCount = 0
    private var thumbnailQueuedAfterAlbumSync = false
    private var postConnectAutoSyncScheduled = false
    private var autoConnectActive = false
    private val sessionLogs = mutableListOf<String>()
    private val timestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val scanTimeout = Runnable {
        handleScanTimeout()
    }

    private val autoConnectRetry = Runnable {
        val app = application ?: return@Runnable
        if (!autoConnectActive || _state.value.connected || _state.value.scanning) return@Runnable
        startAutoConnectScan(app, reason = "retry")
    }

    private val thumbnailTimeout = Runnable {
        if (!thumbnailRequestActive) return@Runnable
        val bytes = thumbnailBuffer.size()
        thumbnailRequestActive = false
        if (bytes > 0) {
            updateState { it.copy(status = "缩略图只收到部分数据（$bytes bytes），未保存；请重试或使用同步眼镜相册") }
            appendLog("thumbnail_timeout partial_bytes=$bytes")
        } else {
            refreshLocalMedia()
            val latestImage = _state.value.localMedia.firstOrNull { it.mimeType.startsWith("image/") }
            updateState {
                it.copy(
                    latestPreviewPath = latestImage?.filePath ?: it.latestPreviewPath,
                    status = "未收到缩略图回传；已刷新本地相册，可使用同步眼镜相册查看完整照片",
                )
            }
            appendLog("thumbnail_timeout no_data")
        }
    }

    private val thumbnailNotifyTimeout = Runnable {
        if (!thumbnailNotifyPending) return@Runnable
        thumbnailNotifyPending = false
        updateState { it.copy(status = "未收到眼镜缩略图事件；请确认眼镜已完成拍摄/AI识别后重试") }
        appendLog("thumbnail_notify_timeout")
    }

    private val albumSyncTimeout = Runnable {
        if (!albumSyncActive) return@Runnable
        albumSyncActive = false
        mainHandler.removeCallbacks(albumConfigRetry)
        refreshLocalMedia()
        updateState {
            it.copy(
                status = if (albumDownloadedCount > 0) {
                    "相册同步未收到完成回调，已刷新本地文件；如仍缺失请重试同步"
                } else {
                    "未收到眼镜 Wi-Fi 文件传输回调；请确认眼镜 Wi-Fi/权限/距离后重试同步"
                },
            )
        }
        appendLog("album_sync_timeout downloaded=$albumDownloadedCount")
        scheduleQueuedThumbnailIfNeeded(reason = "album_sync_timeout")
    }

    private val albumConfigRetry = Runnable {
        val app = application ?: return@Runnable
        if (!albumSyncActive) return@Runnable
        runCatching {
            startAlbumImport(app, reason = "config_retry_$albumConfigRetryCount")
        }.onFailure { throwable ->
            finishAlbumSync()
            updateState { it.copy(status = "相册目录重试失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("album_config_retry_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private val queuedThumbnailRequest = Runnable {
        if (!thumbnailQueuedAfterAlbumSync || albumSyncActive) return@Runnable
        thumbnailQueuedAfterAlbumSync = false
        appendLog("thumbnail_queued_run")
        requestThumbnail()
    }

    private val postConnectSyncDeviceInfo = Runnable {
        if (!isReadyForPostConnectAutoSync()) return@Runnable
        appendLog("post_connect_sync_device_info")
        syncDeviceInfo()
    }

    private val postConnectSyncBattery = Runnable {
        if (!isReadyForPostConnectAutoSync()) return@Runnable
        appendLog("post_connect_sync_battery")
        syncBattery()
    }

    private val postConnectSyncMediaCount = Runnable {
        if (!isReadyForPostConnectAutoSync()) return@Runnable
        appendLog("post_connect_sync_media_count")
        requestMediaCount()
    }

    private val postConnectSyncLocalMedia = Runnable {
        if (isReadyForPostConnectAutoSync()) {
            appendLog("post_connect_sync_local_media")
            refreshLocalMedia()
        }
        postConnectAutoSyncScheduled = false
    }

    fun initialize(app: Application) {
        if (_state.value.initialized) return
        application = app
        runCatching {
            val rememberedDevice = rememberedDevice(app)
            LargeDataHandler.getInstance()
            BleOperateManager.getInstance(app)
            BleOperateManager.getInstance().setApplication(app)
            BleOperateManager.getInstance().init()
            BleBaseControl.getInstance(app).setmContext(app)
            registerSdkReceivers(app)
            setupAlbumSync(app)
            refreshLocalMedia()
            updateState {
                it.copy(
                    initialized = true,
                    rememberedDeviceSummary = rememberedDeviceSummary(rememberedDevice),
                    status = "HeyCyan SDK 已初始化，等待扫描眼镜",
                )
            }
            appendLog("sdk_initialized albumDir=${albumDir(app).absolutePath}")
        }.onFailure { throwable ->
            updateState {
                it.copy(
                    initialized = false,
                    status = "HeyCyan SDK 初始化失败：${throwable.message ?: throwable.javaClass.simpleName}",
                )
            }
            appendLog("sdk_init_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun autoConnectLastDevice(context: Context) {
        val app = application ?: return appendLog("auto_connect_skipped sdk_not_initialized")
        if (_state.value.connected) return
        val device = rememberedDevice(app)
        if (device == null) {
            updateState { it.copy(rememberedDeviceSummary = rememberedDeviceSummary(null)) }
            appendLog("auto_connect_skipped no_remembered_device")
            return
        }
        if (!hasBluetoothRuntimePermissions(context)) {
            appendLog("auto_connect_skipped permission_missing")
            return
        }
        autoConnectActive = true
        if (_state.value.scanning) {
            appendLog("auto_connect_skip_already_scanning")
            return
        }
        startAutoConnectScan(context.applicationContext ?: context, reason = "initial")
    }

    private fun startAutoConnectScan(context: Context, reason: String) {
        val app = application ?: return appendLog("auto_connect_skipped sdk_not_initialized")
        val device = rememberedDevice(app) ?: return
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled != true) {
            updateState { it.copy(status = "蓝牙未开启，无法自动连接眼镜") }
            appendLog("auto_connect_skipped bluetooth_disabled")
            scheduleAutoConnectRetry()
            return
        }
        runCatching {
            clearPostConnectAutoSync()
            stopScanInternal(clearDevices = true)
            updateState {
                it.copy(
                    scanning = true,
                    devices = emptyList(),
                    selectedDevice = device,
                    rememberedDeviceSummary = rememberedDeviceSummary(device),
                    status = "正在自动探测上次眼镜 ${device.name}…",
                )
            }
            BleScannerHelper.getInstance().reSetCallback()
            BleScannerHelper.getInstance().scanDevice(context, null, scanCallback)
            mainHandler.removeCallbacks(scanTimeout)
            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
            appendLog("auto_connect_scan_started reason=$reason name=${device.name} address=${device.address}")
        }.onFailure { throwable ->
            updateState { it.copy(scanning = false, status = "自动连接失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("auto_connect_failed ${throwable.message ?: throwable.javaClass.simpleName}")
            scheduleAutoConnectRetry()
        }
    }

    fun startScan(context: Context) {
        val app = application ?: return appendLog("scan_skipped sdk_not_initialized")
        if (_state.value.connected) {
            stopScanInternal(clearDevices = true)
            updateState { it.copy(status = "眼镜已连接，无需继续扫描") }
            appendLog("scan_skipped already_connected")
            return
        }
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled != true) {
            appendLog("scan_failed bluetooth_disabled")
            updateState { it.copy(status = "蓝牙未开启，无法扫描眼镜") }
            return
        }
        runCatching {
            BleScannerHelper.getInstance().reSetCallback()
            updateState {
                it.copy(
                    scanning = true,
                    devices = emptyList(),
                    status = "正在扫描 HeyCyan 眼镜…",
                )
            }
            BleScannerHelper.getInstance().scanDevice(context, null, scanCallback)
            mainHandler.removeCallbacks(scanTimeout)
            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
            appendLog("scan_started")
        }.onFailure { throwable ->
            updateState { it.copy(scanning = false, status = "扫描失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("scan_failed ${throwable.message ?: throwable.javaClass.simpleName}")
            runCatching { BleScannerHelper.getInstance().stopScan(app) }
        }
    }

    fun stopScan() {
        autoConnectActive = false
        mainHandler.removeCallbacks(autoConnectRetry)
        stopScanInternal(clearDevices = false)
        updateState { it.copy(scanning = false, status = "扫描已停止，发现 ${it.devices.size} 台设备") }
        appendLog("scan_stopped devices=${_state.value.devices.size}")
    }

    fun connect(device: GlassesDevice) {
        runCatching {
            clearPostConnectAutoSync()
            rememberDevice(device)
            updateState { it.copy(selectedDevice = device, status = "正在连接 ${device.name}…") }
            stopScanInternal(clearDevices = true)
            updateState {
                it.copy(
                    scanning = false,
                    devices = emptyList(),
                    selectedDevice = device,
                    rememberedDeviceSummary = rememberedDeviceSummary(device),
                    status = "正在连接 ${device.name}…",
                )
            }
            BleOperateManager.getInstance().connectDirectly(device.address)
            appendLog("connect_requested name=${device.name} address=${device.address}")
        }.onFailure { throwable ->
            updateState { it.copy(status = "连接失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("connect_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun disconnect() {
        runCatching {
            autoConnectActive = false
            mainHandler.removeCallbacks(autoConnectRetry)
            clearPostConnectAutoSync()
            BleOperateManager.getInstance().unBindDevice()
            BleOperateManager.getInstance().disconnect()
            updateState {
                it.copy(
                    connected = false,
                    ready = false,
                    status = "已断开眼镜连接",
                )
            }
            appendLog("disconnect_requested")
        }.onFailure { throwable ->
            updateState { it.copy(status = "断开失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("disconnect_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun initDeviceSession() {
        runCatching {
            LargeDataHandler.getInstance().initEnable()
            registerDeviceNotifyListener()
            BleOperateManager.getInstance().setReady(true)
            updateState { it.copy(ready = true, status = "眼镜通道已就绪，可执行拍照/录像/同步") }
            appendLog("device_session_initialized")
        }.onFailure { throwable ->
            updateState { it.copy(status = "设备通道初始化失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("device_session_init_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun syncDeviceInfo() {
        runCatching {
            LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                val summary = "固件 ${response.firmwareVersion.ifBlank { "-" }} / 硬件 ${response.hardwareVersion.ifBlank { "-" }} / Wi-Fi ${response.wifiFirmwareVersion.ifBlank { "-" }}"
                updateState { it.copy(deviceInfoSummary = "设备信息：$summary", status = "设备信息已同步") }
                appendLog("device_info $summary")
            }
            appendLog("device_info_requested")
        }.onFailure { throwable ->
            updateState { it.copy(status = "设备信息同步失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("device_info_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun syncBattery() {
        runCatching {
            LargeDataHandler.getInstance().addBatteryCallBack("visionroute_debug") { _, response ->
                val charging = if (response.isCharging) "充电中" else "未充电"
                updateState {
                    it.copy(
                        batterySummary = "电量：${response.battery}% $charging",
                        status = "电量已同步",
                    )
                }
                appendLog("battery percent=${response.battery} charging=${response.isCharging}")
            }
            LargeDataHandler.getInstance().syncBattery()
            appendLog("battery_requested")
        }.onFailure { throwable ->
            updateState { it.copy(status = "电量同步失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("battery_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun requestMediaCount() {
        sendGlassesControl(
            label = "media_count",
            command = byteArrayOf(0x02, 0x04),
        ) { response ->
            if (response.dataType == 4) {
                val total = response.imageCount + response.videoCount + response.recordCount
                updateState {
                    it.copy(
                        mediaCountSummary = "眼镜媒体：照片 ${response.imageCount} / 视频 ${response.videoCount} / 录音 ${response.recordCount} / 合计 $total",
                        status = "眼镜媒体数量已同步",
                    )
                }
                appendLog("media_count image=${response.imageCount} video=${response.videoCount} record=${response.recordCount}")
            }
        }
    }

    fun takePhoto() {
        sendGlassesControl(
            label = "take_photo",
            command = byteArrayOf(0x02, 0x01, 0x01),
        ) {
            updateState { state -> state.copy(status = "拍照指令已发送，请稍后同步相册") }
        }
    }

    fun startVideo(onStartedConfirmed: (() -> Unit)? = null) {
        sendGlassesControl(
            label = "start_video",
            command = byteArrayOf(0x02, 0x01, 0x02),
        ) {
            updateState { state -> state.copy(videoRecording = true, status = "录像开始指令已发送") }
            onStartedConfirmed?.invoke()
        }
    }

    fun stopVideo() {
        sendGlassesControl(
            label = "stop_video",
            command = byteArrayOf(0x02, 0x01, 0x03),
        ) {
            updateState { state -> state.copy(videoRecording = false, status = "录像停止指令已发送，请稍后同步相册") }
        }
    }

    fun requestThumbnail() {
        val app = application ?: return appendLog("thumbnail_skipped sdk_not_initialized")
        if (albumSyncActive) {
            thumbnailQueuedAfterAlbumSync = true
            updateState { it.copy(status = "相册同步仍在进行，已排队取 AI 缩略图；同步完成或超时后自动尝试") }
            appendLog("thumbnail_queued album_sync_active")
            return
        }
        runCatching {
            registerDeviceNotifyListener()
            thumbnailBuffer = ByteArrayOutputStream()
            thumbnailRequestActive = false
            thumbnailNotifyPending = true
            mainHandler.removeCallbacks(thumbnailTimeout)
            mainHandler.removeCallbacks(thumbnailNotifyTimeout)
            mainHandler.postDelayed(thumbnailNotifyTimeout, THUMBNAIL_TIMEOUT_MS)
            updateState { it.copy(status = "缩略图指令已发送，等待眼镜缩略图事件") }
            sendGlassesControl(
                label = "request_thumbnail",
                command = byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02, 0x02),
            ) { response ->
                if (response.dataType == 1 && response.errorCode == 0 && response.workTypeIng == GLASS_WORK_TRANSFER) {
                    thumbnailNotifyPending = false
                    mainHandler.removeCallbacks(thumbnailNotifyTimeout)
                    updateState { it.copy(status = "眼镜正在传输模式，暂时不能取 AI 缩略图；请等待相册同步完成后重试") }
                    appendLog("thumbnail_busy work=4")
                }
            }
        }.onFailure { throwable ->
            thumbnailNotifyPending = false
            mainHandler.removeCallbacks(thumbnailNotifyTimeout)
            updateState { it.copy(status = "缩略图请求失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("thumbnail_request_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun syncAlbum() {
        val app = application ?: return appendLog("album_sync_skipped sdk_not_initialized")
        if (_state.value.videoRecording) {
            updateState { it.copy(status = "眼镜正在录像，无法同步相册；请先停止录像后再同步") }
            appendLog("album_sync_blocked recording_active")
            return
        }
        runCatching {
            albumConfigRetryCount = 0
            albumDownloadedCount = 0
            updateState { it.copy(status = "正在检查眼镜相册媒体数量…") }
            sendGlassesControl(
                label = "album_media_count",
                command = byteArrayOf(0x02, 0x04),
            ) { response ->
                if (response.dataType == 1 && response.errorCode == 0 && response.workTypeIng == GLASS_WORK_RECORDING) {
                    handleAlbumSyncBlockedByRecording(source = "media_count")
                    return@sendGlassesControl
                }
                if (response.dataType != 4) return@sendGlassesControl
                val total = response.imageCount + response.videoCount + response.recordCount
                updateState {
                    it.copy(
                        mediaCountSummary = "眼镜媒体：照片 ${response.imageCount} / 视频 ${response.videoCount} / 录音 ${response.recordCount} / 合计 $total",
                    )
                }
                appendLog("album_media_count image=${response.imageCount} video=${response.videoCount} record=${response.recordCount}")
                if (total <= 0) {
                    handleEmptyAlbum()
                } else {
                    startAlbumImport(app, reason = "user_media_count_$total")
                }
            }
        }.onFailure { throwable ->
            albumSyncActive = false
            mainHandler.removeCallbacks(albumSyncTimeout)
            mainHandler.removeCallbacks(albumConfigRetry)
            updateState { it.copy(status = "相册同步失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("album_sync_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun refreshLocalMedia() {
        val app = application ?: return
        val media = albumDir(app).walkTopDown()
            .filter { it.isFile && GlassesMediaClassifier.isSupportedMedia(it.name) }
            .map {
                GlassesMediaItem(
                    fileName = it.name,
                    filePath = it.absolutePath,
                    mimeType = GlassesMediaClassifier.mimeType(it.name),
                    lastModified = it.lastModified(),
                )
            }
            .sortedByDescending { it.lastModified }
            .toList()
        updateState {
            it.copy(
                localMedia = media,
                latestThumbnailPath = media.firstOrNull { item -> item.isAiThumbnail() }?.filePath,
                latestPreviewPath = media.firstOrNull { item -> item.mimeType.startsWith("image/") }?.filePath ?: it.latestPreviewPath,
            )
        }
        appendLog("local_media_refreshed count=${media.size}")
    }

    private fun setupAlbumSync(app: Application) {
        val dir = albumDir(app)
        dir.mkdirs()
        GlassesControl.getInstance(app)?.initGlasses(dir.absolutePath)
        GlassesControl.getInstance(app)?.setWifiDownloadListener(object : GlassesControl.WifiFilesDownloadListener {
            override fun onGlassesControlSuccess() {
                markAlbumSyncProgress()
                updateState { it.copy(status = "眼镜已接受相册同步，等待 Wi-Fi 文件传输…") }
                appendLog("album_control_success")
            }

            override fun onGlassesFail(errorCode: Int) {
                if (errorCode == GLASS_WORK_RECORDING) {
                    handleAlbumSyncBlockedByRecording(source = "album_control")
                    return
                }
                finishAlbumSync()
                updateState { it.copy(status = "眼镜相册同步控制失败：$errorCode") }
                appendLog("album_control_failed code=$errorCode")
            }

            override fun wifiSpeed(wifiSpeed: String) {
                markAlbumSyncProgress()
                updateState { it.copy(status = "相册同步中：$wifiSpeed") }
            }

            override fun fileProgress(fileName: String, progress: Int) {
                markAlbumSyncProgress()
                updateState { it.copy(status = "同步 $fileName：$progress%") }
            }

            override fun fileWasDownloadSuccessfully(entity: GlassAlbumEntity) {
                albumDownloadedCount += 1
                markAlbumSyncProgress()
                updateState { it.copy(status = "已同步：${entity.fileName}") }
                appendLog("file_downloaded name=${entity.fileName} path=${entity.filePath}")
                refreshLocalMedia()
            }

            override fun fileCount(index: Int, total: Int) {
                markAlbumSyncProgress()
                updateState { it.copy(status = "相册同步进度：$index / $total") }
            }

            override fun fileDownloadComplete() {
                finishAlbumSync()
                refreshLocalMedia()
                updateState { it.copy(status = "眼镜相册同步完成") }
                appendLog("album_sync_complete")
            }

            override fun fileDownloadError(fileType: Int, errorType: Int) {
                if (shouldRetryAlbumConfig(fileType, errorType)) {
                    albumConfigRetryCount += 1
                    markAlbumSyncProgress()
                    mainHandler.removeCallbacks(albumConfigRetry)
                    mainHandler.postDelayed(albumConfigRetry, ALBUM_CONFIG_RETRY_DELAY_MS)
                    updateState {
                        it.copy(
                            status = "眼镜相册目录暂时无响应，正在自动重试 ${albumConfigRetryCount}/$MAX_ALBUM_CONFIG_RETRY_COUNT…",
                        )
                    }
                    appendLog("album_config_retry_scheduled count=$albumConfigRetryCount type=$fileType error=$errorType")
                    return
                }
                finishAlbumSync()
                refreshLocalMedia()
                val status = if (fileType == FILE_ERROR_TYPE_ALBUM_CONFIG) {
                    "眼镜相册目录读取失败：error=$errorType；请确认眼镜 Wi-Fi 传输已开启后重试"
                } else {
                    "文件同步失败：type=$fileType error=$errorType"
                }
                updateState { it.copy(status = status) }
                appendLog("file_download_error type=$fileType error=$errorType")
            }

            override fun eisEnd(fileName: String, filePath: String) {
                appendLog("eis_end name=$fileName path=$filePath")
            }

            override fun eisError(fileName: String, sourcePath: String, errorInfo: String) {
                appendLog("eis_error name=$fileName source=$sourcePath error=$errorInfo")
            }

            override fun recordingToPcm(fileName: String, filePath: String, duration: Int) {
                appendLog("recording_to_pcm name=$fileName duration=$duration path=$filePath")
                refreshLocalMedia()
            }

            override fun recordingToPcmError(fileName: String, errorInfo: String) {
                appendLog("recording_to_pcm_error name=$fileName error=$errorInfo")
            }

            override fun voiceFromGlassesStatus(status: Int) {
                appendLog("voice_from_glasses_status status=$status")
            }

            override fun voiceFromGlasses(pcmData: ByteArray) {
                appendLog("voice_from_glasses bytes=${pcmData.size}")
            }
        })
    }

    private fun startAlbumImport(app: Application, reason: String) {
        registerDeviceNotifyListener()
        setupAlbumSync(app)
        albumSyncActive = true
        mainHandler.removeCallbacks(albumSyncTimeout)
        mainHandler.removeCallbacks(albumConfigRetry)
        mainHandler.postDelayed(albumSyncTimeout, ALBUM_SYNC_TIMEOUT_MS)
        GlassesControl.getInstance(app)?.importAlbum()
        updateState { it.copy(status = "相册同步已启动，等待眼镜建立 Wi-Fi 文件传输…") }
        appendLog("album_sync_started reason=$reason dir=${albumDir(app).absolutePath}")
    }

    private fun handleEmptyAlbum() {
        albumSyncActive = false
        mainHandler.removeCallbacks(albumSyncTimeout)
        mainHandler.removeCallbacks(albumConfigRetry)
        refreshLocalMedia()
        updateState { it.copy(status = "眼镜相册为空，无需同步；请拍照或录像后再同步") }
        appendLog("album_sync_empty")
    }

    private fun handleAlbumSyncBlockedByRecording(source: String) {
        albumSyncActive = false
        mainHandler.removeCallbacks(albumSyncTimeout)
        mainHandler.removeCallbacks(albumConfigRetry)
        updateState { it.copy(videoRecording = true, status = "眼镜正在录像，无法同步相册；请先停止录像后再同步") }
        appendLog("album_sync_blocked recording source=$source")
    }

    private fun markAlbumSyncProgress() {
        if (!albumSyncActive) return
        mainHandler.removeCallbacks(albumSyncTimeout)
        mainHandler.postDelayed(albumSyncTimeout, ALBUM_SYNC_TIMEOUT_MS)
    }

    private fun finishAlbumSync() {
        albumSyncActive = false
        mainHandler.removeCallbacks(albumSyncTimeout)
        mainHandler.removeCallbacks(albumConfigRetry)
        scheduleQueuedThumbnailIfNeeded(reason = "album_sync_finished")
    }

    private fun shouldRetryAlbumConfig(fileType: Int, errorType: Int): Boolean {
        return fileType == FILE_ERROR_TYPE_ALBUM_CONFIG &&
            errorType == DOWNLOAD_ERROR_UNKNOWN &&
            albumConfigRetryCount < MAX_ALBUM_CONFIG_RETRY_COUNT
    }

    private fun scheduleQueuedThumbnailIfNeeded(reason: String) {
        if (!thumbnailQueuedAfterAlbumSync) return
        mainHandler.removeCallbacks(queuedThumbnailRequest)
        mainHandler.postDelayed(queuedThumbnailRequest, QUEUED_THUMBNAIL_DELAY_MS)
        appendLog("thumbnail_queued_schedule reason=$reason")
    }

    private fun schedulePostConnectAutoSync() {
        if (postConnectAutoSyncScheduled) return
        postConnectAutoSyncScheduled = true
        mainHandler.removeCallbacks(postConnectSyncDeviceInfo)
        mainHandler.removeCallbacks(postConnectSyncBattery)
        mainHandler.removeCallbacks(postConnectSyncMediaCount)
        mainHandler.removeCallbacks(postConnectSyncLocalMedia)
        updateState { it.copy(status = "眼镜通道已就绪，正在自动同步基础信息…") }
        appendLog("post_connect_auto_sync_scheduled")
        mainHandler.postDelayed(postConnectSyncDeviceInfo, POST_CONNECT_SYNC_DELAY_MS)
        mainHandler.postDelayed(postConnectSyncBattery, POST_CONNECT_SYNC_DELAY_MS + POST_CONNECT_SYNC_STEP_MS)
        mainHandler.postDelayed(postConnectSyncMediaCount, POST_CONNECT_SYNC_DELAY_MS + POST_CONNECT_SYNC_STEP_MS * 2)
        mainHandler.postDelayed(postConnectSyncLocalMedia, POST_CONNECT_SYNC_DELAY_MS + POST_CONNECT_SYNC_STEP_MS * 3)
    }

    private fun clearPostConnectAutoSync() {
        postConnectAutoSyncScheduled = false
        mainHandler.removeCallbacks(postConnectSyncDeviceInfo)
        mainHandler.removeCallbacks(postConnectSyncBattery)
        mainHandler.removeCallbacks(postConnectSyncMediaCount)
        mainHandler.removeCallbacks(postConnectSyncLocalMedia)
    }

    private fun isReadyForPostConnectAutoSync(): Boolean {
        return _state.value.connected && _state.value.ready
    }

    private fun sendGlassesControl(
        label: String,
        command: ByteArray,
        onResponse: ((com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse) -> Unit)? = null,
    ) {
        runCatching {
            LargeDataHandler.getInstance().glassesControl(command) { _, response ->
                appendLog("$label response dataType=${response.dataType} error=${response.errorCode} work=${response.workTypeIng}")
                onResponse?.invoke(response)
            }
            appendLog("$label requested")
        }.onFailure { throwable ->
            updateState { it.copy(status = "$label 失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            appendLog("${label}_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun registerSdkReceivers(app: Application) {
        if (localReceiver == null) {
            val receiver = object : QCBluetoothCallbackCloneReceiver() {
                override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
                    val glassesDevice = device?.toGlassesDevice(_state.value.selectedDevice?.rssi ?: 0)
                    if (connected) {
                        autoConnectActive = false
                        mainHandler.removeCallbacks(autoConnectRetry)
                        stopScanInternal(clearDevices = true)
                        glassesDevice?.let { rememberDevice(it) }
                    } else {
                        clearPostConnectAutoSync()
                        if (autoConnectActive) {
                            scheduleAutoConnectRetry()
                        }
                    }
                    updateState {
                        it.copy(
                            connected = connected,
                            scanning = if (connected) false else it.scanning,
                            devices = if (connected) emptyList() else it.devices,
                            selectedDevice = glassesDevice ?: it.selectedDevice,
                            status = if (connected) "眼镜已连接，等待服务发现" else "眼镜已断开",
                        )
                    }
                    appendLog("connect_state connected=$connected device=${glassesDevice?.name ?: "-"}")
                }

                override fun onServiceDiscovered() {
                    LargeDataHandler.getInstance().initEnable()
                    registerDeviceNotifyListener()
                    BleOperateManager.getInstance().setReady(true)
                    updateState { it.copy(connected = true, ready = true, status = "眼镜服务已发现，调试通道就绪") }
                    appendLog("service_discovered")
                    schedulePostConnectAutoSync()
                }

                override fun bleStatus(status: Int, newState: Int) {
                    appendLog("ble_status status=$status newState=$newState")
                }
            }
            LocalBroadcastManager.getInstance(app).registerReceiver(receiver, BleAction.getIntentFilter())
            localReceiver = receiver
        }
        if (deviceReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    if (action.contains("BLUETOOTH") || action.contains("ACL") || action.contains("BOND")) {
                        appendLog("bluetooth_event action=$action")
                    }
                }
            }
            ContextCompat.registerReceiver(
                app,
                receiver,
                BleAction.getDeviceIntentFilter(),
                ContextCompat.RECEIVER_EXPORTED,
            )
            deviceReceiver = receiver
        }
    }

    private fun registerDeviceNotifyListener() {
        if (deviceNotifyListenerRegistered) return
        runCatching {
            LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
            deviceNotifyListenerRegistered = true
            appendLog("device_notify_listener_registered")
        }.onFailure { throwable ->
            appendLog("device_notify_listener_failed ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(commandType: Int, response: GlassesDeviceNotifyRsp) {
            val loadData = response.loadData ?: return
            if (loadData.size <= 6) return
            val notifyType = loadData[6].toInt() and 0xFF
            appendLog(
                "device_notify cmd=$commandType type=0x${notifyType.toString(16)} size=${loadData.size} payload=${loadData.toHexString()}",
            )
            if (notifyType == NOTIFY_TYPE_THUMBNAIL_READY || notifyType == NOTIFY_TYPE_MEDIA_READY) {
                if (albumSyncActive && notifyType == NOTIFY_TYPE_MEDIA_READY) {
                    appendLog("album_sync_device_notify type=0xb")
                }
                if (!thumbnailNotifyPending) return
                application?.let { requestPictureThumbnail(it, reason = "device_notify_0x${notifyType.toString(16)}") }
            }
        }
    }

    private fun requestPictureThumbnail(app: Application, reason: String) {
        if (thumbnailRequestActive) {
            appendLog("thumbnail_pull_ignored active reason=$reason")
            return
        }
        thumbnailNotifyPending = false
        mainHandler.removeCallbacks(thumbnailNotifyTimeout)
        thumbnailBuffer = ByteArrayOutputStream()
        thumbnailRequestActive = true
        mainHandler.removeCallbacks(thumbnailTimeout)
        mainHandler.postDelayed(thumbnailTimeout, THUMBNAIL_TIMEOUT_MS)
        appendLog("thumbnail_pull_started reason=$reason")
        LargeDataHandler.getInstance().getPictureThumbnails { commandType, success, data ->
            mainHandler.post {
                handlePictureThumbnailChunk(app, commandType, success, data)
            }
        }
    }

    private fun handlePictureThumbnailChunk(
        app: Application,
        commandType: Int,
        success: Boolean,
        data: ByteArray?,
    ) {
        if (!thumbnailRequestActive) {
            appendLog("thumbnail_chunk_ignored cmd=$commandType success=$success")
            return
        }
        val byteCount = data?.size ?: 0
        appendLog("thumbnail_chunk cmd=$commandType success=$success bytes=$byteCount")
        if (data != null && data.isNotEmpty()) {
            thumbnailBuffer.write(data)
            updateState { it.copy(status = "正在接收缩略图：${thumbnailBuffer.size()} bytes") }
        }
        if (!success) return

        thumbnailRequestActive = false
        mainHandler.removeCallbacks(thumbnailTimeout)
        val finalBytes = thumbnailBuffer.toByteArray()
        if (finalBytes.isNotEmpty()) {
            saveThumbnail(app, finalBytes, commandType)
        } else {
            refreshLocalMedia()
            val latestImage = _state.value.localMedia.firstOrNull { it.mimeType.startsWith("image/") }
            updateState {
                it.copy(
                    latestPreviewPath = latestImage?.filePath ?: it.latestPreviewPath,
                    status = "眼镜未回传缩略图数据；已刷新本地相册，可点“同步眼镜相册”获取完整照片",
                )
            }
            appendLog("thumbnail_empty_final cmd=$commandType")
        }
    }

    private fun saveThumbnail(app: Application, bytes: ByteArray, commandType: Int) {
        val file = File(albumDir(app), "thumbnail_${timestampFormatter.format(Date())}.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        refreshLocalMedia()
        updateState { it.copy(latestPreviewPath = file.absolutePath, latestThumbnailPath = file.absolutePath, status = "缩略图已保存到本机") }
        appendLog("thumbnail_saved cmd=$commandType bytes=${bytes.size} path=${file.absolutePath}")
    }

    private fun ByteArray.toHexString(limit: Int = 24): String {
        return take(limit).joinToString(separator = " ") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        } + if (size > limit) " ..." else ""
    }

    private val scanCallback = object : ScanWrapperCallback {
        override fun onStart() {
            updateState { it.copy(scanning = true, status = "正在扫描 HeyCyan 眼镜…") }
        }

        override fun onStop() {
            updateState { it.copy(scanning = false, status = "扫描已停止，发现 ${it.devices.size} 台设备") }
        }

        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (_state.value.connected) return
            if (device == null) return
            val glassesDevice = device.toGlassesDevice(rssi)
            if (!glassesDevice.isLikelyHeyCyanDevice()) return
            updateDevice(glassesDevice)
        }

        override fun onScanFailed(errorCode: Int) {
            updateState { it.copy(scanning = false, status = "扫描失败：$errorCode") }
            appendLog("scan_failed code=$errorCode")
            if (autoConnectActive) {
                scheduleAutoConnectRetry()
            }
        }

        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) = Unit

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results.orEmpty().forEach { result ->
                onLeScan(result.device, result.rssi, null)
            }
        }
    }

    private fun updateDevice(device: GlassesDevice) {
        val remembered = rememberedDevice(application)
        if (remembered?.address.equals(device.address, ignoreCase = true)) {
            appendLog("auto_connect_matched name=${device.name} address=${device.address}")
            connect(device)
            return
        }
        updateState { current ->
            if (current.connected) return@updateState current
            val existing = current.devices.firstOrNull { it.address == device.address }
            if (existing != null && kotlin.math.abs(existing.rssi - device.rssi) < 5) {
                return@updateState current
            }
            val devices = (current.devices.filterNot { it.address == device.address } + device)
                .sortedByDescending { it.rssi }
            current.copy(devices = devices, status = "发现 ${devices.size} 台设备")
        }
    }

    private fun stopScanInternal(clearDevices: Boolean) {
        val app = application ?: return
        runCatching { BleScannerHelper.getInstance().stopScan(app) }
        mainHandler.removeCallbacks(scanTimeout)
        updateState { it.copy(scanning = false, devices = if (clearDevices) emptyList() else it.devices) }
    }

    private fun handleScanTimeout() {
        stopScanInternal(clearDevices = false)
        if (autoConnectActive && !_state.value.connected && rememberedDevice(application) != null) {
            updateState { it.copy(scanning = false, status = "暂未发现上次眼镜，保持后台自动重试；请打开眼镜电源") }
            appendLog("auto_connect_scan_timeout_retry devices=${_state.value.devices.size}")
            scheduleAutoConnectRetry()
            return
        }
        updateState { it.copy(scanning = false, status = "扫描已停止，发现 ${it.devices.size} 台设备") }
        appendLog("scan_timeout devices=${_state.value.devices.size}")
    }

    private fun scheduleAutoConnectRetry() {
        if (!autoConnectActive || _state.value.connected) return
        mainHandler.removeCallbacks(autoConnectRetry)
        mainHandler.postDelayed(autoConnectRetry, AUTO_CONNECT_RETRY_DELAY_MS)
        appendLog("auto_connect_retry_scheduled delay_ms=$AUTO_CONNECT_RETRY_DELAY_MS")
    }

    private fun BluetoothDevice.toGlassesDevice(rssi: Int): GlassesDevice {
        val deviceName = runCatching { name }.getOrNull().orEmpty().ifBlank { "HeyCyan 眼镜" }
        return GlassesDevice(
            name = deviceName,
            address = address,
            rssi = rssi,
        )
    }

    private fun GlassesDevice.isLikelyHeyCyanDevice(): Boolean {
        val normalizedName = name.uppercase(Locale.US)
        return HEYCYAN_DEVICE_PREFIXES.any { normalizedName.startsWith(it) }
    }

    private fun GlassesMediaItem.isAiThumbnail(): Boolean {
        return mimeType.startsWith("image/") && fileName.startsWith("thumbnail_", ignoreCase = true)
    }

    private fun albumDir(app: Application): File {
        val root = app.getExternalFilesDir(null) ?: app.filesDir
        return File(root, "heycyan_media")
    }

    fun fullSessionLog(): String {
        return synchronized(sessionLogs) {
            sessionLogs.joinToString(separator = "\n")
        }
    }

    private fun rememberDevice(device: GlassesDevice) {
        val app = application ?: return
        prefs(app).edit()
            .putString(PREF_LAST_DEVICE_NAME, device.name)
            .putString(PREF_LAST_DEVICE_ADDRESS, device.address)
            .apply()
        updateState { it.copy(rememberedDeviceSummary = rememberedDeviceSummary(device)) }
    }

    private fun rememberedDevice(app: Application?): GlassesDevice? {
        if (app == null) return null
        val prefs = prefs(app)
        val address = prefs.getString(PREF_LAST_DEVICE_ADDRESS, null).orEmpty()
        if (address.isBlank()) return null
        val name = prefs.getString(PREF_LAST_DEVICE_NAME, null).orEmpty().ifBlank { "HeyCyan 眼镜" }
        return GlassesDevice(name = name, address = address, rssi = 0)
    }

    private fun rememberedDeviceSummary(device: GlassesDevice?): String {
        return device?.let { "上次设备：${it.name} ${it.address}，打开页面后自动探测连接" }
            ?: "上次设备：未记录，连接成功后会自动记忆"
    }

    private fun prefs(app: Application): SharedPreferences {
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun hasBluetoothRuntimePermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateState(reducer: (GlassesDebugState) -> GlassesDebugState) {
        _state.value = reducer(_state.value)
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $message"
        synchronized(sessionLogs) {
            sessionLogs += line
        }
        updateState {
            it.copy(logs = (it.logs + line).takeLast(MAX_LOG_LINES))
        }
    }
}
