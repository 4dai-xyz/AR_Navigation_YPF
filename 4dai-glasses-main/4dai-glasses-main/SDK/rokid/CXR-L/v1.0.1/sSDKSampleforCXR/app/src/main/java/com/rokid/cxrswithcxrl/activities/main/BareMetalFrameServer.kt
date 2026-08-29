package com.rokid.cxrswithcxrl.activities.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

data class BareMetalServerStatus(
    val running: Boolean,
    val port: Int,
    val ips: List<String>,
    val message: String,
    val captureCount: Long = 0L,
    val lastCaptureLatencyMs: Long = -1L,
    val lastStreamFrameCount: Int = 0,
    val lastStreamDurationMs: Long = -1L,
    val lastStreamFps: Double = 0.0,
    val lastFrameWidth: Int = 0,
    val lastFrameHeight: Int = 0,
    val phoneConnected: Boolean = false,
) {
    fun summary(): String {
        val endpoint = ips.firstOrNull()?.let { "http://$it:$port" } ?: "http://<眼镜IP>:$port"
        val frameSize = if (lastFrameWidth > 0 && lastFrameHeight > 0) {
            " · ${lastFrameWidth}×$lastFrameHeight"
        } else {
            ""
        }
        return buildString {
            append(if (running) "HTTP 图传服务已启动" else "HTTP 图传服务未启动")
            append("\n地址：$endpoint")
            if (lastStreamFps > 0.0) {
                append("\n图传：${String.format(Locale.US, "%.1f", lastStreamFps)} FPS$frameSize")
            } else if (captureCount > 0L) {
                append("\n图传：已输出画面$frameSize")
            } else if (phoneConnected) {
                append("\n图传：手机已连接，等待画面")
            } else {
                append("\n图传：等待手机连接")
            }
            if (!message.contains("frame_count") && !message.contains("latency_ms")) {
                append("\n$message")
            }
        }
    }
}

data class BareMetalCaptureResult(
    val ok: Boolean,
    val bytes: ByteArray = ByteArray(0),
    val width: Int = 0,
    val height: Int = 0,
    val latencyMs: Long = -1L,
    val message: String = "",
)

data class BareMetalImuSample(
    val imuTimestampMs: Long,
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val accuracy: String,
)

private data class EncodedJpegFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

class BareMetalFrameServer(
    private val context: Context,
    private val onStatus: (BareMetalServerStatus) -> Unit,
    private val latestImuSample: () -> BareMetalImuSample? = { null },
    private val onHudCommand: (Map<String, String>) -> Unit = {},
    private val onHttpClientActive: (String) -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val captureCount = AtomicLong(0L)
    private val capturer = DirectJpegCapturer(appContext)
    private var serverSocket: ServerSocket? = null
    private var startedAtMs: Long = 0L
    private var lastCaptureLatencyMs: Long = -1L
    private var lastStreamFrameCount: Int = 0
    private var lastStreamDurationMs: Long = -1L
    private var lastStreamFps: Double = 0.0
    private var lastFrameWidth: Int = 0
    private var lastFrameHeight: Int = 0
    private var lastHttpClientActiveAtMs: Long = 0L
    private var lastHttpClientActiveEmitAtMs: Long = 0L

    fun start(port: Int = DEFAULT_PORT) {
        if (!running.compareAndSet(false, true)) return
        startedAtMs = System.currentTimeMillis()
        executor.execute {
            runCatching {
                ServerSocket(port).use { server ->
                    serverSocket = server
                    emit("等待手机端连接")
                    while (running.get()) {
                        val socket = runCatching { server.accept() }.getOrNull() ?: break
                        executor.execute { handleClient(socket) }
                    }
                }
            }.onFailure { throwable ->
                running.set(false)
                emit("服务启动失败：${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        capturer.close()
        executor.shutdownNow()
        emit("服务已停止")
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            runCatching {
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val requestLine = readRequestLine(input)
                val target = requestLine.split(" ").getOrNull(1).orEmpty()
                val path = target.substringBefore("?")
                val query = target.substringAfter("?", missingDelimiterValue = "")
                drainHeaders(input)
                when (path) {
                    "/", "" -> {
                        markHttpClientActive("HTTP 状态探测")
                        writeText(output, "VisionRoute Rokid bare-metal frame server\n/status\n/capture.jpg\n/mjpeg\n/hud\n")
                    }
                    "/status" -> {
                        markHttpClientActive("HTTP 状态探测")
                        writeJson(output, statusJson())
                    }
                    "/capture.jpg" -> {
                        markHttpClientActive("HTTP 单帧取图")
                        writeCapture(output)
                    }
                    "/mjpeg" -> {
                        markHttpClientActive("HTTP 图传已连接")
                        writeMjpeg(output)
                    }
                    "/hud" -> writeHud(output, query)
                    else -> writeText(output, "Not found: $path", status = "404 Not Found")
                }
            }.onFailure { throwable ->
                emit("客户端连接已断开：${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    private fun writeCapture(output: BufferedOutputStream) {
        val result = capturer.capturePreviewJpeg()
        if (!result.ok) {
            writeJson(output, """{"ok":false,"message":"${jsonEscape(result.message)}"}""", status = "503 Service Unavailable")
            return
        }
        lastCaptureLatencyMs = result.latencyMs
        lastFrameWidth = result.width
        lastFrameHeight = result.height
        val count = captureCount.incrementAndGet()
        output.write(
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: image/jpeg\r\n")
                append("Content-Length: ${result.bytes.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("X-Frame-Count: $count\r\n")
                append("X-Capture-Latency-Ms: ${result.latencyMs}\r\n")
                append("X-Frame-Width: ${result.width}\r\n")
                append("X-Frame-Height: ${result.height}\r\n")
                append("\r\n")
            }.toByteArray(),
        )
        output.write(result.bytes)
        output.flush()
        emit("已输出 JPEG frame_count=$count latency_ms=${result.latencyMs}")
    }

    private fun writeMjpeg(output: BufferedOutputStream) {
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY\r\nCache-Control: no-store\r\n\r\n"
                .toByteArray(),
        )
        output.flush()
        val streamStartedAt = System.currentTimeMillis()
        var streamFrames = 0
        capturer.streamJpegs(maxFrames = MJPEG_MAX_FRAMES) { result ->
            if (!running.get()) return@streamJpegs false
            lastCaptureLatencyMs = result.latencyMs
            streamFrames += 1
            val durationMs = (System.currentTimeMillis() - streamStartedAt).coerceAtLeast(1L)
            lastStreamFrameCount = streamFrames
            lastStreamDurationMs = durationMs
            lastStreamFps = streamFrames * 1000.0 / durationMs
            lastFrameWidth = result.width
            lastFrameHeight = result.height
            val count = captureCount.incrementAndGet()
            output.write(
                "--$MJPEG_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${result.bytes.size}\r\nX-Frame-Count: $count\r\nX-Capture-Latency-Ms: ${result.latencyMs}\r\nX-Frame-Width: ${result.width}\r\nX-Frame-Height: ${result.height}\r\n\r\n"
                    .toByteArray(),
            )
            output.write(result.bytes)
            output.write("\r\n".toByteArray())
            output.flush()
            emit("已输出 MJPEG frame_count=$count latency_ms=${result.latencyMs}")
            true
        }
        output.write("--$MJPEG_BOUNDARY--\r\n".toByteArray())
        output.flush()
    }

    private fun writeHud(output: BufferedOutputStream, query: String) {
        val pairs = parseQueryPairs(query)
        if (pairs.isEmpty()) {
            writeJson(output, """{"ok":false,"message":"empty hud payload"}""", status = "400 Bad Request")
            return
        }
        markHttpClientActive("手机已连接（HTTP HUD）")
        onHudCommand(pairs)
        writeJson(
            output,
            """{"ok":true,"message":"hud updated","request_id":"${jsonEscape(pairs["request_id"].orEmpty())}"}""",
        )
    }

    private fun statusJson(): String {
        val ips = localIps()
        val endpoint = ips.firstOrNull()?.let { "http://$it:$DEFAULT_PORT" }.orEmpty()
        val imu = latestImuSample()
        val connected = phoneConnected()
        return buildString {
            append("{")
            append("\"ok\":true,")
            append("\"mode\":\"rokid_bare_metal_http\",")
            append("\"running\":${running.get()},")
            append("\"phone_connected\":$connected,")
            append("\"port\":$DEFAULT_PORT,")
            append("\"endpoint\":\"${jsonEscape(endpoint)}\",")
            append("\"ips\":[${ips.joinToString(",") { "\"${jsonEscape(it)}\"" }}],")
            append("\"capture_count\":${captureCount.get()},")
            append("\"last_capture_latency_ms\":$lastCaptureLatencyMs,")
            append("\"last_stream_frame_count\":$lastStreamFrameCount,")
            append("\"last_stream_duration_ms\":$lastStreamDurationMs,")
            append("\"last_stream_fps\":${String.format(Locale.US, "%.2f", lastStreamFps)},")
            append("\"uptime_ms\":${System.currentTimeMillis() - startedAtMs},")
            if (imu != null) {
                append("\"imu\":{")
                append("\"source\":\"rokid_imu\",")
                append("\"imu_timestamp_ms\":${imu.imuTimestampMs},")
                append("\"yaw_deg\":${imu.yawDeg.format1()},")
                append("\"pitch_deg\":${imu.pitchDeg.format1()},")
                append("\"roll_deg\":${imu.rollDeg.format1()},")
                append("\"accuracy\":\"${jsonEscape(imu.accuracy)}\"")
                append("},")
            } else {
                append("\"imu\":null,")
            }
            append("\"endpoints\":[\"/status\",\"/capture.jpg\",\"/mjpeg\",\"/hud\"]")
            append("}")
        }
    }

    private fun writeText(output: BufferedOutputStream, text: String, status: String = "200 OK") {
        val bytes = text.toByteArray()
        output.write("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun writeJson(output: BufferedOutputStream, text: String, status: String = "200 OK") {
        val bytes = text.toByteArray()
        output.write("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun emit(message: String) {
        onStatus(
            BareMetalServerStatus(
                running = running.get(),
                port = DEFAULT_PORT,
                ips = localIps(),
                message = message,
                captureCount = captureCount.get(),
                lastCaptureLatencyMs = lastCaptureLatencyMs,
                lastStreamFrameCount = lastStreamFrameCount,
                lastStreamDurationMs = lastStreamDurationMs,
                lastStreamFps = lastStreamFps,
                lastFrameWidth = lastFrameWidth,
                lastFrameHeight = lastFrameHeight,
                phoneConnected = phoneConnected(),
            ),
        )
    }

    private fun markHttpClientActive(message: String) {
        val nowMs = System.currentTimeMillis()
        lastHttpClientActiveAtMs = nowMs
        onHttpClientActive(message)
        if (nowMs - lastHttpClientActiveEmitAtMs >= HTTP_CLIENT_ACTIVE_EMIT_INTERVAL_MS || message.contains("HUD")) {
            lastHttpClientActiveEmitAtMs = nowMs
            emit(message)
        }
    }

    private fun phoneConnected(): Boolean {
        return System.currentTimeMillis() - lastHttpClientActiveAtMs <= HTTP_CLIENT_ACTIVE_TTL_MS
    }

    private fun readRequestLine(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1 || value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
            if (buffer.size() > 4096) break
        }
        return buffer.toString()
    }

    private fun drainHeaders(input: BufferedInputStream) {
        var previous = 0
        var current: Int
        var lineBreaks = 0
        while (true) {
            current = input.read()
            if (current == -1) return
            if (previous == '\r'.code && current == '\n'.code) {
                lineBreaks += 1
                if (lineBreaks >= 2) return
            } else if (current != '\r'.code) {
                lineBreaks = 0
            }
            previous = current
        }
    }

    private fun localIps(): List<String> {
        val addresses = mutableListOf<String>()
        runCatching {
            val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                addresses += String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff,
                )
            }
        }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                for (address in networkInterface.inetAddresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        addresses += address.hostAddress.orEmpty()
                    }
                }
            }
        }
        return addresses.filter { it.isNotBlank() }.distinct()
    }

    companion object {
        const val DEFAULT_PORT = 18080
        private const val MJPEG_BOUNDARY = "visionroute"
        private const val MJPEG_MAX_FRAMES = 600
        private const val HTTP_CLIENT_ACTIVE_TTL_MS = 5_000L
        private const val HTTP_CLIENT_ACTIVE_EMIT_INTERVAL_MS = 1_000L
    }
}

class DirectJpegCapturer(
    private val context: Context,
) : Closeable {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("VisionRouteBareMetalCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var lastCameraClosedAtMs = 0L
    @Volatile
    private var cachedPreviewFrame: BareMetalCaptureResult? = null
    @Volatile
    private var cachedPreviewFrameAtMs = 0L

    @Synchronized
    @SuppressLint("MissingPermission")
    fun captureJpeg(timeoutMs: Long = 5_000L): BareMetalCaptureResult {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return BareMetalCaptureResult(ok = false, message = "缺少 CAMERA 权限")
        }
        waitForCameraCooldown()
        val cameraId = chooseCameraId() ?: return BareMetalCaptureResult(ok = false, message = "未找到可用摄像头")
        val size = chooseJpegSize(cameraId)
        val startedAt = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        var imageReader: ImageReader? = null
        var cameraDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var result = BareMetalCaptureResult(ok = false, message = "capture timeout", width = size.width, height = size.height)

        fun cleanup() {
            runCatching { session?.close() }
            runCatching { cameraDevice?.close() }
            runCatching { imageReader?.close() }
            markCameraClosed()
        }

        return runCatching {
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    image.use {
                        val buffer: ByteBuffer = it.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        result = BareMetalCaptureResult(
                            ok = true,
                            bytes = bytes,
                            width = size.width,
                            height = size.height,
                            latencyMs = System.currentTimeMillis() - startedAt,
                            message = "ok",
                        )
                        latch.countDown()
                    }
                }, cameraHandler)
            }
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        camera.createCaptureSession(
                            listOf(imageReader!!.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(captureSession: CameraCaptureSession) {
                                    session = captureSession
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(imageReader!!.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.JPEG_QUALITY, 72.toByte())
                                    }.build()
                                    captureSession.capture(request, null, cameraHandler)
                                }

                                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                    result = BareMetalCaptureResult(ok = false, message = "创建拍照会话失败", width = size.width, height = size.height)
                                    latch.countDown()
                                }
                            },
                            cameraHandler,
                        )
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        result = BareMetalCaptureResult(ok = false, message = "摄像头断开", width = size.width, height = size.height)
                        latch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        result = BareMetalCaptureResult(ok = false, message = "摄像头错误：$error", width = size.width, height = size.height)
                        latch.countDown()
                    }
                },
                cameraHandler,
            )
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            result
        }.getOrElse { throwable ->
            BareMetalCaptureResult(ok = false, message = throwable.message ?: throwable.javaClass.simpleName, width = size.width, height = size.height)
        }.also {
            cleanup()
        }
    }

    fun capturePreviewJpeg(timeoutMs: Long = 5_000L): BareMetalCaptureResult {
        freshCachedPreviewFrame()?.let { return it }
        var capturedFrame: BareMetalCaptureResult? = null
        val result = streamJpegs(
            maxFrames = 1,
            configureTimeoutMs = timeoutMs,
            frameTimeoutMs = timeoutMs,
        ) { frame ->
            capturedFrame = frame
            false
        }
        return capturedFrame ?: result
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun streamJpegs(
        maxFrames: Int,
        configureTimeoutMs: Long = 5_000L,
        frameTimeoutMs: Long = 3_000L,
        onFrame: (BareMetalCaptureResult) -> Boolean,
    ): BareMetalCaptureResult {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return BareMetalCaptureResult(ok = false, message = "缺少 CAMERA 权限")
        }
        waitForCameraCooldown()
        val cameraId = chooseCameraId() ?: return BareMetalCaptureResult(ok = false, message = "未找到可用摄像头")
        val sizes = chooseYuvStreamSizes(cameraId)
        var lastResult = BareMetalCaptureResult(ok = false, message = "无可用图传分辨率")
        sizes.forEachIndexed { index, size ->
            val result = streamJpegsAtSize(
                cameraId = cameraId,
                size = size,
                maxFrames = maxFrames,
                configureTimeoutMs = configureTimeoutMs,
                frameTimeoutMs = frameTimeoutMs,
                onFrame = onFrame,
            )
            if (result.ok || index == sizes.lastIndex) {
                return result
            }
            lastResult = result.copy(message = "${result.message}; fallback_to_next_resolution")
            waitForCameraCooldown()
        }
        return lastResult
    }

    @SuppressLint("MissingPermission")
    private fun streamJpegsAtSize(
        cameraId: String,
        size: Size,
        maxFrames: Int,
        configureTimeoutMs: Long,
        frameTimeoutMs: Long,
        onFrame: (BareMetalCaptureResult) -> Boolean,
    ): BareMetalCaptureResult {
        val startedAt = System.currentTimeMillis()
        val configuredLatch = CountDownLatch(1)
        val frameQueue = LinkedBlockingQueue<BareMetalCaptureResult>(1)
        var imageReader: ImageReader? = null
        var cameraDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var setupResult = BareMetalCaptureResult(ok = false, message = "stream configure timeout", width = size.width, height = size.height)
        var lastQueuedAtMs = 0L

        fun cleanup() {
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { cameraDevice?.close() }
            runCatching { imageReader?.close() }
            markCameraClosed()
        }

        return runCatching {
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    image.use {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastQueuedAtMs < STREAM_FRAME_INTERVAL_MS) return@use
                        lastQueuedAtMs = nowMs
                        val encoded = it.toCorrectedJpegFrame(quality = 58)
                        val frame = BareMetalCaptureResult(
                            ok = true,
                            bytes = encoded.bytes,
                            width = encoded.width,
                            height = encoded.height,
                            latencyMs = System.currentTimeMillis() - startedAt,
                            message = "ok",
                        )
                        cachePreviewFrame(frame)
                        if (!frameQueue.offer(frame)) {
                            frameQueue.poll()
                            frameQueue.offer(frame)
                        }
                    }
                }, cameraHandler)
            }
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        camera.createCaptureSession(
                            listOf(imageReader!!.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(captureSession: CameraCaptureSession) {
                                    session = captureSession
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(imageReader!!.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    }.build()
                                    captureSession.setRepeatingRequest(request, null, cameraHandler)
                                    setupResult = BareMetalCaptureResult(ok = true, width = size.width, height = size.height, message = "stream configured")
                                    configuredLatch.countDown()
                                }

                                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                    setupResult = BareMetalCaptureResult(ok = false, message = "创建连续取帧会话失败", width = size.width, height = size.height)
                                    configuredLatch.countDown()
                                }
                            },
                            cameraHandler,
                        )
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        setupResult = BareMetalCaptureResult(ok = false, message = "摄像头断开", width = size.width, height = size.height)
                        configuredLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        setupResult = BareMetalCaptureResult(ok = false, message = "摄像头错误：$error", width = size.width, height = size.height)
                        configuredLatch.countDown()
                    }
                },
                cameraHandler,
            )
            if (!configuredLatch.await(configureTimeoutMs, TimeUnit.MILLISECONDS) || !setupResult.ok) {
                return@runCatching setupResult
            }
            var frames = 0
            var lastFrame = setupResult
            while (frames < maxFrames) {
                val frame = frameQueue.poll(frameTimeoutMs, TimeUnit.MILLISECONDS) ?: break
                lastFrame = frame
                frames += 1
                if (!onFrame(frame)) break
            }
            if (frames == 0) {
                BareMetalCaptureResult(ok = false, message = "stream frame timeout", width = size.width, height = size.height)
            } else {
                lastFrame
            }
        }.getOrElse { throwable ->
            BareMetalCaptureResult(ok = false, message = throwable.message ?: throwable.javaClass.simpleName, width = size.width, height = size.height)
        }.also {
            cleanup()
        }
    }

    override fun close() {
        cameraThread.quitSafely()
    }

    private fun chooseCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
    }

    private fun chooseJpegSize(cameraId: String): Size {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
        return sizes.firstOrNull { it.width == 640 && it.height == 480 }
            ?: sizes.firstOrNull { it.width == 640 && it.height == 360 }
            ?: sizes.filter { it.width <= 1280 && it.height <= 720 }
                .maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(640, 480)
    }

    private fun chooseYuvStreamSizes(cameraId: String): List<Size> {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        return listOfNotNull(
            sizes.firstOrNull { it.width == 1280 && it.height == 720 },
            sizes.firstOrNull { it.width == 640 && it.height == 480 },
            sizes.firstOrNull { it.width == 640 && it.height == 360 },
            sizes.filter { it.width <= 800 && it.height <= 600 }
                .maxByOrNull { it.width * it.height },
            sizes.minByOrNull { it.width * it.height },
            Size(640, 480),
        ).distinctBy { "${it.width}x${it.height}" }
    }

    private fun waitForCameraCooldown() {
        val elapsedMs = System.currentTimeMillis() - lastCameraClosedAtMs
        val waitMs = CAMERA_SESSION_COOLDOWN_MS - elapsedMs
        if (waitMs > 0) {
            Thread.sleep(waitMs)
        }
    }

    private fun markCameraClosed() {
        lastCameraClosedAtMs = System.currentTimeMillis()
    }

    private fun cachePreviewFrame(frame: BareMetalCaptureResult) {
        cachedPreviewFrame = frame
        cachedPreviewFrameAtMs = System.currentTimeMillis()
    }

    private fun freshCachedPreviewFrame(): BareMetalCaptureResult? {
        val frame = cachedPreviewFrame ?: return null
        val ageMs = System.currentTimeMillis() - cachedPreviewFrameAtMs
        if (ageMs > CACHED_PREVIEW_FRAME_TTL_MS) return null
        return frame.copy(latencyMs = ageMs, message = "cached_preview_frame")
    }
}

private const val STREAM_FRAME_INTERVAL_MS = 200L
private const val CAMERA_SESSION_COOLDOWN_MS = 5_000L
private const val CACHED_PREVIEW_FRAME_TTL_MS = 30_000L

private fun Image.toCorrectedJpegFrame(quality: Int): EncodedJpegFrame {
    val nv21 = toNv21()
    val correctedNv21 = nv21.rotateCounterClockwise90(width, height)
    val correctedWidth = height
    val correctedHeight = width
    val bytes = ByteArrayOutputStream().use { output ->
        YuvImage(correctedNv21, ImageFormat.NV21, correctedWidth, correctedHeight, null)
            .compressToJpeg(Rect(0, 0, correctedWidth, correctedHeight), quality, output)
        output.toByteArray()
    }
    return EncodedJpegFrame(bytes = bytes, width = correctedWidth, height = correctedHeight)
}

private fun Image.toNv21(): ByteArray {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val nv21 = ByteArray(width * height * 3 / 2)

    var outputIndex = 0
    for (row in 0 until height) {
        val rowOffset = row * yPlane.rowStride
        for (column in 0 until width) {
            nv21[outputIndex++] = yBuffer.get(rowOffset + column * yPlane.pixelStride)
        }
    }

    val chromaHeight = height / 2
    val chromaWidth = width / 2
    for (row in 0 until chromaHeight) {
        val uRowOffset = row * uPlane.rowStride
        val vRowOffset = row * vPlane.rowStride
        for (column in 0 until chromaWidth) {
            nv21[outputIndex++] = vBuffer.get(vRowOffset + column * vPlane.pixelStride)
            nv21[outputIndex++] = uBuffer.get(uRowOffset + column * uPlane.pixelStride)
        }
    }
    return nv21
}

private fun ByteArray.rotateCounterClockwise90(width: Int, height: Int): ByteArray {
    val outputWidth = height
    val outputHeight = width
    val rotated = ByteArray(size)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val outputX = y
            val outputY = width - 1 - x
            rotated[outputY * outputWidth + outputX] = this[y * width + x]
        }
    }

    val inputYSize = width * height
    val outputYSize = outputWidth * outputHeight
    val inputChromaWidth = width / 2
    val inputChromaHeight = height / 2
    val outputChromaWidth = outputWidth / 2
    for (y in 0 until inputChromaHeight) {
        for (x in 0 until inputChromaWidth) {
            val outputX = y
            val outputY = inputChromaWidth - 1 - x
            val inputIndex = inputYSize + (y * inputChromaWidth + x) * 2
            val outputIndex = outputYSize + (outputY * outputChromaWidth + outputX) * 2
            rotated[outputIndex] = this[inputIndex]
            rotated[outputIndex + 1] = this[inputIndex + 1]
        }
    }
    return rotated
}

private fun jsonEscape(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}

private fun parseQueryPairs(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    return query.split("&")
        .mapNotNull { part ->
            val key = part.substringBefore("=", missingDelimiterValue = "").decodeUrl()
            if (key.isBlank()) return@mapNotNull null
            val value = part.substringAfter("=", missingDelimiterValue = "").decodeUrl()
            key to value
        }
        .toMap()
}

private fun String.decodeUrl(): String {
    return runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
}

private fun Double.format1(): String {
    return ((this * 10.0).roundToInt() / 10.0).toString()
}
