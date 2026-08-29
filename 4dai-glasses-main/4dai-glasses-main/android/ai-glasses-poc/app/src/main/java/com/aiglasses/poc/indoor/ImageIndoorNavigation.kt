package com.aiglasses.poc.indoor

import android.content.res.AssetManager
import org.json.JSONObject
import java.util.PriorityQueue

class ImageIndoorNavigationRepository(
    val graph: ImageIndoorNavGraph,
    private val resolver: ImageIndoorPoiResolver,
) {
    fun searchPoi(query: String): List<ImageIndoorPoiResolverItem> {
        val keyword = query.normalizeSearchText()
        if (keyword.isBlank()) {
            return emptyList()
        }
        return resolver.items
            .mapNotNull { item ->
                val names = listOf(
                    item.name,
                    item.displayName,
                    item.venueName,
                    item.venueAddress,
                    item.subtitle,
                ).plus(item.aliases)
                    .filter { it.isNotBlank() }
                val score = names.minOfOrNull { name -> matchScore(keyword, name.normalizeSearchText()) }
                    ?: Int.MAX_VALUE
                if (score == Int.MAX_VALUE) null else item to score
            }
            .sortedWith(compareBy<Pair<ImageIndoorPoiResolverItem, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
    }

    fun resolverItem(poiId: String): ImageIndoorPoiResolverItem? {
        return resolver.items.firstOrNull { it.poiId == poiId }
    }

    fun resolverItems(): List<ImageIndoorPoiResolverItem> {
        return resolver.items
    }

    fun planRoute(startNodeId: String, targetNodeId: String): ImageIndoorRoutePlan? {
        if (graph.node(startNodeId) == null || graph.node(targetNodeId) == null) {
            return null
        }
        val distances = mutableMapOf(startNodeId to 0.0)
        val previous = mutableMapOf<String, PreviousStep>()
        val visited = mutableSetOf<String>()
        val queue = PriorityQueue<NodeCost>(compareBy { it.cost })
        queue.add(NodeCost(startNodeId, 0.0))

        while (queue.isNotEmpty()) {
            val current = queue.poll() ?: break
            if (!visited.add(current.nodeId)) {
                continue
            }
            if (current.nodeId == targetNodeId) {
                break
            }
            graph.outgoingEdges(current.nodeId).forEach { edge ->
                val nextNodeId = edge.toNodeId
                if (nextNodeId in visited) {
                    return@forEach
                }
                val nextCost = current.cost + edge.weight
                if (nextCost < (distances[nextNodeId] ?: Double.POSITIVE_INFINITY)) {
                    distances[nextNodeId] = nextCost
                    previous[nextNodeId] = PreviousStep(current.nodeId, edge)
                    queue.add(NodeCost(nextNodeId, nextCost))
                }
            }
        }

        val totalCost = distances[targetNodeId] ?: return null
        val edges = mutableListOf<ImageIndoorNavEdge>()
        var cursor = targetNodeId
        while (cursor != startNodeId) {
            val step = previous[cursor] ?: return null
            edges.add(step.edge)
            cursor = step.fromNodeId
        }
        val orderedEdges = edges.asReversed()
        val nodeIds = buildList {
            add(startNodeId)
            orderedEdges.forEach { add(it.toNodeId) }
        }
        val nodes = nodeIds.mapNotNull { graph.node(it) }
        if (nodes.size != nodeIds.size) {
            return null
        }
        return ImageIndoorRoutePlan(
            start = nodes.first(),
            target = nodes.last(),
            nodes = nodes,
            edges = orderedEdges,
            totalCostSeconds = totalCost,
            verticalSteps = buildVerticalSteps(orderedEdges),
        )
    }

    private fun buildVerticalSteps(edges: List<ImageIndoorNavEdge>): List<String> {
        return edges.mapNotNull { edge ->
            if (edge.travelMode == "walk") {
                return@mapNotNull null
            }
            val from = graph.node(edge.fromNodeId) ?: return@mapNotNull null
            val to = graph.node(edge.toNodeId) ?: return@mapNotNull null
            if (from.floorId == to.floorId) {
                return@mapNotNull null
            }
            val action = when (edge.travelMode) {
                "elevator" -> "乘电梯"
                "stairs" -> "走楼梯"
                "escalator" -> "乘扶梯"
                else -> "换层"
            }
            "$action ${from.floorId} -> ${to.floorId}"
        }
    }

    companion object {
        fun loadFromAssets(
            assetManager: AssetManager,
            graphPath: String,
            resolverPath: String,
        ): ImageIndoorNavigationRepository {
            val graphJson = assetManager.open(graphPath).bufferedReader().use { it.readText() }
            val resolverJson = assetManager.open(resolverPath).bufferedReader().use { it.readText() }
            return ImageIndoorNavigationRepository(
                graph = ImageIndoorNavGraph.fromJson(graphJson),
                resolver = ImageIndoorPoiResolver.fromJson(resolverJson),
            )
        }

        private fun matchScore(keyword: String, value: String): Int {
            return when {
                value == keyword -> 0
                value.startsWith(keyword) -> 1
                value.contains(keyword) -> 2
                keyword.contains(value) -> 3
                else -> Int.MAX_VALUE
            }
        }
    }
}

data class ImageIndoorNavGraph(
    val coordinateSystem: ImageIndoorCoordinateSystem,
    val floors: List<ImageIndoorFloor>,
    val nodes: List<ImageIndoorNavNode>,
    val edges: List<ImageIndoorNavEdge>,
    val pois: List<ImageIndoorPoi>,
    val entrances: List<ImageIndoorEntrance>,
    val connectors: List<ImageIndoorConnector>,
    val sharedAmapAlignment: ImageIndoorSharedAmapAlignment? = null,
) {
    private val nodesById = nodes.associateBy { it.nodeId }
    private val edgesByStart = buildMap<String, MutableList<ImageIndoorNavEdge>> {
        edges.forEach { edge ->
            getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge)
            if (edge.bidirectional) {
                getOrPut(edge.toNodeId) { mutableListOf() }.add(edge.reversed())
            }
        }
    }

    fun node(nodeId: String): ImageIndoorNavNode? = nodesById[nodeId]

    fun floor(floorId: String): ImageIndoorFloor? = floors.firstOrNull { it.floorId == floorId }

    fun outgoingEdges(nodeId: String): List<ImageIndoorNavEdge> = edgesByStart[nodeId].orEmpty()

    companion object {
        fun fromJson(json: String): ImageIndoorNavGraph {
            val root = JSONObject(json)
            val coordinate = root.getJSONObject("coordinate_system")
            return ImageIndoorNavGraph(
                coordinateSystem = ImageIndoorCoordinateSystem(
                    type = coordinate.getString("type"),
                    origin = coordinate.getString("origin"),
                    xAxis = coordinate.getString("x_axis"),
                    yAxis = coordinate.getString("y_axis"),
                    unit = coordinate.getString("unit"),
                ),
                floors = root.getJSONArray("floors").mapObjects { item ->
                    ImageIndoorFloor(
                        floorId = item.getString("floor_id"),
                        image = item.getString("image"),
                        width = item.getInt("width"),
                        height = item.getInt("height"),
                    )
                },
                nodes = root.getJSONArray("nodes").mapObjects { item ->
                    ImageIndoorNavNode(
                        nodeId = item.getString("node_id"),
                        floorId = item.getString("floor_id"),
                        nodeType = item.optString("node_type"),
                        x = item.getDouble("x"),
                        y = item.getDouble("y"),
                    )
                },
                edges = root.getJSONArray("edges").mapObjects { item ->
                    ImageIndoorNavEdge(
                        edgeId = item.getString("edge_id"),
                        fromNodeId = item.getString("from_node_id"),
                        toNodeId = item.getString("to_node_id"),
                        floorId = item.optString("floor_id"),
                        travelMode = item.getString("travel_mode"),
                        bidirectional = item.optBoolean("bidirectional", true),
                        distance = item.optDouble("distance", item.optDouble("distance_px", 1.0)),
                        costSeconds = item.optDouble("cost_seconds", item.optDouble("distance", 1.0)),
                    )
                },
                pois = root.getJSONArray("pois").mapObjects { item ->
                    ImageIndoorPoi(
                        poiId = item.getString("poi_id"),
                        name = item.optString("poi_name", item.optString("name")),
                        floorId = item.getString("floor_id"),
                        routeNodeId = item.getString("route_node_id"),
                    )
                },
                entrances = root.getJSONArray("entrances").mapObjects { item ->
                    ImageIndoorEntrance(
                        entranceId = item.getString("entrance_id"),
                        floorId = item.getString("floor_id"),
                        entranceType = item.getString("entrance_type"),
                        routeNodeId = item.getString("route_node_id"),
                    )
                },
                connectors = root.getJSONArray("connectors").mapObjects { item ->
                    ImageIndoorConnector(
                        connectorId = item.getString("connector_id"),
                        floorId = item.getString("floor_id"),
                        connectorType = item.getString("connector_type"),
                        routeNodeId = item.getString("route_node_id"),
                    )
                },
                sharedAmapAlignment = root.optJSONObject("shared_amap_alignment")
                    ?.let { ImageIndoorSharedAmapAlignment.fromJson(it) },
            )
        }
    }
}

data class ImageIndoorPoiResolver(
    val items: List<ImageIndoorPoiResolverItem>,
) {
    companion object {
        fun fromJson(json: String): ImageIndoorPoiResolver {
            val root = JSONObject(json)
            return ImageIndoorPoiResolver(
                items = root.getJSONArray("items").mapObjects { item ->
                    ImageIndoorPoiResolverItem(
                        poiId = item.getString("poi_id"),
                        name = item.getString("name"),
                        displayName = item.optString("display_name"),
                        floorId = item.getString("floor_id"),
                        aliases = item.optJSONArray("aliases")?.mapStrings().orEmpty(),
                        routeNodeId = item.getString("route_node_id"),
                        venueName = item.optString("venue_name"),
                        venueAddress = item.optString("venue_address", item.optString("address")),
                        subtitle = item.optString("subtitle"),
                        badges = item.optJSONArray("badges")?.mapStrings().orEmpty(),
                        externalRefs = item.optJSONObject("external_refs")?.let { refs ->
                            ImageIndoorPoiExternalRefs(
                                amapPoiId = refs.optNullableString("amap_poi_id"),
                                amapSearchable = refs.optBoolean("amap_searchable", false),
                                amapSearchStatus = refs.optString("amap_search_status"),
                                amapMatchSource = refs.optString("amap_match_source"),
                                venueAmapPoiId = refs.optNullableString("venue_amap_poi_id"),
                                venueAmapSearchKeyword = refs.optString("venue_amap_search_keyword"),
                            )
                        },
                        outdoorHandoff = item.optJSONObject("outdoor_handoff")?.let { handoff ->
                            ImageIndoorOutdoorHandoff(
                                venueId = handoff.optString("venue_id"),
                                venueName = handoff.optString("venue_name"),
                                venueAddress = handoff.optString("venue_address"),
                                venueAmapPoiId = handoff.optNullableString("venue_amap_poi_id"),
                                venueAmapSearchKeyword = handoff.optString("venue_amap_search_keyword"),
                                strategy = handoff.optString("strategy"),
                                preferredEntranceId = handoff.optString("preferred_entrance_id"),
                                preferredEntranceRouteNodeId = handoff.optString("preferred_entrance_route_node_id"),
                                preferredEntranceFloorId = handoff.optString("preferred_entrance_floor_id"),
                                preferredEntranceGcj02 = handoff.optJSONObject("preferred_entrance_gcj02")?.let { point ->
                                    ImageIndoorGcj02(
                                        latitude = point.getDouble("lat"),
                                        longitude = point.getDouble("lng"),
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
    }
}

data class ImageIndoorCoordinateSystem(
    val type: String,
    val origin: String,
    val xAxis: String,
    val yAxis: String,
    val unit: String,
)

data class ImageIndoorFloor(
    val floorId: String,
    val image: String,
    val width: Int,
    val height: Int,
)

data class ImageIndoorNavNode(
    val nodeId: String,
    val floorId: String,
    val nodeType: String,
    val x: Double,
    val y: Double,
)

data class ImageIndoorNavEdge(
    val edgeId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val floorId: String,
    val travelMode: String,
    val bidirectional: Boolean,
    val distance: Double,
    val costSeconds: Double,
) {
    val weight: Double = costSeconds.takeIf { it > 0.0 } ?: distance

    fun reversed(): ImageIndoorNavEdge {
        return copy(fromNodeId = toNodeId, toNodeId = fromNodeId)
    }
}

data class ImageIndoorPoi(
    val poiId: String,
    val name: String,
    val floorId: String,
    val routeNodeId: String,
)

data class ImageIndoorEntrance(
    val entranceId: String,
    val floorId: String,
    val entranceType: String,
    val routeNodeId: String,
)

data class ImageIndoorConnector(
    val connectorId: String,
    val floorId: String,
    val connectorType: String,
    val routeNodeId: String,
)

data class ImageIndoorPoiResolverItem(
    val poiId: String,
    val name: String,
    val displayName: String,
    val floorId: String,
    val aliases: List<String>,
    val routeNodeId: String,
    val venueName: String,
    val venueAddress: String,
    val subtitle: String,
    val badges: List<String>,
    val externalRefs: ImageIndoorPoiExternalRefs?,
    val outdoorHandoff: ImageIndoorOutdoorHandoff?,
)

data class ImageIndoorPoiExternalRefs(
    val amapPoiId: String?,
    val amapSearchable: Boolean,
    val amapSearchStatus: String,
    val amapMatchSource: String,
    val venueAmapPoiId: String?,
    val venueAmapSearchKeyword: String,
)

data class ImageIndoorOutdoorHandoff(
    val venueId: String,
    val venueName: String,
    val venueAddress: String,
    val venueAmapPoiId: String?,
    val venueAmapSearchKeyword: String,
    val strategy: String,
    val preferredEntranceId: String,
    val preferredEntranceRouteNodeId: String,
    val preferredEntranceFloorId: String,
    val preferredEntranceGcj02: ImageIndoorGcj02?,
)

data class ImageIndoorGcj02(
    val latitude: Double,
    val longitude: Double,
)

data class ImageIndoorSharedAmapAlignment(
    val schemaVersion: String,
    val mode: String,
    val sourceCoordinate: String,
    val targetCoordinate: String,
    val appliesToFloors: Set<String>,
    val assumption: String,
    val originNodeId: String,
    val originFloorId: String,
    val originX: Double,
    val originY: Double,
    val originLatitude: Double,
    val originLongitude: Double,
    val metersPerDegreeLat: Double,
    val metersPerDegreeLngAtOrigin: Double,
    val transformA: Double,
    val transformB: Double,
) {
    fun appliesToFloor(floorId: String): Boolean {
        return mode == SHARED_SAME_VIEWPORT_MODE &&
            sourceCoordinate == SOURCE_IMAGE_PIXEL &&
            targetCoordinate == TARGET_GCJ02 &&
            floorId in appliesToFloors
    }

    fun project(node: ImageIndoorNavNode): ImageIndoorGcj02? {
        if (!appliesToFloor(node.floorId)) {
            return null
        }
        return project(node.x, node.y)
    }

    fun project(x: Double, y: Double): ImageIndoorGcj02 {
        val deltaX = x - originX
        val deltaY = y - originY
        val eastMeters = transformA * deltaX - transformB * deltaY
        val northMeters = transformB * deltaX + transformA * deltaY
        return ImageIndoorGcj02(
            latitude = originLatitude + northMeters / metersPerDegreeLat,
            longitude = originLongitude + eastMeters / metersPerDegreeLngAtOrigin,
        )
    }

    companion object {
        fun fromJson(json: JSONObject): ImageIndoorSharedAmapAlignment {
            val parameters = json.getJSONObject("parameters")
            val originPixel = parameters.getJSONObject("origin_pixel")
            val originGcj02 = parameters.getJSONObject("origin_gcj02")
            return ImageIndoorSharedAmapAlignment(
                schemaVersion = json.optString("schema_version"),
                mode = json.optString("mode"),
                sourceCoordinate = json.optString("source_coordinate"),
                targetCoordinate = json.optString("target_coordinate"),
                appliesToFloors = json.getJSONArray("applies_to_floors").mapStrings().toSet(),
                assumption = json.optString("assumption"),
                originNodeId = parameters.getString("origin_node_id"),
                originFloorId = parameters.getString("origin_floor_id"),
                originX = originPixel.getDouble("x"),
                originY = originPixel.getDouble("y"),
                originLatitude = originGcj02.getDouble("lat"),
                originLongitude = originGcj02.getDouble("lng"),
                metersPerDegreeLat = parameters.getDouble("meters_per_degree_lat"),
                metersPerDegreeLngAtOrigin = parameters.getDouble("meters_per_degree_lng_at_origin"),
                transformA = parameters.getDouble("transform_a"),
                transformB = parameters.getDouble("transform_b"),
            )
        }

        private const val SHARED_SAME_VIEWPORT_MODE = "shared_same_viewport_all_floors"
        private const val SOURCE_IMAGE_PIXEL = "image_pixel"
        private const val TARGET_GCJ02 = "gcj02"
    }
}

data class ImageIndoorRoutePlan(
    val start: ImageIndoorNavNode,
    val target: ImageIndoorNavNode,
    val nodes: List<ImageIndoorNavNode>,
    val edges: List<ImageIndoorNavEdge>,
    val totalCostSeconds: Double,
    val verticalSteps: List<String>,
) {
    fun walkSegmentsForFloor(floorId: String): List<List<ImageIndoorNavNode>> {
        val segments = mutableListOf<List<ImageIndoorNavNode>>()
        var currentSegment = mutableListOf<ImageIndoorNavNode>()
        edges.forEachIndexed { index, edge ->
            val from = nodes[index]
            val to = nodes[index + 1]
            val sameFloorWalk = edge.travelMode == "walk" &&
                from.floorId == floorId &&
                to.floorId == floorId
            if (sameFloorWalk) {
                if (currentSegment.isEmpty() || currentSegment.last().nodeId != from.nodeId) {
                    currentSegment.add(from)
                }
                currentSegment.add(to)
            } else if (currentSegment.isNotEmpty()) {
                segments.add(currentSegment)
                currentSegment = mutableListOf()
            }
        }
        if (currentSegment.isNotEmpty()) {
            segments.add(currentSegment)
        }
        return segments
    }
}

object ImageIndoorCoordinateMapper {
    fun mapToScreen(
        nodeX: Double,
        nodeY: Double,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): ImageIndoorScreenPoint {
        val scale = minOf(
            viewWidth.toDouble() / imageWidth.toDouble(),
            viewHeight.toDouble() / imageHeight.toDouble(),
        )
        val offsetX = (viewWidth - imageWidth * scale) / 2.0
        val offsetY = (viewHeight - imageHeight * scale) / 2.0
        return ImageIndoorScreenPoint(
            x = (nodeX * scale + offsetX).toFloat(),
            y = (nodeY * scale + offsetY).toFloat(),
            scale = scale.toFloat(),
            offsetX = offsetX.toFloat(),
            offsetY = offsetY.toFloat(),
        )
    }
}

data class ImageIndoorScreenPoint(
    val x: Float,
    val y: Float,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

private data class NodeCost(
    val nodeId: String,
    val cost: Double,
)

private data class PreviousStep(
    val fromNodeId: String,
    val edge: ImageIndoorNavEdge,
)

private fun String.normalizeSearchText(): String {
    return lowercase().filter { it.isLetterOrDigit() || it.code > 127 }
}

private inline fun <T> org.json.JSONArray.mapObjects(block: (JSONObject) -> T): List<T> {
    return buildList {
        for (index in 0 until length()) {
            add(block(getJSONObject(index)))
        }
    }
}

private fun org.json.JSONArray.mapStrings(): List<String> {
    return buildList {
        for (index in 0 until length()) {
            add(getString(index))
        }
    }
}

private fun JSONObject.optNullableString(name: String): String? {
    if (isNull(name)) {
        return null
    }
    return optString(name).takeIf { it.isNotBlank() }
}
