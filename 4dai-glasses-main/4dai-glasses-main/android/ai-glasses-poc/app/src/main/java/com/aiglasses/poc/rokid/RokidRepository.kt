package com.aiglasses.poc.rokid

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Environment
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.aiglasses.poc.VisionRouteApplication
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.callbacks.IImageStreamCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class RokidSessionMode(val label: String) {
    NONE("未连接"),
    CUSTOM_VIEW("CUSTOMVIEW"),
    CUSTOM_APP("CUSTOMAPP"),
}

data class RokidDebugState(
    val mode: RokidSessionMode = RokidSessionMode.NONE,
    val cxrConnected: Boolean = false,
    val btConnected: Boolean = false,
    val customViewOpened: Boolean = false,
    val customAppInstalled: Boolean = false,
    val customAppOpened: Boolean = false,
    val installing: Boolean = false,
    val takingPhoto: Boolean = false,
    val customAppRecording: Boolean = false,
    val lastPhoto: Bitmap? = null,
    val lastPhotoBytes: Int = 0,
    val lastCaptureSummary: String = "",
    val lastImuSummary: String = "",
    val lastVoiceCommandSummary: String = "",
    val lastHudSummary: String = "",
    val lastCommandResponse: String = "",
    val lastRecordInfo: String = "",
    val bareMetalSummary: String = "裸机 HTTP 图传：未连接",
    val bareMetalPhoto: Bitmap? = null,
    val bareMetalPhotoBytes: Int = 0,
    val bareMetalLoading: Boolean = false,
    val bareMetalStreaming: Boolean = false,
    val bareMetalDiscoveryRunning: Boolean = false,
    val bareMetalEndpointSuggestion: String = "",
    val offlineCaptureSummary: String = "离线采集：未连接",
    val offlineCaptureRecording: Boolean = false,
    val offlineCaptureDownloading: Boolean = false,
    val offlineCaptureSessions: String = "暂无离线采集记录",
    val photoBenchmarkSummary: String = "CXR-L 拍照基准：未运行",
    val photoBenchmarkRunning: Boolean = false,
    val serviceVersion: String = "",
    val serviceVersionCode: Int? = null,
    val lastDiagnostic: String = "",
    val requiresReauthorization: Boolean = false,
    val status: String = "Rokid 未连接",
    val logs: List<String> = listOf("Rokid 调试页已就绪"),
) {
    val sceneReady: Boolean
        get() = customViewOpened || customAppOpened

    val linkReady: Boolean
        get() = cxrConnected && btConnected
}

class RokidRepository(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val visionRouteApp = appContext as? VisionRouteApplication
    private val _state = MutableStateFlow(RokidDebugState())
    val state: StateFlow<RokidDebugState> = _state.asStateFlow()

    private var cxrLink: CXRLink? = null
    private var customCommandCount = 0
    private var connectAttemptId = 0
    private var pendingPhotoStartedAtMs: Long? = null
    private var photoBenchmarkSession: PhotoBenchmarkSession? = null
    private var pendingPhotoBenchmarkConfig: PhotoBenchmarkConfig? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bareMetalClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val bareMetalStreamClient = bareMetalClient.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val offlineDownloadClient = bareMetalClient.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
    private val bareMetalDiscoveryClient = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .callTimeout(800, TimeUnit.MILLISECONDS)
        .build()
    @Volatile
    private var bareMetalStreamActive = false
    private var bareMetalStreamCall: Call? = null
    @Volatile
    private var offlineCaptureStatusPolling = false

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            appendLog("CXR-L 连接状态：$connected")
            update { state ->
                val next = state.copy(cxrConnected = connected)
                next.copy(status = linkStatus(next, if (connected) "CXR-L 已连接，等待蓝牙状态" else "CXR-L 未连接"))
            }
            maybeQueryCustomAppInstalled()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            appendLog("Rokid 眼镜蓝牙状态：$connected")
            update { state ->
                val next = state.copy(btConnected = connected)
                next.copy(status = linkStatus(next, if (connected) "Rokid 眼镜蓝牙已连接" else "等待 Rokid 眼镜蓝牙连接"))
            }
            maybeQueryCustomAppInstalled()
        }

        override fun onGlassAiAssistStart() {
            appendLog("Rokid AI Assist start")
        }

        override fun onGlassAiAssistStop() {
            appendLog("Rokid AI Assist stop")
        }

        override fun onGlassDeviceInfo(deviceInfo: GlassInfo) {
            appendLog("Rokid 设备信息：$deviceInfo")
        }

        override fun onGlassWearingStatus(wearing: Boolean) {
            appendLog("Rokid 佩戴状态：$wearing")
        }

        override fun onGlassAiInterrupt(interruptWake: Boolean) {
            appendLog("Rokid AI interrupt=$interruptWake")
        }
    }

    private val customViewCallback = object : ICustomViewCbk {
        override fun onCustomViewOpened() {
            appendLog("CUSTOMVIEW 已打开")
            update { it.copy(customViewOpened = true, status = "CUSTOMVIEW 已打开，可拍照") }
        }

        override fun onCustomViewUpdated() {
            appendLog("CUSTOMVIEW 已更新")
        }

        override fun onCustomViewClosed() {
            appendLog("CUSTOMVIEW 已关闭")
            update { it.copy(customViewOpened = false, status = "CUSTOMVIEW 已关闭") }
        }

        override fun onCustomViewIconsSent() {
            appendLog("CUSTOMVIEW 图标已发送")
        }

        override fun onCustomViewError(code: Int, msg: String?) {
            appendLog("CUSTOMVIEW 错误 code=$code msg=${msg.orEmpty()}")
            update { it.copy(customViewOpened = false, status = "CUSTOMVIEW 错误：$code ${msg.orEmpty()}") }
        }
    }

    private val imageCallback = object : IImageStreamCbk {
        override fun onImageReceived(data: ByteArray?) {
            val bytes = data ?: ByteArray(0)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val nowMs = System.currentTimeMillis()
            val captureLatencyMs = pendingPhotoStartedAtMs?.let { nowMs - it }
            pendingPhotoStartedAtMs = null
            val event = RokidRuntimeBridge.onImageReceived(
                bytes = bytes,
                width = bitmap?.width,
                height = bitmap?.height,
                captureLatencyMs = captureLatencyMs,
                nowMs = nowMs,
            )
            appendLog("rokid_image_received ${event.summary()} capture_latency_ms=${captureLatencyMs ?: -1}")
            recordPhotoBenchmarkSample(captureLatencyMs, bytes.size, success = bitmap != null)
            update {
                it.copy(
                    takingPhoto = false,
                    lastPhoto = bitmap,
                    lastPhotoBytes = bytes.size,
                    lastCaptureSummary = event.summary(),
                    status = if (bitmap != null) "Rokid 拍照成功" else "收到 JPEG，但 Bitmap 解码失败",
                )
            }
        }

        override fun onImageError(code: Int, msg: String?) {
            appendLog("Rokid 拍照失败 code=$code msg=${msg.orEmpty()}")
            update { it.copy(takingPhoto = false, status = "Rokid 拍照失败：$code ${msg.orEmpty()}") }
        }
    }

    private val glassAppCallback = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) {
            appendLog("CUSTOMAPP 安装/更新结果：$success")
            update { it.copy(installing = false, customAppInstalled = success, status = if (success) "眼镜端 App 已安装/更新" else "眼镜端 App 安装/更新失败") }
            if (success) queryCustomAppInstalled()
        }

        override fun onUnInstallAppResult(success: Boolean) {
            appendLog("CUSTOMAPP 卸载结果：$success")
            if (success) RokidRuntimeBridge.setHudCommandSender(null)
            update { it.copy(customAppInstalled = !success, customAppOpened = false) }
        }

        override fun onOpenAppResult(success: Boolean) {
            appendLog("CUSTOMAPP 打开结果：$success")
            RokidRuntimeBridge.setHudCommandSender(if (success) ::sendHudPayloadFromBridge else null)
            update { it.copy(customAppOpened = success, status = if (success) "CUSTOMAPP 已打开，可发送指令/拍照" else "CUSTOMAPP 打开失败") }
        }

        override fun onStopAppResult(success: Boolean) {
            appendLog("CUSTOMAPP 停止结果：$success")
            if (success) RokidRuntimeBridge.setHudCommandSender(null)
            update { it.copy(customAppOpened = !success, status = if (success) "CUSTOMAPP 已停止" else it.status) }
        }

        override fun onGlassAppResume(resumed: Boolean) {
            appendLog("CUSTOMAPP resume：$resumed")
            RokidRuntimeBridge.setHudCommandSender(if (resumed) ::sendHudPayloadFromBridge else null)
            update { it.copy(customAppOpened = resumed) }
        }

        override fun onQueryAppResult(installed: Boolean) {
            appendLog("CUSTOMAPP 查询安装：$installed")
            update { it.copy(customAppInstalled = installed, status = if (installed) "眼镜端协同 App 已安装" else "眼镜端协同 App 未安装") }
        }
    }

    private val customCommandCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (key != ROKID_RESPONSE_KEY) return
            val caps = payload?.let { Caps.fromBytes(it) }
            val text = caps?.let { parseCaps(it) } ?: "empty payload"
            appendLog("收到自定义指令回包：$text")
            val pairs = caps?.let { parseCapsPairs(it) }.orEmpty()
            markCustomAppInteractive()
            handleCustomAppEvent(pairs, text)
        }
    }

    fun connectCustomView(token: String?) {
        if (token.isNullOrBlank()) {
            update { it.copy(status = "请先完成 Rokid 授权") }
            return
        }
        releaseLink()
        val link = CXRLink(context).apply {
            configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMVIEW))
            setCXRLinkCbk(linkCallback)
            setCXRCustomViewCbk(customViewCallback)
            setCXRImageCbk(imageCallback)
        }
        cxrLink = link
        visionRouteApp?.sharedRokidCxrLink = link
        update {
            it.copy(
                mode = RokidSessionMode.CUSTOM_VIEW,
                customViewOpened = false,
                customAppOpened = false,
                requiresReauthorization = false,
                status = "正在建立 CUSTOMVIEW 会话…",
            )
        }
        appendLog("发起 CUSTOMVIEW connect")
        val accepted = runCatching { link.connect(token) }.getOrDefault(false)
        appendLog("CUSTOMVIEW connect accepted=$accepted")
        if (accepted) {
            val attemptId = ++connectAttemptId
            scheduleLinkStateProbes(attemptId, RokidSessionMode.CUSTOM_VIEW)
            scheduleConnectTimeout(attemptId, RokidSessionMode.CUSTOM_VIEW)
        } else {
            update { it.copy(status = "CUSTOMVIEW 会话请求未被 SDK 接受，请检查 Rokid AI App 与授权状态") }
        }
    }

    fun probeBareMetalStatus(endpoint: String) {
        val baseUrl = normalizeBareMetalEndpoint(endpoint)
        update { it.copy(bareMetalLoading = true, bareMetalSummary = "HTTP 图传：正在检查眼镜状态") }
        Thread {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/status")
                    .get()
                    .build()
                bareMetalClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}: $body")
                    appendLog("rokid_bare_metal_status $body")
                    update {
                        it.copy(
                            bareMetalLoading = false,
                            bareMetalSummary = "HTTP 图传：眼镜在线\n$baseUrl",
                        )
                    }
                }
            }.onFailure { throwable ->
                val message = throwable.message ?: throwable.javaClass.simpleName
                appendLog("rokid_bare_metal_status_error $message")
                update {
                    it.copy(
                        bareMetalLoading = false,
                        bareMetalSummary = "HTTP 图传：状态检查失败\n请确认眼镜端已启动并在同一 Wi‑Fi/热点。",
                    )
                }
            }
        }.start()
    }

    fun discoverBareMetalEndpoint(endpoint: String) {
        if (_state.value.bareMetalDiscoveryRunning) return
        val candidates = buildBareMetalDiscoveryCandidates(endpoint)
        update {
            it.copy(
                bareMetalDiscoveryRunning = true,
                bareMetalSummary = "HTTP 图传：正在自动发现眼镜",
            )
        }
        Thread {
            appendLog("rokid_bare_metal_discovery_start candidates=${candidates.size}")
            val executor = Executors.newFixedThreadPool(BareMetalDiscovery.MAX_PARALLEL_REQUESTS)
            val completion = ExecutorCompletionService<Pair<String, String>?>(executor)
            candidates.forEach { baseUrl ->
                completion.submit(Callable { probeBareMetalEndpoint(bareMetalDiscoveryClient, baseUrl) })
            }
            var found: Pair<String, String>? = null
            runCatching {
                for (index in candidates.indices) {
                    val result = completion.poll(BareMetalDiscovery.POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)?.get()
                    if (result != null) {
                        found = result
                        break
                    }
                }
            }
            executor.shutdownNow()
            val discovered = found
            if (discovered != null) {
                appendLog("rokid_bare_metal_discovery_found endpoint=${discovered.first} body=${discovered.second}")
                update {
                    it.copy(
                        bareMetalDiscoveryRunning = false,
                        bareMetalEndpointSuggestion = discovered.first,
                        bareMetalSummary = "HTTP 图传：已发现眼镜\n${discovered.first}",
                    )
                }
            } else {
                appendLog("rokid_bare_metal_discovery_not_found")
                update {
                    it.copy(
                        bareMetalDiscoveryRunning = false,
                        bareMetalSummary = "HTTP 图传：未发现眼镜\n请确认手机和眼镜在同一 Wi‑Fi/热点，并已打开 VisionRoute RokidBridge。",
                    )
                }
            }
        }.start()
    }

    fun captureBareMetalFrame(endpoint: String) {
        val baseUrl = normalizeBareMetalEndpoint(endpoint)
        update { it.copy(bareMetalLoading = true, bareMetalSummary = "HTTP 图传：正在获取一帧画面") }
        Thread {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/capture.jpg")
                    .get()
                    .build()
                bareMetalClient.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (!response.isSuccessful) error("HTTP ${response.code}: ${String(bytes)}")
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val latency = response.header("X-Capture-Latency-Ms").orEmpty()
                    val frameCount = response.header("X-Frame-Count").orEmpty()
                    val event = rememberBareMetalFrame(
                        bytes = bytes,
                        bitmap = bitmap,
                        captureLatencyMs = latency.toLongOrNull(),
                    )
                    appendLog("rokid_bare_metal_frame bytes=${bytes.size} latency_ms=$latency frame_count=$frameCount decoded=${bitmap != null} capture_id=${event?.captureId.orEmpty()}")
                    update {
                        it.copy(
                            bareMetalLoading = false,
                            bareMetalPhoto = bitmap,
                            bareMetalPhotoBytes = bytes.size,
                            bareMetalSummary = "HTTP 图传：已收到眼镜画面\n${bitmap?.width ?: "-"}×${bitmap?.height ?: "-"}",
                        )
                    }
                }
            }.onFailure { throwable ->
                val message = throwable.message ?: throwable.javaClass.simpleName
                appendLog("rokid_bare_metal_frame_error $message")
                update {
                    it.copy(
                        bareMetalLoading = false,
                        bareMetalSummary = "HTTP 图传：取帧失败\n请检查眼镜端和网络连接。",
                    )
                }
            }
        }.start()
    }

    fun startBareMetalStream(endpoint: String) {
        if (bareMetalStreamActive) return
        val baseUrl = normalizeBareMetalEndpoint(endpoint)
        bareMetalStreamActive = true
        update {
            it.copy(
                bareMetalLoading = false,
                bareMetalStreaming = true,
                bareMetalSummary = "HTTP 图传：正在连接眼镜画面",
            )
        }
        Thread {
            var frameCount = 0
            val startedAtMs = System.currentTimeMillis()
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/mjpeg")
                    .get()
                    .build()
                val call = bareMetalStreamClient.newCall(request)
                bareMetalStreamCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        error("HTTP ${response.code}: $body")
                    }
                    val input = response.body?.byteStream() ?: error("empty stream")
                    appendLog("rokid_bare_metal_stream_started endpoint=$baseUrl/mjpeg")
                    readJpegFrames(input) { bytes ->
                        if (!bareMetalStreamActive) return@readJpegFrames false
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        frameCount += 1
                        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
                        val fps = frameCount * 1000.0 / elapsedMs
                        val event = rememberBareMetalFrame(
                            bytes = bytes,
                            bitmap = bitmap,
                            captureLatencyMs = null,
                        )
                        appendLog("rokid_bare_metal_stream_frame index=$frameCount bytes=${bytes.size} decoded=${bitmap != null} capture_id=${event?.captureId.orEmpty()}")
                        update {
                            it.copy(
                                bareMetalPhoto = bitmap,
                                bareMetalPhotoBytes = bytes.size,
                                bareMetalSummary = "HTTP 图传：接收中 · ${fps.format1()} FPS\n${bitmap?.width ?: "-"}×${bitmap?.height ?: "-"}",
                            )
                        }
                        true
                    }
                }
                appendLog("rokid_bare_metal_stream_finished frames=$frameCount")
            }.onFailure { throwable ->
                val message = throwable.message ?: throwable.javaClass.simpleName
                if (bareMetalStreamActive) {
                    appendLog("rokid_bare_metal_stream_error $message")
                    update {
                        it.copy(
                            bareMetalSummary = "HTTP 图传：连接失败\n请检查眼镜端和网络连接。",
                        )
                    }
                } else {
                    appendLog("rokid_bare_metal_stream_stopped frames=$frameCount")
                }
            }
            bareMetalStreamCall = null
            if (bareMetalStreamActive) {
                bareMetalStreamActive = false
                update { it.copy(bareMetalStreaming = false) }
            }
        }.start()
    }

    private fun rememberBareMetalFrame(
        bytes: ByteArray,
        bitmap: Bitmap?,
        captureLatencyMs: Long?,
    ): RokidCapturedFrameEvent? {
        if (bitmap == null) return null
        return RokidRuntimeBridge.onImageReceived(
            bytes = bytes,
            width = bitmap.width,
            height = bitmap.height,
            captureLatencyMs = captureLatencyMs,
            captureMode = "glasses_private_stream",
        )
    }

    fun stopBareMetalStream() {
        if (!bareMetalStreamActive) return
        bareMetalStreamActive = false
        bareMetalStreamCall?.cancel()
        bareMetalStreamCall = null
        update {
            it.copy(
                bareMetalStreaming = false,
                bareMetalSummary = "HTTP 图传：已停止接收",
            )
        }
    }

    fun refreshOfflineCaptureStatus(endpoint: String) {
        update { it.copy(offlineCaptureSummary = "离线采集：正在确认眼镜地址…") }
        Thread {
            runCatching {
                val baseUrl = resolveBareMetalEndpointForOffline(endpoint, "status")
                fetchOfflineCaptureStatus(baseUrl)
            }.onFailure { throwable ->
                val message = throwable.message ?: throwable.javaClass.simpleName
                appendLog("rokid_offline_status_error $message")
                update {
                    it.copy(
                        offlineCaptureSummary = "离线采集：状态查询失败\n$message",
                    )
                }
            }
        }.start()
    }

    fun startOfflineCapture(endpoint: String) {
        stopBareMetalStream()
        update {
            it.copy(
                offlineCaptureSummary = "离线采集：正在确认眼镜地址…",
                offlineCaptureRecording = false,
            )
        }
        Thread {
            runCatching {
                val baseUrl = resolveBareMetalEndpointForOffline(endpoint, "start")
                update {
                    it.copy(
                        offlineCaptureSummary = "离线采集：正在请求眼镜端开始录像…",
                        offlineCaptureRecording = true,
                    )
                }
                val request = Request.Builder()
                    .url("$baseUrl/sessions/start?width=1280&height=720&fps=60")
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
                bareMetalClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}: $body")
                    val json = JSONObject(body)
                    if (json.optInt("code", 1) != 0) error(json.optString("message", "start_failed"))
                    appendLog("rokid_offline_start $body")
                    renderOfflineCapture(baseUrl, json.optJSONObject("offline_capture"), "离线采集：已开始")
                    startOfflineStatusPolling(baseUrl)
                }
            }.onFailure { throwable ->
                val message = throwable.message ?: throwable.javaClass.simpleName
                appendLog("rokid_offline_start_error $message")
                update {
                    it.copy(
                        offlineCaptureRecording = false,
                        offlineCaptureSummary = "离线采集：开始失败\n$message",
                    )
                }
            }
        }.start()
    }

    fun stopOfflineCapture(endpoint: String) {
        update { it.copy(offlineCaptureSummary = "离线采集：正在停止并封装 session…") }
        Thread {
            runCatching {
                val baseUrl = resolveBareMetalEndpointForOffline(endpoint, "stop")
                val request = Request.Builder()
                    .url("$baseUrl/sessions/current/stop")
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
                bareMetalClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}: $body")
                    val json = JSONObject(body)
                    if (json.optInt("code", 1) != 0) error(json.optString("message", "stop_failed"))
                    appendLog("rokid_offline_stop $body")
                    offlineCaptureStatusPolling = false
                    renderOfflineCapture(baseUrl, json.optJSONObject("offline_capture"), "离线采集：已停止")
                    refreshOfflineCaptureSessions(baseUrl)
                }
            }.onFailure { throwable ->
                val message = throwable.message ?: throwable.javaClass.simpleName
                appendLog("rokid_offline_stop_error $message")
                update { it.copy(offlineCaptureSummary = "离线采集：停止失败\n$message") }
            }
        }.start()
    }

    fun downloadLatestOfflineSession(endpoint: String) {
        update { it.copy(offlineCaptureDownloading = true, offlineCaptureSessions = "正在查询眼镜端 session…") }
        Thread {
            runCatching {
                val baseUrl = resolveBareMetalEndpointForOffline(endpoint, "download")
                val sessions = requestText("$baseUrl/sessions")
                val sessionId = JSONObject(sessions)
                    .optJSONArray("sessions")
                    ?.optJSONObject(0)
                    ?.optString("session_id")
                    .orEmpty()
                if (sessionId.isBlank()) error("暂无可下载 session")
                val request = Request.Builder()
                    .url("$baseUrl/sessions/$sessionId/download")
                    .get()
                    .build()
                offlineDownloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}: ${response.body?.string().orEmpty()}")
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) error("下载文件为空")
                    val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?.resolve("RokidOfflineSessions")
                        ?: File(appContext.filesDir, "RokidOfflineSessions")
                    dir.mkdirs()
                    val output = dir.resolve("$sessionId.zip")
                    output.writeBytes(bytes)
                    appendLog("rokid_offline_download session_id=$sessionId bytes=${bytes.size} path=${output.absolutePath}")
                    update {
                        it.copy(
                            offlineCaptureDownloading = false,
                            offlineCaptureSessions = "已下载：$sessionId\n${output.absolutePath}\n${bytes.size / 1024} KB",
                        )
                    }
                }
            }.onFailure { throwable ->
                val message = throwable.message ?: throwable.javaClass.simpleName
                appendLog("rokid_offline_download_error $message")
                update {
                    it.copy(
                        offlineCaptureDownloading = false,
                        offlineCaptureSessions = "下载失败：$message",
                    )
                }
            }
        }.start()
    }

    private fun startOfflineStatusPolling(baseUrl: String) {
        if (offlineCaptureStatusPolling) return
        offlineCaptureStatusPolling = true
        Thread {
            while (offlineCaptureStatusPolling) {
                runCatching {
                    val recording = fetchOfflineCaptureStatus(baseUrl)
                    if (!recording) {
                        offlineCaptureStatusPolling = false
                    }
                }.onFailure { throwable ->
                    appendLog("rokid_offline_poll_error ${throwable.message ?: throwable.javaClass.simpleName}")
                }
                Thread.sleep(1_000L)
            }
        }.start()
    }

    private fun fetchOfflineCaptureStatus(baseUrl: String): Boolean {
        val body = requestText("$baseUrl/status")
        appendLog("rokid_offline_status $body")
        val offline = JSONObject(body).optJSONObject("offline_capture")
        renderOfflineCapture(baseUrl, offline, null)
        return offline?.optBoolean("recording", false) == true
    }

    private fun refreshOfflineCaptureSessions(baseUrl: String) {
        runCatching {
            val body = requestText("$baseUrl/sessions")
            val array = JSONObject(body).optJSONArray("sessions") ?: JSONArray()
            val lines = buildList {
                add("眼镜端 session：${array.length()} 个")
                for (index in 0 until minOf(array.length(), 5)) {
                    val item = array.optJSONObject(index) ?: continue
                    add("${index + 1}. ${item.optString("session_id")} · video=${item.optLong("video_bytes") / 1024}KB · imu=${item.optLong("imu_bytes") / 1024}KB")
                }
            }
            update { it.copy(offlineCaptureSessions = lines.joinToString("\n")) }
        }
    }

    private fun requestText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        bareMetalClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $body")
            return body
        }
    }

    private fun resolveBareMetalEndpointForOffline(endpoint: String, action: String): String {
        val candidates = linkedSetOf<String>()
        candidates += normalizeBareMetalEndpoint(endpoint)
        _state.value.bareMetalEndpointSuggestion
            .takeIf { it.isNotBlank() }
            ?.let { candidates += normalizeBareMetalEndpoint(it) }
        candidates.forEach { baseUrl ->
            if (probeBareMetalEndpoint(bareMetalDiscoveryClient, baseUrl) != null) {
                appendLog("rokid_offline_endpoint_ready action=$action endpoint=$baseUrl")
                update {
                    it.copy(
                        bareMetalEndpointSuggestion = baseUrl,
                        bareMetalSummary = "HTTP 图传：眼镜在线\n$baseUrl",
                    )
                }
                return baseUrl
            }
        }

        update {
            it.copy(
                offlineCaptureSummary = "离线采集：正在自动发现眼镜…\n请确认手机和眼镜在同一 Wi‑Fi/热点。",
            )
        }
        appendLog("rokid_offline_endpoint_discovery_start action=$action")
        val discovered = discoverBareMetalEndpointBlocking(endpoint)
        if (discovered != null) {
            appendLog("rokid_offline_endpoint_discovered action=$action endpoint=${discovered.first}")
            update {
                it.copy(
                    bareMetalEndpointSuggestion = discovered.first,
                    bareMetalSummary = "HTTP 图传：已发现眼镜\n${discovered.first}",
                )
            }
            return discovered.first
        }
        error("未发现眼镜 HTTP 服务。请先在眼镜端打开 VisionRoute RokidBridge，并确认手机和眼镜在同一 Wi‑Fi/热点。")
    }

    private fun discoverBareMetalEndpointBlocking(endpoint: String): Pair<String, String>? {
        val candidates = buildBareMetalDiscoveryCandidates(endpoint)
        val executor = Executors.newFixedThreadPool(BareMetalDiscovery.MAX_PARALLEL_REQUESTS)
        val completion = ExecutorCompletionService<Pair<String, String>?>(executor)
        candidates.forEach { baseUrl ->
            completion.submit(Callable { probeBareMetalEndpoint(bareMetalDiscoveryClient, baseUrl) })
        }
        return try {
            var found: Pair<String, String>? = null
            for (index in candidates.indices) {
                val result = completion.poll(BareMetalDiscovery.POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)?.get()
                if (result != null) {
                    found = result
                    break
                }
            }
            found
        } finally {
            executor.shutdownNow()
        }
    }

    private fun renderOfflineCapture(baseUrl: String, offline: JSONObject?, prefix: String?) {
        if (offline == null) {
            update {
                it.copy(
                    offlineCaptureRecording = false,
                    offlineCaptureSummary = listOfNotNull(prefix, "离线采集：眼镜端未返回 offline_capture", baseUrl).joinToString("\n"),
                )
            }
            return
        }
        val recording = offline.optBoolean("recording", false)
        val state = offline.optString("state", if (recording) "recording" else "idle")
        val width = offline.optInt("actual_width", offline.optInt("requested_width", 0))
        val height = offline.optInt("actual_height", offline.optInt("requested_height", 0))
        val targetFps = offline.optInt("requested_fps", 0)
        val actualFps = offline.optDouble("actual_video_fps", 0.0)
        val imuHz = offline.optDouble("actual_imu_hz", 0.0)
        val frames = offline.optLong("encoded_frame_count", 0L)
        val imuSamples = offline.optLong("imu_sample_count", 0L)
        val durationMs = offline.optLong("duration_ms", 0L)
        val sessionId = offline.optString("session_id")
        val error = offline.optString("error")
        val summary = buildList {
            if (!prefix.isNullOrBlank()) add(prefix)
            add("状态：${if (recording) "录制中" else state}")
            if (sessionId.isNotBlank()) add("Session：$sessionId")
            add("视频：${width}×${height} · 目标 ${targetFps}Hz · 实际 ${actualFps.format1()} FPS · $frames 帧")
            add("IMU：${imuHz.format1()} Hz · $imuSamples 条")
            add("时长：${formatDuration(durationMs)}")
            add("地址：$baseUrl")
            if (error.isNotBlank()) add("错误：$error")
        }.joinToString("\n")
        update {
            it.copy(
                offlineCaptureRecording = recording,
                offlineCaptureSummary = summary,
            )
        }
    }

    fun openCustomView() {
        val link = cxrLink ?: return update { it.copy(status = "请先建立 CUSTOMVIEW 会话") }
        if (_state.value.mode != RokidSessionMode.CUSTOM_VIEW) {
            update { it.copy(status = "当前不是 CUSTOMVIEW 会话") }
            return
        }
        runCatching {
            link.customViewOpen(buildCustomViewJson())
        }.onSuccess {
            appendLog("已发送 customViewOpen")
            update { it.copy(status = "已发送 CUSTOMVIEW 打开请求") }
        }.onFailure { throwable ->
            appendLog("customViewOpen failed：${throwable.message ?: throwable.javaClass.simpleName}")
            update { it.copy(status = "CUSTOMVIEW 打开失败：${throwable.message ?: throwable.javaClass.simpleName}") }
        }
    }

    fun closeCustomView() {
        runCatching { cxrLink?.customViewClose() }
        update { it.copy(customViewOpened = false, status = "已请求关闭 CUSTOMVIEW") }
    }

    fun connectCustomApp(token: String?) {
        if (token.isNullOrBlank()) {
            update { it.copy(status = "请先完成 Rokid 授权") }
            return
        }
        releaseLink()
        val link = CXRLink(context).apply {
            configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, ROKID_APP_PACKAGE))
            setCXRLinkCbk(linkCallback)
            setCXRImageCbk(imageCallback)
            setCXRCustomCmdCbk(customCommandCallback)
        }
        cxrLink = link
        visionRouteApp?.sharedRokidCxrLink = link
        update {
            it.copy(
                mode = RokidSessionMode.CUSTOM_APP,
                customViewOpened = false,
                customAppOpened = false,
                requiresReauthorization = false,
                status = "正在建立 CUSTOMAPP 会话…",
            )
        }
        appendLog("发起 CUSTOMAPP connect")
        val accepted = runCatching { link.connect(token) }.getOrDefault(false)
        appendLog("CUSTOMAPP connect accepted=$accepted")
        if (accepted) {
            val attemptId = ++connectAttemptId
            scheduleLinkStateProbes(attemptId, RokidSessionMode.CUSTOM_APP)
            scheduleConnectTimeout(attemptId, RokidSessionMode.CUSTOM_APP)
        } else {
            update { it.copy(status = "CUSTOMAPP 会话请求未被 SDK 接受，请检查 Rokid AI App 与授权状态") }
        }
    }

    fun queryCustomAppInstalled() {
        val link = cxrLink ?: return update { it.copy(status = "请先建立 CUSTOMAPP 会话") }
        runCatching { link.appIsInstalled(glassAppCallback) }
            .onFailure { throwable -> update { it.copy(status = "查询协同 App 失败：${throwable.message ?: throwable.javaClass.simpleName}") } }
    }

    fun installCustomApp(endpoint: String = "") {
        val link = cxrLink ?: return update { it.copy(status = "请先建立 CUSTOMAPP 会话") }
        val apk = resolveInstallApkCandidates().firstOrNull()
        if (apk == null) {
            update { it.copy(status = "未找到眼镜端 APK：内置包缺失，或请放到 /sdcard/DCIM/Rokid/cxrL.apk") }
            return
        }
        val embeddedInfo = readBridgeApkInfo(apk)
        update {
            it.copy(
                installing = true,
                status = "正在检查眼镜端 App 版本…\n本地：${embeddedInfo?.label().orEmpty().ifBlank { apk.name }}",
            )
        }
        Thread {
            val installedInfo = discoverBridgeAppInfoForUpdate(link, endpoint)
            if (embeddedInfo != null && installedInfo != null && installedInfo.info.matches(embeddedInfo)) {
                appendLog("眼镜端 App 已是最新 local=${embeddedInfo.label()} remote=${installedInfo.info.label()} endpoint=${installedInfo.endpoint}")
                update {
                    it.copy(
                        installing = false,
                        customAppInstalled = true,
                        bareMetalEndpointSuggestion = installedInfo.endpoint,
                        status = "眼镜端 App 已是最新，无需更新\n眼镜：${installedInfo.info.label()}\n本地：${embeddedInfo.label()}",
                    )
                }
                return@Thread
            }
            val reason = when {
                embeddedInfo == null -> "无法读取本地 APK 版本信息，执行更新"
                installedInfo == null -> "未读取到眼镜端版本信息，执行更新"
                else -> "版本或编译时间不一致，执行更新\n眼镜：${installedInfo.info.label()}\n本地：${embeddedInfo.label()}"
            }
            appendLog("眼镜端 App 需要更新：$reason")
            mainHandler.post {
                runCatching {
                    link.appUploadAndInstall(apk.absolutePath, glassAppCallback)
                }.onSuccess {
                    appendLog("开始上传安装/更新：${apk.absolutePath}")
                    update { it.copy(installing = true, status = "正在上传安装/更新眼镜端 App…\n$reason") }
                }.onFailure { throwable ->
                    update { it.copy(installing = false, status = "安装/更新请求失败：${throwable.message ?: throwable.javaClass.simpleName}") }
                }
            }
        }
            .also { it.name = "rokid-bridge-update-check" }
            .start()
    }

    fun openCustomApp() {
        val link = cxrLink ?: return update { it.copy(status = "请先建立 CUSTOMAPP 会话") }
        runCatching { link.appStart("$ROKID_APP_PACKAGE$ROKID_APP_MAIN_PAGE", glassAppCallback) }
            .onSuccess { update { it.copy(status = "已发送 CUSTOMAPP 打开请求") } }
            .onFailure { throwable -> update { it.copy(status = "CUSTOMAPP 打开请求失败：${throwable.message ?: throwable.javaClass.simpleName}") } }
    }

    fun stopCustomApp() {
        val link = cxrLink ?: return
        if (_state.value.customAppOpened) {
            sendCustomAppAction("EXIT_APP", "已发送 EXIT_APP，等待眼镜端协同 App 退出…")
            mainHandler.postDelayed({
                runCatching { cxrLink?.appStop(glassAppCallback) }
                update { it.copy(status = "已发送 CUSTOMAPP 停止请求") }
            }, 600L)
            return
        }
        runCatching { link.appStop(glassAppCallback) }
        update { it.copy(status = "已发送 CUSTOMAPP 停止请求") }
    }

    fun takePhoto() {
        if (_state.value.photoBenchmarkRunning) {
            update { it.copy(status = "CXR-L 拍照基准运行中，请等待完成") }
            return
        }
        val link = cxrLink ?: return update { it.copy(status = "请先建立 Rokid 会话") }
        if (!_state.value.sceneReady) {
            update { it.copy(status = "请先打开 CUSTOMVIEW 或 CUSTOMAPP 场景，再拍照") }
            return
        }
        val startedAtMs = System.currentTimeMillis()
        pendingPhotoStartedAtMs = startedAtMs
        update { it.copy(takingPhoto = true, lastPhoto = null, lastPhotoBytes = 0, status = "正在触发 Rokid 拍照…") }
        runCatching { link.takePhoto(1024, 768, 80) }
            .onSuccess {
                schedulePhotoTimeout(startedAtMs)
            }
            .onFailure { throwable ->
                pendingPhotoStartedAtMs = null
                update { it.copy(takingPhoto = false, status = "Rokid 拍照请求失败：${throwable.message ?: throwable.javaClass.simpleName}") }
            }
    }

    fun startPhotoBenchmark() {
        val link = cxrLink ?: return update { it.copy(status = "请先建立 Rokid 会话") }
        if (!_state.value.sceneReady) {
            update { it.copy(status = "请先打开 CUSTOMVIEW 或 CUSTOMAPP 场景，再运行拍照基准") }
            return
        }
        if (_state.value.photoBenchmarkRunning) return
        val queue = PHOTO_BENCHMARK_CONFIGS.flatMap { config ->
            List(PHOTO_BENCHMARK_ROUNDS) { config }
        }.toMutableList()
        photoBenchmarkSession = PhotoBenchmarkSession(queue = queue)
        appendLog("rokid_photo_benchmark_start rounds=$PHOTO_BENCHMARK_ROUNDS configs=${PHOTO_BENCHMARK_CONFIGS.joinToString { it.label }}")
        update {
            it.copy(
                photoBenchmarkRunning = true,
                photoBenchmarkSummary = "CXR-L 拍照基准：准备开始，共 ${queue.size} 次",
                takingPhoto = true,
                status = "CXR-L 拍照基准运行中…",
            )
        }
        requestNextPhotoBenchmarkSample(link)
    }

    fun sendCustomCommand() {
        sendCustomAppAction("PING", "已发送自定义指令，等待回包…")
    }

    fun openGlassWifiSettings() {
        sendCustomAppAction("OPEN_WIFI_SETTINGS", "已请求眼镜端打开 Wi‑Fi 设置…")
    }

    fun startCustomAppRecord() {
        sendCustomAppAction(
            action = "START_RECORD",
            pendingStatus = "已发送 START_RECORD，等待眼镜端 Camera2+IMU 离线采集开始…",
            extras = mapOf("width" to "1280", "height" to "720", "fps" to "60"),
        )
    }

    fun stopCustomAppRecord() {
        sendCustomAppAction("STOP_RECORD", "已发送 STOP_RECORD，等待眼镜端录像文件信息…")
    }

    fun sendDemoHudUpdate() {
        val payload = RokidHudPayload(
            directionArrow = "↑",
            nextAction = "直行",
            targetName = "B17",
            floorId = "F1",
            distanceToNextActionMeters = 12.0,
            headingState = RokidRuntimeBridge.latestImuSample()?.let { "imu_bridging" } ?: "heading_unavailable",
            statusText = "VisionRoute HUD 调试",
        )
        RokidRuntimeBridge.onHudUpdate(payload)
        sendCustomAppAction(
            action = "HUD_UPDATE",
            pendingStatus = "已下发 Rokid HUD 调试指向标…",
            requestId = payload.requestId,
            extras = payload.toCommandPairs(),
        )
        update { it.copy(lastHudSummary = payload.summary()) }
    }

    private fun sendHudPayloadFromBridge(payload: RokidHudPayload): Boolean {
        val link = cxrLink ?: return false
        if (!_state.value.customAppOpened) return false
        return runCatching {
            link.sendCustomCmd(
                ROKID_CLIENT_KEY,
                Caps().apply {
                    write("action")
                    write("HUD_UPDATE")
                    write("request_id")
                    write(payload.requestId)
                    write("source")
                    write("VisionRoute")
                    payload.toCommandPairs().forEach { (key, value) ->
                        write(key)
                        write(value)
                    }
                },
            )
        }.onSuccess {
            appendLog("已发送 Rokid HUD request_id=${payload.requestId} action=${payload.nextAction}")
            update { it.copy(status = "已下发 Rokid HUD：${payload.nextAction}", lastHudSummary = payload.summary()) }
        }.onFailure { throwable ->
            appendLog("Rokid HUD 下发失败 request_id=${payload.requestId} message=${throwable.message ?: throwable.javaClass.simpleName}")
            update { it.copy(status = "Rokid HUD 下发失败：${throwable.message ?: throwable.javaClass.simpleName}") }
        }.isSuccess
    }

    private fun markCustomAppInteractive() {
        if (_state.value.mode != RokidSessionMode.CUSTOM_APP) return
        RokidRuntimeBridge.setHudCommandSender(::sendHudPayloadFromBridge)
        update { state ->
            if (state.customAppOpened && state.customAppInstalled) {
                state
            } else {
                state.copy(
                    customAppInstalled = true,
                    customAppOpened = true,
                    status = if (state.status.startsWith("已发送")) {
                        state.status
                    } else {
                        "CUSTOMAPP 已通信，可下发 HUD/指令"
                    },
                )
            }
        }
    }

    fun injectDemoImuSample() {
        val sample = RokidRuntimeBridge.onImuSample(
            RokidImuSample(
                imuTimestampMs = System.currentTimeMillis(),
                yawDeg = 90.0,
                pitchDeg = 0.0,
                rollDeg = 0.0,
                accuracy = "debug",
            ),
        )
        appendLog("rokid_imu_sample ${sample.summary()} source=debug_inject")
        update { it.copy(lastImuSummary = sample.summary(), status = "已注入 Rokid IMU 调试样本") }
    }

    fun injectDemoVoiceCommand() {
        val command = RokidRuntimeBridge.onVoiceCommand(
            RokidVoiceCommandParser.parse("Hi Rokid，我要去 B17"),
        )
        appendLog("rokid_voice_command ${command.summary()} source=debug_inject")
        update { it.copy(lastVoiceCommandSummary = command.summary(), status = "已注入 Rokid 语音命令：B17") }
    }

    private fun sendCustomAppAction(
        action: String,
        pendingStatus: String,
        requestId: String = "vr_${System.currentTimeMillis()}_${customCommandCount++}",
        extras: Map<String, String> = emptyMap(),
    ) {
        val link = cxrLink ?: return update { it.copy(status = "请先建立 CUSTOMAPP 会话") }
        if (!_state.value.customAppOpened) {
            update { it.copy(status = "请先打开眼镜端 CUSTOMAPP，再发送自定义指令") }
            return
        }
        runCatching {
            link.sendCustomCmd(
                ROKID_CLIENT_KEY,
                Caps().apply {
                    write("action")
                    write(action)
                    write("request_id")
                    write(requestId)
                    write("source")
                    write("VisionRoute")
                    extras.forEach { (key, value) ->
                        write(key)
                        write(value)
                    }
                },
            )
        }.onSuccess {
            appendLog("已发送 Rokid 指令 action=$action request_id=$requestId")
            update { it.copy(status = pendingStatus) }
        }.onFailure { throwable ->
            update { it.copy(status = "发送自定义指令失败：${throwable.message ?: throwable.javaClass.simpleName}") }
        }
    }

    fun release() {
        stopBareMetalStream()
        offlineCaptureStatusPolling = false
        releaseLink()
    }

    private fun releaseLink() {
        connectAttemptId++
        pendingPhotoStartedAtMs = null
        val state = _state.value
        runCatching { if (state.customViewOpened) cxrLink?.customViewClose() }
        runCatching { if (state.customAppOpened) cxrLink?.appStop(glassAppCallback) }
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        visionRouteApp?.sharedRokidCxrLink = null
        RokidRuntimeBridge.setHudCommandSender(null)
        update {
            it.copy(
                mode = RokidSessionMode.NONE,
                cxrConnected = false,
                btConnected = false,
                customViewOpened = false,
                customAppOpened = false,
                customAppRecording = false,
                takingPhoto = false,
                lastDiagnostic = "",
                requiresReauthorization = false,
            )
        }
    }

    private fun schedulePhotoTimeout(startedAtMs: Long) {
        mainHandler.postDelayed({
            if (pendingPhotoStartedAtMs != startedAtMs || !_state.value.takingPhoto) return@postDelayed
            pendingPhotoStartedAtMs = null
            val benchmarkConfig = pendingPhotoBenchmarkConfig
            pendingPhotoBenchmarkConfig = null
            if (benchmarkConfig != null) {
                appendLog("rokid_photo_benchmark_timeout config=${benchmarkConfig.label} timeout_ms=$PHOTO_TIMEOUT_MS")
                recordPhotoBenchmarkFailure(benchmarkConfig)
                requestNextPhotoBenchmarkSample(cxrLink)
                return@postDelayed
            }
            appendLog("rokid_image_timeout started_at_ms=$startedAtMs timeout_ms=$PHOTO_TIMEOUT_MS")
            update { it.copy(takingPhoto = false, status = "Rokid 拍照超时：未收到 JPEG 回包") }
        }, PHOTO_TIMEOUT_MS)
    }

    private fun requestNextPhotoBenchmarkSample(link: CXRLink?) {
        val session = photoBenchmarkSession ?: return
        val next = session.queue.removeFirstOrNull()
        if (next == null) {
            finishPhotoBenchmark()
            return
        }
        val startedAtMs = System.currentTimeMillis()
        pendingPhotoStartedAtMs = startedAtMs
        pendingPhotoBenchmarkConfig = next
        val accepted = runCatching { link?.takePhoto(next.width, next.height, next.quality) == true }
            .getOrDefault(false)
        appendLog("rokid_photo_benchmark_request config=${next.label} accepted=$accepted remaining=${session.queue.size}")
        if (accepted) {
            update {
                it.copy(
                    takingPhoto = true,
                    photoBenchmarkSummary = benchmarkSummary(session, running = true),
                    status = "CXR-L 拍照基准运行中：${next.label}",
                )
            }
            schedulePhotoTimeout(startedAtMs)
        } else {
            pendingPhotoStartedAtMs = null
            pendingPhotoBenchmarkConfig = null
            recordPhotoBenchmarkFailure(next)
            mainHandler.postDelayed({ requestNextPhotoBenchmarkSample(cxrLink) }, PHOTO_BENCHMARK_INTERVAL_MS)
        }
    }

    private fun recordPhotoBenchmarkSample(latencyMs: Long?, bytes: Int, success: Boolean) {
        val config = pendingPhotoBenchmarkConfig ?: return
        pendingPhotoBenchmarkConfig = null
        val session = photoBenchmarkSession ?: return
        session.results += PhotoBenchmarkResult(
            config = config,
            latencyMs = latencyMs,
            bytes = bytes,
            success = success && latencyMs != null,
        )
        appendLog("rokid_photo_sample config=${config.label} index=${session.results.size} capture_latency_ms=${latencyMs ?: -1} bytes=$bytes success=${success && latencyMs != null}")
        update { it.copy(photoBenchmarkSummary = benchmarkSummary(session, running = true)) }
        mainHandler.postDelayed({ requestNextPhotoBenchmarkSample(cxrLink) }, PHOTO_BENCHMARK_INTERVAL_MS)
    }

    private fun recordPhotoBenchmarkFailure(config: PhotoBenchmarkConfig) {
        val session = photoBenchmarkSession ?: return
        session.results += PhotoBenchmarkResult(config = config, latencyMs = null, bytes = 0, success = false)
        update { it.copy(photoBenchmarkSummary = benchmarkSummary(session, running = true)) }
    }

    private fun finishPhotoBenchmark() {
        val session = photoBenchmarkSession ?: return
        val summary = benchmarkSummary(session, running = false)
        appendLog("rokid_photo_benchmark_summary ${summary.replace('\n', ' ')}")
        photoBenchmarkSession = null
        pendingPhotoBenchmarkConfig = null
        pendingPhotoStartedAtMs = null
        update {
            it.copy(
                photoBenchmarkRunning = false,
                takingPhoto = false,
                photoBenchmarkSummary = summary,
                status = "CXR-L 拍照基准完成",
            )
        }
    }

    private fun benchmarkSummary(session: PhotoBenchmarkSession, running: Boolean): String {
        val finished = session.results.size
        val total = finished + session.queue.size + if (pendingPhotoBenchmarkConfig != null) 1 else 0
        val header = "CXR-L 拍照基准：${if (running) "运行中" else "完成"} $finished/$total"
        val lines = PHOTO_BENCHMARK_CONFIGS.map { config ->
            val results = session.results.filter { it.config == config }
            val successes = results.filter { it.success && it.latencyMs != null }
            val latencies = successes.mapNotNull { it.latencyMs }.sorted()
            val failures = results.size - successes.size
            if (latencies.isEmpty()) {
                "${config.label}: samples=${results.size} failures=$failures"
            } else {
                val avgBytes = successes.map { it.bytes }.average().roundToInt()
                "${config.label}: samples=${results.size} p50_ms=${percentile(latencies, 0.50)} p95_ms=${percentile(latencies, 0.95)} max_ms=${latencies.last()} avg_bytes=$avgBytes failures=$failures"
            }
        }
        return (listOf(header) + lines).joinToString("\n")
    }

    private fun percentile(sorted: List<Long>, ratio: Double): Long {
        val index = ((sorted.size - 1) * ratio).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun scheduleLinkStateProbes(attemptId: Int, mode: RokidSessionMode) {
        listOf(800L, 2_000L, 5_000L, 10_000L).forEach { delayMs ->
            mainHandler.postDelayed({
                probeLinkState(attemptId, mode)
            }, delayMs)
        }
    }

    private fun scheduleConnectTimeout(attemptId: Int, mode: RokidSessionMode) {
        mainHandler.postDelayed({
            probeLinkState(attemptId, mode)
            val state = _state.value
            if (attemptId != connectAttemptId || state.mode != mode || state.linkReady) return@postDelayed
            val serviceBinderMissing = !state.cxrConnected && state.lastDiagnostic.contains("服务查询=未返回")
            val missing = if (serviceBinderMissing) {
                "CXR-L 服务 binder"
            } else {
                listOfNotNull(
                    if (!state.cxrConnected) "CXR-L" else null,
                    if (!state.btConnected) "眼镜蓝牙" else null,
                ).joinToString("、")
            }
            appendLog("${mode.label} connect timeout missing=$missing diagnostic=${state.lastDiagnostic}")
            val nextStatus = if (serviceBinderMissing) {
                "${mode.label} 会话未就绪：Rokid AI App 未返回 CXR 服务 binder。普通蓝牙已连接仍需要 CXR 授权通过；请重新点击“授权”刷新 Token 后再连接。"
            } else {
                "${mode.label} 会话未就绪：未检测到 $missing；${state.lastDiagnostic.ifBlank { "请确认 Rokid AI App 已连接眼镜后重试" }}"
            }
            update {
                it.copy(status = nextStatus, requiresReauthorization = serviceBinderMissing)
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private fun probeLinkState(attemptId: Int, mode: RokidSessionMode) {
        val link = cxrLink ?: return
        if (attemptId != connectAttemptId || _state.value.mode != mode) return
        val queriedBt = runCatching { link.isGlassBtConnected() }.getOrNull()
        val serviceReady = queriedBt != null || _state.value.cxrConnected
        val diagnostic = buildString {
            append("SDK客户端=")
            append(ROKID_CLIENT_VERSION)
            append("，服务查询=")
            append(if (serviceReady) "可用" else "未返回")
            append("，SDK蓝牙=")
            append(if (serviceReady) queriedBt?.let { if (it) "已连接" else "未连接" } ?: "未知" else "无法查询")
        }
        appendLog("Rokid SDK 状态探测：$diagnostic")
        update { state ->
            val nextBtConnected = if (serviceReady) queriedBt ?: state.btConnected else state.btConnected
            val nextCxrConnected = state.cxrConnected || (serviceReady && nextBtConnected)
            val next = state.copy(
                cxrConnected = nextCxrConnected,
                btConnected = nextBtConnected,
                serviceVersion = ROKID_CLIENT_VERSION,
                serviceVersionCode = state.serviceVersionCode,
                lastDiagnostic = diagnostic,
                requiresReauthorization = if (serviceReady) false else state.requiresReauthorization,
            )
            next.copy(status = linkStatus(next, "正在等待 Rokid CXR-L 回调；$diagnostic"))
        }
        maybeQueryCustomAppInstalled()
    }

    private fun maybeQueryCustomAppInstalled() {
        val state = _state.value
        if (state.mode != RokidSessionMode.CUSTOM_APP || !state.linkReady || state.customAppInstalled) return
        runCatching { cxrLink?.appIsInstalled(glassAppCallback) }
            .onSuccess { appendLog("链路就绪，自动查询 CUSTOMAPP 安装状态") }
            .onFailure { throwable -> appendLog("自动查询 CUSTOMAPP 安装失败：${throwable.message ?: throwable.javaClass.simpleName}") }
    }

    private fun linkStatus(state: RokidDebugState, fallback: String): String {
        if (!state.linkReady) return fallback
        return when (state.mode) {
            RokidSessionMode.CUSTOM_VIEW -> "CUSTOMVIEW 会话已建立，请打开 View"
            RokidSessionMode.CUSTOM_APP -> "CUSTOMAPP 会话已建立，请查询/打开协同 App"
            RokidSessionMode.NONE -> "Rokid 会话已建立"
        }
    }

    private fun resolveInstallApkCandidates(): List<File> {
        val embeddedApk = prepareEmbeddedCustomAppApk()
        return listOfNotNull(
            embeddedApk,
            appContext.getExternalFilesDir(Environment.DIRECTORY_DCIM + File.separator + "Rokid")?.resolve("cxrL.apk"),
            appContext.filesDir.resolve("cxrL.apk"),
            File("/sdcard/DCIM/Rokid/cxrL.apk"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM + File.separator + "Rokid")?.resolve("cxrL.apk"),
        ).filter { it.exists() && it.isFile }
    }

    private fun prepareEmbeddedCustomAppApk(): File? {
        val targetDir = appContext.filesDir.resolve("rokid")
        val target = targetDir.resolve(ROKID_BRIDGE_APK_FILE_NAME)
        val copied = runCatching {
            targetDir.mkdirs()
            val tmp = targetDir.resolve("$ROKID_BRIDGE_APK_FILE_NAME.tmp")
            appContext.assets.open(ROKID_BRIDGE_APK_ASSET).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        }.onSuccess { apk ->
            appendLog("内置眼镜端 APK 已准备：${apk.absolutePath} bytes=${apk.length()}")
        }.onFailure { throwable ->
            appendLog("内置眼镜端 APK 准备失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }.getOrNull()
        return copied?.takeIf { it.exists() && it.isFile && it.length() > 0L }
    }

    private fun readBridgeApkInfo(apk: File): RokidBridgeAppInfo? {
        @Suppress("DEPRECATION")
        val packageInfo = appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA)
            ?: return null
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val buildTime = packageInfo.applicationInfo
            ?.metaData
            ?.getString(ROKID_BRIDGE_BUILD_TIME_META)
            .orEmpty()
        return RokidBridgeAppInfo(
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = versionCode,
            buildTime = buildTime,
        )
    }

    private fun discoverBridgeAppInfoForUpdate(link: CXRLink, endpoint: String): RokidBridgeEndpointInfo? {
        discoverBridgeAppInfo(ROKID_BRIDGE_UPDATE_DISCOVERY_MS, endpoint)?.let { return it }
        runCatching { link.appStart("$ROKID_APP_PACKAGE$ROKID_APP_MAIN_PAGE", glassAppCallback) }
            .onSuccess { appendLog("更新前版本检查：已尝试打开眼镜端 App 以读取版本") }
            .onFailure { throwable -> appendLog("更新前版本检查：打开眼镜端 App 失败 ${throwable.message ?: throwable.javaClass.simpleName}") }
        runCatching { Thread.sleep(ROKID_BRIDGE_UPDATE_START_WAIT_MS) }
        return discoverBridgeAppInfo(ROKID_BRIDGE_UPDATE_DISCOVERY_MS, endpoint)
    }

    private fun discoverBridgeAppInfo(maxWaitMs: Long, endpoint: String): RokidBridgeEndpointInfo? {
        val seedEndpoint = endpoint.ifBlank { _state.value.bareMetalEndpointSuggestion }
        val candidates = buildBareMetalDiscoveryCandidates(seedEndpoint)
        val executor = Executors.newFixedThreadPool(BareMetalDiscovery.MAX_PARALLEL_REQUESTS)
        val completion = ExecutorCompletionService<RokidBridgeEndpointInfo?>(executor)
        candidates.forEach { baseUrl ->
            completion.submit(
                Callable {
                    probeBareMetalEndpoint(bareMetalDiscoveryClient, baseUrl)
                        ?.let { (endpoint, body) ->
                            parseBridgeAppInfo(body)?.let { info -> RokidBridgeEndpointInfo(endpoint, info) }
                        }
                },
            )
        }
        return try {
            val deadlineMs = System.currentTimeMillis() + maxWaitMs
            var completed = 0
            while (completed < candidates.size && System.currentTimeMillis() < deadlineMs) {
                val waitMs = (deadlineMs - System.currentTimeMillis()).coerceIn(1L, 250L)
                val result = completion.poll(waitMs, TimeUnit.MILLISECONDS) ?: continue
                completed += 1
                result.get()?.let { return it }
            }
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun parseBridgeAppInfo(body: String): RokidBridgeAppInfo? {
        val app = runCatching { JSONObject(body).optJSONObject("app") }.getOrNull() ?: return null
        val versionCode = app.optLong("version_code", -1L).takeIf { it >= 0L } ?: return null
        return RokidBridgeAppInfo(
            versionName = app.optString("version_name"),
            versionCode = versionCode,
            buildTime = app.optString("build_time"),
        )
    }

    private fun buildCustomViewJson(): String {
        val root = JSONObject()
            .put("type", "LinearLayout")
            .put(
                "props",
                JSONObject()
                    .put("id", "root")
                    .put("layout_width", "match_parent")
                    .put("layout_height", "match_parent")
                    .put("orientation", "vertical")
                    .put("gravity", "center")
                    .put("backgroundColor", "#FF000000"),
            )
            .put(
                "children",
                JSONArray()
                    .put(textNode("title", "VisionRoute", "22sp", "#00FF00", true))
                    .put(textNode("subtitle", "Rokid CUSTOMVIEW 已打开", "16sp", "#00FF00", false))
                    .put(textNode("hint", "手机端可触发拍照 JPEG 回传", "14sp", "#00FF00", false)),
            )
        return root.toString()
    }

    private fun textNode(id: String, text: String, size: String, color: String, bold: Boolean): JSONObject {
        val props = JSONObject()
            .put("id", id)
            .put("layout_width", "wrap_content")
            .put("layout_height", "wrap_content")
            .put("text", text)
            .put("textColor", color)
            .put("textSize", size)
            .put("gravity", "center")
            .put("paddingTop", "8dp")
            .put("paddingBottom", "8dp")
        if (bold) props.put("textStyle", "bold")
        return JSONObject()
            .put("type", "TextView")
            .put("props", props)
    }

    private fun parseCaps(caps: Caps): String {
        val builder = StringBuilder("{")
        for (index in 0 until caps.size()) {
            val value = caps.at(index)
            val text = "${value.typeLabel()}:${value.toReadableString()}"
            builder.append(text).append(",")
        }
        if (builder.length > 1) builder.deleteCharAt(builder.length - 1)
        return builder.append("}").toString()
    }

    private fun parseCapsPairs(caps: Caps): Map<String, String> {
        val values = (0 until caps.size()).map { index -> caps.at(index).toReadableString() }
        return values.chunked(2)
            .filter { it.size == 2 }
            .associate { it[0] to it[1] }
    }

    private fun handleCustomAppEvent(pairs: Map<String, String>, rawText: String) {
        val event = pairs["event"].orEmpty()
        when (event) {
            "RECORD_STARTED" -> {
                val info = formatPairs(pairs)
                update {
                    it.copy(
                        customAppRecording = true,
                        lastCommandResponse = rawText,
                        lastRecordInfo = info,
                        status = "Rokid 眼镜端录像已开始",
                    )
                }
            }
            "RECORD_STOPPED" -> {
                val info = formatPairs(pairs)
                update {
                    it.copy(
                        customAppRecording = false,
                        lastCommandResponse = rawText,
                        lastRecordInfo = info,
                        status = "Rokid 眼镜端录像已停止，已返回文件信息",
                    )
                }
            }
            "RECORD_ERROR" -> {
                val info = formatPairs(pairs)
                update {
                    it.copy(
                        customAppRecording = false,
                        lastCommandResponse = rawText,
                        lastRecordInfo = info,
                        status = "Rokid 眼镜端录像失败：${pairs["message"].orEmpty()}",
                    )
                }
            }
            "APP_EXITING" -> update {
                it.copy(
                    customAppOpened = false,
                    customAppRecording = false,
                    lastCommandResponse = rawText,
                    status = "眼镜端协同 App 正在退出",
                )
            }
            "IMU_SAMPLE" -> handleImuSample(pairs)
            "VOICE_COMMAND" -> handleVoiceCommand(pairs)
            "HUD_ACK" -> {
                val info = formatPairs(pairs)
                appendLog("hud_update_ack $info")
                update { it.copy(lastCommandResponse = rawText, status = "Rokid HUD 已确认") }
            }
            "WIFI_ENABLE_REQUESTED" -> {
                val info = formatPairs(pairs)
                appendLog("wifi_enable_requested_ack $info")
                update { it.copy(lastCommandResponse = rawText, status = "眼镜端 Wi‑Fi 开关请求已发送") }
            }
            "WIFI_SETTINGS_OPENED" -> {
                val info = formatPairs(pairs)
                appendLog("wifi_settings_opened_ack $info")
                update { it.copy(lastCommandResponse = rawText, status = "眼镜端 Wi‑Fi 设置已打开") }
            }
            else -> update { it.copy(lastCommandResponse = rawText, status = "收到自定义指令回包") }
        }
    }

    private fun handleImuSample(pairs: Map<String, String>) {
        val sample = RokidImuSample.fromPairs(pairs)
        if (sample == null) {
            appendLog("rokid_imu_sample_invalid ${formatPairs(pairs)}")
            update { it.copy(status = "收到 Rokid IMU 事件，但字段不完整") }
            return
        }
        val latest = RokidRuntimeBridge.onImuSample(sample)
        appendLog("rokid_imu_sample ${latest.summary()}")
        update { it.copy(lastImuSummary = latest.summary()) }
    }

    private fun handleVoiceCommand(pairs: Map<String, String>) {
        val rawText = pairs["raw_text"] ?: pairs["text"] ?: pairs["message"] ?: ""
        val requestId = pairs["request_id"].orEmpty().ifBlank { "voice_${System.currentTimeMillis()}" }
        val command = RokidRuntimeBridge.onVoiceCommand(
            RokidVoiceCommandParser.parse(rawText, requestId),
        )
        appendLog("rokid_voice_command ${command.summary()} poi_id=${command.targetText?.let { "poi_booth_${it.lowercase()}" } ?: "-"}")
        update { it.copy(lastVoiceCommandSummary = command.summary(), status = "收到 Rokid 语音命令：${command.intent}") }
    }

    private fun formatPairs(pairs: Map<String, String>): String {
        return pairs.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private fun Caps.Value.typeLabel(): String {
        return when (type()) {
            Caps.Value.TYPE_STRING -> "string"
            Caps.Value.TYPE_INT32, Caps.Value.TYPE_UINT32 -> "int"
            Caps.Value.TYPE_INT64, Caps.Value.TYPE_UINT64 -> "long"
            Caps.Value.TYPE_FLOAT -> "float"
            Caps.Value.TYPE_DOUBLE -> "double"
            Caps.Value.TYPE_OBJECT -> "object"
            Caps.Value.TYPE_BINARY -> "binary"
            else -> "unknown"
        }
    }

    private fun Caps.Value.toReadableString(): String {
        return when (type()) {
            Caps.Value.TYPE_STRING -> string
            Caps.Value.TYPE_INT32, Caps.Value.TYPE_UINT32 -> int.toString()
            Caps.Value.TYPE_INT64, Caps.Value.TYPE_UINT64 -> long.toString()
            Caps.Value.TYPE_FLOAT -> float.toString()
            Caps.Value.TYPE_DOUBLE -> double.toString()
            Caps.Value.TYPE_OBJECT -> parseCaps(`object`)
            Caps.Value.TYPE_BINARY -> binary?.let { binary ->
                Base64.encodeToString(binary.data.copyOf(binary.length), Base64.NO_WRAP)
            } ?: "null"
            else -> "null"
        }
    }

    private fun update(block: (RokidDebugState) -> RokidDebugState) {
        _state.value = block(_state.value)
    }

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        update { state ->
            state.copy(logs = (listOf(message) + state.logs).take(80))
        }
    }

    companion object {
        private const val TAG = "RokidRepository"
        private const val ROKID_APP_PACKAGE = "com.aiglasses.rokidbridge"
        private const val ROKID_APP_MAIN_PAGE = ".MainActivity"
        private const val ROKID_BRIDGE_APK_ASSET = "rokid/visionroute_rokid_bridge.apk"
        private const val ROKID_BRIDGE_APK_FILE_NAME = "visionroute_rokid_bridge.apk"
        private const val ROKID_BRIDGE_BUILD_TIME_META = "com.aiglasses.rokidbridge.BUILD_TIME"
        private const val ROKID_BRIDGE_UPDATE_DISCOVERY_MS = 3_000L
        private const val ROKID_BRIDGE_UPDATE_START_WAIT_MS = 2_000L
        private const val ROKID_CLIENT_KEY = "rk_custom_client"
        private const val ROKID_CLIENT_VERSION = "client-l 1.0.3"
        private const val ROKID_RESPONSE_KEY = "rk_custom_key"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val PHOTO_TIMEOUT_MS = 8_000L
        private const val PHOTO_BENCHMARK_ROUNDS = 30
        private const val PHOTO_BENCHMARK_INTERVAL_MS = 500L
        private val PHOTO_BENCHMARK_CONFIGS = listOf(
            PhotoBenchmarkConfig(640, 480, 60),
            PhotoBenchmarkConfig(800, 600, 70),
            PhotoBenchmarkConfig(1024, 768, 80),
        )
    }
}

private data class PhotoBenchmarkConfig(
    val width: Int,
    val height: Int,
    val quality: Int,
) {
    val label: String = "${width}x${height}_q$quality"
}

private data class PhotoBenchmarkResult(
    val config: PhotoBenchmarkConfig,
    val latencyMs: Long?,
    val bytes: Int,
    val success: Boolean,
)

private data class PhotoBenchmarkSession(
    val queue: MutableList<PhotoBenchmarkConfig>,
    val results: MutableList<PhotoBenchmarkResult> = mutableListOf(),
)

private data class RokidBridgeAppInfo(
    val versionName: String,
    val versionCode: Long,
    val buildTime: String,
) {
    fun matches(other: RokidBridgeAppInfo): Boolean {
        return versionName == other.versionName &&
            versionCode == other.versionCode &&
            buildTime.isNotBlank() &&
            buildTime == other.buildTime
    }

    fun label(): String {
        val build = buildTime.ifBlank { "编译时间未知" }
        return "v$versionName($versionCode) · $build"
    }
}

private data class RokidBridgeEndpointInfo(
    val endpoint: String,
    val info: RokidBridgeAppInfo,
)

private fun normalizeBareMetalEndpoint(endpoint: String): String {
    val trimmed = endpoint.trim().trimEnd('/')
    if (trimmed.isBlank()) return "http://127.0.0.1:18080"
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "http://$trimmed"
    }
}

private fun bareMetalEndpointHint(baseUrl: String): String {
    return if (baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost", ignoreCase = true)) {
        "\n提示：手机端 127.0.0.1 需要先执行 adb reverse tcp:18080 tcp:18080；否则请填写眼镜实际 IP。"
    } else {
        ""
    }
}

private fun buildBareMetalDiscoveryCandidates(endpoint: String): List<String> {
    val candidates = linkedSetOf<String>()
    val trimmed = endpoint.trim()
    if (trimmed.isNotBlank()) {
        candidates += normalizeBareMetalEndpoint(trimmed)
    }
    localIpv4Addresses().forEach { localIp ->
        val prefix = localIp.substringBeforeLast('.', missingDelimiterValue = "")
        if (prefix.isBlank()) return@forEach
        for (host in 1..254) {
            val candidateIp = "$prefix.$host"
            if (candidateIp != localIp) {
                candidates += "http://$candidateIp:${BareMetalDiscovery.PORT}"
            }
        }
    }
    BareMetalDiscovery.COMMON_SUBNET_PREFIXES.forEach { prefix ->
        for (host in 1..254) {
            candidates += "http://$prefix.$host:${BareMetalDiscovery.PORT}"
        }
    }
    return candidates.toList()
}

private fun localIpv4Addresses(): List<String> {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { networkInterface -> networkInterface.isUp && !networkInterface.isLoopback }
            .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
            .map { address -> address.hostAddress.orEmpty() }
            .filter { address -> address.isNotBlank() }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())
}

private fun probeBareMetalEndpoint(client: OkHttpClient, baseUrl: String): Pair<String, String>? {
    return runCatching {
        val request = Request.Builder()
            .url("$baseUrl/status")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use null
            if (!body.contains("rokid_bare_metal_http")) return@use null
            baseUrl to body
        }
    }.getOrNull()
}

private fun readJpegFrames(
    input: InputStream,
    onFrame: (ByteArray) -> Boolean,
) {
    val frame = ByteArrayOutputStream()
    var previous = -1
    var inJpeg = false
    while (true) {
        val value = input.read()
        if (value < 0) return
        if (!inJpeg) {
            if (previous == 0xFF && value == 0xD8) {
                frame.reset()
                frame.write(0xFF)
                frame.write(0xD8)
                inJpeg = true
            }
            previous = value
            continue
        }

        frame.write(value)
        if (frame.size() > MAX_BARE_METAL_FRAME_BYTES) {
            frame.reset()
            inJpeg = false
        } else if (previous == 0xFF && value == 0xD9) {
            if (!onFrame(frame.toByteArray())) return
            frame.reset()
            inJpeg = false
        }
        previous = value
    }
}

private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = durationMs % 1000
    return if (minutes > 0) {
        String.format(Locale.US, "%d分%02d.%03d秒", minutes, seconds, millis)
    } else {
        String.format(Locale.US, "%d.%03d秒", seconds, millis)
    }
}

private const val MAX_BARE_METAL_FRAME_BYTES = 2 * 1024 * 1024

private object BareMetalDiscovery {
    const val PORT = 18080
    const val MAX_PARALLEL_REQUESTS = 32
    const val POLL_TIMEOUT_MS = 900L
    val COMMON_SUBNET_PREFIXES = listOf(
        "192.168.43",
        "192.168.49",
        "192.168.137",
        "172.20.10",
    )
}
