package com.aiglasses.poc.outdoor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import android.view.MotionEvent
import android.view.View
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.AMapException
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Poi
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapModelCross
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.navi.model.RouteOverlayOptions
import java.util.Locale
import kotlin.math.cos
import com.amap.api.navi.R as AmapNaviR

class AmapOutdoorNavigator(
    context: Context,
    private val naviView: AMapNaviView,
    private val listener: Listener,
) : AMapNaviListener, AMapNaviViewListener {
    private val appContext = context.applicationContext
    private val amapNavi: AMapNavi? = runCatching {
        AMapNavi.getInstance(appContext).also { it.addAMapNaviListener(this) }
    }.getOrElse { throwable ->
        listener.onOutdoorNaviError("高德导航初始化失败：${throwable.message ?: throwable::class.java.simpleName}")
        null
    }
    private var routeReadyDispatched = false
    private var lastLocationLogAt = 0L
    private var lastNaviInfoLogAt = 0L
    private var lastTrafficLogAt = 0L
    private var lastRoutePath: AMapNaviPath? = null
    private var fallbackRoutePolyline: Polyline? = null
    private var passedRoutePolyline: Polyline? = null
    private var currentLocationMarker: Marker? = null
    private var selectedPoiMarker: Marker? = null
    private var currentLocationIcon: BitmapDescriptor? = null
    private var lastFallbackRoutePointCount = 0
    private var lastPassedRouteProgressKey = -1
    private var lastRouteProgressPercent = 0
    private var lastRerouteRequestAt = 0L
    private var lastNaviLocation: AMapNaviLocation? = null
    private var lastNaviInfo: NaviInfo? = null
    private var routeStartPoint: LatLng? = null
    private var isNavigating = false
    private var isHeadingUpMode = true
    private var isSimulationNavigation = false
    private var activeTravelMode = OutdoorTravelMode.RIDE
    private var isNaviViewConfigured = false
    private var navigationFollowPausedUntilMs = 0L
    private val recoverNavigationLockRunnable = Runnable { recoverNavigationLock() }

    private fun configureNaviViewIfNeeded() {
        if (isNaviViewConfigured) return
        isNaviViewConfigured = true
        naviView.setViewOptions(
            AMapNaviViewOptions().apply {
                setAutoDrawRoute(true)
                setAutoDisplayOverview(true)
                setTrafficLine(true)
                setTrafficLayerEnabled(true)
                setLayoutVisible(false)
                setAfterRouteAutoGray(false)
                setAutoLockCar(true)
                setPointToCenter(0.5, MAP_FOLLOW_CENTER_Y_RATIO)
                setNaviArrowVisible(true)
                setSecondActionVisible(true)
                setRouteOverlayOptions(
                    RouteOverlayOptions().apply {
                        setLineWidth(36f)
                        setArrowColor(Color.BLUE)
                    },
                )
                },
            )
        runCatching {
            naviView.setNaviMode(AMapNaviView.CAR_UP_MODE)
            applyHeadingGesturePolicy(headingUp = true)
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德视角默认车头向上设置失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
        runCatching {
            naviView.setAMapNaviViewListener(this)
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德导航视图监听注册失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
        runCatching {
            naviView.setOnMapTouchListener { event ->
                handleMapTouch(event)
            }
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德地图触摸监听注册失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
        scheduleBindMapPoiSelection()
        applyMapViewportOffset()
        scheduleBindBuiltInChromeActions()
    }

    fun onCreate(savedInstanceState: Bundle?) {
        naviView.onCreate(savedInstanceState)
        configureNaviViewIfNeeded()
    }

    fun onResume() {
        naviView.onResume()
    }

    fun onPause() {
        naviView.onPause()
    }

    fun onSaveInstanceState(outState: Bundle) {
        naviView.onSaveInstanceState(outState)
    }

    fun onDestroy() {
        amapNavi?.removeAMapNaviListener(this)
        amapNavi?.stopGPS()
        amapNavi?.stopNavi()
        selectedPoiMarker?.remove()
        selectedPoiMarker = null
        naviView.onDestroy()
        AMapNavi.destroy()
    }

    fun calculateRoute(mode: OutdoorTravelMode, start: OutdoorPoint, end: OutdoorPoint) {
        val navi = amapNavi
        if (navi == null) {
            listener.onOutdoorNaviError("高德导航不可用")
            return
        }
        activeTravelMode = mode
        routeReadyDispatched = false
        lastRoutePath = null
        fallbackRoutePolyline?.remove()
        fallbackRoutePolyline = null
        passedRoutePolyline?.remove()
        passedRoutePolyline = null
        lastPassedRouteProgressKey = -1
        lastRouteProgressPercent = 0
        val startPoint = NaviLatLng(start.latitude, start.longitude)
        val endPoint = NaviLatLng(end.latitude, end.longitude)
        routeStartPoint = LatLng(start.latitude, start.longitude)
        val accepted = when (mode) {
            OutdoorTravelMode.RIDE -> navi.calculateRideRoute(startPoint, endPoint)
            OutdoorTravelMode.EBIKE -> navi.calculateEleBikeRoute(startPoint, endPoint)
            OutdoorTravelMode.DRIVE -> navi.calculateDriveRoute(
                listOf(startPoint),
                listOf(endPoint),
                PathPlanningStrategy.DRIVING_DEFAULT,
            )
            OutdoorTravelMode.WALK -> navi.calculateWalkRoute(startPoint, endPoint)
        }
        scheduleBindMapPoiSelection()
        if (!accepted) {
            listener.onOutdoorNaviError("高德${mode.displayName}算路请求被拒绝")
        }
    }

    fun startNavigation(useSimulation: Boolean) {
        val navi = amapNavi
        if (navi == null) {
            listener.onOutdoorNaviError("高德导航不可用")
            return
        }
        val gpsStarted = if (useSimulation) {
            navi.stopGPS()
            navi.setEmulatorNaviSpeed(EMULATOR_NAVI_SPEED)
            false
        } else {
            navi.startGPS()
        }
        val naviType = if (useSimulation) NaviType.EMULATOR else NaviType.GPS
        val naviStarted = navi.startNavi(naviType)
        if (naviStarted) {
            isNavigating = true
            isSimulationNavigation = useSimulation
            navigationFollowPausedUntilMs = 0L
            naviView.removeCallbacks(recoverNavigationLockRunnable)
            routeStartPoint?.let { updateCurrentLocationMarker(it, 0f) }
            setBuiltInNavigationChromeVisible(true)
            applyMapViewportOffset()
            scheduleRecoverLockMode()
            scheduleEnsureFallbackRouteVisible()
            val typeLabel = if (useSimulation) "模拟导航" else "GPS"
            listener.onOutdoorNavigationStarted("${activeTravelMode.displayName} $typeLabel", gpsStarted)
            return
        }
        listener.onOutdoorNaviError("高德导航启动被拒绝 type=$naviType gpsStarted=$gpsStarted")
    }

    fun stopNavigation() {
        isNavigating = false
        isSimulationNavigation = false
        navigationFollowPausedUntilMs = 0L
        naviView.removeCallbacks(recoverNavigationLockRunnable)
        setBuiltInNavigationChromeVisible(false)
        amapNavi?.stopNavi()
        amapNavi?.stopGPS()
        fallbackRoutePolyline?.remove()
        fallbackRoutePolyline = null
        passedRoutePolyline?.remove()
        passedRoutePolyline = null
        currentLocationMarker?.remove()
        currentLocationMarker = null
        selectedPoiMarker?.remove()
        selectedPoiMarker = null
        lastRoutePath = null
        lastNaviInfo = null
        routeStartPoint = null
        routeReadyDispatched = false
        lastFallbackRoutePointCount = 0
        lastPassedRouteProgressKey = -1
        lastRouteProgressPercent = 0
        lastRerouteRequestAt = 0L
        runCatching {
            naviView.map.clear()
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德导航退出清理地图失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
        scheduleBindMapPoiSelection()
    }

    fun cancelNavigation() {
        onNaviCancel()
    }

    fun recenterToCurrentLocation() {
        if (isNavigating) {
            navigationFollowPausedUntilMs = 0L
            naviView.removeCallbacks(recoverNavigationLockRunnable)
            recoverNavigationLock()
            showToast("已回到当前位置")
            listener.onOutdoorNaviEvent("高德视角已回到当前位置，恢复锁车模式")
            return
        }
        val currentPoint = lastNaviLocation?.coord?.toLatLngOrNull()
            ?: runCatching { naviView.map.myLocation?.toLatLngOrNull() }.getOrNull()
        if (currentPoint == null) {
            showToast("暂无当前位置")
            listener.onOutdoorNaviEvent("高德视角回到当前位置失败：暂无定位")
            return
        }
        runCatching {
            naviView.map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentPoint, CURRENT_LOCATION_ZOOM))
        }.onSuccess {
            showToast("已回到当前位置")
            listener.onOutdoorNaviEvent("高德视角已回到当前位置 lat=${currentPoint.latitude} lng=${currentPoint.longitude}")
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德视角回到当前位置失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun centerOnCurrentLocation(point: OutdoorPoint) {
        val currentPoint = LatLng(point.latitude, point.longitude)
        routeStartPoint = currentPoint
        updateCurrentLocationMarker(currentPoint, 0f)
        runCatching {
            naviView.map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentPoint, CURRENT_LOCATION_ZOOM))
        }.onSuccess {
            listener.onOutdoorNaviEvent("高德地图已定位到当前位置 lat=${currentPoint.latitude} lng=${currentPoint.longitude}")
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德地图定位到当前位置失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun toggleHeadingMode() {
        if (isHeadingUpMode) {
            setMapHeadingMode(AMapNaviView.NORTH_UP_MODE, headingUp = false, label = "北向上")
        } else {
            setMapHeadingMode(AMapNaviView.CAR_UP_MODE, headingUp = true, label = "车头向上")
        }
    }

    fun previewPoiSelection(poi: OutdoorPoiOption) {
        if (isNavigating) {
            listener.onOutdoorNaviEvent("高德地点预览被拦截：导航中不可直接切换目的地")
            return
        }
        val point = LatLng(poi.latitude, poi.longitude)
        val title = poi.title.trim().ifBlank { "搜索选中位置" }
        renderSelectedPoiMarker(point, title)
        listener.onOutdoorNaviEvent("高德地点已定位到地图 label=${poi.label()}")
    }

    private fun bindMapPoiSelection() {
        runCatching {
            naviView.map.setTouchPoiEnable(true)
            naviView.map.setOnPOIClickListener { poi ->
                handleMapPoiClick(poi)
            }
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德地图 POI 点选监听注册失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun scheduleBindMapPoiSelection() {
        bindMapPoiSelection()
        naviView.post { bindMapPoiSelection() }
        naviView.postDelayed({ bindMapPoiSelection() }, 300L)
        naviView.postDelayed({ bindMapPoiSelection() }, 900L)
    }

    private fun handleMapPoiClick(poi: Poi?) {
        if (poi == null) {
            listener.onOutdoorNaviEvent("高德地图 POI 点选失败：poi为空")
            return
        }
        if (isNavigating) {
            showToast("导航中请先退出再切换目的地")
            listener.onOutdoorNaviEvent("高德地图 POI 点选被拦截：导航中不可直接切换目的地")
            return
        }
        val coordinate = poi.coordinate
        if (coordinate == null) {
            listener.onOutdoorNaviEvent("高德地图 POI 点选失败：坐标为空 name=${poi.name.orEmpty()}")
            return
        }
        val title = poi.name.orEmpty().trim().ifBlank { "地图点选位置" }
        val option = OutdoorPoiOption(
            poiId = poi.poiId.orEmpty(),
            title = title,
            address = "地图点选",
            city = "",
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
        )
        renderSelectedPoiMarker(coordinate, title)
        showToast("已选中：$title")
        listener.onOutdoorMapPoiSelected(option)
    }

    private fun renderSelectedPoiMarker(point: LatLng, title: String) {
        selectedPoiMarker?.remove()
        selectedPoiMarker = naviView.map.addMarker(
            MarkerOptions()
                .position(point)
                .title(title)
                .snippet("地图点选目的地")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .anchor(0.5f, 1.0f)
                .zIndex(SELECTED_POI_MARKER_Z_INDEX),
        )
        runCatching {
            naviView.map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, SELECTED_POI_ZOOM))
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德地图 POI 点选视角移动失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    override fun onNaviSetting() {
        showToast("当前演示未开放高德设置页")
        listener.onOutdoorNaviEvent("高德内置设置已点击，当前演示未开放设置页")
    }

    override fun onNaviCancel() {
        stopNavigation()
        showToast("已退出高德导航")
        listener.onOutdoorNavigationCanceled()
    }

    override fun onNaviBackClick(): Boolean {
        onNaviCancel()
        return true
    }

    override fun onNaviMapMode(mode: Int) {
        val headingUp = mode == AMapNaviView.CAR_UP_MODE
        isHeadingUpMode = headingUp
        applyHeadingGesturePolicy(headingUp)
        if (!headingUp) {
            enforceNorthUpCamera()
        }
        listener.onOutdoorNaviEvent("高德地图模式切换 mode=$mode")
    }

    override fun onNaviTurnClick() {
        listener.onOutdoorNaviEvent("高德转向区域已点击")
    }

    override fun onNextRoadClick() {
        listener.onOutdoorNaviEvent("高德下一道路区域已点击")
    }

    override fun onScanViewButtonClick() {
        pauseNavigationFollow()
        val overviewResult = displayOverview()
        if (overviewResult) {
            showToast("已切换路线全览")
            listener.onOutdoorNaviEvent("高德路线全览已点击")
        } else {
            showToast("高德全览不可用，已使用 App 兜底全览")
        }
    }

    override fun onLockMap(locked: Boolean) {
        if (!locked) {
            pauseNavigationFollow()
        } else {
            navigationFollowPausedUntilMs = 0L
        }
        listener.onOutdoorNaviEvent("高德地图锁定状态 locked=$locked")
    }

    override fun onNaviViewLoaded() {
        scheduleBindBuiltInChromeActions()
        listener.onOutdoorNaviEvent("高德导航视图加载完成")
    }

    override fun onMapTypeChanged(type: Int) {
        listener.onOutdoorNaviEvent("高德地图类型变化 type=$type")
    }

    override fun onNaviViewShowMode(mode: Int) {
        listener.onOutdoorNaviEvent("高德导航视图显示模式 mode=$mode")
    }

    override fun onInitNaviFailure() {
        listener.onOutdoorNaviError("高德导航初始化回调失败")
    }

    override fun onInitNaviSuccess() {
        listener.onOutdoorNaviEvent("高德导航初始化成功")
    }

    override fun onStartNavi(type: Int) {
        isNavigating = true
        navigationFollowPausedUntilMs = 0L
        setBuiltInNavigationChromeVisible(true)
        scheduleBindBuiltInChromeActions()
        listener.onOutdoorNaviEvent("高德导航开始 type=$type")
    }

    override fun onTrafficStatusUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastTrafficLogAt < 5000L) {
            return
        }
        lastTrafficLogAt = now
        listener.onOutdoorNaviEvent("高德路况已更新")
    }

    override fun onLocationChange(location: AMapNaviLocation?) {
        val now = System.currentTimeMillis()
        lastNaviLocation = location ?: lastNaviLocation
        location?.coord?.toLatLngOrNull()?.let { point ->
            val bearing = location.roadBearing.takeIf { it != 0f } ?: location.bearing
            updateCurrentLocationMarker(point, bearing)
            if (isNavigating) {
                followCurrentLocation(point, bearing)
                updatePassedRoute(point)
                requestRerouteIfOffRoute(point)
            }
        }
        if (location != null && now - lastLocationLogAt >= 3000L) {
            lastLocationLogAt = now
            listener.onOutdoorNaviInfo(buildNaviSummary(lastNaviInfo, location.speed))
            listener.onOutdoorNaviEvent("高德位置更新 speed=${formatSpeed(displaySpeedKmh(lastNaviInfo, location.speed))}")
        }
    }

    override fun onGetNavigationText(type: Int, text: String?) {
        listener.onOutdoorNaviEvent("高德导航播报 type=$type text=${text.orDash()}")
        text?.takeIf { it.isNotBlank() }?.let(listener::onOutdoorNavigationText)
    }

    override fun onGetNavigationText(text: String?) {
        listener.onOutdoorNaviEvent("高德导航播报 text=${text.orDash()}")
        text?.takeIf { it.isNotBlank() }?.let(listener::onOutdoorNavigationText)
    }

    override fun onEndEmulatorNavi() {
        isNavigating = false
        isSimulationNavigation = false
        setBuiltInNavigationChromeVisible(false)
        listener.onOutdoorNaviEvent("高德模拟导航已结束")
    }

    override fun onArriveDestination() {
        isNavigating = false
        isSimulationNavigation = false
        setBuiltInNavigationChromeVisible(false)
        listener.onOutdoorArriveDestination()
    }

    override fun onCalculateRouteFailure(errorCode: Int) {
        listener.onOutdoorNaviError("高德${activeTravelMode.displayName}算路失败 errorCode=$errorCode")
    }

    override fun onReCalculateRouteForYaw() {
        listener.onOutdoorNaviEvent("高德偏航重算中")
        requestYawReroute("高德偏航回调")
    }

    override fun onReCalculateRouteForTrafficJam() {
        listener.onOutdoorNaviEvent("高德拥堵重算中")
    }

    override fun onArrivedWayPoint(wayId: Int) {
        listener.onOutdoorNaviEvent("高德到达途经点 waypoint=$wayId")
    }

    override fun onGpsOpenStatus(enabled: Boolean) {
        listener.onOutdoorNaviEvent("高德 GPS 开关状态 enabled=$enabled")
    }

    override fun onNaviInfoUpdate(naviInfo: NaviInfo?) {
        val now = System.currentTimeMillis()
        if (naviInfo == null || now - lastNaviInfoLogAt < 1000L) {
            return
        }
        lastNaviInfo = naviInfo
        lastNaviInfoLogAt = now
        updateProgressFromNaviInfo(naviInfo)
        listener.onOutdoorNaviInfo(buildNaviSummary(naviInfo, lastNaviLocation?.speed))
    }

    override fun updateCameraInfo(cameraInfos: Array<AMapNaviCameraInfo>?) = Unit

    override fun updateIntervalCameraInfo(
        startCameraInfo: AMapNaviCameraInfo?,
        endCameraInfo: AMapNaviCameraInfo?,
        status: Int,
    ) = Unit

    override fun onServiceAreaUpdate(serviceAreaInfos: Array<AMapServiceAreaInfo>?) = Unit

    override fun showCross(cross: AMapNaviCross?) = Unit

    override fun hideCross() = Unit

    override fun showModeCross(modelCross: AMapModelCross?) = Unit

    override fun hideModeCross() = Unit

    override fun showLaneInfo(laneInfos: Array<AMapLaneInfo>?, laneBackgroundInfo: ByteArray?, laneRecommendedInfo: ByteArray?) = Unit

    override fun showLaneInfo(laneInfo: AMapLaneInfo?) = Unit

    override fun hideLaneInfo() = Unit

    override fun onCalculateRouteSuccess(routeIds: IntArray?) {
        dispatchRouteReady("routeIds=${routeIds?.joinToString().orDash()}")
    }

    override fun notifyParallelRoad(status: Int) {
        listener.onOutdoorNaviEvent("高德平行路状态 status=$status")
    }

    override fun OnUpdateTrafficFacility(trafficFacilityInfos: Array<AMapNaviTrafficFacilityInfo>?) = Unit

    override fun OnUpdateTrafficFacility(trafficFacilityInfo: AMapNaviTrafficFacilityInfo?) = Unit

    override fun updateAimlessModeStatistics(stat: AimLessModeStat?) = Unit

    override fun updateAimlessModeCongestionInfo(info: AimLessModeCongestionInfo?) = Unit

    override fun onPlayRing(type: Int) = Unit

    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
        val routeIds = result?.routeid?.joinToString()
        dispatchRouteReady("routeIds=${routeIds.orDash()}")
    }

    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
        val message = buildString {
            append("高德${activeTravelMode.displayName}算路失败")
            append(" code=${result?.errorCode?.toString().orDash()}")
            append(" description=${result?.errorDescription.orDash()}")
            append(" detail=${result?.errorDetail.orDash()}")
        }
        scheduleBindMapPoiSelection()
        listener.onOutdoorNaviError(message)
    }

    override fun onNaviRouteNotify(data: AMapNaviRouteNotifyData?) {
        listener.onOutdoorNaviEvent("高德路线通知 notify=${data?.notifyType?.toString().orDash()}")
    }

    override fun onGpsSignalWeak(weak: Boolean) {
        listener.onOutdoorNaviEvent("高德 GPS 信号弱=$weak")
    }

    private fun dispatchRouteReady(routeIdSummary: String) {
        if (routeReadyDispatched) {
            return
        }
        routeReadyDispatched = true
        val path = amapNavi?.naviPath
        if (path == null) {
            listener.onOutdoorRouteReady("高德${activeTravelMode.displayName}路线已就绪 | $routeIdSummary")
            routeStartPoint?.let { updateCurrentLocationMarker(it, 0f) }
            scheduleBindMapPoiSelection()
            return
        }
        lastRoutePath = path
        (lastNaviLocation?.coord?.toLatLngOrNull() ?: path.startPoint?.toLatLngOrNull() ?: routeStartPoint)?.let {
            updateCurrentLocationMarker(it, 0f)
        }
        renderFallbackRoute(path, report = true)
        scheduleEnsureFallbackRouteVisible()
        displayOverview()
        scheduleBindMapPoiSelection()
        listener.onOutdoorRouteReady(
            "高德${activeTravelMode.displayName}路线已就绪 | 距离=${formatDistance(path.allLength)} 时间=${formatDuration(path.allTime)} 路段数=${path.stepsCount} $routeIdSummary",
        )
    }

    private fun Any?.orDash(): String = this?.toString()?.takeIf { it.isNotBlank() } ?: "-"

    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) {
            String.format(Locale.CHINA, "%.1fkm", meters / 1000.0)
        } else {
            "${meters.coerceAtLeast(0)}m"
        }
    }

    private fun formatDuration(seconds: Int): String {
        val totalSeconds = seconds.coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val remainingSeconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分${remainingSeconds}秒"
            minutes > 0 -> "${minutes}分${remainingSeconds}秒"
            else -> "${remainingSeconds}秒"
        }
    }

    private fun formatSpeed(speedKmh: Float): String {
        val kmh = speedKmh.coerceAtLeast(0f)
        return String.format(Locale.CHINA, "%.1fkm/h", kmh.coerceAtLeast(0f))
    }

    private fun displaySpeedKmh(naviInfo: NaviInfo?, locationSpeed: Float?): Float {
        val naviSpeed = naviInfo?.currentSpeed?.takeIf { it > 0 }?.toFloat()
        val locationSpeedKmh = locationSpeed?.takeIf { it > 0.1f }?.let { speed ->
            if (speed <= 25f) speed * 3.6f else speed
        }
        val fallbackSpeed = if (isSimulationNavigation) EMULATOR_NAVI_SPEED.toFloat() else 0f
        return naviSpeed ?: locationSpeedKmh ?: fallbackSpeed
    }

    private fun buildNaviSummary(naviInfo: NaviInfo?, speed: Float?): String {
        val speedText = formatSpeed(displaySpeedKmh(naviInfo, speed))
        val progressText = "${lastRouteProgressPercent.coerceIn(0, 100)}%"
        if (naviInfo == null) {
            return "方式=${activeTravelMode.displayName} 速度=$speedText 进度=$progressText 等待高德导航进度"
        }
        return "方式=${activeTravelMode.displayName} 距目的地=${formatDistance(naviInfo.pathRetainDistance)} 预计时间=${formatDuration(naviInfo.pathRetainTime)} 当前路=${naviInfo.currentRoadName.orDash()} 下一路=${naviInfo.nextRoadName.orDash()} 速度=$speedText 进度=$progressText"
    }

    private fun showToast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun scheduleBindBuiltInChromeActions() {
        naviView.post { bindBuiltInChromeActions() }
        naviView.postDelayed({ bindBuiltInChromeActions() }, 300L)
        naviView.postDelayed({ bindBuiltInChromeActions() }, 900L)
    }

    private fun scheduleDisplayOverview() {
        naviView.postDelayed({ displayOverview() }, 600L)
        naviView.postDelayed({ displayOverview() }, 1500L)
    }

    private fun scheduleRecoverLockMode() {
        naviView.post { recoverNavigationLock() }
        naviView.postDelayed({ recoverNavigationLock() }, 600L)
        naviView.postDelayed({ recoverNavigationLock() }, 1500L)
    }

    private fun scheduleEnsureFallbackRouteVisible() {
        val path = lastRoutePath ?: amapNavi?.naviPath ?: return
        naviView.postDelayed({ renderFallbackRoute(path, report = false) }, 300L)
        naviView.postDelayed({ renderFallbackRoute(path, report = false) }, 1200L)
    }

    private fun displayOverview(): Boolean {
        val path = lastRoutePath ?: amapNavi?.naviPath
        if (path != null) {
            renderFallbackRoute(path, report = false)
        }
        val routePoints = path?.toLatLngList().orEmpty()
        val result = runCatching {
            naviView.displayOverview()
        }
        setBuiltInNavigationChromeVisible(isNavigating)
        scheduleBindBuiltInChromeActions()
        if (routePoints.size >= MIN_ROUTE_POINT_COUNT) {
            naviView.postDelayed({ moveCameraToRoute(routePoints) }, 240L)
        }
        if (result.isSuccess) {
            return true
        }
        val throwable = result.exceptionOrNull()
        if (path != null) {
            moveCameraToRoute(routePoints)
        }
        listener.onOutdoorNaviEvent(
            "高德原生全览不可用，已切换 App 兜底全览：${throwable?.message ?: throwable?.javaClass?.simpleName.orDash()}",
        )
        return false
    }

    private fun renderFallbackRoute(path: AMapNaviPath, report: Boolean) {
        if (!isNavigating && !report) {
            return
        }
        val points = path.toLatLngList()
        if (points.size < MIN_ROUTE_POINT_COUNT) {
            if (report) {
                listener.onOutdoorNaviEvent("App 兜底路线未绘制：路线点不足 points=${points.size}")
            }
            return
        }
        naviView.post {
            runCatching {
                fallbackRoutePolyline?.remove()
                passedRoutePolyline?.remove()
                lastPassedRouteProgressKey = -1
                lastRouteProgressPercent = 0
                fallbackRoutePolyline = naviView.map.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .width(FALLBACK_ROUTE_WIDTH)
                        .color(FALLBACK_ROUTE_COLOR)
                        .zIndex(FALLBACK_ROUTE_Z_INDEX)
                        .visible(true),
                )
                moveCameraToRoute(points)
            }.onSuccess {
                if (report || lastFallbackRoutePointCount != points.size) {
                    listener.onOutdoorNaviEvent("App 兜底路线已绘制 points=${points.size}")
                }
                lastFallbackRoutePointCount = points.size
            }.onFailure { throwable ->
                listener.onOutdoorNaviEvent("App 兜底路线绘制失败：${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    private fun moveCameraToRoute(points: List<LatLng>) {
        if (points.size < MIN_ROUTE_POINT_COUNT) {
            return
        }
        runCatching {
            val boundsBuilder = LatLngBounds.builder()
            points.forEach { boundsBuilder.include(it) }
            naviView.map.animateCamera(
                CameraUpdateFactory.newLatLngBoundsRect(
                    boundsBuilder.build(),
                    ROUTE_OVERVIEW_PADDING_LEFT,
                    ROUTE_OVERVIEW_PADDING_RIGHT,
                    ROUTE_OVERVIEW_PADDING_TOP,
                    ROUTE_OVERVIEW_PADDING_BOTTOM,
                ),
            )
        }.recoverCatching {
            val boundsBuilder = LatLngBounds.builder()
            points.forEach { boundsBuilder.include(it) }
            naviView.map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), ROUTE_OVERVIEW_PADDING),
            )
        }
    }

    private fun followCurrentLocation(point: LatLng, bearing: Float, force: Boolean = false) {
        if (!force && System.currentTimeMillis() < navigationFollowPausedUntilMs) {
            return
        }
        runCatching {
            applyMapViewportOffset()
            if (!isHeadingUpMode) {
                enforceNorthUpCamera(point)
                return@runCatching
            }
            val cameraBearing = if (isHeadingUpMode) bearing.normalizedBearing() else 0f
            naviView.map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(point)
                        .zoom(CURRENT_LOCATION_ZOOM)
                        .bearing(cameraBearing)
                        .tilt(if (isHeadingUpMode) NAVIGATION_CAMERA_TILT else 0f)
                        .build(),
                ),
            )
        }
    }

    private fun applyMapViewportOffset() {
        naviView.post {
            val width = naviView.width
            val height = naviView.height
            if (width <= 0 || height <= 0) {
                return@post
            }
            runCatching {
                naviView.map.setPointToCenter(
                    width / 2,
                    (height * MAP_FOLLOW_CENTER_Y_RATIO).toInt(),
                )
            }.onFailure { throwable ->
                listener.onOutdoorNaviEvent("高德地图中心点偏移设置失败：${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    private fun recoverNavigationLock() {
        if (!isNavigating) {
            return
        }
        navigationFollowPausedUntilMs = 0L
        naviView.removeCallbacks(recoverNavigationLockRunnable)
        runCatching {
            applyMapViewportOffset()
            naviView.recoverLockMode()
            lastNaviLocation?.let { location ->
                val point = location.coord?.toLatLngOrNull()
                val bearing = location.roadBearing.takeIf { it != 0f } ?: location.bearing
                if (point != null) {
                    followCurrentLocation(point, bearing, force = true)
                }
            }
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德锁车跟随恢复失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun handleMapTouch(event: MotionEvent?) {
        if (!isNavigating || event == null) {
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> pauseNavigationFollow()
        }
    }

    private fun pauseNavigationFollow(timeoutMs: Long = USER_MAP_IDLE_RETURN_MS) {
        if (!isNavigating) {
            return
        }
        navigationFollowPausedUntilMs = System.currentTimeMillis() + timeoutMs
        naviView.removeCallbacks(recoverNavigationLockRunnable)
        naviView.postDelayed(recoverNavigationLockRunnable, timeoutMs)
    }

    private fun setMapHeadingMode(mode: Int, headingUp: Boolean, label: String) {
        runCatching {
            isHeadingUpMode = headingUp
            naviView.setNaviMode(mode)
            applyHeadingGesturePolicy(headingUp)
            applyMapViewportOffset()
            if (headingUp) {
                naviView.recoverLockMode()
            } else {
                enforceNorthUpCamera()
                naviView.postDelayed({ enforceNorthUpCamera() }, NORTH_UP_ENFORCE_DELAY_MS)
            }
        }.onSuccess {
            showToast("已切换为$label")
            listener.onOutdoorNaviEvent("高德视角已切换为$label")
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德视角切换失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun applyHeadingGesturePolicy(headingUp: Boolean) {
        runCatching {
            naviView.map.uiSettings.setRotateGesturesEnabled(headingUp)
            naviView.map.uiSettings.setTiltGesturesEnabled(headingUp)
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德视角手势策略设置失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun enforceNorthUpCamera(targetOverride: LatLng? = null) {
        runCatching {
            naviView.setNaviMode(AMapNaviView.NORTH_UP_MODE)
            val currentCamera = naviView.map.cameraPosition
            val target = targetOverride ?: lastNaviLocation?.coord?.toLatLngOrNull() ?: currentCamera.target
            naviView.map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(target)
                        .zoom(currentCamera.zoom)
                        .bearing(0f)
                        .tilt(0f)
                        .build(),
                ),
            )
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德北向上相机锁定失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun updatePassedRoute(currentPoint: LatLng) {
        val routePoints = lastRoutePath?.toLatLngList() ?: return
        if (routePoints.size < MIN_ROUTE_POINT_COUNT) {
            return
        }
        val projection = projectPointOnRoute(currentPoint, routePoints)
        lastRouteProgressPercent = projection.progressPercent
        val progressKey = (projection.passedDistanceMeters / PASSED_ROUTE_UPDATE_STEP_METERS).toInt()
        if (progressKey == lastPassedRouteProgressKey) {
            return
        }
        lastPassedRouteProgressKey = progressKey
        val passedPoints = buildPassedRoutePoints(routePoints, projection)
        naviView.post {
            runCatching {
                passedRoutePolyline?.remove()
                passedRoutePolyline = if (passedPoints.size >= MIN_ROUTE_POINT_COUNT) {
                    naviView.map.addPolyline(
                        PolylineOptions()
                            .addAll(passedPoints)
                            .width(PASSED_ROUTE_WIDTH)
                            .color(PASSED_ROUTE_COLOR)
                            .zIndex(PASSED_ROUTE_Z_INDEX)
                            .visible(true),
                    )
                } else {
                    null
                }
            }.onFailure { throwable ->
                listener.onOutdoorNaviEvent("已走路线灰线绘制失败：${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    private fun requestRerouteIfOffRoute(currentPoint: LatLng) {
        val routePoints = lastRoutePath?.toLatLngList() ?: return
        if (routePoints.size < MIN_ROUTE_POINT_COUNT) {
            return
        }
        val minDistance = projectPointOnRoute(currentPoint, routePoints).distanceMeters
        if (minDistance <= ROUTE_DEVIATION_THRESHOLD_METERS) {
            return
        }
        requestYawReroute("偏离既定路线 ${formatDistance(minDistance.toInt())}")
    }

    private fun requestYawReroute(reason: String) {
        val navi = amapNavi ?: return
        val now = System.currentTimeMillis()
        if (now - lastRerouteRequestAt < REROUTE_MIN_INTERVAL_MS) {
            return
        }
        lastRerouteRequestAt = now
        showToast("已偏离路线，正在重新规划")
        listener.onOutdoorNaviEvent("高德偏航：$reason，正在重新规划")
        runCatching {
            navi.reCalculateRoute(DEFAULT_REROUTE_STRATEGY)
        }.onSuccess { accepted ->
            if (accepted) {
                listener.onOutdoorNaviEvent("高德自动重算路线已请求：$reason")
            } else {
                listener.onOutdoorNaviEvent("高德自动重算路线请求被拒绝：$reason")
            }
        }.onFailure { throwable ->
            listener.onOutdoorNaviEvent("高德自动重算路线失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun updateCurrentLocationMarker(point: LatLng, bearing: Float) {
        naviView.post {
            runCatching {
                val icon = currentLocationIcon ?: createCurrentLocationIcon().also {
                    currentLocationIcon = it
                }
                val rotateAngle = markerRotateAngle(bearing)
                val marker = currentLocationMarker
                if (marker == null || marker.isRemoved) {
                    currentLocationMarker = naviView.map.addMarker(
                        MarkerOptions()
                            .position(point)
                            .anchor(0.5f, 0.5f)
                            .icon(icon)
                            .rotateAngle(rotateAngle)
                            .zIndex(CURRENT_LOCATION_MARKER_Z_INDEX)
                            .visible(true),
                    )
                } else {
                    marker.position = point
                    marker.setRotateAngle(rotateAngle)
                    marker.setVisible(true)
                }
            }.onFailure { throwable ->
                listener.onOutdoorNaviEvent("当前位置箭头绘制失败：${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    private fun markerRotateAngle(bearing: Float): Float {
        return if (isHeadingUpMode) 0f else 360f - bearing.normalizedBearing()
    }

    private fun createCurrentLocationIcon(): BitmapDescriptor {
        val size = CURRENT_LOCATION_MARKER_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(72, 0, 0, 0)
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.34f, shadowPaint)

        val arrowPath = Path().apply {
            moveTo(size / 2f, size * 0.12f)
            lineTo(size * 0.78f, size * 0.82f)
            lineTo(size / 2f, size * 0.68f)
            lineTo(size * 0.22f, size * 0.82f)
            close()
        }
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 118, 210)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = size * 0.08f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(arrowPath, strokePaint)
        canvas.drawPath(arrowPath, arrowPaint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun AMapNaviPath.toLatLngList(): List<LatLng> {
        val coords = coordList.orEmpty().ifEmpty {
            steps.orEmpty().flatMap { it.coords.orEmpty() }
        }
        return coords.mapNotNull { coord ->
            val latitude = coord.latitude
            val longitude = coord.longitude
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                LatLng(latitude, longitude)
            } else {
                null
            }
        }
    }

    private fun NaviLatLng.toLatLngOrNull(): LatLng? {
        val pointLatitude = latitude
        val pointLongitude = longitude
        return if (pointLatitude in -90.0..90.0 && pointLongitude in -180.0..180.0) {
            LatLng(pointLatitude, pointLongitude)
        } else {
            null
        }
    }

    private fun Location.toLatLngOrNull(): LatLng? {
        val pointLatitude = latitude
        val pointLongitude = longitude
        return if (pointLatitude in -90.0..90.0 && pointLongitude in -180.0..180.0) {
            LatLng(pointLatitude, pointLongitude)
        } else {
            null
        }
    }

    private fun distanceMeters(from: LatLng, to: LatLng): Float {
        val result = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, result)
        return result[0]
    }

    private fun projectPointOnRoute(point: LatLng, routePoints: List<LatLng>): RouteProjection {
        var totalDistance = 0f
        var accumulatedDistance = 0f
        var bestProjection = RouteProjection(
            segmentStartIndex = 0,
            fraction = 0.0,
            distanceMeters = Float.MAX_VALUE,
            passedDistanceMeters = 0f,
            totalDistanceMeters = 0f,
        )
        for (index in 0 until routePoints.lastIndex) {
            val start = routePoints[index]
            val end = routePoints[index + 1]
            val segmentDistance = distanceMeters(start, end)
            val fraction = projectPointFraction(point, start, end)
            val projectedPoint = interpolate(start, end, fraction)
            val distance = distanceMeters(point, projectedPoint)
            if (distance < bestProjection.distanceMeters) {
                bestProjection = RouteProjection(
                    segmentStartIndex = index,
                    fraction = fraction,
                    distanceMeters = distance,
                    passedDistanceMeters = accumulatedDistance + segmentDistance * fraction.toFloat(),
                    totalDistanceMeters = 0f,
                )
            }
            accumulatedDistance += segmentDistance
            totalDistance += segmentDistance
        }
        return bestProjection.copy(totalDistanceMeters = totalDistance)
    }

    private fun projectPointFraction(point: LatLng, start: LatLng, end: LatLng): Double {
        val latitudeScale = LATITUDE_METERS
        val longitudeScale = LATITUDE_METERS * cos(Math.toRadians(point.latitude))
        val pointX = (point.longitude - start.longitude) * longitudeScale
        val pointY = (point.latitude - start.latitude) * latitudeScale
        val endX = (end.longitude - start.longitude) * longitudeScale
        val endY = (end.latitude - start.latitude) * latitudeScale
        val denominator = endX * endX + endY * endY
        if (denominator <= 0.0) {
            return 0.0
        }
        return ((pointX * endX + pointY * endY) / denominator).coerceIn(0.0, 1.0)
    }

    private fun buildPassedRoutePoints(routePoints: List<LatLng>, projection: RouteProjection): List<LatLng> {
        val passedPoints = routePoints.take(projection.segmentStartIndex + 1).toMutableList()
        if (projection.segmentStartIndex < routePoints.lastIndex && projection.fraction > 0.001) {
            passedPoints.add(
                interpolate(
                    routePoints[projection.segmentStartIndex],
                    routePoints[projection.segmentStartIndex + 1],
                    projection.fraction,
                ),
            )
        }
        return passedPoints
    }

    private fun interpolate(start: LatLng, end: LatLng, fraction: Double): LatLng {
        return LatLng(
            start.latitude + (end.latitude - start.latitude) * fraction,
            start.longitude + (end.longitude - start.longitude) * fraction,
        )
    }

    private fun updateProgressFromNaviInfo(naviInfo: NaviInfo) {
        val totalDistance = lastRoutePath?.allLength?.takeIf { it > 0 } ?: return
        val remainingDistance = naviInfo.pathRetainDistance.coerceIn(0, totalDistance)
        val progress = ((totalDistance - remainingDistance) * 100f / totalDistance).toInt()
        lastRouteProgressPercent = progress.coerceIn(lastRouteProgressPercent, 100)
    }

    private fun Float.normalizedBearing(): Float {
        val value = this % 360f
        return if (value < 0f) value + 360f else value
    }

    private data class RouteProjection(
        val segmentStartIndex: Int,
        val fraction: Double,
        val distanceMeters: Float,
        val passedDistanceMeters: Float,
        val totalDistanceMeters: Float,
    ) {
        val progressPercent: Int
            get() = if (totalDistanceMeters > 0f) {
                (passedDistanceMeters * 100f / totalDistanceMeters).toInt().coerceIn(0, 100)
            } else {
                0
            }
    }

    private fun bindBuiltInChromeActions() {
        hideBuiltInView(AmapNaviR.id.exit_navigation_portrait)
        hideBuiltInView(AmapNaviR.id.exit_navigation_land)
        hideBuiltInView(AmapNaviR.id.exit_navigation_sim)
        hideBuiltInView(AmapNaviR.id.navigation_settings_portrait)
        hideBuiltInView(AmapNaviR.id.navigation_settings_land)
        hideBuiltInView(AmapNaviR.id.navigation_info_layout)
        hideBuiltInView(AmapNaviR.id.navigation_info_layout_sim)
        hideBuiltInView(AmapNaviR.id.bottom_layout)
        hideBuiltInView(AmapNaviR.id.lbs_navi_custom_bottom_view)
        hideBuiltInView(AmapNaviR.id.info_portrait)
        hideBuiltInView(AmapNaviR.id.remaining_info_portrait)
        hideBuiltInView(AmapNaviR.id.expect_info_portrait)
        hideBuiltInView(AmapNaviR.id.navi_footer_line_start)
        hideBuiltInView(AmapNaviR.id.navi_footer_line_end)
        hideBuiltInView(AmapNaviR.id.navi_sdk_lbs_navi_speed)
        hideBuiltInView(AmapNaviR.id.navigation_road_switches_container)
        hideBuiltInView(AmapNaviR.id.road_switches_layout)
        hideBuiltInView(AmapNaviR.id.navi_whole_road_condition)
        hideBuiltInView(AmapNaviR.id.navi_whole_road_condition_group)
        hideBuiltInView(AmapNaviR.id.navi_sdk_tmc_bar_container)
        hideBuiltInView(AmapNaviR.id.navi_sdk_lbs_navi_traffic_bar)
        hideBuiltInView(AmapNaviR.id.navi_sdk_tmc_bar_txt)
        bindNavigationOnlyView(AmapNaviR.id.navi_sdk_autonavi_btn_preview) { onScanViewButtonClick() }
        bindNavigationOnlyView(AmapNaviR.id.navi_sdk_autonavi_zoom_and_preview_view) { onScanViewButtonClick() }
        bindNavigationOnlyView(AmapNaviR.id.navigation_preview) { onScanViewButtonClick() }
    }

    private fun setBuiltInNavigationChromeVisible(visible: Boolean) {
        runCatching {
            naviView.viewOptions?.apply {
                setLayoutVisible(visible)
                naviView.setViewOptions(this)
            }
        }
        naviView.post { bindBuiltInChromeActions() }
    }

    private fun bindNavigationOnlyView(id: Int, action: () -> Unit) {
        val target = naviView.findViewById<View?>(id) ?: return
        if (!isNavigating) {
            target.visibility = View.GONE
            target.setOnClickListener(null)
            return
        }
        target.visibility = View.VISIBLE
        target.isClickable = true
        target.isFocusable = true
        target.setOnClickListener { action() }
    }

    private fun showNavigationOnlyView(id: Int) {
        naviView.findViewById<View?>(id)?.visibility = if (isNavigating) View.VISIBLE else View.GONE
    }

    private fun hideBuiltInView(id: Int) {
        naviView.findViewById<View?>(id)?.apply {
            visibility = View.GONE
            setOnClickListener(null)
            isClickable = false
            isFocusable = false
        }
    }

    interface Listener {
        fun onOutdoorRouteReady(summary: String)
        fun onOutdoorNavigationStarted(type: String, gpsStarted: Boolean)
        fun onOutdoorNavigationCanceled()
        fun onOutdoorNaviInfo(summary: String)
        fun onOutdoorNaviEvent(summary: String)
        fun onOutdoorNavigationText(text: String)
        fun onOutdoorArriveDestination()
        fun onOutdoorNaviError(summary: String)
        fun onOutdoorMapPoiSelected(poi: OutdoorPoiOption)
    }
}

data class OutdoorPoint(
    val latitude: Double,
    val longitude: Double,
)

enum class OutdoorTravelMode(
    val id: String,
    val displayName: String,
) {
    RIDE("ride", "自行车"),
    EBIKE("ebike", "电瓶车"),
    DRIVE("drive", "驾车"),
    WALK("walk", "步行"),
}

private const val MIN_ROUTE_POINT_COUNT = 2
private const val FALLBACK_ROUTE_WIDTH = 22f
private const val FALLBACK_ROUTE_Z_INDEX = 999f
private const val PASSED_ROUTE_WIDTH = 24f
private const val PASSED_ROUTE_Z_INDEX = 999.5f
private const val ROUTE_OVERVIEW_PADDING = 180
private const val ROUTE_OVERVIEW_PADDING_LEFT = 140
private const val ROUTE_OVERVIEW_PADDING_RIGHT = 140
private const val ROUTE_OVERVIEW_PADDING_TOP = 240
private const val ROUTE_OVERVIEW_PADDING_BOTTOM = 340
private const val CURRENT_LOCATION_ZOOM = 18f
private const val CURRENT_LOCATION_MARKER_SIZE = 64
private const val CURRENT_LOCATION_MARKER_Z_INDEX = 1000f
private const val SELECTED_POI_MARKER_Z_INDEX = 1001f
private const val SELECTED_POI_ZOOM = 18f
private const val EMULATOR_NAVI_SPEED = 20
private const val MAP_FOLLOW_CENTER_Y_RATIO = 0.62
private const val NAVIGATION_CAMERA_TILT = 45f
private const val NORTH_UP_ENFORCE_DELAY_MS = 250L
private const val ROUTE_DEVIATION_THRESHOLD_METERS = 45f
private const val PASSED_ROUTE_UPDATE_STEP_METERS = 3f
private const val REROUTE_MIN_INTERVAL_MS = 15_000L
private const val DEFAULT_REROUTE_STRATEGY = 0
private const val USER_MAP_IDLE_RETURN_MS = 3_000L
private const val LATITUDE_METERS = 111_320.0
private val FALLBACK_ROUTE_COLOR = Color.rgb(0, 122, 255)
private val PASSED_ROUTE_COLOR = Color.rgb(156, 163, 175)
