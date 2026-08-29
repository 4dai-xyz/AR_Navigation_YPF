package com.aiglasses.poc

import com.aiglasses.poc.connection.ConnectionState
import com.aiglasses.poc.indoor.IndoorNavigationMode
import com.aiglasses.poc.indoor.ManualIndoorDemoScripts
import com.aiglasses.poc.indoor.ManualIndoorDemoState
import com.aiglasses.poc.nav.NavState

data class PocUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val selectedProviderId: String = "glasses_album_sync",
    val navState: NavState = NavState.OUTDOOR_IDLE,
    val outdoorSummary: String = "室外：高德 SDK 加载中",
    val providerSummary: String = "图像来源：glasses_album_sync",
    val pcBackendSummary: String = "PC后台：未连接",
    val pcBackendConnected: Boolean = false,
    val serviceSummary: String = "服务：未检查",
    val venueSummary: String = "场馆：未加载",
    val requestSummary: String = "请求：暂无请求",
    val localizationSummary: String = "定位：暂无结果",
    val routeSummary: String = "路径：暂无结果",
    val fallbackSummary: String = "降级：无",
    val lowConfidenceSummary: String = "低置信度：无",
    val errorSummary: String = "错误：无",
    val lastSuccessfulProviderId: String? = null,
    val lastFailedProviderId: String? = null,
    val lastProviderFailureReason: String? = null,
    val lastLocalizationStatus: String? = null,
    val lastFloorId: String? = null,
    val lastPositionX: Double? = null,
    val lastPositionY: Double? = null,
    val lastLocalizationConfidence: Double? = null,
    val lastMatchedLandmarkPoiId: String? = null,
    val lastMatchedLandmarkDisplayName: String? = null,
    val exhibitionRouteNodes: Map<String, ExhibitionRouteNode> = emptyMap(),
    val exhibitionPois: Map<String, ExhibitionPoi> = emptyMap(),
    val exhibitionActiveRouteNodeIds: List<String> = emptyList(),
    val exhibitionActiveTargetPoiId: String? = null,
    val logs: List<String> = emptyList(),
    val uiSignals: UiSignals = UiSignals(),
    val topCard: TopCardUiModel = TopCardUiModel(),
    val bottomBar: BottomActionBarUiModel = BottomActionBarUiModel(),
    val mapChrome: MapChromeUiModel = MapChromeUiModel(),
    val debugPanel: DebugPanelUiModel = DebugPanelUiModel(),
    val indoorBasemap: IndoorBasemapUiModel = IndoorBasemapUiModel(),
    val indoorMode: IndoorNavigationMode = IndoorNavigationMode.MANUAL_DEMO,
    val manualIndoorDemo: ManualIndoorDemoState = ManualIndoorDemoScripts.initialState(),
)

data class ExhibitionRouteNode(
    val nodeId: String,
    val floorId: String,
    val x: Double,
    val y: Double,
    val nodeType: String,
    val refId: String?,
)

data class ExhibitionPoi(
    val poiId: String,
    val displayName: String,
    val floorId: String,
    val x: Double,
    val y: Double,
    val routeNodeId: String?,
)

data class UiSignals(
    val amapReady: Boolean = false,
    val lowConfidenceActive: Boolean = false,
    val fallbackActive: Boolean = false,
    val fallbackFromProviderId: String? = null,
    val fallbackToProviderId: String? = null,
)

enum class DemoPhase {
    OUTDOOR,
    HANDOFF,
    INDOOR,
    ERROR,
    ABORTED,
}

enum class BottomActionGroup {
    OUTDOOR_PREPARE,
    OUTDOOR_ROUTE_READY,
    OUTDOOR_NAVIGATING,
    HANDOFF,
    INDOOR_FLOW,
    ERROR,
    ABORTED,
}

data class TopCardUiModel(
    val phase: DemoPhase = DemoPhase.OUTDOOR,
    val title: String = "室外导航",
    val headline: String = "等待初始化",
    val detail: String = "高德 SDK 加载中",
    val warning: String? = null,
    val error: String? = null,
    val showLowConfidence: Boolean = false,
    val showFallback: Boolean = false,
)

data class BottomActionBarUiModel(
    val group: BottomActionGroup = BottomActionGroup.OUTDOOR_PREPARE,
    val showRouteModeToggle: Boolean = false,
    val showUseCurrentLocation: Boolean = false,
    val showPoiSearch: Boolean = false,
    val showPrepareRoute: Boolean = false,
    val showStartNavigation: Boolean = false,
    val showExternalNavigation: Boolean = false,
    val showContinueNavigation: Boolean = false,
    val showEnterVenue: Boolean = false,
    val showExitIndoor: Boolean = false,
    val showCaptureAndLocate: Boolean = false,
    val showRequestRoute: Boolean = false,
    val showManualIndoorControls: Boolean = false,
    val showProviderSelector: Boolean = false,
)

data class MapChromeUiModel(
    val showRecenter: Boolean = true,
    val showOrientationToggle: Boolean = true,
    val showOverview: Boolean = false,
    val showExitNavigation: Boolean = false,
    val showProgressLane: Boolean = false,
    val keepMapClear: Boolean = false,
)

data class DebugPanelUiModel(
    val defaultExpanded: Boolean = false,
    val forceCollapseOnNavigation: Boolean = false,
    val showCloudConfig: Boolean = true,
    val showProviderSection: Boolean = true,
    val showHealthAction: Boolean = true,
    val showVenueAction: Boolean = true,
    val showCaptureAction: Boolean = true,
    val showRouteAction: Boolean = true,
    val showLowConfidenceMock: Boolean = true,
    val showFallbackMock: Boolean = true,
    val showLogs: Boolean = true,
)

data class IndoorBasemapUiModel(
    val enabled: Boolean = false,
    val available: Boolean = false,
    val expectedPoiId: String? = null,
    val activePoiId: String? = null,
    val activeFloorName: String? = null,
    val availableFloorNames: List<String> = emptyList(),
    val mismatchWarning: String? = null,
    val statusSummary: String = "高德室内底图：未启用",
)

fun PocUiState.withUiContract(): PocUiState {
    val phase = navState.toDemoPhase()
    val warning = when {
        uiSignals.lowConfidenceActive -> lowConfidenceSummary
        uiSignals.fallbackActive -> fallbackSummary
        else -> null
    }?.takeUnless { it.endsWith("无") }
    val error = errorSummary.takeUnless { it == "错误：无" }
        ?.takeUnless { navState != NavState.ERROR && !it.startsWith("警告") }

    return copy(
        topCard = TopCardUiModel(
            phase = phase,
            title = topCardTitle(phase),
            headline = navStateHeadline(),
            detail = topCardDetail(),
            warning = warning,
            error = error,
            showLowConfidence = uiSignals.lowConfidenceActive,
            showFallback = uiSignals.fallbackActive,
        ),
        bottomBar = bottomBarModel(),
        mapChrome = mapChromeModel(),
        debugPanel = debugPanelModel(),
    )
}

private fun PocUiState.topCardDetail(): String {
    return when (navState) {
        NavState.OUTDOOR_IDLE,
        NavState.OUTDOOR_READY,
        NavState.OUTDOOR_ROUTE_READY,
        NavState.OUTDOOR_NAVIGATING -> outdoorSummary
        NavState.ENTRY_HANDOFF_PENDING -> requestSummary
        NavState.INDOOR_READY,
        NavState.INDOOR_CAPTURING,
        NavState.INDOOR_LOCATING,
        NavState.INDOOR_LOW_CONFIDENCE -> indoorDetail()
        NavState.INDOOR_ROUTING,
        NavState.INDOOR_ROUTE_READY -> indoorDetail()
        NavState.ERROR,
        NavState.ABORTED -> errorSummary.takeUnless { it == "错误：无" } ?: requestSummary
    }
}

private fun PocUiState.indoorDetail(): String {
    if (indoorMode != IndoorNavigationMode.MANUAL_DEMO) {
        return if (navState == NavState.INDOOR_ROUTING || navState == NavState.INDOOR_ROUTE_READY) {
            routeSummary
        } else {
            localizationSummary
        }
    }
    val demo = manualIndoorDemo
    return listOfNotNull(
        "当前模式：室内手动演示（${indoorMode.id}）",
        "当前楼层：${demo.currentFloorId}",
        "当前提示：${demo.instruction}",
        "当前节点：${demo.currentNodeLabel}",
        "目标节点：${demo.targetNodeLabel}",
        demo.correction?.let { "纠错提示：$it" },
        if (demo.arrived) "到达状态：已到达店铺门口" else "到达状态：进行中",
    ).joinToString(separator = "\n")
}

private fun PocUiState.bottomBarModel(): BottomActionBarUiModel {
    return when (navState) {
        NavState.OUTDOOR_IDLE,
        NavState.OUTDOOR_READY -> BottomActionBarUiModel(
            group = BottomActionGroup.OUTDOOR_PREPARE,
            showRouteModeToggle = true,
            showUseCurrentLocation = true,
            showPoiSearch = true,
            showPrepareRoute = true,
            showExternalNavigation = true,
            showEnterVenue = true,
        )
        NavState.OUTDOOR_ROUTE_READY -> BottomActionBarUiModel(
            group = BottomActionGroup.OUTDOOR_ROUTE_READY,
            showRouteModeToggle = true,
            showUseCurrentLocation = true,
            showPoiSearch = true,
            showPrepareRoute = true,
            showStartNavigation = true,
            showExternalNavigation = true,
            showEnterVenue = true,
        )
        NavState.OUTDOOR_NAVIGATING -> BottomActionBarUiModel(
            group = BottomActionGroup.OUTDOOR_NAVIGATING,
            showContinueNavigation = true,
            showEnterVenue = true,
        )
        NavState.ENTRY_HANDOFF_PENDING -> BottomActionBarUiModel(
            group = BottomActionGroup.HANDOFF,
            showEnterVenue = true,
            showProviderSelector = true,
        )
        NavState.INDOOR_READY,
        NavState.INDOOR_CAPTURING,
        NavState.INDOOR_LOCATING,
        NavState.INDOOR_LOW_CONFIDENCE,
        NavState.INDOOR_ROUTING,
        NavState.INDOOR_ROUTE_READY -> BottomActionBarUiModel(
            group = BottomActionGroup.INDOOR_FLOW,
            showExitIndoor = true,
            showManualIndoorControls = indoorMode == IndoorNavigationMode.MANUAL_DEMO,
            showCaptureAndLocate = indoorMode == IndoorNavigationMode.CLOUD_RELOCALIZATION,
            showRequestRoute = indoorMode == IndoorNavigationMode.CLOUD_RELOCALIZATION,
            showProviderSelector = indoorMode == IndoorNavigationMode.CLOUD_RELOCALIZATION,
        )
        NavState.ERROR -> BottomActionBarUiModel(
            group = BottomActionGroup.ERROR,
            showUseCurrentLocation = true,
            showPoiSearch = true,
            showPrepareRoute = true,
            showExternalNavigation = true,
            showEnterVenue = true,
            showExitIndoor = true,
            showCaptureAndLocate = true,
            showRequestRoute = true,
            showProviderSelector = true,
        )
        NavState.ABORTED -> BottomActionBarUiModel(
            group = BottomActionGroup.ABORTED,
            showUseCurrentLocation = true,
            showPoiSearch = true,
            showPrepareRoute = true,
            showExternalNavigation = true,
            showEnterVenue = true,
        )
    }
}

private fun PocUiState.mapChromeModel(): MapChromeUiModel {
    val isOutdoorPhase = navState in setOf(
        NavState.OUTDOOR_IDLE,
        NavState.OUTDOOR_READY,
        NavState.OUTDOOR_ROUTE_READY,
        NavState.OUTDOOR_NAVIGATING,
        NavState.ENTRY_HANDOFF_PENDING,
    )
    return MapChromeUiModel(
        showRecenter = phaseAllowsMapControls(),
        showOrientationToggle = isOutdoorPhase,
        showOverview = navState == NavState.OUTDOOR_ROUTE_READY || navState == NavState.OUTDOOR_NAVIGATING,
        showExitNavigation = navState == NavState.OUTDOOR_NAVIGATING,
        showProgressLane = navState == NavState.OUTDOOR_NAVIGATING,
        keepMapClear = navState == NavState.OUTDOOR_NAVIGATING,
    )
}

private fun PocUiState.debugPanelModel(): DebugPanelUiModel {
    return DebugPanelUiModel(
        defaultExpanded = false,
        forceCollapseOnNavigation = navState == NavState.OUTDOOR_NAVIGATING,
    )
}

private fun PocUiState.phaseAllowsMapControls(): Boolean {
    return navState != NavState.ERROR && navState != NavState.ABORTED
}

private fun PocUiState.navStateHeadline(): String {
    return when (navState) {
        NavState.OUTDOOR_IDLE -> if (uiSignals.amapReady) "等待室外路线" else "高德 SDK 未就绪"
        NavState.OUTDOOR_READY -> "室外算路中"
        NavState.OUTDOOR_ROUTE_READY -> "室外路线已就绪"
        NavState.OUTDOOR_NAVIGATING -> "室外导航中"
        NavState.ENTRY_HANDOFF_PENDING -> "等待进入室内"
        NavState.INDOOR_READY -> if (indoorMode == IndoorNavigationMode.MANUAL_DEMO) "室内手动演示已就绪" else "室内导航已就绪"
        NavState.INDOOR_CAPTURING -> "正在采图"
        NavState.INDOOR_LOCATING -> "正在定位"
        NavState.INDOOR_LOW_CONFIDENCE -> "定位低置信度"
        NavState.INDOOR_ROUTING -> "正在请求室内路径"
        NavState.INDOOR_ROUTE_READY -> if (indoorMode == IndoorNavigationMode.MANUAL_DEMO && manualIndoorDemo.arrived) {
            "已到达店铺门口"
        } else if (indoorMode == IndoorNavigationMode.MANUAL_DEMO) {
            "室内手动演示中"
        } else {
            "室内路径已就绪"
        }
        NavState.ERROR -> "当前流程异常"
        NavState.ABORTED -> "演示已中止"
    }
}

private fun topCardTitle(phase: DemoPhase): String {
    return when (phase) {
        DemoPhase.OUTDOOR -> "室外导航"
        DemoPhase.HANDOFF -> "场馆交接"
        DemoPhase.INDOOR -> "室内导航"
        DemoPhase.ERROR -> "异常状态"
        DemoPhase.ABORTED -> "演示已中止"
    }
}

private fun NavState.toDemoPhase(): DemoPhase {
    return when (this) {
        NavState.OUTDOOR_IDLE,
        NavState.OUTDOOR_READY,
        NavState.OUTDOOR_ROUTE_READY,
        NavState.OUTDOOR_NAVIGATING -> DemoPhase.OUTDOOR
        NavState.ENTRY_HANDOFF_PENDING -> DemoPhase.HANDOFF
        NavState.INDOOR_READY,
        NavState.INDOOR_CAPTURING,
        NavState.INDOOR_LOCATING,
        NavState.INDOOR_LOW_CONFIDENCE,
        NavState.INDOOR_ROUTING,
        NavState.INDOOR_ROUTE_READY -> DemoPhase.INDOOR
        NavState.ERROR -> DemoPhase.ERROR
        NavState.ABORTED -> DemoPhase.ABORTED
    }
}
