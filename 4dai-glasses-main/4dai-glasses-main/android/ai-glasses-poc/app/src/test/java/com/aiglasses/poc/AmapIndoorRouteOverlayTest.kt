package com.aiglasses.poc

import com.aiglasses.poc.indoor.AmapIndoorRouteOverlay
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapIndoorRouteOverlayTest {
    @Test
    fun wudaokouOverlayAssetCanBeParsed() {
        val overlay = loadOverlay()

        assertEquals("manual_demo_wudaokou_west_to_2f_tata", overlay.routeId)
        assertEquals("venue_bj_wudaokou_shopping_center_demo", overlay.venueId)
        assertEquals("poi_f2_tata_door_demo", overlay.targetPoiId)
        assertEquals(7, overlay.nodes.size)
    }

    @Test
    fun wudaokouOverlayContainsFloorPolylines() {
        val overlay = loadOverlay()

        assertEquals(
            listOf(
                "node_f1_west_gate_demo",
                "node_f1_west_corridor_turn_demo",
                "node_f1_escalator_up_demo_01",
            ),
            overlay.routeNodeIdsForFloor("F1"),
        )
        assertEquals(
            listOf(
                "node_f2_escalator_out_demo_01",
                "node_f2_tata_corridor_turn_01_demo",
                "node_f2_tata_corridor_turn_02_demo",
                "node_f2_tata_door_demo",
            ),
            overlay.routeNodeIdsForFloor("F2"),
        )
    }

    @Test
    fun wudaokouOverlayUsesGcj02ForAnchors() {
        val overlay = loadOverlay()
        val westGate = overlay.node("node_f1_west_gate_demo")
        val tataDoor = overlay.node("node_f2_tata_door_demo")

        assertEquals(39.991583, westGate?.latitude ?: 0.0, 0.000001)
        assertEquals(116.338965, westGate?.longitude ?: 0.0, 0.000001)
        assertEquals(39.991556, tataDoor?.latitude ?: 0.0, 0.000001)
        assertEquals(116.339568, tataDoor?.longitude ?: 0.0, 0.000001)
    }

    @Test
    fun f2PolylineOnlyContainsF2Nodes() {
        val overlay = loadOverlay()
        val f2Nodes = overlay.routeNodesForFloor("F2")

        assertTrue(f2Nodes.isNotEmpty())
        assertTrue(f2Nodes.all { it.floorId == "F2" })
        assertFalse(f2Nodes.any { it.floorId == "F1" })
    }

    private fun loadOverlay(): AmapIndoorRouteOverlay {
        val json = File("src/main/assets/indoor_routes/wudaokou_tata_amap_gcj02_overlay.json").readText()
        return AmapIndoorRouteOverlay.fromJson(json)
    }
}
