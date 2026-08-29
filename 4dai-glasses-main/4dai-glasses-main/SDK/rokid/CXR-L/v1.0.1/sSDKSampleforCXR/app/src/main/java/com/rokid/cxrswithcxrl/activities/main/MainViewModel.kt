package com.rokid.cxrswithcxrl.activities.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.rokid.cxr.Caps
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxrswithcxrl.receiver.KeyEventListener
import com.rokid.cxrswithcxrl.receiver.KeyReceiver
import com.rokid.cxrswithcxrl.receiver.KeyType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import java.util.Locale

class MainViewModel: ViewModel() {
    private val _capsFromClient = MutableStateFlow("等待手机连接")
    val capsFromClient = _capsFromClient.asStateFlow()
    private val _hudText = MutableStateFlow("等待手机端下发导航指示")
    val hudText = _hudText.asStateFlow()
    private val _hudUiState = MutableStateFlow(RokidHudUiState())
    val hudUiState = _hudUiState.asStateFlow()
    private var lastAcceptedHudSeq = 0L
    private val _voiceStatus = MutableStateFlow("语音监听未启动")
    val voiceStatus = _voiceStatus.asStateFlow()
    private val _bareMetalStatus = MutableStateFlow("裸机 HTTP 服务未启动")
    val bareMetalStatus = _bareMetalStatus.asStateFlow()
    var onExitRequested: (() -> Unit)? = null

    private val cxrBridge = CXRServiceBridge()
    private var appContext: Context? = null
    private var recordController: RokidVideoRecordController? = null
    private var bareMetalFrameServer: BareMetalFrameServer? = null
    private var sensorManager: SensorManager? = null
    private var imuSensor: Sensor? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isVoiceRecognitionActive = false
    private var lastImuSentAtMs = 0L
    @Volatile
    private var latestImuSample: BareMetalImuSample? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    private val voiceHandler = Handler(Looper.getMainLooper())

    private val cmdKey = "rk_custom_key"

    private val clientKey = "rk_custom_client"

    private val keyEventListener = object : KeyEventListener {
        override fun onKeyEvent(keyType: KeyType) {
            // Keep hardware key events local; the bridge UI should not expose SDK demo messages.
        }

    }

    val keyReceiver = KeyReceiver(keyEventListener)

    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR && event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) {
                return
            }
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastImuSentAtMs < IMU_SEND_INTERVAL_MS) return
            lastImuSentAtMs = nowMs
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationValues)
            val sample = BareMetalImuSample(
                imuTimestampMs = nowMs,
                yawDeg = normalizeDegrees(Math.toDegrees(orientationValues[0].toDouble())),
                pitchDeg = Math.toDegrees(orientationValues[1].toDouble()),
                rollDeg = Math.toDegrees(orientationValues[2].toDouble()),
                accuracy = "sensor_${event.accuracy}",
            )
            latestImuSample = sample
            sendImuSample(sample)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val connectionCallback = object : CXRServiceBridge.StatusListener{
        override fun onConnected(p0: String?, p1: String?, p2: Int) {
            Log.d("CXR", "onConnected")
        }

        override fun onDisconnected() {
            Log.d("CXR", "onDisconnected")
        }

        override fun onConnecting(p0: String?, p1: String?, p2: Int) {
            Log.d("CXR", "onConnecting")
        }

        override fun onARTCStatus(p0: Float, p1: Boolean) {
        }

        override fun onAudioNoise(p0: Float) {
        }

        override fun onRokidAccountChanged(p0: String?) {
        }

    }

    private val msgCallback = object : CXRServiceBridge.MsgCallback {
        override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
            val pairs = args?.let { parseCapsPairs(it) }.orEmpty()
            _capsFromClient.value = clientCommandStatus(pairs)
            handleClientCommand(args)
        }
    }

    init {
        cxrBridge.setStatusListener(connectionCallback)
        cxrBridge.subscribe(clientKey, msgCallback)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        recordController = RokidVideoRecordController(context.applicationContext)
        bareMetalFrameServer = BareMetalFrameServer(
            context = context.applicationContext,
            onStatus = { status ->
                _bareMetalStatus.value = status.summary()
            },
            latestImuSample = { latestImuSample },
            onHudCommand = { pairs -> handleHttpHudUpdate(pairs) },
            onHttpClientActive = { message -> _capsFromClient.value = message },
        ).also { it.start() }
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        imuSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        imuSensor?.let { sensor ->
            sensorManager?.registerListener(imuListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        } ?: sendMessage("Rokid IMU unavailable")
    }

    fun startVoiceRecognition(context: Context) {
        val applicationContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _voiceStatus.value = "缺少麦克风权限，无法监听真实语音"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            _voiceStatus.value = "当前固件未提供语音识别服务"
            sendMessage("Android SpeechRecognizer unavailable on this Rokid firmware")
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
                setRecognitionListener(voiceRecognitionListener)
            }
        }
        isVoiceRecognitionActive = true
        startListeningNow()
    }

    fun sendMessage(str: String){
        cxrBridge.sendMessage(cmdKey, Caps().apply {
            write("message")
            write(str)
        })
    }

    fun sendDemoVoiceCommand(text: String) {
        sendVoiceCommand(text, "rokid_glasses_voice_unavailable_demo")
        _voiceStatus.value = "已发送模拟语音命令：$text"
    }

    fun release() {
        isVoiceRecognitionActive = false
        voiceHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        sensorManager?.unregisterListener(imuListener)
        bareMetalFrameServer?.close()
        bareMetalFrameServer = null
        recordController?.release()
    }

    fun exitApp(activity: Activity) {
        stopActiveRecord("")
        sendMessage("VisionRoute glasses app exiting")
        activity.finishAndRemoveTask()
    }

    private fun handleClientCommand(args: Caps?) {
        val pairs = args?.let { parseCapsPairs(it) }.orEmpty()
        when (pairs["action"]) {
            "PING" -> sendRecordEvent(
                RokidRecordEvent(
                    event = "PONG",
                    ok = true,
                    message = "VisionRoute glasses app is alive",
                ),
                pairs["request_id"].orEmpty(),
            )
            "START_RECORD" -> startRecord(pairs["request_id"].orEmpty())
            "STOP_RECORD" -> stopRecord(pairs["request_id"].orEmpty())
            "HUD_UPDATE" -> handleHudUpdate(pairs)
            "EXIT_APP" -> exitRequested(pairs["request_id"].orEmpty())
        }
    }

    private fun handleHudUpdate(pairs: Map<String, String>) {
        val hudSeq = pairs["hud_seq"]?.toLongOrNull()
        if (hudSeq != null && hudSeq < lastAcceptedHudSeq) {
            return
        }
        if (hudSeq != null) {
            lastAcceptedHudSeq = hudSeq
        }
        _hudUiState.value = RokidHudUiState.fromPairs(pairs)
        val text = listOfNotNull(
            pairs["direction_arrow"]?.takeIf { it.isNotBlank() },
            pairs["next_action"]?.takeIf { it.isNotBlank() },
            pairs["target_name"]?.takeIf { it.isNotBlank() }?.let { "目标：$it" },
            pairs["current_location_name"]?.takeIf { it.isNotBlank() }?.let { "当前位置：$it" },
            pairs["distance_to_next_action_m"]?.takeIf { it.isNotBlank() }?.let { "距离：${it}m" },
            pairs["heading_state"]?.takeIf { it.isNotBlank() }?.let { "航向：$it" },
            pairs["status_text"]?.takeIf { it.isNotBlank() },
        ).joinToString("\n")
        _hudText.value = text.ifBlank { "HUD 已收到空更新" }
        runCatching {
            cxrBridge.sendMessage(cmdKey, Caps().apply {
                write("event")
                write("HUD_ACK")
                write("ok")
                write("true")
                write("request_id")
                write(pairs["request_id"].orEmpty())
                write("message")
                write("HUD updated")
            })
        }
    }

    private fun handleHttpHudUpdate(pairs: Map<String, String>) {
        _capsFromClient.value = "手机已连接（HTTP HUD）"
        handleHudUpdate(pairs)
    }

    private fun sendImuSample(sample: BareMetalImuSample) {
        cxrBridge.sendMessage(cmdKey, Caps().apply {
            write("event")
            write("IMU_SAMPLE")
            write("source")
            write("rokid_imu")
            write("imu_timestamp_ms")
            write(sample.imuTimestampMs.toString())
            write("yaw_deg")
            write(sample.yawDeg.format1())
            write("pitch_deg")
            write(sample.pitchDeg.format1())
            write("roll_deg")
            write(sample.rollDeg.format1())
            write("accuracy")
            write(sample.accuracy)
        })
    }

    private val voiceRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _voiceStatus.value = "正在监听：请说“导航到 B17”或“我要去 B17”"
        }

        override fun onBeginningOfSpeech() {
            _voiceStatus.value = "正在识别语音…"
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _voiceStatus.value = "语音结束，等待识别结果…"
        }

        override fun onError(error: Int) {
            _voiceStatus.value = "语音识别错误：${voiceErrorLabel(error)}，继续监听"
            scheduleVoiceRestart(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 600L else 1_200L)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            handleRecognizedVoice(text)
            scheduleVoiceRestart(400L)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun startListeningNow() {
        if (!isVoiceRecognitionActive) return
        val recognizer = speechRecognizer ?: return
        runCatching {
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }.onFailure { throwable ->
            _voiceStatus.value = "启动语音监听失败：${throwable.message ?: throwable.javaClass.simpleName}"
            scheduleVoiceRestart(1_500L)
        }
    }

    private fun scheduleVoiceRestart(delayMs: Long) {
        if (!isVoiceRecognitionActive) return
        voiceHandler.removeCallbacksAndMessages(null)
        voiceHandler.postDelayed({ startListeningNow() }, delayMs)
    }

    private fun handleRecognizedVoice(text: String) {
        if (text.isBlank()) {
            _voiceStatus.value = "未识别到有效语音，继续监听"
            return
        }
        if (!looksLikeNavigationCommand(text)) {
            _voiceStatus.value = "等待导航语音指令"
            return
        }
        sendVoiceCommand(text, "rokid_glasses_speech_recognizer")
        _voiceStatus.value = "已发送语音命令：$text"
    }

    private fun sendVoiceCommand(text: String, source: String) {
        val requestId = "voice_${System.currentTimeMillis()}"
        cxrBridge.sendMessage(cmdKey, Caps().apply {
            write("event")
            write("VOICE_COMMAND")
            write("source")
            write(source)
            write("request_id")
            write(requestId)
            write("raw_text")
            write(text)
        })
    }

    private fun looksLikeNavigationCommand(text: String): Boolean {
        val compact = text.replace("\\s+".toRegex(), "")
        if (compact.isBlank()) return false
        val commandMarkers = listOf("导航到", "我要去", "带我去", "重新定位", "重定位", "退出导航", "结束导航", "确认", "取消")
        if (commandMarkers.any { compact.contains(it) }) return true
        return Regex("[A-Za-z]\\d{1,4}").containsMatchIn(compact)
    }

    private fun voiceErrorLabel(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "音频错误"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少权限"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "未匹配"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
            SpeechRecognizer.ERROR_SERVER -> "服务错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
            else -> "未知错误$error"
        }
    }

    private fun exitRequested(requestId: String) {
        stopActiveRecord(requestId)
        sendRecordEvent(
            RokidRecordEvent(
                event = "APP_EXITING",
                ok = true,
                message = "眼镜端协同 App 正在退出",
            ),
            requestId,
        )
        onExitRequested?.invoke()
    }

    private fun stopActiveRecord(requestId: String) {
        val controller = recordController
        if (controller?.isRecording() == true) {
            stopRecord(requestId)
        }
    }

    private fun startRecord(requestId: String) {
        val context = appContext
        val controller = recordController
        if (context == null || controller == null) {
            sendRecordEvent(recordError("录像模块未初始化"), requestId)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendRecordEvent(recordError("缺少 CAMERA 权限"), requestId)
            return
        }
        controller.start { event -> sendRecordEvent(event, requestId) }
    }

    private fun stopRecord(requestId: String) {
        val controller = recordController
        if (controller == null) {
            sendRecordEvent(recordError("录像模块未初始化"), requestId)
            return
        }
        controller.stop { event -> sendRecordEvent(event, requestId) }
    }

    private fun sendRecordEvent(event: RokidRecordEvent, requestId: String) {
        cxrBridge.sendMessage(cmdKey, Caps().apply {
            write("event")
            write(event.event)
            write("ok")
            write(event.ok.toString())
            write("request_id")
            write(requestId)
            write("message")
            write(event.message)
            write("file_name")
            write(event.fileName)
            write("file_path")
            write(event.filePath)
            write("size_bytes")
            write(event.sizeBytes.toString())
            write("start_time_ms")
            write(event.startTimeMs.toString())
            write("stop_time_ms")
            write(event.stopTimeMs.toString())
            write("duration_ms")
            write(event.durationMs.toString())
            write("width")
            write(event.width.toString())
            write("height")
            write(event.height.toString())
        })
    }

    private fun recordError(message: String): RokidRecordEvent {
        return RokidRecordEvent(
            event = "RECORD_ERROR",
            ok = false,
            message = message,
        )
    }

    private fun clientCommandStatus(pairs: Map<String, String>): String {
        return when (pairs["action"] ?: pairs["event"]) {
            "PING" -> "手机已连接"
            "START_RECORD" -> "手机请求开始录像"
            "STOP_RECORD" -> "手机请求结束录像"
            "HUD_UPDATE" -> "已收到导航指示"
            "EXIT_APP" -> "手机请求退出"
            null -> "手机已连接"
            else -> "手机已连接"
        }
    }

    private fun parseCapsPairs(caps: Caps): Map<String, String> {
        val values = (0 until caps.size()).map { index -> caps.at(index).toReadableString() }
        return values.chunked(2)
            .filter { it.size == 2 }
            .associate { it[0] to it[1] }
    }

    private fun parseCaps(caps: Caps): String {
        val strBuilder = StringBuilder("{")
        for (i in 0 until caps.size()) {
            val capsValue = caps.at(i)
            val string = "${capsValue.typeLabel()}:${capsValue.toReadableString()}"
            strBuilder.append("${string},")
        }
        if (strBuilder.length > 4) {//如果有值，删除最后一个逗号
            strBuilder.deleteCharAt(strBuilder.length - 1)
        }
        strBuilder.append("}")
        return strBuilder.toString()
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
            Caps.Value.TYPE_BINARY -> binary?.let {
                Base64.encodeToString(it.data.copyOf(it.length), Base64.NO_WRAP)
            } ?: "null"
            else -> "null"
        }
    }

    private fun normalizeDegrees(value: Double): Double {
        var normalized = value % 360.0
        if (normalized < 0) normalized += 360.0
        return normalized
    }

    private fun Double.format1(): String {
        return ((this * 10.0).roundToInt() / 10.0).toString()
    }

    companion object {
        private const val IMU_SEND_INTERVAL_MS = 300L
    }
}

data class RokidHudUiState(
    val hasUpdate: Boolean = false,
    val hudSeq: Long = 0L,
    val directionArrow: String = "↑",
    val nextAction: String = "等待导航",
    val targetName: String = "目标未确认",
    val floorId: String = "-",
    val currentLocationName: String = "",
    val nextDistance: String = "",
    val remainingDistance: String = "",
    val remainingDuration: String = "",
    val statusText: String = "等待手机端下发导航指示",
    val alertText: String = "",
    val miniMapRoute: List<RokidHudMapPoint> = emptyList(),
    val miniMapCurrent: RokidHudMapPoint? = null,
    val miniMapTarget: RokidHudMapPoint? = null,
) {
    companion object {
        fun fromPairs(pairs: Map<String, String>): RokidHudUiState {
            return RokidHudUiState(
                hasUpdate = true,
                hudSeq = pairs["hud_seq"]?.toLongOrNull() ?: 0L,
                directionArrow = pairs["direction_arrow"].orEmpty().ifBlank { "↑" },
                nextAction = pairs["next_action"].orEmpty().ifBlank { "继续前进" },
                targetName = pairs["target_name"].orEmpty().ifBlank { "目标未确认" },
                floorId = pairs["floor_id"].orEmpty().ifBlank { "-" },
                currentLocationName = pairs["current_location_name"].orEmpty(),
                nextDistance = pairs["distance_to_next_action_m"].orEmpty().toMetersText(),
                remainingDistance = pairs["remaining_distance_m"].orEmpty().toMetersText(),
                remainingDuration = pairs["remaining_duration_s"].orEmpty().toDurationText(),
                statusText = pairs["status_text"].orEmpty().ifBlank { "导航信息已更新" },
                alertText = pairs["alert_text"].orEmpty(),
                miniMapRoute = pairs["mini_map_route"].orEmpty().toHudMapPoints(),
                miniMapCurrent = pairs["mini_map_current"].orEmpty().toHudMapPoint(),
                miniMapTarget = pairs["mini_map_target"].orEmpty().toHudMapPoint(),
            )
        }

        private fun String.toMetersText(): String {
            val value = toDoubleOrNull() ?: return ""
            return if (value < 1.0) "0米" else "${value.format1()}米"
        }

        private fun String.toDurationText(): String {
            val value = toDoubleOrNull() ?: return ""
            val totalSeconds = kotlin.math.ceil(value.coerceAtLeast(0.0)).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
        }

        private fun Double.format1(): String {
            return ((this * 10.0).roundToInt() / 10.0).toString()
        }

        private fun String.toHudMapPoints(): List<RokidHudMapPoint> {
            return split(";")
                .mapNotNull { it.toHudMapPoint() }
        }

        private fun String.toHudMapPoint(): RokidHudMapPoint? {
            val parts = trim().split(",")
            if (parts.size != 2) return null
            val x = parts[0].toFloatOrNull() ?: return null
            val y = parts[1].toFloatOrNull() ?: return null
            return RokidHudMapPoint(
                x = x.coerceIn(0f, 1000f),
                y = y.coerceIn(0f, 1000f),
            )
        }
    }
}

data class RokidHudMapPoint(
    val x: Float,
    val y: Float,
)
