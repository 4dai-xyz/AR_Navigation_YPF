package com.aiglasses.poc.indoor

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import com.aiglasses.poc.IndoorBasemapUiModel
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.IndoorBuildingInfo
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import kotlin.math.cos
import kotlin.math.PI

class IndoorBasemapController(
    private val context: Context,
    private val container: FrameLayout,
    private val skipSdkMapView: Boolean,
    private val onStatusChanged: (IndoorBasemapUiModel) -> Unit,
    private val onMapTapped: (IndoorMapTap?) -> Unit,
) {
    private var savedState: Bundle? = null
    private var isResumed = false
    private var mapView: MapView? = null
    private var amap: AMap? = null
    private var activeInfo: IndoorBuildingInfo? = null
    private var currentMarker: Marker? = null
    private var targetMarker: Marker? = null
    private var completedRoutePolyline: Polyline? = null
    private var pendingRoutePolyline: Polyline? = null
    private var lastConfig: IndoorBasemapConfig? = null
    private var lastOverlay: IndoorBusinessOverlay? = null
    private var lastFocusedCenter: LatLng? = null
    private var hasFocusedVenue = false
    private var lastStatus = IndoorBasemapUiModel()

    private val indoorBuildingListener = object : AMap.OnIndoorBuildingActiveListener {
        override fun OnIndoorBuilding(info: IndoorBuildingInfo?) {
            activeInfo = info
            lastConfig?.let { config ->
                switchFloorIfMatched(config.floorId)
                publishStatus(config, info, sdkAvailable = info != null)
            }
        }
    }

    fun onCreate(savedInstanceState: Bundle?) {
        savedState = savedInstanceState
    }

    fun onResume() {
        isResumed = true
        mapView?.onResume()
    }

    fun onPause() {
        isResumed = false
        mapView?.onPause()
    }

    fun onSaveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
    }

    fun onDestroy() {
        removeBusinessOverlay()
        mapView?.onDestroy()
        mapView = null
        amap = null
        activeInfo = null
    }

    fun activate(config: IndoorBasemapConfig, overlay: IndoorBusinessOverlay) {
        lastConfig = config
        lastOverlay = overlay
        if (!ensureMapView(config)) {
            return
        }
        val map = amap ?: return
        focusVenueIfNeeded(config)
        switchFloorIfMatched(config.floorId)
        renderBusinessOverlay(map, config, overlay)
        publishStatus(config, activeInfo, sdkAvailable = activeInfo != null)
    }

    fun deactivate() {
        lastConfig = null
        lastOverlay = null
        lastFocusedCenter = null
        hasFocusedVenue = false
        publish(IndoorBasemapUiModel(statusSummary = "高德室内底图：未启用"))
    }

    fun recenterToCurrentLocation(): Boolean {
        val config = lastConfig ?: return false
        val overlay = lastOverlay ?: return false
        val map = amap ?: return false
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(projectToLatLng(config.center, overlay.current), INDOOR_ZOOM))
        return true
    }

    fun latLngFromScreenPoint(x: Float, y: Float): LatLng? {
        val map = amap ?: return null
        return map.projection?.fromScreenLocation(Point(x.toInt(), y.toInt()))
    }

    private fun ensureMapView(config: IndoorBasemapConfig): Boolean {
        if (skipSdkMapView) {
            publish(
                IndoorBasemapUiModel(
                    enabled = true,
                    available = false,
                    expectedPoiId = config.expectedPoiId,
                    statusSummary = "高德室内底图：当前设备跳过 MapView，使用室内预览降级",
                ),
            )
            return false
        }
        if (mapView != null && amap != null) {
            return true
        }
        return runCatching {
            MapView(context).also { view ->
                mapView = view
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                view.onCreate(savedState)
                val map = view.map
                amap = map
                map.showIndoorMap(true)
                map.uiSettings.setIndoorSwitchEnabled(true)
                map.uiSettings.setZoomControlsEnabled(false)
                map.uiSettings.setCompassEnabled(false)
                map.uiSettings.setMyLocationButtonEnabled(false)
                map.setOnIndoorBuildingActiveListener(indoorBuildingListener)
                map.setOnMapClickListener { latLng ->
                    onMapTapped(buildMapTap(latLng))
                }
                view.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        onMapTapped(null)
                    }
                    false
                }
                if (isResumed) {
                    view.onResume()
                }
            }
            true
        }.getOrElse { throwable ->
            publish(
                IndoorBasemapUiModel(
                    enabled = true,
                    available = false,
                    expectedPoiId = config.expectedPoiId,
                    statusSummary = "高德室内底图：初始化失败 ${throwable.message ?: throwable.javaClass.simpleName}",
                ),
            )
            false
        }
    }

    private fun focusVenueIfNeeded(config: IndoorBasemapConfig) {
        val focusedCenter = lastFocusedCenter
        if (hasFocusedVenue && focusedCenter != null && focusedCenter.sameCoordinateAs(config.center)) {
            return
        }
        focusVenue(config)
    }

    private fun focusVenue(config: IndoorBasemapConfig) {
        amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(config.center, INDOOR_ZOOM))
        lastFocusedCenter = config.center
        hasFocusedVenue = true
    }

    private fun LatLng.sameCoordinateAs(other: LatLng): Boolean {
        return latitude == other.latitude && longitude == other.longitude
    }

    private fun switchFloorIfMatched(floorId: String?) {
        val floorName = floorId?.takeIf { it.isNotBlank() } ?: return
        val info = activeInfo ?: return
        val floorNames = info.floor_names ?: return
        val floorIndexes = info.floor_indexs
        val index = resolveAmapFloorIndex(floorNames, floorIndexes, floorName) ?: return
        val matchedFloorName = floorNames.getOrNull(index) ?: return
        val matchedFloorIndex = floorIndexes?.getOrNull(index) ?: floorIndexValueFor(floorName) ?: index
        if (info.activeFloorName == matchedFloorName && info.activeFloorIndex == matchedFloorIndex) {
            return
        }
        info.activeFloorName = matchedFloorName
        info.activeFloorIndex = matchedFloorIndex
        amap?.setIndoorBuildingInfo(info)
    }

    private fun resolveAmapFloorIndex(
        floorNames: Array<String>,
        floorIndexes: IntArray?,
        floorId: String,
    ): Int? {
        val matchedFloorName = resolveAmapFloorName(floorNames, floorId)
        if (matchedFloorName != null) {
            val index = floorNames.indexOf(matchedFloorName)
            if (index >= 0) {
                return index
            }
        }
        val targetFloorIndex = floorIndexValueFor(floorId)
        if (targetFloorIndex != null && floorIndexes != null) {
            val index = floorIndexes.indexOf(targetFloorIndex)
            if (index >= 0) {
                return index
            }
        }
        val targetRank = floorRankFor(floorId) ?: return null
        return floorNames.indexOfFirst { floorRankFor(it) == targetRank }.takeIf { it >= 0 }
    }

    private fun resolveAmapFloorName(floorNames: Array<String>, floorId: String): String? {
        val normalizedFloorId = floorId.trim().uppercase()
        val candidates = buildList {
            add(floorId)
            normalizedFloorId.removePrefix("F").toIntOrNull()?.let {
                add("${it}F")
                add("F$it")
                add("${it}层")
                add("${it}楼")
            }
            normalizedFloorId.removePrefix("B").toIntOrNull()?.let {
                add("B$it")
                add("B${it}F")
                add("B0$it")
                add("B00$it")
                add("-$it")
                add("负${it}F")
                add("负${it}层")
                add("负${it}楼")
                add("地下${it}层")
                add("地下${it}楼")
                if (it == 1) {
                    add("地下一层")
                    add("地下一楼")
                    add("负一层")
                    add("负一楼")
                    add("LG")
                    add("LG1")
                }
            }
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            floorNames.firstOrNull { floorName ->
                floorName.equals(candidate, ignoreCase = true) ||
                    normalizeFloorName(floorName) == normalizeFloorName(candidate)
            }
        }
    }

    private fun floorIndexValueFor(floorId: String): Int? {
        val normalized = floorId.trim().uppercase()
        return when {
            normalized.startsWith("B") -> normalized.drop(1).removeSuffix("F").toIntOrNull()?.unaryMinus()
            normalized.startsWith("F") -> normalized.drop(1).toIntOrNull()
            else -> normalized.toIntOrNull()
        }
    }

    private fun floorRankFor(floorName: String): Int? {
        val normalized = normalizeFloorName(floorName)
        return when {
            normalized == "LG" || normalized == "LG1" -> -1
            normalized == "-1" -> -1
            normalized == "负1F" || normalized == "负1层" || normalized == "负1楼" -> -1
            normalized == "负一层" || normalized == "负一楼" -> -1
            normalized == "地下1层" || normalized == "地下1楼" -> -1
            normalized == "地下一层" || normalized == "地下一楼" -> -1
            normalized.matches(Regex("B\\d+F?")) -> normalized.removePrefix("B").removeSuffix("F").toIntOrNull()?.unaryMinus()
            normalized.matches(Regex("B0+\\d+")) -> normalized.removePrefix("B").trimStart('0').toIntOrNull()?.unaryMinus()
            normalized.matches(Regex("F\\d+")) -> normalized.removePrefix("F").toIntOrNull()
            normalized.matches(Regex("\\d+F")) -> normalized.removeSuffix("F").toIntOrNull()
            normalized.matches(Regex("\\d+层")) -> normalized.removeSuffix("层").toIntOrNull()
            normalized.matches(Regex("\\d+楼")) -> normalized.removeSuffix("楼").toIntOrNull()
            else -> null
        }
    }

    private fun normalizeFloorName(floorName: String): String {
        return floorName.trim()
            .uppercase()
            .replace(" ", "")
            .replace("　", "")
    }

    private fun renderBusinessOverlay(
        map: AMap,
        config: IndoorBasemapConfig,
        overlay: IndoorBusinessOverlay,
    ) {
        removeBusinessOverlay()
        val completedRoutePoints = overlay.completedRoute.map { projectToLatLng(config.center, it) }
        if (completedRoutePoints.size >= 2) {
            completedRoutePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(completedRoutePoints)
                    .width(10f)
                    .color(Color.rgb(156, 163, 175)),
            )
        }
        val pendingRoutePoints = overlay.pendingRoute.map { projectToLatLng(config.center, it) }
        if (pendingRoutePoints.size >= 2) {
            pendingRoutePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(pendingRoutePoints)
                    .width(10f)
                    .color(Color.rgb(37, 99, 235)),
            )
        }
        currentMarker = map.addMarker(
            MarkerOptions()
                .position(projectToLatLng(config.center, overlay.current))
                .title("当前位置")
                .snippet(overlay.current.floorId)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .anchor(0.5f, 1.0f),
        )
        targetMarker = map.addMarker(
            MarkerOptions()
                .position(projectToLatLng(config.center, overlay.target))
                .title(overlay.target.label)
                .snippet(overlay.target.floorId)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .anchor(0.5f, 1.0f),
        )
    }

    private fun removeBusinessOverlay() {
        currentMarker?.remove()
        targetMarker?.remove()
        completedRoutePolyline?.remove()
        pendingRoutePolyline?.remove()
        currentMarker = null
        targetMarker = null
        completedRoutePolyline = null
        pendingRoutePolyline = null
    }

    private fun projectToLatLng(center: LatLng, point: IndoorOverlayPoint): LatLng {
        val explicitLatitude = point.latitude
        val explicitLongitude = point.longitude
        if (explicitLatitude != null && explicitLongitude != null) {
            return LatLng(explicitLatitude, explicitLongitude)
        }
        val deltaLat = (point.y - DEMO_ANCHOR_Y) * DEMO_METERS_PER_UNIT / METERS_PER_DEGREE_LAT
        val metersPerDegreeLng = METERS_PER_DEGREE_LAT * cos(center.latitude * PI / 180.0)
        val deltaLng = if (metersPerDegreeLng == 0.0) {
            0.0
        } else {
            (point.x - DEMO_ANCHOR_X) * DEMO_METERS_PER_UNIT / metersPerDegreeLng
        }
        return LatLng(center.latitude + deltaLat, center.longitude + deltaLng)
    }

    private fun buildMapTap(latLng: LatLng): IndoorMapTap? {
        val config = lastConfig ?: return null
        val metersPerDegreeLng = METERS_PER_DEGREE_LAT * cos(config.center.latitude * PI / 180.0)
        val x = if (metersPerDegreeLng == 0.0) {
            DEMO_ANCHOR_X
        } else {
            ((latLng.longitude - config.center.longitude) * metersPerDegreeLng / DEMO_METERS_PER_UNIT) + DEMO_ANCHOR_X
        }
        val y = ((latLng.latitude - config.center.latitude) * METERS_PER_DEGREE_LAT / DEMO_METERS_PER_UNIT) + DEMO_ANCHOR_Y
        return IndoorMapTap(
            floorId = activeInfo?.activeFloorName ?: config.floorId.orEmpty(),
            x = x,
            y = y,
            latitude = latLng.latitude,
            longitude = latLng.longitude,
        )
    }

    private fun publishStatus(
        config: IndoorBasemapConfig,
        info: IndoorBuildingInfo?,
        sdkAvailable: Boolean,
    ) {
        val activePoiId = info?.poiid
        val activeFloorName = info?.activeFloorName
        val floorNames = info?.floor_names?.toList().orEmpty()
        val expectedPoiId = config.expectedPoiId
        val mismatch = if (!expectedPoiId.isNullOrBlank() && !activePoiId.isNullOrBlank() && expectedPoiId != activePoiId) {
            "高德室内建筑 poiid 不一致：expected=$expectedPoiId active=$activePoiId"
        } else {
            null
        }
        val summary = when {
            mismatch != null -> "高德室内底图：已开启但未命中目标建筑"
            sdkAvailable -> "高德室内底图：已开启 floor=${activeFloorName ?: "-"} poiid=${activePoiId ?: "-"}"
            else -> "高德室内底图：已开启，等待命中室内建筑"
        }
        publish(
            IndoorBasemapUiModel(
                enabled = true,
                available = sdkAvailable && mismatch == null,
                expectedPoiId = expectedPoiId,
                activePoiId = activePoiId,
                activeFloorName = activeFloorName,
                availableFloorNames = floorNames,
                mismatchWarning = mismatch,
                statusSummary = summary,
            ),
        )
    }

    private fun publish(status: IndoorBasemapUiModel) {
        if (status == lastStatus) {
            return
        }
        lastStatus = status
        onStatusChanged(status)
    }

    private companion object {
        private const val INDOOR_ZOOM = 19f
        private const val DEMO_ANCHOR_X = 5.0
        private const val DEMO_ANCHOR_Y = 8.0
        private const val DEMO_METERS_PER_UNIT = 0.8
        private const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}

data class IndoorBasemapConfig(
    val venueId: String,
    val center: LatLng,
    val floorId: String?,
    val expectedPoiId: String? = null,
)

data class IndoorBusinessOverlay(
    val current: IndoorOverlayPoint,
    val target: IndoorOverlayPoint,
    val route: List<IndoorOverlayPoint>,
    val completedRoute: List<IndoorOverlayPoint> = emptyList(),
    val pendingRoute: List<IndoorOverlayPoint> = route,
    val calibrationPoints: List<IndoorOverlayPoint> = emptyList(),
    val calibrationRouteSegments: List<List<IndoorOverlayPoint>> = emptyList(),
)

data class IndoorOverlayPoint(
    val label: String,
    val floorId: String,
    val x: Double,
    val y: Double,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class IndoorMapTap(
    val floorId: String,
    val x: Double,
    val y: Double,
    val latitude: Double,
    val longitude: Double,
)
