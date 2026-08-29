package com.aiglasses.poc.indoor

import android.content.res.AssetManager
import org.json.JSONObject

data class AmapIndoorRouteOverlay(
    val routeId: String,
    val venueId: String,
    val targetPoiId: String,
    val nodes: List<AmapIndoorRouteNode>,
    val routePolylines: List<AmapIndoorRoutePolyline>,
) {
    private val nodesById: Map<String, AmapIndoorRouteNode> = nodes.associateBy { it.nodeId }

    fun matches(routeId: String, venueId: String, targetPoiId: String): Boolean {
        return this.routeId == routeId && this.venueId == venueId && this.targetPoiId == targetPoiId
    }

    fun node(nodeId: String?): AmapIndoorRouteNode? {
        return nodeId?.let { nodesById[it] }
    }

    fun routeNodeIdsForFloor(floorId: String): List<String> {
        return routePolylines.firstOrNull { it.floorId == floorId }?.nodeIds.orEmpty()
    }

    fun routeNodesForFloor(floorId: String): List<AmapIndoorRouteNode> {
        return routeNodeIdsForFloor(floorId).mapNotNull { nodesById[it] }
    }

    companion object {
        fun loadFromAssets(assetManager: AssetManager, path: String): AmapIndoorRouteOverlay {
            return assetManager.open(path).bufferedReader().use { fromJson(it.readText()) }
        }

        fun fromJson(json: String): AmapIndoorRouteOverlay {
            val root = JSONObject(json)
            val nodesJson = root.getJSONArray("nodes")
            val nodes = buildList {
                for (index in 0 until nodesJson.length()) {
                    val item = nodesJson.getJSONObject(index)
                    val gcj02 = item.getJSONObject("gcj02")
                    add(
                        AmapIndoorRouteNode(
                            nodeId = item.getString("node_id"),
                            floorId = item.getString("floor_id"),
                            label = item.getString("label"),
                            nodeType = item.getString("node_type"),
                            latitude = gcj02.getDouble("lat"),
                            longitude = gcj02.getDouble("lng"),
                        ),
                    )
                }
            }
            val polylinesJson = root.getJSONArray("route_polylines")
            val polylines = buildList {
                for (index in 0 until polylinesJson.length()) {
                    val item = polylinesJson.getJSONObject(index)
                    val nodeIdsJson = item.getJSONArray("node_ids")
                    add(
                        AmapIndoorRoutePolyline(
                            floorId = item.getString("floor_id"),
                            nodeIds = buildList {
                                for (nodeIndex in 0 until nodeIdsJson.length()) {
                                    add(nodeIdsJson.getString(nodeIndex))
                                }
                            },
                        ),
                    )
                }
            }
            return AmapIndoorRouteOverlay(
                routeId = root.getString("route_id"),
                venueId = root.getString("venue_id"),
                targetPoiId = root.getString("target_poi_id"),
                nodes = nodes,
                routePolylines = polylines,
            )
        }
    }
}

data class AmapIndoorRouteNode(
    val nodeId: String,
    val floorId: String,
    val label: String,
    val nodeType: String,
    val latitude: Double,
    val longitude: Double,
)

data class AmapIndoorRoutePolyline(
    val floorId: String,
    val nodeIds: List<String>,
)
