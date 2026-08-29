package com.aiglasses.poc.rokid

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class RokidHttpAutoStreamClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val onLog: (String) -> Unit = {},
    private val onFrame: () -> Unit = {},
    private val onEndpointReady: (String) -> Unit = {},
) {
    @Volatile
    private var running = false
    @Volatile
    private var call: Call? = null
    @Volatile
    private var discoveredBaseUrl: String? = null
    @Volatile
    private var statusThread: Thread? = null
    private var statusPollCount = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val discoveryClient = OkHttpClient.Builder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .build()
    private val statusClient = OkHttpClient.Builder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .build()
    private val hudClient = OkHttpClient.Builder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .build()

    fun start() {
        if (running) return
        running = true
        Thread({ runStream() }, "rokid-http-auto-stream").start()
    }

    fun stop() {
        running = false
        call?.cancel()
        call = null
        statusThread?.interrupt()
        statusThread = null
        discoveredBaseUrl = null
    }

    fun sendHud(payload: RokidHudPayload): Boolean {
        val baseUrl = discoveredBaseUrl ?: return false
        Thread(
            {
                runCatching {
                    val request = Request.Builder()
                        .url("$baseUrl/hud?${payload.toHudQuery()}")
                        .get()
                        .build()
                    hudClient.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            error("HTTP ${response.code}: $body")
                        }
                    }
                }.onFailure { throwable ->
                    onLog("rokid_http_hud_error request_id=${payload.requestId} ${throwable.message ?: throwable.javaClass.simpleName}")
                }
            },
            "rokid-http-hud",
        ).start()
        return true
    }

    private fun runStream() {
        while (running) {
            val baseUrl = discoverEndpoint(endpoint)
            if (!running) break
            if (!probeEndpoint(baseUrl)) {
                discoveredBaseUrl = null
                RokidRuntimeBridge.onHttpStatusEvent("rokid_http_auto_discover_not_found")
                statusThread?.interrupt()
                onLog("rokid_http_auto_discover_not_found")
                sleepRetry()
                continue
            }
            discoveredBaseUrl = baseUrl
            RokidRuntimeBridge.onHttpEndpointReady(baseUrl)
            onEndpointReady(baseUrl)
            startStatusPolling(baseUrl)
            streamOnce(baseUrl)
            if (running) {
                sleepRetry()
            }
        }
        call = null
        running = false
    }

    private fun streamOnce(baseUrl: String) {
        var frameCount = 0
        var lastWidth: Int? = null
        var lastHeight: Int? = null
        val startedAtMs = System.currentTimeMillis()
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/mjpeg")
                .get()
                .build()
            val activeCall = client.newCall(request)
            call = activeCall
            activeCall.execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: ${response.body?.string().orEmpty()}")
                }
                val input = response.body?.byteStream() ?: error("empty stream")
                RokidRuntimeBridge.onHttpStatusEvent("rokid_http_auto_stream_started endpoint=$baseUrl/mjpeg")
                onLog("rokid_http_auto_stream_started endpoint=$baseUrl/mjpeg")
                readJpegFrames(input) { bytes ->
                    if (!running) return@readJpegFrames false
                    val nextFrameCount = frameCount + 1
                    val shouldDecodeBounds = nextFrameCount == 1 || nextFrameCount % 30 == 0
                    if (shouldDecodeBounds) {
                        val bounds = decodeBounds(bytes)
                        lastWidth = bounds.first
                        lastHeight = bounds.second
                    }
                    val event = RokidRuntimeBridge.onImageReceived(
                        bytes = bytes,
                        width = lastWidth,
                        height = lastHeight,
                        captureLatencyMs = null,
                        captureMode = "glasses_private_stream",
                    )
                    frameCount = nextFrameCount
                    onFrame()
                    if (frameCount == 1 || frameCount % 30 == 0) {
                        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
                        val fps = frameCount * 1000.0 / elapsedMs
                        onLog(
                            "rokid_http_auto_stream_frame count=$frameCount fps=${"%.1f".format(fps)} " +
                                "bytes=${bytes.size} size=${lastWidth}x${lastHeight} capture_id=${event.captureId}",
                        )
                    }
                    true
                }
                if (running) {
                    onLog("rokid_http_auto_stream_finished frames=$frameCount")
                }
            }
        }.onFailure { throwable ->
            if (running) {
                val summary = "rokid_http_auto_stream_error ${throwable.message ?: throwable.javaClass.simpleName}"
                RokidRuntimeBridge.onHttpStatusEvent(summary)
                onLog(summary)
            }
        }
        call = null
    }

    private fun startStatusPolling(baseUrl: String) {
        if (statusThread?.isAlive == true && discoveredBaseUrl == baseUrl) return
        statusThread?.interrupt()
        statusThread = Thread(
            {
                while (running && discoveredBaseUrl == baseUrl) {
                    pollStatus(baseUrl)
                    runCatching { Thread.sleep(STATUS_POLL_INTERVAL_MS) }
                }
            },
            "rokid-http-status",
        ).also { it.start() }
    }

    private fun pollStatus(baseUrl: String) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/status")
                .get()
                .build()
            statusClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}: $body")
                val status = parseStatus(body)
                RokidRuntimeBridge.onHttpBridgeStatus(status.cxrConnected)
                status.imuSample?.let { sample ->
                    val aged = RokidRuntimeBridge.onImuSample(sample)
                    statusPollCount += 1
                    if (statusPollCount == 1 || statusPollCount % STATUS_LOG_INTERVAL == 0) {
                        onLog("rokid_http_status_imu ${aged.summary()}")
                    }
                }
            }
        }.onFailure { throwable ->
            statusPollCount += 1
            if (statusPollCount == 1 || statusPollCount % STATUS_LOG_INTERVAL == 0) {
                onLog("rokid_http_status_error ${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    private fun parseStatus(body: String): RokidHttpBridgeStatus {
        val json = JSONObject(body)
        val imu = json.optJSONObject("imu") ?: return RokidHttpBridgeStatus(
            cxrConnected = json.optBoolean("cxr_connected", false),
            imuSample = null,
        )
        val yaw = imu.optDoubleOrNull("yaw_deg") ?: return RokidHttpBridgeStatus(
            cxrConnected = json.optBoolean("cxr_connected", false),
            imuSample = null,
        )
        return RokidHttpBridgeStatus(
            cxrConnected = json.optBoolean("cxr_connected", false),
            imuSample = RokidImuSample(
                imuTimestampMs = imu.optLong("imu_timestamp_ms", System.currentTimeMillis()),
                yawDeg = yaw,
                pitchDeg = imu.optDoubleOrNull("pitch_deg") ?: 0.0,
                rollDeg = imu.optDoubleOrNull("roll_deg") ?: 0.0,
                accuracy = imu.optString("accuracy").ifBlank { "http_status" },
            ),
        )
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int?, Int?> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return (options.outWidth.takeIf { it > 0 }) to (options.outHeight.takeIf { it > 0 })
    }

    private fun normalizeEndpoint(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return DEFAULT_ENDPOINT
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun discoverEndpoint(raw: String): String {
        val fallback = normalizeEndpoint(raw)
        if (probeEndpoint(fallback)) {
            return fallback
        }
        val candidates = buildDiscoveryCandidates(fallback)
        if (candidates.isEmpty()) {
            return fallback
        }
        onLog("rokid_http_auto_discover_started candidates=${candidates.size}")
        val executor = Executors.newFixedThreadPool(DISCOVERY_PARALLELISM)
        val completion = ExecutorCompletionService<String?>(executor)
        return try {
            candidates.forEach { candidate ->
                completion.submit(Callable { candidate.takeIf(::probeEndpoint) })
            }
            val deadlineMs = System.currentTimeMillis() + DISCOVERY_MAX_WAIT_MS
            while (System.currentTimeMillis() < deadlineMs) {
                val remainingMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(1L)
                val waitMs = minOf(DISCOVERY_POLL_TIMEOUT_MS, remainingMs)
                val discovered = completion.poll(waitMs, TimeUnit.MILLISECONDS)?.get()
                if (discovered != null) {
                    onLog("rokid_http_auto_discover_found endpoint=$discovered")
                    return discovered
                }
            }
            fallback
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeEndpoint(baseUrl: String): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url("$baseUrl/status")
                .get()
                .build()
            discoveryClient.newCall(request).execute().use { response ->
                response.isSuccessful &&
                    response.body?.string().orEmpty().contains("rokid_bare_metal_http")
            }
        }.getOrDefault(false)
    }

    private fun buildDiscoveryCandidates(fallback: String): List<String> {
        val candidates = linkedSetOf<String>()
        localIpv4Addresses().forEach { localIp ->
            val prefix = localIp.substringBeforeLast('.', missingDelimiterValue = "")
            if (prefix.isBlank()) return@forEach
            for (host in 1..254) {
                val candidateIp = "$prefix.$host"
                if (candidateIp != localIp) {
                    candidates += "http://$candidateIp:$DEFAULT_PORT"
                }
            }
        }
        COMMON_SUBNET_PREFIXES.forEach { prefix ->
            for (host in 1..254) {
                candidates += "http://$prefix.$host:$DEFAULT_PORT"
            }
        }
        candidates.remove(fallback)
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
            if (frame.size() > MAX_FRAME_BYTES) {
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

    private fun sleepRetry() {
        runCatching { Thread.sleep(STREAM_RETRY_DELAY_MS) }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:18080"
        private const val DEFAULT_PORT = 18080
        private const val DISCOVERY_PARALLELISM = 64
        private const val DISCOVERY_POLL_TIMEOUT_MS = 450L
        private const val DISCOVERY_MAX_WAIT_MS = 4_000L
        private const val MAX_FRAME_BYTES = 2 * 1024 * 1024
        private const val STREAM_RETRY_DELAY_MS = 3_000L
        private const val STATUS_POLL_INTERVAL_MS = 250L
        private const val STATUS_LOG_INTERVAL = 20
        private val COMMON_SUBNET_PREFIXES = listOf(
            "192.168.43",
            "192.168.49",
            "192.168.137",
            "192.168.0",
            "192.168.1",
            "10.0.0",
            "172.20.10",
        )
    }
}

private fun RokidHudPayload.toHudQuery(): String {
    val pairs = linkedMapOf(
        "action" to "HUD_UPDATE",
        "request_id" to requestId,
        "source" to "VisionRoute",
    )
    pairs += toCommandPairs()
    return pairs.entries.joinToString("&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    return if (has(name) && !isNull(name)) optDouble(name) else null
}

private data class RokidHttpBridgeStatus(
    val cxrConnected: Boolean,
    val imuSample: RokidImuSample?,
)

private fun String.urlEncode(): String {
    return URLEncoder.encode(this, "UTF-8")
}
