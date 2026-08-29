package com.aiglasses.poc

import com.aiglasses.poc.indoor.ManualIndoorDemoAction
import com.aiglasses.poc.indoor.ManualIndoorDemoController
import com.aiglasses.poc.voice.NavigationVoicePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationVoicePromptsTest {
    @Test
    fun indoorPromptIncludesActionDistanceAndTime() {
        val state = ManualIndoorDemoController().start().state
        val prompt = NavigationVoicePrompts.indoorInstruction(state)

        assertTrue(prompt.contains("室内导航"))
        assertTrue(prompt.contains("当前F1"))
        assertTrue(prompt.contains("直行"))
        assertTrue(prompt.contains("剩余"))
        assertTrue(prompt.contains("预计"))
    }

    @Test
    fun indoorPromptUsesCorrectionFirst() {
        val result = ManualIndoorDemoController().handle(ManualIndoorDemoAction.RIGHT)

        assertEquals("当前应执行：直行", NavigationVoicePrompts.indoorInstruction(result.state))
    }

    @Test
    fun indoorPromptSpeaksArrivalTarget() {
        val controller = ManualIndoorDemoController()
        listOf(
            ManualIndoorDemoAction.UP,
            ManualIndoorDemoAction.LEFT,
            ManualIndoorDemoAction.FLOOR_UP,
            ManualIndoorDemoAction.UP,
            ManualIndoorDemoAction.RIGHT,
            ManualIndoorDemoAction.LEFT,
        ).forEach { controller.handle(it) }

        assertEquals("已到达2F TATA 店铺门口", NavigationVoicePrompts.indoorInstruction(controller.state()))
    }

    @Test
    fun outdoorEventRecognizesReroute() {
        assertEquals("检测到偏离路线，正在重新规划", NavigationVoicePrompts.outdoorEvent("高德偏航重算中"))
    }

    @Test
    fun outdoorEventIgnoresGpsWeakFalse() {
        assertEquals(null, NavigationVoicePrompts.outdoorEvent("高德 GPS 信号弱=false"))
    }
}
