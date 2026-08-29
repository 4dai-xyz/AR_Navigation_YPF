package com.aiglasses.poc

import com.aiglasses.poc.indoor.ImageIndoorCoordinateMapper
import com.aiglasses.poc.indoor.ImageIndoorManualDemoScriptBuilder
import com.aiglasses.poc.indoor.ImageIndoorNavGraph
import com.aiglasses.poc.indoor.ImageIndoorNavigationRepository
import com.aiglasses.poc.indoor.ImageIndoorPoiResolver
import com.aiglasses.poc.indoor.ManualIndoorDemoAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageIndoorNavigationRepositoryTest {
    @Test
    fun graphAndResolverCanBeLoadedFromAssets() {
        val repository = loadRepository()

        assertEquals("image_pixel", repository.graph.coordinateSystem.type)
        assertEquals("top_left", repository.graph.coordinateSystem.origin)
        assertEquals(
            listOf("B1", "F1", "F2", "F3", "F4", "F5", "F6"),
            repository.graph.floors.map { it.floorId },
        )
        assertTrue(repository.graph.nodes.isNotEmpty())
        assertTrue(repository.graph.edges.isNotEmpty())
        assertTrue(repository.graph.entrances.any { it.routeNodeId == START_NODE_ID })
    }

    @Test
    fun resolverMatchesExpectedDemoPoisByNameOrAlias() {
        val repository = loadRepository()

        assertEquals("poi_f1_nike_67", repository.searchPoi("nike").first().poiId)
        assertEquals("poi_f1_starbuck_1", repository.searchPoi("starbuck").first().poiId)
        assertEquals("poi_b1_ruixing_luckin_26", repository.searchPoi("ruixing").first().poiId)
        assertEquals("poi_b1_ruixing_luckin_26", repository.searchPoi("ruixing(luckin)").first().poiId)
    }

    @Test
    fun resolverMatchesChineseAndPinyinBrandAliases() {
        val repository = loadRepository()

        assertEquals("poi_f1_xiaomi_13", repository.searchPoi("小米").first().poiId)
        assertEquals("poi_f1_xiaomi_13", repository.searchPoi("小米之家").first().poiId)
        assertEquals("poi_f1_xiaomi_13", repository.searchPoi("xiaomi").first().poiId)
        assertEquals("poi_b1_yimayila_24", repository.searchPoi("一麻一辣五道口店").first().poiId)
        assertEquals("poi_f1_huawei_7", repository.searchPoi("华为").first().poiId)
        assertEquals("poi_f1_huawei_7", repository.searchPoi("huawei").first().poiId)
        assertEquals("poi_f1_bawangchaji_72", repository.searchPoi("霸王茶姬").first().poiId)
        assertEquals("poi_b1_aichaosuannai_121", repository.searchPoi("aichaosuannai").first().poiId)
        assertEquals("poi_b1_lutaizong_45", repository.searchPoi("卤太宗").first().poiId)
        assertEquals("poi_b1_lutaizong_45", repository.searchPoi("lutaizong").first().poiId)
        assertEquals("poi_f2_tata_70", repository.searchPoi("TATA").first().poiId)
        assertEquals("poi_f2_velwin_923", repository.searchPoi("VELWIN").first().poiId)
    }

    @Test
    fun resolverKeepsDisplayAndHandoffMetadataForSearchCards() {
        val repository = loadRepository()
        val target = repository.searchPoi("aichaosuannai").first()

        assertEquals("爱炒酸奶", target.displayName)
        assertEquals("五道口购物中心", target.venueName)
        assertEquals("北京市海淀区成府路28号", target.venueAddress)
        assertTrue(target.badges.contains("indoor-only"))
        assertFalse(target.externalRefs?.amapSearchable ?: true)
        assertEquals("node_entrance_f1_west_gate_access", target.outdoorHandoff?.preferredEntranceRouteNodeId)
        assertEquals(39.991583, target.outdoorHandoff?.preferredEntranceGcj02?.latitude ?: 0.0, 0.000001)
        assertEquals(116.338965, target.outdoorHandoff?.preferredEntranceGcj02?.longitude ?: 0.0, 0.000001)
    }

    @Test
    fun f1EntranceCanRouteToF1Poi() {
        val repository = loadRepository()
        val target = repository.searchPoi("nike").first()

        val plan = repository.planRoute(START_NODE_ID, target.routeNodeId)

        assertNotNull(plan)
        requireNotNull(plan)
        assertEquals("F1", plan.start.floorId)
        assertEquals("F1", plan.target.floorId)
        assertTrue(plan.edges.isNotEmpty())
        assertTrue(plan.verticalSteps.isEmpty())
        assertTrue(plan.walkSegmentsForFloor("F1").isNotEmpty())
    }

    @Test
    fun f1EntranceCanRouteToB1PoiWithVerticalStep() {
        val repository = loadRepository()
        val target = repository.searchPoi("ruixing").first()

        val plan = repository.planRoute(START_NODE_ID, target.routeNodeId)

        assertNotNull(plan)
        requireNotNull(plan)
        assertEquals("F1", plan.start.floorId)
        assertEquals("B1", plan.target.floorId)
        assertTrue(plan.verticalSteps.isNotEmpty())
        assertTrue(plan.walkSegmentsForFloor("F1").isNotEmpty())
        assertTrue(plan.walkSegmentsForFloor("B1").isNotEmpty())
    }

    @Test
    fun f1EntranceCanRouteToF2PoiWithVerticalStep() {
        val repository = loadRepository()
        val target = repository.searchPoi("TATA").first()

        val plan = repository.planRoute(START_NODE_ID, target.routeNodeId)

        assertNotNull(plan)
        requireNotNull(plan)
        assertEquals("F1", plan.start.floorId)
        assertEquals("F2", plan.target.floorId)
        assertTrue(plan.verticalSteps.isNotEmpty())
        assertTrue(plan.walkSegmentsForFloor("F1").isNotEmpty())
        assertTrue(plan.walkSegmentsForFloor("F2").isNotEmpty())
    }

    @Test
    fun b1RouteBuildsManualDemoScriptForSelectedTarget() {
        val repository = loadRepository()
        val target = repository.searchPoi("lutaizong").first()
        val plan = requireNotNull(repository.planRoute(START_NODE_ID, target.routeNodeId))

        val script = ImageIndoorManualDemoScriptBuilder.build(
            plan = plan,
            venueId = "venue_bj_wudaokou_shopping_center_demo",
            targetPoiId = target.poiId,
            targetLabel = "卤太宗",
        )

        assertEquals("poi_b1_lutaizong_45", script.targetPoiId)
        assertEquals("卤太宗", script.target.label)
        assertEquals("B1", script.target.floorId)
        assertTrue(script.steps.any { it.expectedAction == ManualIndoorDemoAction.FLOOR_DOWN })
        assertFalse(script.routeId.contains("tata", ignoreCase = true))
    }

    @Test
    fun oneWayEscalatorEdgesAreNotUsedInReverse() {
        val graph = loadRepository().graph
        val oneWayEdge = graph.edges.first { edge ->
            edge.travelMode == "escalator" && !edge.bidirectional
        }

        val reverseEdges = graph.outgoingEdges(oneWayEdge.toNodeId)

        assertFalse(reverseEdges.any { it.edgeId == oneWayEdge.edgeId && it.toNodeId == oneWayEdge.fromNodeId })
    }

    @Test
    fun imagePixelMappingKeepsTopLeftOriginAndDoesNotFlipY() {
        val topLeft = ImageIndoorCoordinateMapper.mapToScreen(
            nodeX = 0.0,
            nodeY = 0.0,
            imageWidth = 1440,
            imageHeight = 3200,
            viewWidth = 720,
            viewHeight = 1600,
        )
        val bottomRight = ImageIndoorCoordinateMapper.mapToScreen(
            nodeX = 1440.0,
            nodeY = 3200.0,
            imageWidth = 1440,
            imageHeight = 3200,
            viewWidth = 720,
            viewHeight = 1600,
        )

        assertEquals(0f, topLeft.x, 0.001f)
        assertEquals(0f, topLeft.y, 0.001f)
        assertEquals(720f, bottomRight.x, 0.001f)
        assertEquals(1600f, bottomRight.y, 0.001f)
    }

    @Test
    fun sharedAmapAlignmentProjectsAllFloorsFromImagePixels() {
        val graph = loadRepository().graph
        val alignment = graph.sharedAmapAlignment

        assertNotNull(alignment)
        requireNotNull(alignment)
        assertEquals("shared_same_viewport_all_floors", alignment.mode)
        assertEquals("image_pixel", alignment.sourceCoordinate)
        assertEquals("gcj02", alignment.targetCoordinate)
        assertTrue(alignment.assumption.contains("same", ignoreCase = true))
        assertTrue(alignment.appliesToFloor("B1"))
        assertTrue(alignment.appliesToFloor("F6"))

        val westGate = requireNotNull(graph.node(START_NODE_ID))
        val tata = requireNotNull(graph.node("node_f2_tata_70_access"))
        val westGateGcj02 = requireNotNull(alignment.project(westGate))
        val tataGcj02 = requireNotNull(alignment.project(tata))

        assertEquals(39.991583, westGateGcj02.latitude, 0.000001)
        assertEquals(116.338965, westGateGcj02.longitude, 0.000001)
        assertEquals(39.991556, tataGcj02.latitude, 0.000001)
        assertEquals(116.339568, tataGcj02.longitude, 0.000001)
    }

    private fun loadRepository(): ImageIndoorNavigationRepository {
        val graph = File("src/main/assets/mapping/wudaokou2/wudaokou_all_floors_app_nav_graph.json").readText()
        val resolver = File("src/main/assets/mapping/wudaokou2/wudaokou_all_floors_poi_resolver_app_ready.json").readText()
        return ImageIndoorNavigationRepository(
            graph = ImageIndoorNavGraph.fromJson(graph),
            resolver = ImageIndoorPoiResolver.fromJson(resolver),
        )
    }

    companion object {
        private const val START_NODE_ID = "node_entrance_f1_west_gate_access"
    }
}
