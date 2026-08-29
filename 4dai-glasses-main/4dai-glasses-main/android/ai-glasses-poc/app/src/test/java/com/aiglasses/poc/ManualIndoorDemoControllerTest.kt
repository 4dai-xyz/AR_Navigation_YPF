package com.aiglasses.poc

import com.aiglasses.poc.indoor.ManualIndoorDemoAction
import com.aiglasses.poc.indoor.ManualIndoorDemoController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualIndoorDemoControllerTest {
    @Test
    fun correctActionAdvancesOneStep() {
        val controller = ManualIndoorDemoController()
        val start = controller.start().state

        val result = controller.handle(ManualIndoorDemoAction.UP)

        assertEquals(start.stepIndex + 1, result.state.stepIndex)
        assertEquals("F1 西侧通道转角", result.state.currentNodeLabel)
        assertTrue(result.events.any { it.startsWith("manual_demo_step_advanced") })
    }

    @Test
    fun wrongActionDoesNotAdvance() {
        val controller = ManualIndoorDemoController()
        controller.start()

        val result = controller.handle(ManualIndoorDemoAction.LEFT)

        assertEquals(0, result.state.stepIndex)
        assertEquals("当前应执行：直行", result.state.correction)
        assertTrue(result.events.any { it.startsWith("manual_demo_wrong_action") })
    }

    @Test
    fun backActionReturnsToPreviousStep() {
        val controller = ManualIndoorDemoController()
        controller.start()
        controller.handle(ManualIndoorDemoAction.UP)

        val result = controller.handle(ManualIndoorDemoAction.BACK)

        assertEquals(0, result.state.stepIndex)
        assertEquals("F1 西门入口", result.state.currentNodeLabel)
        assertTrue(result.events.any { it.startsWith("manual_demo_step_back") })
    }

    @Test
    fun floorActionSwitchesToTargetFloor() {
        val controller = ManualIndoorDemoController()
        controller.start()
        controller.handle(ManualIndoorDemoAction.UP)
        controller.handle(ManualIndoorDemoAction.LEFT)

        val result = controller.handle(ManualIndoorDemoAction.FLOOR_UP)

        assertEquals("F2", result.state.currentFloorId)
        assertEquals("F2 扶梯出口", result.state.currentNodeLabel)
        assertTrue(result.events.any { it == "manual_demo_floor_changed from=F1 to=F2" })
    }

    @Test
    fun currentFloorRouteSwitchesAfterFloorUp() {
        val controller = ManualIndoorDemoController()
        val start = controller.start().state

        assertTrue(start.pendingRoute.all { it.floorId == "F1" })

        controller.handle(ManualIndoorDemoAction.UP)
        controller.handle(ManualIndoorDemoAction.LEFT)
        val result = controller.handle(ManualIndoorDemoAction.FLOOR_UP)

        assertEquals("F2", result.state.currentFloorId)
        assertTrue(result.state.completedRoute.all { it.floorId == "F2" })
        assertTrue(result.state.pendingRoute.all { it.floorId == "F2" })
    }

    @Test
    fun fullWudaokouTataActionSequenceArrives() {
        val controller = ManualIndoorDemoController()
        var result = controller.start()

        listOf(
            ManualIndoorDemoAction.UP,
            ManualIndoorDemoAction.LEFT,
            ManualIndoorDemoAction.FLOOR_UP,
            ManualIndoorDemoAction.UP,
            ManualIndoorDemoAction.RIGHT,
            ManualIndoorDemoAction.LEFT,
        ).forEach { action ->
            result = controller.handle(action)
        }

        assertTrue(result.state.arrived)
        assertEquals("F2", result.state.currentFloorId)
        assertEquals("2F TATA 店铺门口", result.state.currentNodeLabel)
        assertEquals("2F TATA 店铺门口", result.state.targetNodeLabel)
        assertTrue(result.events.any { it == "manual_demo_arrived target=2F TATA 店铺门口" })
    }

    @Test
    fun floorDownDoesNotAdvanceWhenCurrentStepNeedsFloorUp() {
        val controller = ManualIndoorDemoController()
        controller.start()
        controller.handle(ManualIndoorDemoAction.UP)
        controller.handle(ManualIndoorDemoAction.LEFT)

        val result = controller.handle(ManualIndoorDemoAction.FLOOR_DOWN)

        assertEquals("F1", result.state.currentFloorId)
        assertEquals("F1 上行扶梯口", result.state.currentNodeLabel)
        assertEquals("当前应执行：上楼", result.state.correction)
        assertTrue(result.events.any { it.startsWith("manual_demo_wrong_action") })
    }

    @Test
    fun resetReturnsToFirstStep() {
        val controller = ManualIndoorDemoController()
        controller.start()
        controller.handle(ManualIndoorDemoAction.UP)

        val result = controller.reset()

        assertEquals(0, result.state.stepIndex)
        assertEquals("F1", result.state.currentFloorId)
        assertEquals("F1 西门入口", result.state.currentNodeLabel)
        assertFalse(result.state.arrived)
        assertTrue(result.events.contains("manual_demo_reset"))
    }

    @Test
    fun defaultScriptUsesWudaokouTataTarget() {
        val state = ManualIndoorDemoController().start().state

        assertEquals("venue_bj_wudaokou_shopping_center_demo", state.venueId)
        assertEquals("poi_f2_tata_door_demo", state.targetPoiId)
        assertEquals("2F TATA 店铺门口", state.targetNodeLabel)
    }

    @Test
    fun defaultScriptKeepsSurveyedDoorCoordinates() {
        val state = ManualIndoorDemoController().start().state

        assertEquals(39.991556, state.target.latitude ?: 0.0, 0.000001)
        assertEquals(116.339568, state.target.longitude ?: 0.0, 0.000001)
    }

    @Test
    fun defaultScriptKeepsSurveyedConnectorCoordinates() {
        val controller = ManualIndoorDemoController()
        controller.start()
        controller.handle(ManualIndoorDemoAction.UP)
        controller.handle(ManualIndoorDemoAction.LEFT)
        val firstFloorConnector = controller.state().current

        assertEquals("F1 上行扶梯口", firstFloorConnector.label)
        assertEquals(39.992093, firstFloorConnector.latitude ?: 0.0, 0.000001)
        assertEquals(116.339331, firstFloorConnector.longitude ?: 0.0, 0.000001)

        controller.handle(ManualIndoorDemoAction.FLOOR_UP)
        val secondFloorConnector = controller.state().current

        assertEquals("F2 扶梯出口", secondFloorConnector.label)
        assertEquals(39.992189, secondFloorConnector.latitude ?: 0.0, 0.000001)
        assertEquals(116.339304, secondFloorConnector.longitude ?: 0.0, 0.000001)
    }
}
