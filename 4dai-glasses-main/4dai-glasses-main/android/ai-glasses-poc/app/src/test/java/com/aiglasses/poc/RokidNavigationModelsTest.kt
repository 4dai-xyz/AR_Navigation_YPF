package com.aiglasses.poc

import com.aiglasses.poc.rokid.RokidImuSample
import com.aiglasses.poc.rokid.RokidHudPayload
import com.aiglasses.poc.rokid.RokidRuntimeBridge
import com.aiglasses.poc.rokid.RokidVoiceCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidNavigationModelsTest {
    @Test
    fun voiceCommandParserParsesNavigateToB17() {
        RokidRuntimeBridge.resetForTest()
        val command = RokidVoiceCommandParser.parse(
            rawText = "Hi Rokid，我要去 B17",
            requestId = "voice_test",
        )

        assertEquals("voice_test", command.requestId)
        assertEquals("rokid_voice_instruction", command.source)
        assertEquals("navigate_to", command.intent)
        assertEquals("B17", command.targetText)
    }

    @Test
    fun voiceCommandParserParsesControlCommands() {
        RokidRuntimeBridge.resetForTest()
        assertEquals("relocalize", RokidVoiceCommandParser.parse("重新定位").intent)
        assertEquals("exit_navigation", RokidVoiceCommandParser.parse("退出导航").intent)
        assertEquals("confirm", RokidVoiceCommandParser.parse("确认").intent)
        assertEquals("cancel", RokidVoiceCommandParser.parse("取消").intent)
    }

    @Test
    fun runtimeBridgeBindsFreshImuToRokidImage() {
        RokidRuntimeBridge.resetForTest()
        val nowMs = 1_700_000_000_000L
        RokidRuntimeBridge.onImuSample(
            RokidImuSample(
                imuTimestampMs = nowMs - 120L,
                yawDeg = 88.0,
                pitchDeg = -2.0,
                rollDeg = 1.0,
                accuracy = "high",
            ),
        )

        val event = RokidRuntimeBridge.onImageReceived(
            bytes = byteArrayOf(1, 2, 3),
            width = 1024,
            height = 768,
            captureLatencyMs = 42L,
            nowMs = nowMs,
        )
        val frame = RokidRuntimeBridge.latestCapturedFrame("F1")

        assertEquals("glasses_private_stream", event.captureMode)
        assertEquals("rokid_glasses_frame", frame.providerId)
        assertEquals(event.captureId, frame.captureId)
        assertEquals("F1", frame.candidateFloorId)
        assertNotNull(frame.imuAtCapture)
        assertEquals(120L, frame.imuAtCapture?.sampleAgeMs)
        assertTrue(RokidRuntimeBridge.hasCapturedFrame())
    }

    @Test
    fun runtimeBridgeReportsNoRokidFrameAfterReset() {
        RokidRuntimeBridge.resetForTest()
        assertFalse(RokidRuntimeBridge.hasCapturedFrame())
    }

    @Test
    fun runtimeBridgeSendsHudThroughRegisteredSender() {
        RokidRuntimeBridge.resetForTest()
        var sentPayload: RokidHudPayload? = null
        val payload = RokidHudPayload(
            requestId = "hud_test",
            directionArrow = "↑",
            nextAction = "直行",
            targetName = "B17",
            floorId = "F1",
            distanceToNextActionMeters = 12.0,
            headingState = "heading_unavailable",
            statusText = "剩余 20米",
        )

        RokidRuntimeBridge.setHudCommandSender {
            sentPayload = it
            true
        }
        val sent = RokidRuntimeBridge.sendHudUpdate(payload)
        RokidRuntimeBridge.setHudCommandSender(null)

        assertEquals(true, sent)
        assertEquals(payload, sentPayload)
        assertEquals(payload, RokidRuntimeBridge.latestHudPayload())
    }

    @Test
    fun runtimeBridgeUsesHeadingAnchorAndRokidImuDelta() {
        RokidRuntimeBridge.resetForTest()
        val nowMs = 1_700_000_000_000L
        val imuAtCapture = RokidImuSample(
            imuTimestampMs = nowMs - 50L,
            yawDeg = 100.0,
            pitchDeg = 0.0,
            rollDeg = 0.0,
            accuracy = "high",
            sampleAgeMs = 50L,
        )
        val anchor = RokidRuntimeBridge.updateHeadingAnchor(
            venueId = "venue_exhibition_demo",
            floorId = "F1",
            x = 36.0,
            y = 12.0,
            landmark = "B17 展台测试卡",
            mapHeadingDeg = 90.0,
            confidence = 0.7,
            imuAtCapture = imuAtCapture,
            nowMs = nowMs,
        )

        assertNotNull(anchor)
        RokidRuntimeBridge.onImuSample(
            RokidImuSample(
                imuTimestampMs = nowMs + 100L,
                yawDeg = 130.0,
                pitchDeg = 0.0,
                rollDeg = 0.0,
                accuracy = "high",
            ),
        )

        assertEquals(120.0, RokidRuntimeBridge.currentMapHeadingDeg(nowMs + 100L) ?: -1.0, 0.01)
        assertEquals("imu_bridging", RokidRuntimeBridge.currentHeadingState(nowMs + 100L))
    }
}
