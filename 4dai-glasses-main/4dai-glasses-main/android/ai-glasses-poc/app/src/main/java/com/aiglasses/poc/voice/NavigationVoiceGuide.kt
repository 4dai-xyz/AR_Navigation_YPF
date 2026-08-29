package com.aiglasses.poc.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.aiglasses.poc.indoor.ManualIndoorDemoState
import java.util.Locale

class NavigationVoiceGuide(
    context: Context,
    private val logger: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var lastSpokenText: String? = null
    private var lastSpokenAtMs: Long = 0L

    private fun record(message: String) {
        Log.d(TAG, message)
        logger(message)
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val languageResult = tts?.setLanguage(Locale.CHINA)
                record("语音播报初始化完成 languageResult=${languageResult ?: "-"}")
                pendingText?.let { text ->
                    pendingText = null
                    speak(text, flush = true)
                }
            } else {
                record("语音播报初始化失败 status=$status")
            }
        }
    }

    fun speak(text: String, flush: Boolean = false) {
        val message = text.trim().takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        if (message == lastSpokenText && now - lastSpokenAtMs < DUPLICATE_SUPPRESS_MS) {
            return
        }
        lastSpokenText = message
        lastSpokenAtMs = now
        if (!ready) {
            pendingText = message
            record("语音播报等待初始化 text=$message")
            return
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts?.speak(message, mode, null, "vr_${now}") ?: TextToSpeech.ERROR
        record("语音播报 text=$message result=$result")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pendingText = null
    }

    private companion object {
        private const val TAG = "VisionRouteVoice"
        private const val DUPLICATE_SUPPRESS_MS = 4_000L
    }
}

object NavigationVoicePrompts {
    fun outdoorStarted(type: String): String {
        return "室外${type}导航已开始，请按地图指引前往入口"
    }

    fun outdoorArrived(): String {
        return "已到达入口附近，请进入场馆后开始室内导航"
    }

    fun outdoorEvent(summary: String): String? {
        return when {
            summary.contains("偏航") || summary.contains("重新规划") -> "检测到偏离路线，正在重新规划"
            summary.contains("GPS 信号弱=true") -> "当前 GPS 信号较弱，请注意地图提示"
            summary.contains("模拟导航已结束") -> "模拟导航已结束"
            else -> null
        }
    }

    fun indoorInstruction(state: ManualIndoorDemoState): String {
        state.correction?.let { return it }
        if (state.arrived) {
            return "已到达${state.targetNodeLabel}"
        }
        val floor = state.currentFloorId
        val action = state.expectedAction?.label.orEmpty()
        val remainingDistance = state.remainingDistanceMeters.toInt().coerceAtLeast(0)
        val remainingTime = formatDuration(state.remainingDurationSeconds)
        return "室内导航，当前${floor}，${action}。剩余${remainingDistance}米，预计${remainingTime}"
    }

    fun formatDuration(seconds: Double): String {
        val totalSeconds = seconds.toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val remainSeconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}分钟${remainSeconds}秒"
        } else {
            "${remainSeconds}秒"
        }
    }
}
