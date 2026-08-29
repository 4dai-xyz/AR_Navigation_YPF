package com.aiglasses.poc.usb

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.aiglasses.poc.glasses.RecordingAnnotationVideoItem
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.VideoCapture
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsbCameraRecordingState(
    val available: Boolean = false,
    val opening: Boolean = false,
    val ready: Boolean = false,
    val recording: Boolean = false,
    val selectedDeviceLabel: String? = null,
    val localMedia: List<RecordingAnnotationVideoItem> = emptyList(),
    val status: String = "USB 相机未检测",
)

object UsbCameraRecordingManager {
    private const val TAG = "UsbCameraRecording"
    private const val PREF_GALLERY_SYNC = "usb_camera_gallery_sync"
    private const val DEFAULT_PREVIEW_WIDTH = 1920
    private const val DEFAULT_PREVIEW_HEIGHT = 1080
    private const val DEFAULT_PREVIEW_FPS = 30
    private const val DEFAULT_VIDEO_FPS = 30
    private const val DEFAULT_VIDEO_BITRATE_MBPS = 8f
    private const val DEFAULT_IFRAME_INTERVAL_SECONDS = 1

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val _state = MutableStateFlow(UsbCameraRecordingState())
    val state: StateFlow<UsbCameraRecordingState> = _state.asStateFlow()

    private var application: Application? = null
    private var cameraHelper: ICameraHelper? = null
    private var selectedDevice: UsbDevice? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewTextureAdded = false
    private var usbReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshDevices(autoOpen = hasCameraPermission())
            }
        }
    }

    private val cameraStateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            if (device.isLikelyUvcCamera()) {
                refreshDevices(autoOpen = hasCameraPermission())
            }
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            val size = preferredPreviewSize()
            updateState {
                it.copy(
                    opening = true,
                    available = true,
                    selectedDeviceLabel = device.displayLabel(),
                    status = "USB 相机已授权，正在打开 ${size.formatLabel()} 预览通道",
                )
            }
            cameraHelper?.openCamera(size)
        }

        override fun onCameraOpen(device: UsbDevice) {
            val helper = cameraHelper ?: return
            runCatching {
                helper.startPreview()
                addPreviewSurfaceIfNeeded(helper)
                applyCaptureConfig(helper)
            }.onFailure { throwable ->
                Log.w(TAG, "prepare preview failed", throwable)
            }
            updateState {
                val size = helper.previewSize ?: defaultPreviewSize()
                it.copy(
                    available = true,
                    opening = false,
                    ready = true,
                    recording = helper.isRecording,
                    selectedDeviceLabel = device.displayLabel(),
                    localMedia = scanLocalMedia(),
                    status = "USB 相机已就绪：${size.formatLabel()}，可录像标记",
                )
            }
        }

        override fun onCameraClose(device: UsbDevice) {
            removePreviewSurfaceIfNeeded()
            updateState {
                it.copy(
                    opening = false,
                    ready = false,
                    recording = false,
                    selectedDeviceLabel = selectedDevice?.displayLabel(),
                    status = "USB 相机已关闭",
                )
            }
        }

        override fun onDeviceClose(device: UsbDevice) {
            updateState { it.copy(opening = false, ready = false, recording = false, status = "USB 设备已关闭") }
        }

        override fun onDetach(device: UsbDevice) {
            if (selectedDevice?.deviceName == device.deviceName) {
                selectedDevice = null
            }
            refreshDevices(autoOpen = false)
        }

        override fun onCancel(device: UsbDevice) {
            updateState { it.copy(opening = false, status = "USB 相机授权已取消") }
        }

        override fun onError(device: UsbDevice?, e: CameraException?) {
            Log.e(TAG, "usb camera error device=${device?.deviceName}", e)
            updateState {
                it.copy(
                    opening = false,
                    ready = false,
                    recording = false,
                    status = "USB 相机错误：${e?.message ?: "未知错误"}",
                )
            }
        }
    }

    fun initialize(app: Application) {
        if (application != null) return
        application = app
        cameraHelper = CameraHelper().apply {
            setStateCallback(cameraStateCallback)
        }
        registerUsbReceiver(app)
        refreshDevices(autoOpen = hasCameraPermission())
        Thread {
            migrateLegacyUsbVideosToGallery()
        }.start()
    }

    fun refreshDevices(autoOpen: Boolean = false) {
        val app = application ?: return
        val devices = readUsbDevices(app).filter { it.isLikelyUvcCamera() }
        if (devices.none { it.deviceName == selectedDevice?.deviceName }) {
            selectedDevice = null
        }
        val currentLabel = selectedDevice?.displayLabel()
        val ready = cameraHelper?.isCameraOpened == true
        updateState {
            it.copy(
                available = devices.isNotEmpty(),
                opening = if (devices.isEmpty()) false else it.opening,
                ready = ready && devices.isNotEmpty(),
                recording = cameraHelper?.isRecording == true,
                selectedDeviceLabel = currentLabel ?: devices.firstOrNull()?.displayLabel(),
                localMedia = scanLocalMedia(),
                status = when {
                    devices.isEmpty() -> "USB 相机未检测"
                    ready -> "USB 相机已就绪，可录像标记"
                    !hasCameraPermission() -> "检测到 USB 相机，等待相机权限"
                    else -> "检测到 USB 相机，等待打开"
                },
            )
        }
        if (devices.isEmpty()) {
            if (cameraHelper?.isCameraOpened == true) {
                cameraHelper?.closeCamera()
            }
            return
        }
        if (autoOpen && hasCameraPermission() && cameraHelper?.isCameraOpened != true) {
            connectFirstCamera()
        }
    }

    fun connectFirstCamera(): Boolean {
        val app = application ?: return false
        if (!hasCameraPermission()) {
            refreshDevices(autoOpen = false)
            return false
        }
        if (cameraHelper?.isCameraOpened == true) {
            refreshDevices(autoOpen = false)
            return true
        }
        val device = readUsbDevices(app).firstOrNull { it.isLikelyUvcCamera() }
            ?: run {
                refreshDevices(autoOpen = false)
                return false
            }
        selectedDevice = device
        updateState {
            it.copy(
                available = true,
                opening = true,
                selectedDeviceLabel = device.displayLabel(),
                status = "正在打开 USB 相机…",
            )
        }
        return runCatching {
            cameraHelper?.selectDevice(device)
            true
        }.onFailure { throwable ->
            updateState { it.copy(opening = false, status = "USB 相机打开失败：${throwable.message ?: throwable.javaClass.simpleName}") }
        }.getOrDefault(false)
    }

    fun startVideo(onStartedConfirmed: () -> Unit): Boolean {
        val helper = cameraHelper
        if (helper?.isCameraOpened != true) {
            connectFirstCamera()
            return false
        }
        if (helper.isRecording) {
            onStartedConfirmed()
            return true
        }
        applyCaptureConfig(helper)
        val output = outputFile(prefix = "usb_video", extension = "mp4")
        val options = VideoCapture.OutputFileOptions.Builder(output).build()
        return runCatching {
            helper.startRecording(
                options,
                object : VideoCapture.OnVideoCaptureCallback {
                    override fun onStart() {
                        updateState { it.copy(recording = true, status = "USB 相机录像中") }
                        onStartedConfirmed()
                    }

                    override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                        updateState {
                            it.copy(recording = false, status = "USB 相机录像已保存，正在同步系统相册：${output.name}")
                        }
                        Thread {
                            val synced = saveMediaToSystemGallery(output, "video/mp4")
                            updateState {
                                it.copy(
                                    recording = false,
                                    localMedia = scanLocalMedia(),
                                    status = if (synced) {
                                        "USB 相机录像已同步到系统相册：${output.name}"
                                    } else {
                                        "USB 相机录像已保存：${output.name}（系统相册同步失败）"
                                    },
                                )
                            }
                        }.start()
                    }

                    override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                        Log.e(TAG, "usb video failed code=$videoCaptureError message=$message", cause)
                        updateState { it.copy(recording = false, status = "USB 相机录像失败：$message") }
                    }
                },
            )
            true
        }.onFailure { throwable ->
            updateState { it.copy(recording = false, status = "USB 相机录像失败：${throwable.message ?: throwable.javaClass.simpleName}") }
        }.getOrDefault(false)
    }

    fun stopVideo() {
        val helper = cameraHelper ?: return
        if (!helper.isRecording) return
        runCatching {
            helper.stopRecording()
            updateState { it.copy(recording = false, status = "正在停止 USB 相机录像…") }
        }.onFailure { throwable ->
            updateState { it.copy(recording = false, status = "USB 相机停止录像失败：${throwable.message ?: throwable.javaClass.simpleName}") }
        }
    }

    fun releaseCameraForDebug(): Boolean {
        val helper = cameraHelper ?: return true
        if (helper.isRecording) {
            updateState { it.copy(status = "USB 相机正在录像，无法切换到调试页") }
            return false
        }
        runCatching {
            removePreviewSurfaceIfNeeded()
            helper.closeCamera()
            refreshDevices(autoOpen = false)
        }.onFailure { throwable ->
            Log.w(TAG, "release camera for debug failed", throwable)
        }
        return true
    }

    fun scanLocalMedia(): List<RecordingAnnotationVideoItem> {
        val app = application ?: return emptyList()
        val files = usbMediaDirectory(app).listFiles()?.filter { it.isFile } ?: emptyList()
        return files.mapNotNull { file ->
            val mimeType = file.mimeTypeFor() ?: return@mapNotNull null
            RecordingAnnotationVideoItem(
                fileName = file.name,
                filePath = file.absolutePath,
                mimeType = mimeType,
                lastModified = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }.sortedWith(compareByDescending<RecordingAnnotationVideoItem> { it.lastModified }.thenByDescending { it.fileName })
    }

    private fun registerUsbReceiver(app: Application) {
        if (usbReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            app.registerReceiver(usbReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    private fun addPreviewSurfaceIfNeeded(helper: ICameraHelper) {
        if (previewTextureAdded) return
        val size = helper.previewSize ?: defaultPreviewSize()
        val texture = previewTexture ?: SurfaceTexture(0).also { previewTexture = it }
        texture.setDefaultBufferSize(size.width, size.height)
        helper.addSurface(texture, false)
        previewTextureAdded = true
    }

    private fun removePreviewSurfaceIfNeeded() {
        val texture = previewTexture ?: return
        if (previewTextureAdded) {
            runCatching { cameraHelper?.removeSurface(texture) }
            previewTextureAdded = false
        }
    }

    private fun applyCaptureConfig(helper: ICameraHelper) {
        runCatching {
            helper.setVideoCaptureConfig(
                helper.getVideoCaptureConfig()
                    .setVideoFrameRate(DEFAULT_VIDEO_FPS)
                    .setBitRate((DEFAULT_VIDEO_BITRATE_MBPS * 1024 * 1024).toInt())
                    .setIFrameInterval(DEFAULT_IFRAME_INTERVAL_SECONDS)
                    .setAudioCaptureEnable(false),
            )
        }.onFailure { throwable ->
            Log.w(TAG, "apply video config failed", throwable)
        }
    }

    private fun updateState(block: (UsbCameraRecordingState) -> UsbCameraRecordingState) {
        val update = {
            _state.value = block(_state.value)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            mainHandler.post { update() }
        }
    }

    private fun hasCameraPermission(): Boolean {
        val app = application ?: return false
        return ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun outputFile(prefix: String, extension: String): File {
        val app = application ?: error("UsbCameraRecordingManager not initialized")
        val timestamp = timestampFormatter.format(Date())
        return File(usbMediaDirectory(app), "${prefix}_$timestamp.$extension")
    }

    private fun usbMediaDirectory(app: Application): File {
        val root = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir
        return File(root, "usb_camera").apply { mkdirs() }
    }

    private fun saveMediaToSystemGallery(source: File, mimeType: String): Boolean {
        val app = application ?: return false
        if (!source.exists() || source.length() <= 0L) {
            Log.w(TAG, "skip gallery sync for missing USB video path=${source.absolutePath}")
            return false
        }
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        var uri: Uri? = null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.SIZE, source.length())
                put(MediaStore.MediaColumns.DATE_MODIFIED, source.lastModified() / 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VisionRoute/USB Camera")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    val targetFile = File(File(baseDir, "VisionRoute/USB Camera").apply { mkdirs() }, source.name)
                    put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
                }
            }
            uri = app.contentResolver.insert(collection, values) ?: error("MediaStore insert failed")
            app.contentResolver.openOutputStream(uri!!)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("MediaStore output stream failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                app.contentResolver.update(
                    uri!!,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            markGallerySynced(source)
            true
        }.onFailure { throwable ->
            uri?.let { app.contentResolver.delete(it, null, null) }
            Log.e(TAG, "gallery sync failed for USB video path=${source.absolutePath}", throwable)
        }.getOrDefault(false)
    }

    private fun migrateLegacyUsbVideosToGallery() {
        if (application == null) return
        val legacyVideos = scanLocalMedia().filter { it.mimeType.startsWith("video/") }
        if (legacyVideos.isEmpty()) return
        var syncedCount = 0
        legacyVideos.forEach { item ->
            val file = File(item.filePath)
            if (isGallerySynced(file) || isVideoAlreadyInSystemGallery(file)) {
                markGallerySynced(file)
                return@forEach
            }
            if (saveMediaToSystemGallery(file, item.mimeType)) {
                syncedCount++
            }
        }
        if (syncedCount > 0) {
            updateState { it.copy(localMedia = scanLocalMedia()) }
            Log.i(TAG, "legacy usb videos migrated count=$syncedCount")
        }
    }

    private fun isVideoAlreadyInSystemGallery(source: File): Boolean {
        val app = application ?: return false
        if (!source.exists() || source.length() <= 0L) return false
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?"
        val selectionArgs = arrayOf(source.name, source.length().toString())
        return runCatching {
            app.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null,
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private fun markGallerySynced(source: File) {
        val app = application ?: return
        app.getSharedPreferences(PREF_GALLERY_SYNC, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(gallerySyncKey(source), true)
            .apply()
    }

    private fun isGallerySynced(source: File): Boolean {
        val app = application ?: return false
        return app.getSharedPreferences(PREF_GALLERY_SYNC, Context.MODE_PRIVATE)
            .getBoolean(gallerySyncKey(source), false)
    }

    private fun gallerySyncKey(source: File): String {
        return "video_${source.absolutePath.hashCode()}_${source.length()}_${source.lastModified()}"
    }

    private fun readUsbDevices(context: Context): List<UsbDevice> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values.toList()
    }

    private fun defaultPreviewSize(): Size {
        return Size(
            UVCCamera.UVC_VS_FRAME_MJPEG,
            DEFAULT_PREVIEW_WIDTH,
            DEFAULT_PREVIEW_HEIGHT,
            DEFAULT_PREVIEW_FPS,
            listOf(DEFAULT_PREVIEW_FPS),
        )
    }

    private fun preferredPreviewSize(): Size {
        val supported = runCatching {
            cameraHelper?.getSupportedSizeList().orEmpty()
        }.getOrDefault(emptyList())
        return supported
            .flatMap { size ->
                size.safeFpsList().map { fps -> Size(size.type, size.width, size.height, fps, listOf(fps)) }
            }
            .firstOrNull {
                it.type == UVCCamera.UVC_VS_FRAME_MJPEG &&
                    it.width == DEFAULT_PREVIEW_WIDTH &&
                    it.height == DEFAULT_PREVIEW_HEIGHT &&
                    it.fps == DEFAULT_PREVIEW_FPS
            }
            ?: supported
                .flatMap { size ->
                    size.safeFpsList().map { fps -> Size(size.type, size.width, size.height, fps, listOf(fps)) }
                }
                .firstOrNull {
                    it.width == DEFAULT_PREVIEW_WIDTH &&
                        it.height == DEFAULT_PREVIEW_HEIGHT &&
                        it.fps == DEFAULT_PREVIEW_FPS
                }
            ?: defaultPreviewSize()
    }

    private fun Size.safeFpsList(): List<Int> {
        return (fpsList ?: emptyList()).filter { it > 0 }.ifEmpty {
            listOf(fps.takeIf { it > 0 } ?: DEFAULT_PREVIEW_FPS)
        }.distinct()
    }

    private fun Size.formatLabel(): String {
        return "${width}x$height @ ${fps}fps"
    }

    private fun UsbDevice.isLikelyUvcCamera(): Boolean {
        if (deviceClass == UsbConstants.USB_CLASS_VIDEO || deviceClass == UsbConstants.USB_CLASS_MISC) return true
        for (index in 0 until interfaceCount) {
            val usbInterface = getInterface(index)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_VIDEO || usbInterface.interfaceClass == UsbConstants.USB_CLASS_MISC) {
                return true
            }
        }
        return false
    }

    private fun UsbDevice.displayLabel(): String {
        return "UVC ${vendorId}:${productId}"
    }

    private fun File.mimeTypeFor(): String? {
        return when (extension.lowercase(Locale.US)) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            else -> null
        }
    }
}
