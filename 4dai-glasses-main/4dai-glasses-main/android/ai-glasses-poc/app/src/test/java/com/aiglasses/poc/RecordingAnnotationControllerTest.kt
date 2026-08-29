package com.aiglasses.poc

import com.aiglasses.poc.glasses.GlassesDevice
import com.aiglasses.poc.glasses.RecordingAnnotationAction
import com.aiglasses.poc.glasses.RecordingAnnotationController
import com.aiglasses.poc.glasses.RecordingAnnotationDeviceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAnnotationControllerTest {
    @Test
    fun recordsActionsWithElapsedTime() {
        var elapsedMs = 1_000L
        var wallMs = 2_000L
        val controller = RecordingAnnotationController(
            elapsedTimeMs = { elapsedMs },
            wallTimeMs = { wallMs },
        )

        controller.start(
            device = GlassesDevice("W632_demo", "00:11", -50),
            existingVideoPaths = setOf("old.mp4"),
        )
        elapsedMs = 2_000L
        controller.confirmRecordingStarted()
        elapsedMs = 3_500L
        wallMs = 4_500L
        val session = controller.record(RecordingAnnotationAction.TURN_LEFT)!!

        assertEquals(1, session.events.size)
        assertEquals(RecordingAnnotationAction.TURN_LEFT, session.events.first().action)
        assertEquals(1_500L, session.events.first().elapsedMs)
    }

    @Test
    fun undoRemovesLastAction() {
        var elapsedMs = 10_000L
        val controller = RecordingAnnotationController(
            elapsedTimeMs = { elapsedMs },
            wallTimeMs = { 20_000L },
        )

        controller.start(device = null, existingVideoPaths = emptySet())
        controller.confirmRecordingStarted()
        controller.record(RecordingAnnotationAction.FORWARD)
        elapsedMs = 11_000L
        controller.record(RecordingAnnotationAction.FLOOR_UP)
        val session = controller.undoLast()!!

        assertEquals(1, session.events.size)
        assertEquals(RecordingAnnotationAction.FORWARD, session.events.first().action)
    }

    @Test
    fun stopProducesSerializableJson() {
        var elapsedMs = 100L
        val controller = RecordingAnnotationController(
            elapsedTimeMs = { elapsedMs },
            wallTimeMs = { 1_000L },
        )

        controller.start(device = null, existingVideoPaths = emptySet())
        elapsedMs = 600L
        controller.confirmRecordingStarted()
        elapsedMs = 1_100L
        controller.record(RecordingAnnotationAction.FLOOR_DOWN)
        elapsedMs = 2_100L
        val session = controller.stop()!!
        val json = session.toJsonString()

        assertFalse(session.active)
        assertEquals(1_500L, session.durationMs)
        assertTrue(json.contains("\"type\": \"FLOOR_DOWN\""))
        assertTrue(json.contains("\"time_alignment\": \"sdk_start_video_response\""))
        assertTrue(json.contains("\"elapsed_ms\": 500"))
    }

    @Test
    fun manualSyncMarkerBecomesTimelineZero() {
        var elapsedMs = 1_000L
        val controller = RecordingAnnotationController(
            elapsedTimeMs = { elapsedMs },
            wallTimeMs = { 2_000L },
        )

        controller.start(device = null, existingVideoPaths = emptySet())
        elapsedMs = 1_400L
        controller.confirmRecordingStarted()
        elapsedMs = 2_200L
        controller.markManualSync()
        elapsedMs = 2_900L
        val session = controller.record(RecordingAnnotationAction.FORWARD)!!

        assertEquals(700L, session.events.first().elapsedMs)
        assertEquals("manual_walk_start_marker", session.timeAlignment)
    }

    @Test
    fun actionsAreIgnoredUntilRecordingIsAligned() {
        val controller = RecordingAnnotationController(
            elapsedTimeMs = { 1_000L },
            wallTimeMs = { 2_000L },
        )

        val session = controller.start(device = null, existingVideoPaths = emptySet())
        val afterRecord = controller.record(RecordingAnnotationAction.FORWARD)!!

        assertFalse(session.aligned)
        assertTrue(afterRecord.events.isEmpty())
    }

    @Test
    fun usbCameraSourceIsSerialized() {
        val controller = RecordingAnnotationController(
            elapsedTimeMs = { 1_000L },
            wallTimeMs = { 2_000L },
        )

        val session = controller.start(
            source = RecordingAnnotationDeviceSource.USB_CAMERA,
            deviceName = "UVC 1234:5678",
            deviceAddress = null,
            existingVideoPaths = emptySet(),
        )
        val json = session.toJsonString()

        assertEquals(RecordingAnnotationDeviceSource.USB_CAMERA, session.deviceSource)
        assertTrue(json.contains("\"source\": \"usb_camera\""))
        assertTrue(json.contains("\"source_label\": \"USB 相机\""))
    }
}
