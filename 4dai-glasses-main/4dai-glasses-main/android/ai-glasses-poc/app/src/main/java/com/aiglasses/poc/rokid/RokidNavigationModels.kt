package com.aiglasses.poc.rokid

import com.aiglasses.poc.image.CapturedFrame
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlin.math.roundToInt

data class RokidImuSample(
    val source: String = SOURCE,
    val imuTimestampMs: Long,
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val accuracy: String,
    val sampleAgeMs: Long = 0L,
) {
    fun withAge(nowMs: Long = System.currentTimeMillis()): RokidImuSample {
        return copy(sampleAgeMs = (nowMs - imuTimestampMs).coerceAtLeast(0L))
    }

    fun toJsonString(): String {
        return JSONObject()
            .put("source", source)
            .put("imu_timestamp_ms", imuTimestampMs)
            .put("yaw_deg", yawDeg)
            .put("pitch_deg", pitchDeg)
            .put("roll_deg", rollDeg)
            .put("accuracy", accuracy)
            .put("sample_age_ms", sampleAgeMs)
            .toString()
    }

    fun summary(): String {
        return "yaw=${yawDeg.format1()} pitch=${pitchDeg.format1()} roll=${rollDeg.format1()} age=${sampleAgeMs}ms accuracy=$accuracy"
    }

    companion object {
        const val SOURCE = "rokid_imu"

        fun fromPairs(pairs: Map<String, String>, nowMs: Long = System.currentTimeMillis()): RokidImuSample? {
            val yaw = pairs["yaw_deg"]?.toDoubleOrNull() ?: return null
            val pitch = pairs["pitch_deg"]?.toDoubleOrNull() ?: 0.0
            val roll = pairs["roll_deg"]?.toDoubleOrNull() ?: 0.0
            val timestamp = pairs["imu_timestamp_ms"]?.toLongOrNull() ?: nowMs
            return RokidImuSample(
                imuTimestampMs = timestamp,
                yawDeg = yaw,
                pitchDeg = pitch,
                rollDeg = roll,
                accuracy = pairs["accuracy"].orEmpty().ifBlank { "unknown" },
                sampleAgeMs = (nowMs - timestamp).coerceAtLeast(0L),
            )
        }
    }
}

data class RokidCapturedFrameEvent(
    val captureId: String,
    val captureTimestampMs: Long,
    val captureMode: String,
    val imageBytes: Int,
    val width: Int?,
    val height: Int?,
    val captureLatencyMs: Long?,
    val imuAtCapture: RokidImuSample?,
) {
    fun summary(): String {
        return "capture_id=$captureId mode=$captureMode bytes=$imageBytes size=${width ?: "-"}x${height ?: "-"} imu=${imuAtCapture?.summary() ?: "none"}"
    }
}

data class VoiceNavigationCommand(
    val requestId: String,
    val source: String = SOURCE,
    val intent: String,
    val rawText: String,
    val targetText: String?,
) {
    fun summary(): String {
        return "request_id=$requestId source=$source intent=$intent target=${targetText.orDash()} raw=$rawText"
    }

    companion object {
        const val SOURCE = "rokid_voice_instruction"
    }
}

object RokidVoiceCommandParser {
    fun parse(rawText: String, requestId: String = "voice_${System.currentTimeMillis()}"): VoiceNavigationCommand {
        val text = rawText.trim()
        val compact = text.replace("\\s+".toRegex(), "")
        val intent = when {
            compact.contains("重新定位") || compact.contains("重定位") -> "relocalize"
            compact.contains("退出导航") || compact.contains("结束导航") -> "exit_navigation"
            compact == "确认" || compact.endsWith("确认") -> "confirm"
            compact == "取消" || compact.endsWith("取消") -> "cancel"
            else -> "navigate_to"
        }
        return VoiceNavigationCommand(
            requestId = requestId,
            intent = intent,
            rawText = rawText,
            targetText = if (intent == "navigate_to") extractTargetText(text) else null,
        )
    }

    private fun extractTargetText(text: String): String? {
        val cleaned = text
            .replace("Hi Rokid", "", ignoreCase = true)
            .replace("嗨 Rokid", "", ignoreCase = true)
            .replace("，", " ")
            .replace(",", " ")
            .trim()
        val markers = listOf("导航到", "我要去", "带我去", "去")
        markers.forEach { marker ->
            val index = cleaned.indexOf(marker)
            if (index >= 0) {
                return cleaned.substring(index + marker.length)
                    .trim()
                    .trim('。', '.', '！', '!')
                    .takeIf { it.isNotBlank() }
            }
        }
        return cleaned.takeIf { it.isNotBlank() }
    }
}

data class RokidHeadingAnchor(
    val anchorId: String,
    val venueId: String,
    val floorId: String,
    val x: Double,
    val y: Double,
    val landmark: String?,
    val mapHeadingDeg: Double,
    val imuYawDeg: Double,
    val createdAtMs: Long,
    val confidence: Double?,
) {
    fun currentMapHeadingDeg(sample: RokidImuSample): Double {
        return normalize360(mapHeadingDeg + normalizeSigned(sample.yawDeg - imuYawDeg))
    }

    fun summary(): String {
        return "anchor_id=$anchorId floor=$floorId map_heading=${mapHeadingDeg.format1()} imu_yaw=${imuYawDeg.format1()} confidence=${confidence?.format1().orDash()} landmark=${landmark.orDash()}"
    }
}

private object RokidHudSequence {
    private val nextValue = AtomicLong(System.currentTimeMillis())

    fun next(): Long = nextValue.incrementAndGet()
}

data class RokidHudPayload(
    val requestId: String = "hud_${System.currentTimeMillis()}",
    val hudSeq: Long = RokidHudSequence.next(),
    val hudTimestampMs: Long = System.currentTimeMillis(),
    val directionArrow: String,
    val nextAction: String,
    val targetName: String,
    val floorId: String,
    val distanceToNextActionMeters: Double?,
    val remainingDistanceMeters: Double? = null,
    val remainingDurationSeconds: Double? = null,
    val currentLocationName: String = "",
    val headingState: String,
    val statusText: String,
    val alertText: String = "",
    val miniMapRoute: String = "",
    val miniMapCurrent: String = "",
    val miniMapTarget: String = "",
    val mapHeadingDeg: Double? = null,
) {
    fun toCommandPairs(): Map<String, String> {
        val pairs = linkedMapOf(
            "hud_seq" to hudSeq.toString(),
            "hud_timestamp_ms" to hudTimestampMs.toString(),
            "direction_arrow" to directionArrow,
            "next_action" to nextAction,
            "target_name" to targetName,
            "floor_id" to floorId,
            "distance_to_next_action_m" to (distanceToNextActionMeters?.format1() ?: ""),
            "remaining_distance_m" to (remainingDistanceMeters?.format1() ?: ""),
            "remaining_duration_s" to (remainingDurationSeconds?.format1() ?: ""),
            "current_location_name" to currentLocationName,
            "heading_state" to headingState,
            "status_text" to statusText,
            "alert_text" to alertText,
            "mini_map_route" to miniMapRoute,
            "mini_map_current" to miniMapCurrent,
            "mini_map_target" to miniMapTarget,
        )
        mapHeadingDeg?.let { pairs["map_heading_deg"] = it.format1() }
        return pairs
    }

    fun summary(): String {
        return "request_id=$requestId hud_seq=$hudSeq arrow=$directionArrow action=$nextAction target=$targetName current=${currentLocationName.ifBlank { "-" }} floor=$floorId next_distance=${distanceToNextActionMeters?.format1().orDash()} remaining_distance=${remainingDistanceMeters?.format1().orDash()} remaining_duration=${remainingDurationSeconds?.format1().orDash()} heading=$headingState alert=${alertText.ifBlank { "-" }} minimap=${miniMapRoute.ifBlank { "-" }} map_heading=${mapHeadingDeg?.format1().orDash()}"
    }
}

object RokidRuntimeBridge {
    private const val MAX_IMU_AT_CAPTURE_AGE_MS = 300L
    private const val MAX_IMU_HUD_AGE_MS = 3_000L
    private const val MAX_HEADING_ANCHOR_AGE_MS = 30_000L
    private var captureCounter = 0
    private var latestImageBytes: ByteArray? = null
    private var latestCaptureEvent: RokidCapturedFrameEvent? = null
    private var latestImuSample: RokidImuSample? = null
    private var latestVoiceCommand: VoiceNavigationCommand? = null
    private var latestHudPayload: RokidHudPayload? = null
    private var latestHeadingAnchor: RokidHeadingAnchor? = null
    @Volatile
    private var customAppHudCommandSender: ((RokidHudPayload) -> Boolean)? = null
    @Volatile
    private var httpHudCommandSender: ((RokidHudPayload) -> Boolean)? = null
    @Volatile
    private var customAppHudAvailable: Boolean = false
    @Volatile
    private var bridgeCxrConnected: Boolean = false
    @Volatile
    private var httpHudSenderInstalled: Boolean = false
    @Volatile
    private var httpEndpointReady: Boolean = false
    @Volatile
    private var httpStatusLabel: String = "未连接"
    @Volatile
    private var httpEndpointLabel: String = ""

    fun onImuSample(sample: RokidImuSample): RokidImuSample {
        val aged = sample.withAge()
        latestImuSample = aged
        return aged
    }

    fun latestImuSample(): RokidImuSample? = latestImuSample?.withAge()

    fun onImageReceived(
        bytes: ByteArray,
        width: Int?,
        height: Int?,
        captureLatencyMs: Long?,
        captureMode: String = "glasses_private_stream",
        nowMs: Long = System.currentTimeMillis(),
    ): RokidCapturedFrameEvent {
        val captureId = "cap_${nowMs}_${captureCounter++}"
        val imu = latestImuSample?.withAge(nowMs)
            ?.takeIf { it.sampleAgeMs <= MAX_IMU_AT_CAPTURE_AGE_MS }
        val event = RokidCapturedFrameEvent(
            captureId = captureId,
            captureTimestampMs = nowMs,
            captureMode = captureMode,
            imageBytes = bytes.size,
            width = width,
            height = height,
            captureLatencyMs = captureLatencyMs,
            imuAtCapture = imu,
        )
        latestImageBytes = bytes.copyOf()
        latestCaptureEvent = event
        return event
    }

    fun latestCapturedFrame(candidateFloorId: String?): CapturedFrame {
        val bytes = latestImageBytes ?: error("暂无 Rokid HTTP 图传图像，请先启动眼镜端 RokidBridge 并等待图传画面")
        val event = latestCaptureEvent ?: error("暂无 Rokid HTTP 图传元数据，请先启动眼镜端 RokidBridge 并等待图传画面")
        return CapturedFrame(
            providerId = "rokid_glasses_frame",
            bytes = bytes.copyOf(),
            fileName = "${event.captureId}.jpg",
            candidateFloorId = candidateFloorId,
            width = event.width,
            height = event.height,
            captureId = event.captureId,
            captureTimestampMs = event.captureTimestampMs,
            captureMode = event.captureMode,
            imuAtCapture = event.imuAtCapture,
        )
    }

    fun hasCapturedFrame(): Boolean = latestImageBytes != null && latestCaptureEvent != null

    fun onVoiceCommand(command: VoiceNavigationCommand): VoiceNavigationCommand {
        latestVoiceCommand = command
        return command
    }

    fun latestVoiceCommand(): VoiceNavigationCommand? = latestVoiceCommand

    fun onHudUpdate(payload: RokidHudPayload): RokidHudPayload {
        latestHudPayload = payload
        return payload
    }

    fun latestHudPayload(): RokidHudPayload? = latestHudPayload

    fun updateHeadingAnchor(
        venueId: String,
        floorId: String,
        x: Double,
        y: Double,
        landmark: String?,
        mapHeadingDeg: Double?,
        confidence: Double?,
        imuAtCapture: RokidImuSample?,
        nowMs: Long = System.currentTimeMillis(),
    ): RokidHeadingAnchor? {
        val heading = mapHeadingDeg ?: return null
        val imu = imuAtCapture?.withAge(nowMs)
            ?.takeIf { it.sampleAgeMs <= MAX_IMU_AT_CAPTURE_AGE_MS }
            ?: return null
        val anchor = RokidHeadingAnchor(
            anchorId = "heading_anchor_$nowMs",
            venueId = venueId,
            floorId = floorId,
            x = x,
            y = y,
            landmark = landmark,
            mapHeadingDeg = normalize360(heading),
            imuYawDeg = normalize360(imu.yawDeg),
            createdAtMs = nowMs,
            confidence = confidence,
        )
        latestHeadingAnchor = anchor
        return anchor
    }

    fun latestHeadingAnchor(): RokidHeadingAnchor? = latestHeadingAnchor

    fun currentMapHeadingDeg(nowMs: Long = System.currentTimeMillis()): Double? {
        val anchor = latestHeadingAnchor ?: return null
        if (nowMs - anchor.createdAtMs > MAX_HEADING_ANCHOR_AGE_MS) return null
        val sample = latestImuSample?.withAge(nowMs)
            ?.takeIf { it.sampleAgeMs <= MAX_IMU_HUD_AGE_MS }
            ?: return null
        return anchor.currentMapHeadingDeg(sample)
    }

    fun setHudCommandSender(sender: ((RokidHudPayload) -> Boolean)?) {
        val wasAvailable = customAppHudAvailable
        customAppHudCommandSender = sender
        customAppHudAvailable = sender != null
        if (!wasAvailable && sender != null) {
            latestHudPayload?.let { sender(it) }
        }
    }

    fun setHttpHudCommandSender(sender: ((RokidHudPayload) -> Boolean)?) {
        httpHudCommandSender = sender
        httpHudSenderInstalled = sender != null
        if (sender == null) {
            httpEndpointReady = false
            bridgeCxrConnected = false
            httpStatusLabel = "未连接"
            httpEndpointLabel = ""
        } else if (!httpEndpointReady) {
            httpStatusLabel = "查找中"
        }
    }

    fun onHttpEndpointReady(baseUrl: String) {
        httpEndpointReady = true
        httpStatusLabel = "已连接"
        httpEndpointLabel = baseUrl
    }

    fun onHttpBridgeStatus(cxrConnected: Boolean) {
        bridgeCxrConnected = cxrConnected
    }

    fun onHttpStatusEvent(summary: String) {
        when {
            summary.contains("discover_not_found") -> {
                httpEndpointReady = false
                bridgeCxrConnected = false
                httpStatusLabel = "未发现"
            }
            summary.contains("stream_started") || summary.contains("auto_endpoint_ready") -> {
                httpEndpointReady = true
                httpStatusLabel = "已连接"
            }
            summary.contains("stream_error") || summary.contains("status_error") -> {
                httpStatusLabel = if (httpEndpointReady) "异常" else "未连接"
            }
        }
    }

    fun connectionStatus(): RokidConnectionStatus {
        return RokidConnectionStatus(
            customAppAvailable = customAppHudAvailable,
            cxrConnected = customAppHudAvailable || bridgeCxrConnected,
            httpConfigured = httpHudSenderInstalled,
            httpConnected = httpEndpointReady,
            httpStatusLabel = httpStatusLabel,
            httpEndpoint = httpEndpointLabel,
        )
    }

    fun sendHudUpdate(payload: RokidHudPayload): Boolean {
        onHudUpdate(payload)
        val customSent = customAppHudCommandSender?.invoke(payload) == true
        val httpSent = httpHudCommandSender?.invoke(payload) == true
        return customSent || httpSent
    }

    fun currentHeadingState(): String {
        return currentHeadingState(System.currentTimeMillis())
    }

    fun currentHeadingState(nowMs: Long): String {
        val anchor = latestHeadingAnchor ?: return "heading_unavailable"
        if (nowMs - anchor.createdAtMs > MAX_HEADING_ANCHOR_AGE_MS) return "stale_heading"
        val sample = latestImuSample?.withAge(nowMs) ?: return "heading_unavailable"
        return if (sample.sampleAgeMs <= MAX_IMU_HUD_AGE_MS) {
            "imu_bridging"
        } else {
            "stale_heading"
        }
    }

    fun resetForTest() {
        captureCounter = 0
        latestImageBytes = null
        latestCaptureEvent = null
        latestImuSample = null
        latestVoiceCommand = null
        latestHudPayload = null
        latestHeadingAnchor = null
        customAppHudCommandSender = null
        httpHudCommandSender = null
        customAppHudAvailable = false
        bridgeCxrConnected = false
        httpHudSenderInstalled = false
        httpEndpointReady = false
        httpStatusLabel = "未连接"
        httpEndpointLabel = ""
    }
}

data class RokidConnectionStatus(
    val customAppAvailable: Boolean,
    val cxrConnected: Boolean,
    val httpConfigured: Boolean,
    val httpConnected: Boolean,
    val httpStatusLabel: String,
    val httpEndpoint: String,
)

private fun String?.orDash(): String = this ?: "-"

private fun Double.format1(): String = (this * 10.0).roundToInt().let { (it / 10).toString() + "." + kotlin.math.abs(it % 10).toString() }

private fun Double?.orDash(): String = this?.format1() ?: "-"

private fun normalize360(value: Double): Double {
    val normalized = value % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}

private fun normalizeSigned(value: Double): Double {
    val normalized = normalize360(value)
    return if (normalized > 180.0) normalized - 360.0 else normalized
}
