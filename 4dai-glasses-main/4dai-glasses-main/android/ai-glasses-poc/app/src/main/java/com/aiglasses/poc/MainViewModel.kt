package com.aiglasses.poc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiglasses.poc.connection.ConnectionState
import com.aiglasses.poc.connection.MockConnector
import com.aiglasses.poc.image.CapturedFrame
import com.aiglasses.poc.image.ImageProvider
import com.aiglasses.poc.image.MockAlbumSyncProvider
import com.aiglasses.poc.image.MockPhoneFallbackProvider
import com.aiglasses.poc.image.MockThumbnailProvider
import com.aiglasses.poc.image.RokidGlassesFrameProvider
import com.aiglasses.poc.indoor.IndoorNavigationMode
import com.aiglasses.poc.indoor.ManualIndoorDemoAction
import com.aiglasses.poc.indoor.ManualIndoorDemoController
import com.aiglasses.poc.indoor.ManualIndoorDemoResult
import com.aiglasses.poc.indoor.ManualIndoorDemoScript
import com.aiglasses.poc.nav.DemoNavigationStateMachine
import com.aiglasses.poc.nav.NavState
import com.aiglasses.poc.network.ApiClientException
import com.aiglasses.poc.network.LocalizationApiClient
import com.aiglasses.poc.network.LocalizationResult
import com.aiglasses.poc.network.PcBackendPairingConnectionResult
import com.aiglasses.poc.network.RouteResult
import com.aiglasses.poc.outdoor.AmapInitResult
import com.aiglasses.poc.rokid.RokidHudPayload
import com.aiglasses.poc.rokid.RokidRuntimeBridge
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainViewModel : ViewModel() {
    private val connector = MockConnector()
    private val stateMachine = DemoNavigationStateMachine()
    private val apiClient = LocalizationApiClient()
    private var manualIndoorDemoController = ManualIndoorDemoController()
    private val sessionLogs = mutableListOf<String>()
    private val providerOrder = listOf(
        "glasses_album_sync",
        "glasses_thumbnail",
        PHONE_PROVIDER_ID,
    )
    private val selectableProviderIds = providerOrder + "rokid_glasses_frame"
    private val providers: Map<String, ImageProvider> = listOf(
        RokidGlassesFrameProvider(),
        MockAlbumSyncProvider(),
        MockThumbnailProvider(),
        MockPhoneFallbackProvider(),
    ).associateBy { it.id }

    private val _uiState = MutableStateFlow(
        PocUiState(providerSummary = providerSummary("glasses_album_sync")),
    )
    val uiState: StateFlow<PocUiState> = _uiState
        .map { it.withUiContract() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = _uiState.value.withUiContract(),
        )

    val providerIds: List<String> = selectableProviderIds
    val providerLabels: List<String> = selectableProviderIds.map { providerDisplayLabel(it) }

    fun onAmapInitResult(result: AmapInitResult) {
        _uiState.value = _uiState.value.copy(
            outdoorSummary = if (result.sdkConfigured) {
                "当前状态：室外就绪\n真实高德 SDK 已就绪，请选择骑行/步行并输入 GCJ-02 起点和场馆入口坐标"
            } else {
                "当前状态：室外不可用\n高德 SDK 未就绪，真实室外算路不可用"
            },
            requestSummary = "高德初始化：${result.message}",
            errorSummary = if (result.sdkConfigured) "错误：无" else "高德警告：${result.message}",
            uiSignals = _uiState.value.uiSignals.copy(amapReady = result.sdkConfigured),
        )
        pushLog("高德初始化 key_configured=${result.keyConfigured} sdk_configured=${result.sdkConfigured} message=${result.message}")
    }

    fun selectProvider(providerId: String) {
        val provider = providers[providerId] ?: return
        _uiState.value = _uiState.value.copy(
            selectedProviderId = provider.id,
            providerSummary = providerSummary(provider.id),
            errorSummary = "错误：无",
            uiSignals = _uiState.value.uiSignals.copy(
                fallbackActive = false,
                fallbackFromProviderId = null,
                fallbackToProviderId = null,
            ),
        )
        pushLog("图像来源已选择 provider_id=${provider.id}")
    }

    fun prepareOutdoorRoute(venueId: String, targetPoiId: String) {
        val state = stateMachine.onOutdoorRouteReady()
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "室外：手动路线已就绪 | venue_id=${venueId.trim()} target_poi_id=${targetPoiId.trim()} entrance=manual_entry",
            requestSummary = "室外算路：手动兜底已就绪",
            errorSummary = "错误：无",
        )
        pushLog("室外路线已就绪 state_after=$state venue_id=${venueId.trim()} target_poi_id=${targetPoiId.trim()} source=manual_fallback")
    }

    fun onAmapOutdoorRouteCalculating(mode: String, start: String, end: String) {
        _uiState.value = _uiState.value.copy(
            navState = stateMachine.onOutdoorReady(),
            outdoorSummary = "当前状态：室外算路中\n方式=$mode 起点=$start 终点=$end",
            requestSummary = "高德${mode}算路：计算中 start=$start end=$end",
            errorSummary = "错误：无",
        )
        pushLog("高德${mode}算路开始 start=$start end=$end")
    }

    fun onAmapOutdoorRouteReady(summary: String) {
        val state = stateMachine.onOutdoorRouteReady()
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "当前状态：室外路线已就绪\n$summary",
            requestSummary = "高德室外算路：已就绪",
            errorSummary = "错误：无",
        )
        pushLog("高德室外路线已就绪 state_after=$state summary=$summary")
    }

    fun onAmapOutdoorNavigationStarted(type: String, gpsStarted: Boolean) {
        val state = stateMachine.onOutdoorNavigating()
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "当前状态：室外导航中\n高德 $type 导航运行中 gpsStarted=$gpsStarted",
            requestSummary = "高德导航：已开始 type=$type gpsStarted=$gpsStarted",
            errorSummary = "错误：无",
        )
        pushLog("高德导航已开始 state_after=$state type=$type gps_started=$gpsStarted")
    }

    fun onAmapExternalNavigationStarted(
        entranceName: String,
        entrancePoint: String,
        mode: String,
        launchedUri: String?,
        installed: Boolean,
    ) {
        val state = stateMachine.onOutdoorNavigating()
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "当前状态：室外导航中\n已跳转高德地图 App 前往入口：$entranceName",
            requestSummary = "外部高德导航：已启动 mode=$mode installed=$installed entrance=$entrancePoint",
            errorSummary = "错误：无",
        )
        pushLog("外部高德导航启动成功 state_after=$state mode=$mode installed=$installed entrance_name=$entranceName entrance=$entrancePoint uri=${launchedUri.orDash()}")
    }

    fun onAmapExternalNavigationFailed(
        entranceName: String,
        entrancePoint: String,
        mode: String,
        installed: Boolean,
        reason: String,
    ) {
        _uiState.value = _uiState.value.copy(
            navState = stateMachine.onError(),
            outdoorSummary = "当前状态：外部高德导航不可用\n$reason",
            requestSummary = "外部高德导航：失败 mode=$mode installed=$installed entrance=$entrancePoint",
            errorSummary = "$reason；可使用手动进入场馆继续室内演示",
        )
        pushLog("外部高德导航启动失败 mode=$mode installed=$installed entrance_name=$entranceName entrance=$entrancePoint reason=$reason")
    }

    fun onAmapExternalNavigationReturnChecking(entranceName: String, entrancePoint: String) {
        _uiState.value = _uiState.value.copy(
            requestSummary = "外部高德导航：用户返回 App，正在检查入口距离 entrance=$entrancePoint",
            errorSummary = "错误：无",
        )
        pushLog("外部高德导航返回检查开始 entrance_name=$entranceName entrance=$entrancePoint")
    }

    fun onAmapExternalNavigationReturnNear(distanceMeters: Float, thresholdMeters: Float) {
        val state = stateMachine.onEntryHandoffPending()
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "室外：用户已返回 App，距离入口约 ${distanceMeters.toInt()}m，等待手动确认交接",
            requestSummary = "入口交接：距离入口 <= ${thresholdMeters.toInt()}m，请点击“我已到入口，开始室内导航”",
            errorSummary = "错误：无",
        )
        pushLog("外部高德导航返回检查通过 state_after=$state distance_m=${distanceMeters.toInt()} threshold_m=${thresholdMeters.toInt()}")
    }

    fun onAmapExternalNavigationReturnFar(distanceMeters: Float, thresholdMeters: Float) {
        _uiState.value = _uiState.value.copy(
            outdoorSummary = "当前状态：室外导航中\n用户已返回 App，距离入口约 ${distanceMeters.toInt()}m，可继续高德导航或手动确认到达",
            requestSummary = "外部高德导航：距离入口 ${distanceMeters.toInt()}m > ${thresholdMeters.toInt()}m",
            errorSummary = "错误：无",
        )
        pushLog("外部高德导航返回距离较远 distance_m=${distanceMeters.toInt()} threshold_m=${thresholdMeters.toInt()}")
    }

    fun onAmapExternalNavigationReturnLocationFailed(reason: String) {
        _uiState.value = _uiState.value.copy(
            outdoorSummary = "当前状态：室外导航中\n用户已返回 App，但无法确认入口距离，可手动确认到达",
            requestSummary = "外部高德导航：返回后定位失败，保留手动交接入口",
            errorSummary = "$reason；可点击“我已到入口，开始室内导航”",
        )
        pushLog("外部高德导航返回定位失败 reason=$reason")
    }

    fun onAmapExternalNavigationCanceled() {
        val state = stateMachine.onOutdoorRouteReady()
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "当前状态：外部高德导航已退出\n可重新选择内嵌或外部导航",
            requestSummary = "外部高德导航：用户在 App 内退出会话",
            errorSummary = "错误：无",
        )
        pushLog("外部高德导航会话已退出 state_after=$state")
    }

    fun onAmapOutdoorNaviInfo(summary: String) {
        val current = _uiState.value
        val outdoorSummary = if (current.navState == NavState.OUTDOOR_NAVIGATING) {
            "当前状态：室外导航中\n$summary"
        } else {
            current.outdoorSummary
        }
        _uiState.value = current.copy(
            outdoorSummary = outdoorSummary,
            requestSummary = "高德导航：$summary",
        )
        pushLog("高德导航信息 $summary")
    }

    fun onAmapOutdoorNaviEvent(summary: String) {
        val shouldShowOnOutdoorPanel = summary.startsWith("高德内置设置") ||
            summary.startsWith("高德路线全览") ||
            summary.startsWith("高德偏航") ||
            summary.startsWith("高德地图模式") ||
            summary.startsWith("高德视角")
        val current = _uiState.value
        val isTerminalProblemState = current.navState == NavState.ERROR || current.navState == NavState.ABORTED
        _uiState.value = current.copy(
            outdoorSummary = if (shouldShowOnOutdoorPanel && !isTerminalProblemState) {
                "室外：$summary"
            } else {
                current.outdoorSummary
            },
            requestSummary = if (isTerminalProblemState) {
                current.requestSummary
            } else {
                "高德导航事件：$summary"
            },
        )
        pushLog("高德导航事件 summary=$summary")
    }

    fun onIndoorBasemapChanged(model: IndoorBasemapUiModel) {
        if (_uiState.value.indoorBasemap == model) {
            return
        }
        _uiState.value = _uiState.value.copy(indoorBasemap = model)
        pushLog(
            "高德室内底图 status=${model.statusSummary} " +
                "available=${model.available} active_poiid=${model.activePoiId.orDash()} " +
                "active_floor=${model.activeFloorName.orDash()} expected_poiid=${model.expectedPoiId.orDash()}",
        )
    }

    fun onAmapOutdoorNavigationCanceled() {
        val state = stateMachine.onOutdoorReady()
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "当前状态：室外导航已退出\n可重新搜索或准备路线",
            requestSummary = "高德导航：用户点击退出，已清空当前导航路线",
            errorSummary = "错误：无",
        )
        pushLog("高德导航已退出 state_after=$state")
    }

    fun onAmapCurrentLocationRequesting() {
        _uiState.value = _uiState.value.copy(
            requestSummary = "高德定位：正在获取当前室外起点",
            errorSummary = "错误：无",
        )
        pushLog("高德定位开始")
    }

    fun onAmapCurrentLocationReady(summary: String) {
        _uiState.value = _uiState.value.copy(
            requestSummary = "高德定位：成功 $summary",
            errorSummary = "错误：无",
        )
        pushLog("高德定位成功 summary=$summary")
    }

    fun onAmapPoiSearchStarted(keyword: String, city: String, around: String?) {
        _uiState.value = _uiState.value.copy(
            requestSummary = "高德地点搜索：keyword=${keyword.trim().orDash()} city=${city.trim().orDash()} around=${around.orDash()}",
            errorSummary = "错误：无",
        )
        pushLog("高德地点搜索开始 keyword=${keyword.trim().orDash()} city=${city.trim().orDash()} around=${around.orDash()}")
    }

    fun onAmapPoiSearchResult(count: Int, firstLabel: String?) {
        _uiState.value = _uiState.value.copy(
            requestSummary = "高德地点搜索：resultCount=$count first=${firstLabel.orDash()}",
            errorSummary = if (count > 0) "错误：无" else "高德地点搜索结果为空",
        )
        pushLog("高德地点搜索结果 count=$count first=${firstLabel.orDash()}")
    }

    fun onAmapPoiSelected(label: String) {
        _uiState.value = _uiState.value.copy(
            requestSummary = "高德地点已选择：$label",
            errorSummary = "错误：无",
        )
        pushLog("高德地点已选择 label=$label")
    }

    fun onAmapOutdoorArriveDestination() {
        _uiState.value = _uiState.value.copy(
            navState = stateMachine.onEntryHandoffPending(),
            outdoorSummary = "室外：高德已到达场馆入口，等待交接",
            requestSummary = "高德导航：已到达终点，请点击“进入场馆”继续室内流程",
            errorSummary = "错误：无",
        )
        pushLog("高德到达终点 state_after=${_uiState.value.navState}")
    }

    fun onAmapOutdoorError(summary: String) {
        val displaySummary = "$summary；可尝试“用高德地图导航到入口”或手动进入场馆"
        _uiState.value = _uiState.value.copy(
            navState = stateMachine.onError(),
            outdoorSummary = "当前状态：室外错误\n$displaySummary",
            requestSummary = displaySummary,
            errorSummary = displaySummary,
        )
        pushLog("高德错误 summary=$summary external_fallback_available=true")
    }

    fun onAmapLocationPermissionDenied(summary: String) {
        val current = _uiState.value
        _uiState.value = current.copy(
            outdoorSummary = "当前状态：等待定位授权\n$summary",
            requestSummary = "定位权限：$summary",
            errorSummary = "警告：$summary",
        )
        pushLog("定位权限未授予 summary=$summary")
    }

    fun onAmapOutdoorRouteFailedWithSelection(summary: String) {
        val state = stateMachine.onEntryHandoffPending()
        val displaySummary = "$summary；请使用高德 App 导航到入口，到达后点击“进入场馆”继续室内导航"
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "当前状态：等待入口交接\n$displaySummary",
            requestSummary = displaySummary,
            errorSummary = "错误：无",
        )
        pushLog("高德算路失败转入外部导航提示 state_after=$state summary=$summary")
    }

    fun startOutdoorNavigation() {
        val state = stateMachine.onOutdoorNavigating()
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "当前状态：室外导航中\n手动导航正在前往场馆入口",
            requestSummary = "室外导航：手动兜底运行中，骑手到达入口后点击“进入场馆”",
            errorSummary = "错误：无",
        )
        pushLog("室外导航已开始 state_after=$state source=manual_fallback")
    }

    fun enterVenue() {
        viewModelScope.launch {
            val pendingState = stateMachine.onEntryHandoffPending()
            _uiState.value = _uiState.value.copy(
                navState = pendingState,
                outdoorSummary = "室外：骑手已到达模拟入口，等待交接",
                requestSummary = "入口交接：正在加载室内手动演示脚本",
                errorSummary = "错误：无",
            )
            pushLog("入口交接等待中 state_after=$pendingState trigger=manual_enter_venue")
            delay(250)
            val result = manualIndoorDemoController.start()
            applyManualIndoorDemoResult(
                result = result,
                navState = stateMachine.onIndoorRouteReady(),
                requestSummary = "室内手动演示：脚本已加载，按提示使用方向键推进",
            )
        }
    }

    fun enterConferenceIndoorMode(floorId: String, venueId: String, targetPoiId: String) {
        val state = stateMachine.onIndoorReady()
        val cleanTargetPoiId = targetPoiId.trim()
        _uiState.value = _uiState.value.copy(
            navState = state,
            indoorMode = IndoorNavigationMode.CLOUD_RELOCALIZATION,
            lastFloorId = floorId,
            lastLocalizationConfidence = null,
            outdoorSummary = "室外导航：会场演示模式已屏蔽",
            localizationSummary = "定位：等待 Rokid 眼镜图传",
            routeSummary = if (cleanTargetPoiId.isBlank()) {
                "路径：未开始导航，请搜索展台号"
            } else {
                "路径：等待定位后规划到 $cleanTargetPoiId"
            },
            requestSummary = "会场室内导航已准备",
            errorSummary = "错误：无",
        )
        pushLog("conference_indoor_mode_ready state_after=$state venue_id=$venueId floor_id=$floorId target_poi_id=${cleanTargetPoiId.orDash()}")
    }

    fun updateConferenceWalkDemoPosition(
        floorId: String,
        x: Double,
        y: Double,
        routeSummary: String,
    ) {
        val current = _uiState.value
        _uiState.value = current.copy(
            navState = stateMachine.onIndoorRouteReady(),
            lastLocalizationStatus = "ok",
            lastFloorId = floorId,
            lastPositionX = x,
            lastPositionY = y,
            lastLocalizationConfidence = 1.0,
            lastMatchedLandmarkPoiId = null,
            lastMatchedLandmarkDisplayName = null,
            localizationSummary = "模拟步行：x=${x.formatOneDecimal()}, y=${y.formatOneDecimal()}",
            routeSummary = routeSummary,
            requestSummary = "模拟步行：正在播放室内导航",
            errorSummary = "错误：无",
            lowConfidenceSummary = "低置信度：无",
            uiSignals = current.uiSignals.copy(lowConfidenceActive = false),
        )
    }

    fun onRokidHttpAutoStreamEvent(summary: String) {
        val displaySummary = when {
            summary.contains("discover_started") -> "Rokid 图传：正在查找眼镜"
            summary.contains("discover_found") -> "Rokid 图传：已发现眼镜，正在连接"
            summary.contains("stream_started") -> "Rokid 图传：正在接收画面"
            summary.contains("stream_frame") -> "Rokid 图传：画面已更新"
            summary.contains("stream_finished") -> "Rokid 图传：已断开，正在重连"
            summary.contains("stream_error") -> "Rokid 图传：连接失败，请检查眼镜端和同一 Wi‑Fi/热点"
            else -> "Rokid 图传：状态更新"
        }
        _uiState.value = _uiState.value.copy(
            providerSummary = "图像来源：Rokid 眼镜图传",
            requestSummary = displaySummary,
            errorSummary = if (summary.contains("error", ignoreCase = true)) {
                "警告：$displaySummary"
            } else {
                "错误：无"
            },
        )
        pushLog(summary)
    }

    fun useManualIndoorDemoScript(script: ManualIndoorDemoScript) {
        manualIndoorDemoController = ManualIndoorDemoController(script)
        val state = manualIndoorDemoController.state()
        _uiState.value = _uiState.value.copy(
            indoorMode = IndoorNavigationMode.MANUAL_DEMO,
            manualIndoorDemo = state,
            lastFloorId = state.currentFloorId,
            lastPositionX = state.current.x,
            lastPositionY = state.current.y,
            lastLocalizationConfidence = null,
            routeSummary = "室内手动演示：已绑定当前规划路线 ${state.currentStepNumber}/${state.totalSteps}",
            errorSummary = "错误：无",
        )
        pushLog("manual_demo_route_bound route_id=${script.routeId} target_poi_id=${script.targetPoiId} steps=${script.steps.size}")
    }

    fun exitIndoor() {
        val demoState = manualIndoorDemoController.reset().state
        val state = stateMachine.onOutdoorReady()
        _uiState.value = _uiState.value.copy(
            navState = state,
            outdoorSummary = "室外：已从室内模式返回，可重新算路",
            requestSummary = "退出室内：手动退出完成",
            manualIndoorDemo = demoState,
            lowConfidenceSummary = "低置信度：无",
            errorSummary = "错误：无",
            uiSignals = _uiState.value.uiSignals.copy(lowConfidenceActive = false),
        )
        pushLog("退出室内 state_after=$state trigger=manual_exit_indoor")
    }

    fun handleManualIndoorAction(action: ManualIndoorDemoAction) {
        val result = manualIndoorDemoController.handle(action)
        applyManualIndoorDemoResult(
            result = result,
            navState = stateMachine.onIndoorRouteReady(),
            requestSummary = "室内手动演示：${result.state.instruction}",
        )
    }

    fun resetManualIndoorDemo() {
        applyManualIndoorDemoResult(
            result = manualIndoorDemoController.reset(),
            navState = stateMachine.onIndoorRouteReady(),
            requestSummary = "室内手动演示：已重置到起点",
        )
    }

    fun abortDemo(reason: String) {
        val state = stateMachine.onAbort()
        _uiState.value = _uiState.value.copy(
            navState = state,
            requestSummary = "演示已中止",
            errorSummary = "已中止：$reason",
        )
        pushLog("Demo 已中止 state_after=$state reason=$reason")
    }

    fun checkHealth(baseUrl: String) {
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                requestSummary = "健康检查：正在检查 ${baseUrl.trim()}",
                errorSummary = "错误：无",
            )
            runCatching {
                apiClient.health(baseUrl.trim())
            }.onSuccess { result ->
                val elapsedMs = elapsedSince(startedAt)
                val pcSummary = formatPcBackendSummary(baseUrl.trim(), result.serviceMode, result.recognitionMode, result.venueId, result.algorithmBackendSummary)
                _uiState.value = _uiState.value.copy(
                    pcBackendSummary = pcSummary,
                    pcBackendConnected = true,
                    serviceSummary = "服务：status=${result.status}, mode=${result.serviceMode.orDash()}, recognition=${result.recognitionMode.orDash()}, venue=${result.venueId.orDash()}, landmarks=${result.landmarkCount.orDash()}, backend=${result.algorithmBackendSummary.orDash()}",
                    requestSummary = "健康检查：成功 requestId=${result.requestId} clientMs=${elapsedMs}",
                    errorSummary = "错误：无",
                )
                pushLog("健康检查成功 request_id=${result.requestId} status=${result.status} service_mode=${result.serviceMode.orDash()} recognition_mode=${result.recognitionMode.orDash()} venue_id=${result.venueId.orDash()} landmark_count=${result.landmarkCount.orDash()} client_ms=$elapsedMs")
            }.onFailure { throwable ->
                val elapsedMs = elapsedSince(startedAt)
                val error = formatFailure("健康检查", throwable) + "；请确认手机和 PC 同一 Wi‑Fi、baseUrl 使用 http://PC局域网IP:8000、后台已启动且防火墙放行"
                _uiState.value = _uiState.value.copy(
                    navState = stateMachine.onError(),
                    pcBackendSummary = "PC后台：连接失败 base_url=${baseUrl.trim().orDash()}",
                    pcBackendConnected = false,
                    serviceSummary = error,
                    requestSummary = "健康检查：失败 clientMs=$elapsedMs",
                    errorSummary = error,
                )
                pushFailureLog("健康检查失败", throwable, elapsedMs)
            }
        }
    }

    fun pairPcBackend(
        pairingUrl: String,
        onConnected: (PcBackendPairingConnectionResult) -> Unit,
    ) {
        viewModelScope.launch {
            val cleanUrl = pairingUrl.trim()
            val startedAt = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                pcBackendSummary = "PC后台：正在配对",
                pcBackendConnected = false,
                requestSummary = "PC后台配对：正在读取二维码",
                errorSummary = "错误：无",
            )
            runCatching {
                apiClient.pairPcBackend(cleanUrl)
            }.onSuccess { result ->
                val elapsedMs = elapsedSince(startedAt)
                val pairing = result.pairing
                val health = result.health
                val venueId = pairing.venueId ?: health.venueId
                val serviceMode = health.serviceMode ?: pairing.serviceMode
                val recognitionMode = health.recognitionMode ?: pairing.recognitionMode
                _uiState.value = _uiState.value.copy(
                    pcBackendSummary = formatPcBackendSummary(result.selectedBaseUrl, serviceMode, recognitionMode, venueId, health.algorithmBackendSummary),
                    pcBackendConnected = true,
                    serviceSummary = "服务：PC后台已连接 base_url=${result.selectedBaseUrl}, mode=${serviceMode.orDash()}, recognition=${recognitionMode.orDash()}, venue=${venueId.orDash()}, backend=${health.algorithmBackendSummary.orDash()}",
                    requestSummary = "PC后台配对：成功 clientMs=$elapsedMs",
                    errorSummary = "错误：无",
                )
                pushLog("PC后台配对成功 pairing_url=$cleanUrl base_url=${result.selectedBaseUrl} venue_id=${venueId.orDash()} service_mode=${serviceMode.orDash()} recognition_mode=${recognitionMode.orDash()} preferred_capture_mode=${pairing.preferredCaptureMode.orDash()} client_ms=$elapsedMs")
                onConnected(result)
            }.onFailure { throwable ->
                val elapsedMs = elapsedSince(startedAt)
                val error = formatFailure("PC后台配对", throwable) + "；请确认手机和 PC 同一 Wi‑Fi、PC 后台已启动、Windows 防火墙已放行、手机浏览器可打开 health URL"
                _uiState.value = _uiState.value.copy(
                    pcBackendSummary = "PC后台：配对失败",
                    pcBackendConnected = false,
                    serviceSummary = error,
                    requestSummary = "PC后台配对：失败 clientMs=$elapsedMs",
                    errorSummary = error,
                )
                pushFailureLog("PC后台配对失败", throwable, elapsedMs)
            }
        }
    }

    fun onPcBackendPairingInvalid(reason: String) {
        val error = "PC后台配对失败：$reason"
        _uiState.value = _uiState.value.copy(
            pcBackendSummary = "PC后台：配对失败",
            pcBackendConnected = false,
            serviceSummary = error,
            requestSummary = "PC后台配对：二维码无效",
            errorSummary = error,
        )
        pushLog(error)
    }

    fun loadVenueMeta(baseUrl: String, venueId: String) {
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                requestSummary = "场馆元数据：正在加载 venue_id=${venueId.trim()}",
                errorSummary = "错误：无",
            )
            runCatching {
                apiClient.venueMeta(baseUrl.trim(), venueId.trim())
            }.onSuccess { result ->
                val elapsedMs = elapsedSince(startedAt)
                _uiState.value = _uiState.value.copy(
                    venueSummary = "场馆：${result.venueName} | default=${result.defaultFloorId} | floors=${result.supportedFloors.joinToString()} | pois=${result.targetPoiCount}",
                    requestSummary = "场馆元数据：成功 requestId=${result.requestId} clientMs=${elapsedMs}",
                    errorSummary = "错误：无",
                    exhibitionRouteNodes = result.routeNodes.associate { node ->
                        node.nodeId to ExhibitionRouteNode(
                            nodeId = node.nodeId,
                            floorId = node.floorId,
                            x = node.x,
                            y = node.y,
                            nodeType = node.nodeType,
                            refId = node.refId,
                        )
                    },
                    exhibitionPois = result.pois.associate { poi ->
                        poi.poiId to ExhibitionPoi(
                            poiId = poi.poiId,
                            displayName = poi.displayName,
                            floorId = poi.floorId,
                            x = poi.x,
                            y = poi.y,
                            routeNodeId = poi.routeNodeId,
                        )
                    },
                )
                pushLog("场馆元数据成功 request_id=${result.requestId} venue_id=${result.venueId} default_floor=${result.defaultFloorId} floors=${result.supportedFloors.joinToString()} pois=${result.pois.size} route_nodes=${result.routeNodes.size} client_ms=$elapsedMs")
            }.onFailure { throwable ->
                val elapsedMs = elapsedSince(startedAt)
                val error = formatFailure("场馆元数据", throwable)
                _uiState.value = _uiState.value.copy(
                    navState = stateMachine.onError(),
                    venueSummary = error,
                    requestSummary = "场馆元数据：失败 clientMs=$elapsedMs",
                    errorSummary = error,
                )
                pushFailureLog("场馆元数据失败", throwable, elapsedMs)
            }
        }
    }

    fun connectMock() {
        viewModelScope.launch {
            pushLog("设备连接开始 connector=mock")
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.CONNECTING,
                requestSummary = "设备连接：正在连接 mock",
            )
            val state = connector.connect()
            _uiState.value = _uiState.value.copy(
                connectionState = state,
                requestSummary = "设备连接：state=$state",
                errorSummary = "错误：无",
            )
            pushLog("设备连接状态 connector=mock state=$state")
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            val state = connector.disconnect()
            _uiState.value = _uiState.value.copy(
                connectionState = state,
                requestSummary = "设备连接：state=$state",
            )
            pushLog("设备连接状态 connector=mock state=$state")
        }
    }

    fun fallbackSelectedProvider(reason: String) {
        fallbackSelectedProviderInternal(reason)
    }

    fun simulateLowConfidence() {
        val state = stateMachine.onLocalizationStatus("low_confidence", hasStablePosition = false)
        _uiState.value = _uiState.value.copy(
            navState = state,
            localizationSummary = "定位：status=low_confidence, confidence=0.42, action=request_more_images, source=mock",
            lowConfidenceSummary = "低置信度：已模拟，暂停强转向提示",
            requestSummary = "调试：已强制进入低置信度分支",
            errorSummary = "警告：已模拟 low_confidence",
            lastLocalizationStatus = "low_confidence",
            lastLocalizationConfidence = 0.42,
            uiSignals = _uiState.value.uiSignals.copy(lowConfidenceActive = true),
        )
        pushLog("调试低置信度已触发 state_after=$state confidence=0.42 suggested_action=request_more_images")
    }

    fun beginPhoneCameraCapture(candidateFloorId: String?) {
        ensureIndoorReadyForCapture()
        _uiState.value = _uiState.value.copy(
            selectedProviderId = PHONE_PROVIDER_ID,
            navState = stateMachine.onIndoorCaptureStarted(),
            providerSummary = providerSummary(PHONE_PROVIDER_ID),
            requestSummary = "手机摄像头：等待图像 candidateFloor=${candidateFloorId.orDash()}",
            errorSummary = "错误：无",
            uiSignals = _uiState.value.uiSignals.copy(lowConfidenceActive = false),
        )
        pushLog("手机摄像头采图开始 state_after=${_uiState.value.navState} provider_id=$PHONE_PROVIDER_ID candidate_floor_id=${candidateFloorId.orDash()}")
    }

    fun onPhoneCaptureFailed(reason: String) {
        val error = "图像来源失败 provider=$PHONE_PROVIDER_ID message=$reason"
        _uiState.value = _uiState.value.copy(
            navState = stateMachine.onError(),
            requestSummary = "手机摄像头：失败",
            errorSummary = error,
        )
        pushLog("手机摄像头失败 state_after=${_uiState.value.navState} provider_id=$PHONE_PROVIDER_ID message=$reason")
    }

    fun captureAndLocate(
        baseUrl: String,
        venueId: String,
        candidateFloorId: String?,
        targetPoiId: String?,
        debugTarget: String?,
        allowProviderFallback: Boolean = true,
        quietUi: Boolean = false,
        onComplete: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            try {
                val provider = providers[_uiState.value.selectedProviderId] ?: return@launch
                ensureIndoorReadyForCapture()
                val stateBefore = _uiState.value.navState
                val captureStartedAt = System.currentTimeMillis()
                if (!quietUi) {
                    _uiState.value = _uiState.value.copy(
                        navState = stateMachine.onIndoorCaptureStarted(),
                        providerSummary = providerSummary(provider.id),
                        requestSummary = "采图：正在获取眼镜画面",
                        errorSummary = "错误：无",
                        uiSignals = _uiState.value.uiSignals.copy(lowConfidenceActive = false),
                    )
                }
                if (!quietUi) {
                    pushLog("采图开始 state_before=$stateBefore state_after=${_uiState.value.navState} provider_id=${provider.id} capture_mode=${provider.id} candidate_floor_id=${candidateFloorId.orDash()} target_poi_id=${targetPoiId.orDash()}")
                }
                val frame = try {
                    provider.capture(candidateFloorId?.trim()?.ifBlank { null })
                } catch (throwable: Throwable) {
                    handleCaptureFailure(provider.id, throwable, allowProviderFallback)
                    return@launch
                }
                val captureMs = elapsedSince(captureStartedAt)
                _uiState.value = _uiState.value.copy(lastSuccessfulProviderId = frame.providerId)
                if (!quietUi) {
                    pushLog("采图成功 provider_id=${frame.providerId} capture_id=${frame.captureId.orDash()} capture_mode=${frame.captureMode ?: frame.providerId} capture_ms=$captureMs image_bytes=${frame.bytes.size} image_size=${frame.width.orDash()}x${frame.height.orDash()} imu_at_capture=${frame.imuAtCapture?.summary() ?: "none"}")
                }
                locateFrame(
                    baseUrl = baseUrl,
                    venueId = venueId,
                    targetPoiId = targetPoiId,
                    debugTarget = debugTarget,
                    frame = frame,
                    previousCaptureMs = captureMs,
                    quietUi = quietUi,
                )
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun locateCapturedFrame(
        baseUrl: String,
        venueId: String,
        targetPoiId: String?,
        debugTarget: String?,
        frame: CapturedFrame,
        quietUi: Boolean = false,
    ) {
        viewModelScope.launch {
            pushLog("采图成功 provider_id=${frame.providerId} capture_id=${frame.captureId.orDash()} capture_mode=${frame.captureMode ?: frame.providerId} capture_ms=phone_camera image_bytes=${frame.bytes.size} image_size=${frame.width.orDash()}x${frame.height.orDash()} imu_at_capture=${frame.imuAtCapture?.summary() ?: "none"}")
            locateFrame(
                baseUrl = baseUrl,
                venueId = venueId,
                targetPoiId = targetPoiId,
                debugTarget = debugTarget,
                frame = frame,
                previousCaptureMs = null,
                quietUi = quietUi,
            )
        }
    }

    fun requestRoute(baseUrl: String, venueId: String, targetPoiId: String) {
        val state = _uiState.value
        val floorId = state.lastFloorId
        val x = state.lastPositionX
        val y = state.lastPositionY
        if (state.lastLocalizationStatus != "ok" || floorId == null || x == null || y == null) {
            val error = "路径规划被拦截：需要先获得 status=ok 的定位结果"
            _uiState.value = state.copy(
                navState = stateMachine.onError(),
                routeSummary = error,
                errorSummary = error,
            )
            pushLog("路径规划被拦截 reason=missing_stable_localization last_status=${state.lastLocalizationStatus.orDash()}")
            return
        }
        viewModelScope.launch {
            requestRouteFromPosition(
                baseUrl = baseUrl,
                venueId = venueId,
                floorId = floorId,
                x = x,
                y = y,
                targetPoiId = targetPoiId,
                trigger = "manual",
            )
        }
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(logs = emptyList())
    }

    fun fullSessionLog(): String {
        return sessionLogs.joinToString(separator = "\n")
    }

    private suspend fun locateFrame(
        baseUrl: String,
        venueId: String,
        targetPoiId: String?,
        debugTarget: String?,
        frame: CapturedFrame,
        previousCaptureMs: Long?,
        quietUi: Boolean,
    ) {
        val locateStartedAt = System.currentTimeMillis()
        val stateBefore = _uiState.value.navState
        if (!quietUi) {
            _uiState.value = _uiState.value.copy(
                navState = stateMachine.onIndoorLocateStarted(),
                providerSummary = providerSummary(frame.providerId),
                requestSummary = "定位：正在识别眼镜画面",
                errorSummary = "错误：无",
            )
        }
        if (!quietUi) {
            pushLog("定位开始 state_before=$stateBefore state_after=${_uiState.value.navState} provider_id=${frame.providerId} capture_id=${frame.captureId.orDash()} capture_mode=${frame.captureMode ?: frame.providerId} candidate_floor_id=${frame.candidateFloorId.orDash()} target_poi_id=${targetPoiId.orDash()} debug_target=${debugTarget.orDash()} capture_ms=${previousCaptureMs?.toString() ?: "phone_camera"} imu_at_capture=${frame.imuAtCapture?.summary() ?: "none"}")
        }
        runCatching {
            apiClient.locate(
                baseUrl = baseUrl.trim(),
                venueId = venueId.trim(),
                frame = frame,
                targetPoiId = targetPoiId?.trim()?.ifBlank { null },
                debugTarget = debugTarget?.trim()?.ifBlank { null },
            )
        }.onSuccess { result ->
            val elapsedMs = elapsedSince(locateStartedAt)
            val hasStablePosition = result.floorId != null && result.x != null && result.y != null
            val isStableLocation = result.status == "ok" && hasStablePosition
            val current = _uiState.value
            val previousLocalizationStatus = current.lastLocalizationStatus
            val quietFailure = quietUi && !isStableLocation
            val nextState = if (quietFailure) {
                current.navState
            } else {
                stateMachine.onLocalizationStatus(result.status, hasStablePosition)
            }
            val displayState = if (quietUi) current.navState else nextState
            val matchedLandmarkForLog = result.matchedLandmarkDisplayName?.let { displayName ->
                "$displayName(${result.matchedLandmarkPoiId.orDash()})"
            } ?: "-"
            _uiState.value = current.copy(
                navState = displayState,
                localizationSummary = if (quietFailure) {
                    current.localizationSummary
                } else {
                    localizationDisplaySummary(result)
                },
                requestSummary = if (quietUi) {
                    current.requestSummary
                } else if (result.status == "ok") {
                    "定位：已更新（${elapsedMs}ms）"
                } else {
                    "定位：${localizationStatusLabel(result.status)}（${elapsedMs}ms）"
                },
                lowConfidenceSummary = if (quietUi) {
                    current.lowConfidenceSummary
                } else if (result.status == "low_confidence") {
                    "低置信度：请重新面向地标拍摄"
                } else {
                    "低置信度：无"
                },
                errorSummary = if (quietUi) current.errorSummary else localizationErrorSummary(result.status),
                lastLocalizationStatus = result.status,
                lastFloorId = if (isStableLocation) result.floorId else current.lastFloorId,
                lastPositionX = if (isStableLocation) result.x else current.lastPositionX,
                lastPositionY = if (isStableLocation) result.y else current.lastPositionY,
                lastLocalizationConfidence = result.confidence,
                lastMatchedLandmarkPoiId = if (isStableLocation) result.matchedLandmarkPoiId else current.lastMatchedLandmarkPoiId,
                lastMatchedLandmarkDisplayName = if (isStableLocation) result.matchedLandmarkDisplayName else current.lastMatchedLandmarkDisplayName,
                uiSignals = if (quietUi) {
                    current.uiSignals
                } else {
                    current.uiSignals.copy(lowConfidenceActive = result.status == "low_confidence")
                },
            )
            if (!quietUi || (result.status != "ok" && previousLocalizationStatus != result.status)) {
                pushLog("定位结果 request_id=${result.requestId} state_before=$stateBefore state_after=$nextState provider_id=${frame.providerId} capture_id=${frame.captureId.orDash()} capture_mode=${frame.captureMode ?: frame.providerId} status=${result.status} floor_id=${result.floorId.orDash()} x=${result.x.orDash()} y=${result.y.orDash()} confidence=${result.confidence} landmark=${matchedLandmarkForLog} heading_map_deg=${result.headingMapHeadingDeg.orDash()} heading_source=${result.headingSource.orDash()} latency_ms=${result.latencyMs} client_ms=$elapsedMs failure_stage=${result.failureStage.orDash()} suggested_action=${result.suggestedAction.orDash()}")
            }
            if (isStableLocation) {
                updateHeadingAnchorIfPossible(venueId, result, frame)
            } else if (!quietUi && (result.status == "low_confidence" || result.status == "not_found")) {
                publishLocalizationWarningHud(result, targetPoiId)
            }
            if (isStableLocation && !targetPoiId.isNullOrBlank()) {
                requestRouteFromPosition(
                    baseUrl = baseUrl,
                    venueId = venueId,
                    floorId = result.floorId!!,
                    x = result.x!!,
                    y = result.y!!,
                    targetPoiId = targetPoiId,
                    trigger = "auto_after_locate",
                )
            }
        }.onFailure { throwable ->
            val elapsedMs = elapsedSince(locateStartedAt)
            val error = formatFailure("定位", throwable)
            if (quietUi) {
                val current = _uiState.value
                if (current.lastLocalizationStatus != "error") {
                    _uiState.value = current.copy(lastLocalizationStatus = "error", lastLocalizationConfidence = null)
                    pushFailureLog("后台自动定位失败", throwable, elapsedMs)
                }
                return
            }
            _uiState.value = _uiState.value.copy(
                navState = stateMachine.onError(),
                localizationSummary = error,
                requestSummary = "定位：失败 clientMs=$elapsedMs",
                errorSummary = error,
                lastLocalizationStatus = "error",
                lastLocalizationConfidence = null,
                uiSignals = _uiState.value.uiSignals.copy(lowConfidenceActive = false),
            )
            pushFailureLog("定位失败", throwable, elapsedMs)
        }
    }

    private fun ensureIndoorReadyForCapture() {
        if (!_uiState.value.navState.isIndoorOrHandoff()) {
            val readyState = stateMachine.onIndoorReady()
            _uiState.value = _uiState.value.copy(
                navState = readyState,
                requestSummary = "室内：直接联调采图前自动就绪",
                uiSignals = _uiState.value.uiSignals.copy(lowConfidenceActive = false),
            )
            pushLog("室内已就绪 state_after=$readyState trigger=direct_capture")
        }
    }

    private fun handleCaptureFailure(providerId: String, throwable: Throwable, allowProviderFallback: Boolean) {
        val message = throwable.message ?: throwable::class.java.simpleName
        val fallbackApplied = if (allowProviderFallback) {
            fallbackSelectedProviderInternal("采图失败 provider=$providerId message=$message")
        } else {
            false
        }
        if (!fallbackApplied) {
            val error = "图像来源失败 provider=$providerId message=$message"
            _uiState.value = _uiState.value.copy(
                navState = stateMachine.onError(),
                requestSummary = "采图：失败 provider=$providerId",
                lastFailedProviderId = providerId,
                lastProviderFailureReason = message,
                errorSummary = error,
            )
            if (!allowProviderFallback) {
                pushLog("图像来源失败且禁止降级 provider_id=$providerId message=$message")
            }
        }
    }

    private fun fallbackSelectedProviderInternal(reason: String): Boolean {
        val currentProviderId = _uiState.value.selectedProviderId
        val currentIndex = providerOrder.indexOf(currentProviderId)
        val fallbackProviderId = providerOrder.getOrNull(currentIndex + 1)
        if (fallbackProviderId == null) {
            val error = "图像来源失败 provider=$currentProviderId，且没有可用兜底来源"
            _uiState.value = _uiState.value.copy(
                navState = stateMachine.onError(),
                requestSummary = "图像来源降级：不可用",
                fallbackSummary = "降级：失败 from=$currentProviderId to=none",
                lastFailedProviderId = currentProviderId,
                lastProviderFailureReason = reason,
                errorSummary = error,
                uiSignals = _uiState.value.uiSignals.copy(
                    fallbackActive = false,
                    fallbackFromProviderId = currentProviderId,
                    fallbackToProviderId = null,
                ),
            )
            pushLog("图像来源降级失败 from=$currentProviderId reason=$reason")
            return false
        }
        _uiState.value = _uiState.value.copy(
            selectedProviderId = fallbackProviderId,
            providerSummary = providerSummary(fallbackProviderId),
            fallbackSummary = "降级：$currentProviderId -> $fallbackProviderId",
            requestSummary = "图像来源降级：$fallbackProviderId 已就绪",
            lastFailedProviderId = currentProviderId,
            lastProviderFailureReason = reason,
            errorSummary = "图像来源失败：$reason；已切换到 $fallbackProviderId",
            uiSignals = _uiState.value.uiSignals.copy(
                fallbackActive = true,
                fallbackFromProviderId = currentProviderId,
                fallbackToProviderId = fallbackProviderId,
            ),
        )
        pushLog("图像来源已降级 from=$currentProviderId to=$fallbackProviderId reason=$reason")
        return true
    }

    private fun updateHeadingAnchorIfPossible(
        venueId: String,
        result: LocalizationResult,
        frame: CapturedFrame,
    ) {
        val floorId = result.floorId ?: return
        val x = result.x ?: return
        val y = result.y ?: return
        val anchor = RokidRuntimeBridge.updateHeadingAnchor(
            venueId = venueId.trim(),
            floorId = floorId,
            x = x,
            y = y,
            landmark = result.matchedLandmarkDisplayName ?: result.matchedLandmarkPoiId,
            mapHeadingDeg = result.headingMapHeadingDeg,
            confidence = result.headingConfidence,
            imuAtCapture = frame.imuAtCapture,
        )
        if (anchor != null) {
            pushLog("heading_anchor_update ${anchor.summary()} capture_id=${frame.captureId.orDash()}")
            return
        }
        val reason = when {
            result.headingMapHeadingDeg == null -> "missing_heading_hint"
            frame.imuAtCapture == null -> "missing_fresh_rokid_imu"
            else -> "stale_rokid_imu"
        }
        pushLog("heading_anchor_skipped reason=$reason capture_id=${frame.captureId.orDash()} heading_map_deg=${result.headingMapHeadingDeg.orDash()} imu_at_capture=${frame.imuAtCapture?.summary() ?: "none"}")
    }

    private fun applyManualIndoorDemoResult(
        result: ManualIndoorDemoResult,
        navState: NavState,
        requestSummary: String,
    ) {
        val demo = result.state
        _uiState.value = _uiState.value.copy(
            navState = navState,
            indoorMode = IndoorNavigationMode.MANUAL_DEMO,
            manualIndoorDemo = demo,
            lastFloorId = demo.currentFloorId,
            lastPositionX = demo.current.x,
            lastPositionY = demo.current.y,
            lastLocalizationConfidence = null,
            outdoorSummary = "室外：已交接到室内手动演示",
            localizationSummary = "室内手动演示：${demo.currentFloorId} · ${demo.currentNodeLabel}",
            routeSummary = "室内手动演示：${demo.instruction}",
            requestSummary = requestSummary,
            errorSummary = if (demo.correction == null) "错误：无" else "纠错：${demo.correction}",
            lowConfidenceSummary = "低置信度：无",
            uiSignals = _uiState.value.uiSignals.copy(lowConfidenceActive = false),
        )
        result.events.forEach { pushLog(it) }
    }

    private fun providerSummary(providerId: String): String {
        return "图像来源：${providerDisplayLabel(providerId)}"
    }

    fun providerDisplayLabel(providerId: String): String {
        val provider = providers[providerId]
        return provider?.displayName ?: providerId
    }

    private fun localizationDisplaySummary(result: LocalizationResult): String {
        val confidence = "${(result.confidence * 100).coerceIn(0.0, 100.0).toInt()}%"
        val landmark = result.matchedLandmarkDisplayName?.takeIf { it.isNotBlank() }
        val floor = result.floorId?.takeIf { it.isNotBlank() }
        return when (result.status) {
            "ok" -> listOfNotNull(
                "定位成功",
                landmark,
                floor?.let { "楼层 $it" },
                "置信度 $confidence",
            ).joinToString(" · ")
            "low_confidence" -> "定位不够稳定，请面向测试卡或展台重拍 · 置信度 $confidence"
            "not_found" -> "未识别到有效地标，请面向测试卡或展台"
            else -> "定位失败，请检查眼镜图传和识别服务"
        }
    }

    private fun localizationStatusLabel(status: String): String {
        return when (status) {
            "ok" -> "成功"
            "low_confidence" -> "置信度低"
            "not_found" -> "未找到地标"
            else -> "失败"
        }
    }

    private fun localizationErrorSummary(status: String): String {
        return when (status) {
            "ok" -> "错误：无"
            "low_confidence" -> "识别置信度低，请重新面向地标拍摄"
            "not_found" -> "未识别到地标，请面向测试卡或展台重拍"
            else -> "定位失败，请检查眼镜图传和识别服务"
        }
    }

    private suspend fun requestRouteFromPosition(
        baseUrl: String,
        venueId: String,
        floorId: String,
        x: Double,
        y: Double,
        targetPoiId: String,
        trigger: String,
    ) {
        val cleanTargetPoiId = targetPoiId.trim()
        if (cleanTargetPoiId.isBlank()) return
        val routeStartedAt = System.currentTimeMillis()
        val stateBefore = _uiState.value.navState
        _uiState.value = _uiState.value.copy(
            navState = stateMachine.onIndoorRouteStarted(),
            requestSummary = "路径规划：正在生成路线",
            errorSummary = "错误：无",
        )
        pushLog("路径规划开始 trigger=$trigger state_before=$stateBefore state_after=${_uiState.value.navState} floor_id=$floorId x=$x y=$y target_poi_id=$cleanTargetPoiId")
        runCatching {
            apiClient.requestRoute(baseUrl.trim(), venueId.trim(), floorId, x, y, cleanTargetPoiId)
        }.onSuccess { result ->
            val elapsedMs = elapsedSince(routeStartedAt)
            val hudSent = publishRouteHud(result, floorId)
            _uiState.value = _uiState.value.copy(
                navState = stateMachine.onIndoorRouteReady(),
                routeSummary = routeSummary(result, hudSent),
                requestSummary = "路径规划：路线已生成（${elapsedMs}ms）",
                errorSummary = "错误：无",
                exhibitionActiveRouteNodeIds = result.pathNodes,
                exhibitionActiveTargetPoiId = result.targetPoiId,
            )
            pushLog("路径规划成功 trigger=$trigger request_id=${result.requestId} route_id=${result.routeId} state_after=${_uiState.value.navState} next_turn=${result.nextTurn} distance_to_next=${result.distanceToNextTurn} distance_to_target=${result.distanceToTarget} path_nodes=${result.pathNodes.joinToString("->").orDash()} hud_sent=$hudSent client_ms=$elapsedMs")
        }.onFailure { throwable ->
            val elapsedMs = elapsedSince(routeStartedAt)
            val error = formatFailure("路径规划", throwable)
            _uiState.value = _uiState.value.copy(
                navState = stateMachine.onError(),
                routeSummary = error,
                requestSummary = "路径规划：失败 clientMs=$elapsedMs",
                errorSummary = error,
            )
            pushFailureLog("路径规划失败", throwable, elapsedMs)
        }
    }

    private fun publishRouteHud(result: RouteResult, floorId: String): Boolean {
        val mapHeadingDeg = RokidRuntimeBridge.currentMapHeadingDeg()
        val remainingDurationSeconds = estimateIndoorRouteDurationSeconds(result.distanceToTarget)
        val miniMap = buildHudMiniMap(result)
        val payload = RokidHudPayload(
            directionArrow = hudArrowForTurn(result.nextTurn),
            nextAction = readableTurn(result.nextTurn),
            targetName = displayNameForTarget(result.targetPoiId),
            floorId = floorId,
            distanceToNextActionMeters = result.distanceToNextTurn,
            remainingDistanceMeters = result.distanceToTarget,
            remainingDurationSeconds = remainingDurationSeconds,
            headingState = RokidRuntimeBridge.currentHeadingState(),
            statusText = if (mapHeadingDeg != null) {
                "剩余 ${result.distanceToTarget.formatMeters()} · 约 ${remainingDurationSeconds.formatDuration()}"
            } else {
                "剩余 ${result.distanceToTarget.formatMeters()} · 约 ${remainingDurationSeconds.formatDuration()}"
            },
            miniMapRoute = miniMap.route,
            miniMapCurrent = miniMap.current,
            miniMapTarget = miniMap.target,
            mapHeadingDeg = mapHeadingDeg,
        )
        val sent = RokidRuntimeBridge.sendHudUpdate(payload)
        pushLog("hud_update request_id=${payload.requestId} sent=$sent ${payload.summary()}")
        return sent
    }

    private fun publishLocalizationWarningHud(result: LocalizationResult, targetPoiId: String?): Boolean {
        val alert = when (result.status) {
            "low_confidence" -> "定位置信度低，请面向测试卡或展台"
            "not_found" -> "未识别到地标，请重新面向展台"
            else -> "定位异常，请重新取景"
        }
        val payload = RokidHudPayload(
            directionArrow = "!",
            nextAction = "重新定位",
            targetName = targetPoiId?.let(::displayNameForTarget).orEmpty().ifBlank { "目标未确认" },
            floorId = result.floorId ?: _uiState.value.lastFloorId ?: "-",
            distanceToNextActionMeters = null,
            remainingDistanceMeters = null,
            remainingDurationSeconds = null,
            headingState = "heading_unavailable",
            statusText = alert,
            alertText = alert,
        )
        val sent = RokidRuntimeBridge.sendHudUpdate(payload)
        pushLog("hud_warning_update request_id=${payload.requestId} sent=$sent status=${result.status} ${payload.summary()}")
        return sent
    }

    private fun displayNameForTarget(targetPoiId: String): String {
        return _uiState.value.exhibitionPois[targetPoiId]?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: targetPoiId
    }

    private fun estimateIndoorRouteDurationSeconds(distanceMeters: Double): Double {
        return (distanceMeters / INDOOR_WALKING_SPEED_MPS).coerceAtLeast(1.0)
    }

    private fun buildHudMiniMap(result: RouteResult): HudMiniMapPayload {
        val state = _uiState.value
        val routePoints = result.pathNodes
            .mapNotNull { state.exhibitionRouteNodes[it] }
            .map { HudMapPoint(it.x, it.y) }
        val current = if (state.lastPositionX != null && state.lastPositionY != null) {
            HudMapPoint(state.lastPositionX, state.lastPositionY)
        } else {
            routePoints.firstOrNull()
        }
        val target = state.exhibitionPois[result.targetPoiId]
            ?.let { HudMapPoint(it.x, it.y) }
            ?: routePoints.lastOrNull()
        val allPoints = buildList {
            current?.let(::add)
            routePoints.forEach(::add)
            target?.let(::add)
        }.removeAdjacentDuplicates()
        if (allPoints.size < 2) return HudMiniMapPayload()

        val minX = allPoints.minOf { it.x }
        val maxX = allPoints.maxOf { it.x }
        val minY = allPoints.minOf { it.y }
        val maxY = allPoints.maxOf { it.y }
        val width = (maxX - minX).takeIf { it > 0.0001 } ?: 1.0
        val height = (maxY - minY).takeIf { it > 0.0001 } ?: 1.0

        fun normalize(point: HudMapPoint): HudMapPoint {
            val x = (((point.x - minX) / width) * 880.0 + 60.0).roundToInt().coerceIn(0, 1000)
            val y = (((point.y - minY) / height) * 880.0 + 60.0).roundToInt().coerceIn(0, 1000)
            return HudMapPoint(x.toDouble(), y.toDouble())
        }

        return HudMiniMapPayload(
            route = allPoints.map { normalize(it).toCommandString() }.joinToString(";"),
            current = current?.let { normalize(it).toCommandString() }.orEmpty(),
            target = target?.let { normalize(it).toCommandString() }.orEmpty(),
        )
    }

    private fun List<HudMapPoint>.removeAdjacentDuplicates(): List<HudMapPoint> {
        return fold(mutableListOf()) { points, point ->
            if (points.lastOrNull() != point) points += point
            points
        }
    }

    private fun routeSummary(result: RouteResult, hudSent: Boolean): String {
        val path = result.pathNodes.takeIf { it.isNotEmpty() }?.joinToString(" -> ") ?: "-"
        return "路径：${readableTurn(result.nextTurn)}，下一动作 ${result.distanceToNextTurn.formatMeters()}，剩余 ${result.distanceToTarget.formatMeters()}，跨楼层=${result.crossFloorRequired}，HUD=${if (hudSent) "已下发" else "待连接"}，path=$path"
    }

    private fun Double.formatOneDecimal(): String {
        return ((this * 10.0).roundToInt() / 10.0).toString()
    }

    private fun readableTurn(turn: String): String {
        return when (turn) {
            "arrive" -> "到达"
            "go_straight" -> "直行"
            "turn_left" -> "左转"
            "turn_right" -> "右转"
            "take_escalator_up" -> "上楼"
            "take_escalator_down" -> "下楼"
            else -> if (turn.isInternalRouteToken()) "直行" else turn
        }
    }

    private fun String.isInternalRouteToken(): Boolean {
        val normalized = lowercase()
        return listOf("walkable", "road_node", "route_node", "grid", "node_")
            .any { normalized.contains(it) }
    }

    private fun hudArrowForTurn(turn: String): String {
        return when (turn) {
            "arrive" -> "●"
            "turn_left" -> "←"
            "turn_right" -> "→"
            "take_escalator_up" -> "↟"
            "take_escalator_down" -> "↡"
            else -> "↑"
        }
    }

    private fun Double.formatMeters(): String {
        return if (this < 1.0) {
            "0米"
        } else {
            "${"%.1f".format(this)}米"
        }
    }

    private fun Double.formatDuration(): String {
        val totalSeconds = kotlin.math.ceil(coerceAtLeast(0.0)).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
    }

    private fun formatPcBackendSummary(
        baseUrl: String,
        serviceMode: String?,
        recognitionMode: String?,
        venueId: String?,
        backendSummary: String?,
    ): String {
        return "PC后台：已连接 base_url=${baseUrl.orDash()}, venue=${venueId.orDash()}, mode=${serviceMode.orDash()}, recognition=${recognitionMode.orDash()}, backend=${backendSummary.orDash()}"
    }

    private fun Double.formatDegrees(): String = "${"%.1f".format(this)}°"

    private fun formatFailure(scope: String, throwable: Throwable): String {
        return if (throwable is ApiClientException) {
            "$scope 失败 code=${throwable.code?.toString() ?: "-"} requestId=${throwable.requestId ?: "-"} message=${throwable.message}"
        } else {
            "$scope 失败 message=${throwable.message ?: throwable::class.java.simpleName}"
        }
    }

    private fun pushFailureLog(event: String, throwable: Throwable, elapsedMs: Long) {
        if (throwable is ApiClientException) {
            pushLog("$event error_code=${throwable.code?.toString() ?: "-"} request_id=${throwable.requestId ?: "-"} message=${throwable.message} client_ms=$elapsedMs")
        } else {
            pushLog("$event error_type=${throwable::class.java.simpleName} message=${throwable.message ?: "-"} client_ms=$elapsedMs")
        }
    }

    private fun pushLog(message: String) {
        sessionLogs += message
        val existing = _uiState.value.logs.takeLast(99)
        _uiState.value = _uiState.value.copy(logs = existing + message)
    }

    private fun elapsedSince(startedAt: Long): Long = System.currentTimeMillis() - startedAt

    private fun Any?.orDash(): String = this?.toString()?.takeIf { it.isNotBlank() } ?: "-"

    private fun NavState.isIndoorOrHandoff(): Boolean {
        return when (this) {
            NavState.ENTRY_HANDOFF_PENDING,
            NavState.INDOOR_READY,
            NavState.INDOOR_CAPTURING,
            NavState.INDOOR_LOCATING,
            NavState.INDOOR_LOW_CONFIDENCE,
            NavState.INDOOR_ROUTING,
            NavState.INDOOR_ROUTE_READY -> true
            else -> false
        }
    }

    companion object {
        const val PHONE_PROVIDER_ID = "phone_camera_fallback"
        private const val INDOOR_WALKING_SPEED_MPS = 1.2
    }
}

private data class HudMapPoint(
    val x: Double,
    val y: Double,
) {
    fun toCommandString(): String = "${x.roundToInt()},${y.roundToInt()}"
}

private data class HudMiniMapPayload(
    val route: String = "",
    val current: String = "",
    val target: String = "",
)
