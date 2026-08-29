package com.aiglasses.poc

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aiglasses.poc.connection.ConnectionState
import com.aiglasses.poc.databinding.ActivityMainBinding
import com.aiglasses.poc.glasses.GlassesDebugState
import com.aiglasses.poc.glasses.GlassesDebugActivity
import com.aiglasses.poc.glasses.GlassesMediaItem
import com.aiglasses.poc.glasses.HeyCyanGlassesManager
import com.aiglasses.poc.glasses.RecordingAnnotationAction
import com.aiglasses.poc.glasses.RecordingAnnotationController
import com.aiglasses.poc.glasses.RecordingAnnotationDeviceSource
import com.aiglasses.poc.glasses.RecordingAnnotationSessionStore
import com.aiglasses.poc.glasses.RecordingAnnotationVideoItem
import com.aiglasses.poc.image.CapturedFrame
import com.aiglasses.poc.indoor.AmapIndoorRouteNode
import com.aiglasses.poc.indoor.AmapIndoorRouteOverlay
import com.aiglasses.poc.indoor.IndoorBasemapConfig
import com.aiglasses.poc.indoor.IndoorBasemapController
import com.aiglasses.poc.indoor.IndoorBusinessOverlay
import com.aiglasses.poc.indoor.ImageIndoorEntrance
import com.aiglasses.poc.indoor.ImageIndoorManualDemoScriptBuilder
import com.aiglasses.poc.indoor.ImageIndoorNavigationRepository
import com.aiglasses.poc.indoor.ImageIndoorNavNode
import com.aiglasses.poc.indoor.ImageIndoorPoiResolverItem
import com.aiglasses.poc.indoor.ImageIndoorRoutePlan
import com.aiglasses.poc.indoor.IndoorMapTap
import com.aiglasses.poc.indoor.IndoorNavigationMode
import com.aiglasses.poc.indoor.IndoorOverlayPoint
import com.aiglasses.poc.indoor.ManualIndoorDemoAction
import com.aiglasses.poc.indoor.ManualIndoorDemoPoint
import com.aiglasses.poc.indoor.ManualIndoorDemoState
import com.aiglasses.poc.indoor.ManualIndoorDemoScripts
import com.aiglasses.poc.nav.NavState
import com.aiglasses.poc.network.PcBackendAutoDiscoveryClient
import com.aiglasses.poc.outdoor.AmapOutdoorBridge
import com.aiglasses.poc.outdoor.AmapOutdoorDiscovery
import com.aiglasses.poc.outdoor.AmapExternalNavigationLauncher
import com.aiglasses.poc.outdoor.AmapExternalNavigationRequest
import com.aiglasses.poc.outdoor.AmapOutdoorNavigator
import com.aiglasses.poc.outdoor.ExternalLocationIntentParser
import com.aiglasses.poc.outdoor.ExternalLocationPayload
import com.aiglasses.poc.rokid.RokidAuthManager
import com.aiglasses.poc.rokid.RokidDebugActivity
import com.aiglasses.poc.rokid.RokidHudPayload
import com.aiglasses.poc.rokid.RokidHttpAutoStreamClient
import com.aiglasses.poc.rokid.RokidImuSample
import com.aiglasses.poc.rokid.RokidRepository
import com.aiglasses.poc.rokid.RokidRuntimeBridge
import com.aiglasses.poc.rokid.RokidVoiceCommandParser
import com.aiglasses.poc.rokid.VoiceNavigationCommand
import com.aiglasses.poc.outdoor.OutdoorPoiDirectionHint
import com.aiglasses.poc.outdoor.OutdoorPoint
import com.aiglasses.poc.outdoor.OutdoorPoiOption
import com.aiglasses.poc.outdoor.OutdoorPoiVisualType
import com.aiglasses.poc.outdoor.OutdoorTravelMode
import com.aiglasses.poc.usb.UsbCameraDebugActivity
import com.aiglasses.poc.usb.dual.DualSensorRecordingActivity
import com.aiglasses.poc.usb.UsbCameraRecordingManager
import com.aiglasses.poc.usb.UsbCameraRecordingState
import com.aiglasses.poc.voice.NavigationVoiceGuide
import com.aiglasses.poc.voice.NavigationVoicePrompts
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNaviView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var outdoorNavigator: AmapOutdoorNavigator
    private lateinit var outdoorDiscovery: AmapOutdoorDiscovery
    private lateinit var externalNavigationLauncher: AmapExternalNavigationLauncher
    private lateinit var indoorBasemapController: IndoorBasemapController
    private lateinit var navigationVoiceGuide: NavigationVoiceGuide
    private lateinit var recordingAnnotationStore: RecordingAnnotationSessionStore
    private lateinit var outdoorPoiAdapter: ArrayAdapter<String>
    private lateinit var outdoorTravelModeAdapter: ArrayAdapter<String>
    private lateinit var indoorImageNavEntranceAdapter: ArrayAdapter<String>
    private var amapNaviView: AMapNaviView? = null
    private var isEmbeddedAmapViewSkipped = false
    private val viewModel: MainViewModel by viewModels()
    private val recordingAnnotationController = RecordingAnnotationController(
        elapsedTimeMs = { SystemClock.elapsedRealtime() },
        wallTimeMs = { System.currentTimeMillis() },
    )
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val defaultIndoorCalibrationStoreRaw by lazy { readAssetText(DEFAULT_INDOOR_CALIBRATION_POINTS_ASSET) }
    private var pendingPhoneLocateInput: LocateInput? = null
    private var pendingLocationPermissionAction: (() -> Unit)? = null
    private var outdoorPoiOptions: List<OutdoorPoiOption> = emptyList()
    private var rawOutdoorPoiOptions: List<OutdoorPoiOption> = emptyList()
    private val outdoorTravelModes = listOf(
        OutdoorTravelMode.RIDE,
        OutdoorTravelMode.EBIKE,
        OutdoorTravelMode.DRIVE,
        OutdoorTravelMode.WALK,
    )
    private var lastCalculatedTravelMode: OutdoorTravelMode? = null
    private var lastNavigationSimulation = true
    private var startNavigationAfterRouteReady = false
    private var isBottomPanelExpanded = true
    private var isDebugPanelExpanded = false
    private var isManualControlSheetExpanded = false
    private var manualControlFloatingX: Float? = null
    private var manualControlFloatingY: Float? = null
    private var manualControlDragStartRawX = 0f
    private var manualControlDragStartRawY = 0f
    private var manualControlDragStartX = 0f
    private var manualControlDragStartY = 0f
    private var statusBarExtraTopInset = 0
    private var externalNavigationSession: ExternalNavigationSession? = null
    private var selectedSearchType: SearchType = SearchType.STORE
    private var outdoorSearchUiState: SearchUiState = SearchUiState.IDLE_HOME
    private var isSearchIdleSuggestionsCollapsed = false
    private var searchIdleDragStartY = 0f
    private var selectedOutdoorPoi: OutdoorPoiOption? = null
    private var shouldCenterOutdoorMapAfterCurrentLocation = false
    private var pendingOutdoorMapCenterPoint: OutdoorPoint? = null
    private var lastManualIndoorVoiceKey: String? = null
    private var hasRequestedInitialOutdoorLocation = false
    private val wudaokouAmapRouteOverlay: AmapIndoorRouteOverlay? by lazy {
        runCatching {
            AmapIndoorRouteOverlay.loadFromAssets(assets, WUDAOKOU_TATA_AMAP_OVERLAY_ASSET)
        }.getOrNull()
    }
    private var pendingExternalReturnCheck = false
    private var lastIndoorMapHostVisible = false
    private var lastRenderedNavState: NavState? = null
    private var hasSelectedMapPoiPendingNavigation = false
    private var suppressOutdoorPoiSelectionCallback = false
    private var suppressOutdoorSearchTextWatcher = false
    private var outdoorSearchJob: Job? = null
    private var lastOutdoorSearchKeyword: String? = null
    private var isIndoorAnnotationModeEnabled = false
    private var isIndoorCalibrationOverlayEnabled = false
    private var pendingManualCalibrationFloorId: String? = null
    private var savedIndoorCalibrationPoints: Map<String, SavedIndoorCalibrationPoint> = emptyMap()
    private var savedImageIndoorCalibrationPoints: Map<String, SavedIndoorCalibrationPoint> = emptyMap()
    private var indoorImageNavigation: ImageIndoorNavigationRepository? = null
    private var indoorImageNavEntrances: List<ImageIndoorEntrance> = emptyList()
    private var indoorImageNavCandidates: List<ImageIndoorPoiResolverItem> = emptyList()
    private var indoorImageNavPlan: ImageIndoorRoutePlan? = null
    private var indoorImageNavSelectedFloorId: String = "F1"
    private var pendingIndoorImageFocusTarget = false
    private var lastFocusedIndoorImageCurrentKey: String? = null
    private var lastConferenceHudMiniMapPositionKey: String? = null
    private var conferenceDisplayedCurrentPosition: IndoorMapPosition? = null
    private var lastConferenceDisplayedPositionHudAtMs = 0L
    private var conferenceWalkDemoJob: Job? = null
    private val indoorAnnotationRows = mutableListOf<String>()
    private var latestGlassesState: GlassesDebugState = GlassesDebugState()
    private var latestUsbCameraState: UsbCameraRecordingState = UsbCameraRecordingState()
    private var pendingUsbAnnotationStart = false
    private var currentDockModel: DockModel = DockModel.empty()
    private var pendingHiddenDebugReveal: Runnable? = null
    private var isVerboseDebugPanelVisible = false
    private val recentSearchHistory = mutableListOf<String>()
    private var hideDefaultRecentSuggestions = false
    private var pendingUnifiedIndoorTargets: Map<String, ImageIndoorPoiResolverItem> = emptyMap()
    private var pendingLocationPermissionPurpose: LocationPermissionPurpose? = null
    private var pendingLocationPermissionAfterHandled: (() -> Unit)? = null
    private var hiddenDebugTapCount = 0
    private var lastHiddenDebugTapAtMs = 0L
    private var lastHandledRokidVoiceRequestId: String? = null
    private var rokidHttpAutoStreamClient: RokidHttpAutoStreamClient? = null
    private var rokidBridgeAutoLaunchRepository: RokidRepository? = null
    private var conferenceBackendDiscoveryJob: Job? = null
    @Volatile
    private var lastConferenceAutoLocateStartedAtMs = 0L
    @Volatile
    private var isConferenceAutoLocateInFlight = false

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val action = pendingLocationPermissionAction
            val purpose = pendingLocationPermissionPurpose ?: LocationPermissionPurpose.INTERACTIVE
            val afterHandled = pendingLocationPermissionAfterHandled
            pendingLocationPermissionAction = null
            pendingLocationPermissionPurpose = null
            pendingLocationPermissionAfterHandled = null
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                hasLocationPermission()
            ) {
                action?.invoke()
                afterHandled?.invoke()
            } else {
                handleLocationPermissionDenied(purpose)
                if (purpose != LocationPermissionPurpose.INITIAL_LOCATION) {
                    afterHandled?.invoke()
                }
            }
        }

    private val glassesPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            autoConnectGlassesIfPossible()
        }

    private val usbCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val permissionsReady = missingUsbCameraPermissions().isEmpty()
            if (!permissionsReady) {
                pendingUsbAnnotationStart = false
                Toast.makeText(this, "未授予相机权限，无法使用 USB 相机录像标记", Toast.LENGTH_SHORT).show()
            }
            UsbCameraRecordingManager.refreshDevices(autoOpen = permissionsReady)
        }

    private val phoneCameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            val input = pendingPhoneLocateInput
            pendingPhoneLocateInput = null
            if (input == null) {
                viewModel.onPhoneCaptureFailed("缺少待定位输入")
                return@registerForActivityResult
            }
            if (bitmap == null) {
                viewModel.onPhoneCaptureFailed("相机已取消或不可用")
                return@registerForActivityResult
            }
            viewModel.locateCapturedFrame(
                baseUrl = input.baseUrl,
                venueId = input.venueId,
                targetPoiId = input.targetPoiId,
                debugTarget = input.debugTarget,
                frame = bitmap.toCapturedFrame(input.floorId),
            )
        }

    private val pcBackendQrPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchPcBackendPairingScanner()
            } else {
                viewModel.onPcBackendPairingInvalid("未授予相机权限，无法扫描 PC 后台二维码")
                Toast.makeText(this, "未授予相机权限，无法扫码", Toast.LENGTH_SHORT).show()
            }
        }

    private val pcBackendPairingScanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            handlePcBackendPairingScanResult(result.contents)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val amapInitResult = AmapOutdoorBridge.initialize(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        recordingAnnotationStore = RecordingAnnotationSessionStore(application)
        HeyCyanGlassesManager.initialize(application)
        UsbCameraRecordingManager.initialize(application)
        UsbCameraRecordingManager.refreshDevices(autoOpen = missingUsbCameraPermissions().isEmpty())
        navigationVoiceGuide = NavigationVoiceGuide(this) { message ->
            viewModel.onAmapOutdoorNaviEvent(message)
        }
        applyStatusBarSafeArea()
        setupManualIndoorFloatingControls()
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        configureTopSearchInputs()
        bindSearchTypeTabs()
        bindTravelModePills()
        bindPrimaryDockActions()
        bindSearchIdleSuggestionDrag()
        bindHiddenDebugReveal()

        val providerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            viewModel.providerLabels,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerProvider.adapter = providerAdapter

        outdoorPoiAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf(getString(R.string.outdoor_poi_empty)),
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerOutdoorPoi.adapter = outdoorPoiAdapter
        binding.spinnerOutdoorPoi.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressOutdoorPoiSelectionCallback) {
                    return
                }
                val poi = selectedOutdoorPoiForSpinnerPosition(position) ?: return
                applyOutdoorPoiToEntry(
                    poi = poi,
                    sourceLabel = "搜索结果选择",
                    previewOnMap = true,
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        outdoorTravelModeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            outdoorTravelModes.map { it.displayName },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerOutdoorTravelMode.adapter = outdoorTravelModeAdapter

        setupIndoorImageNavigation()
        externalNavigationLauncher = AmapExternalNavigationLauncher(this)
        indoorBasemapController = IndoorBasemapController(
            context = this,
            container = binding.amapIndoorMapContainer,
            skipSdkMapView = shouldSkipEmbeddedAmapViewOnThisDevice(),
            onStatusChanged = viewModel::onIndoorBasemapChanged,
            onMapTapped = ::handleIndoorMapTapped,
        )
        indoorBasemapController.onCreate(savedInstanceState)
        viewModel.onAmapInitResult(amapInitResult)
        outdoorDiscovery = AmapOutdoorDiscovery(
            context = this,
            listener = object : AmapOutdoorDiscovery.Listener {
                override fun onCurrentLocationReady(point: OutdoorPoint, city: String, summary: String) {
                    binding.editOutdoorStartLat.setText(point.latitude.toString())
                    binding.editOutdoorStartLng.setText(point.longitude.toString())
                    if (binding.editOutdoorSearchCity.text.isNullOrBlank() && city.isNotBlank()) {
                        binding.editOutdoorSearchCity.setText(city)
                    }
                    persistSettings()
                    viewModel.onAmapCurrentLocationReady(summary)
                    if (shouldCenterOutdoorMapAfterCurrentLocation) {
                        shouldCenterOutdoorMapAfterCurrentLocation = false
                        centerOutdoorMapOnCurrentLocation(point)
                    }
                    handleExternalReturnLocation(point)
                }

                override fun onPoiSearchResult(items: List<OutdoorPoiOption>) {
                    val mergedItems = mergeUnifiedIndoorPoiResults(lastOutdoorSearchKeyword.orEmpty(), items)
                    applyOutdoorPoiSearchResult(mergedItems)
                    viewModel.onAmapPoiSearchResult(outdoorPoiOptions.size, outdoorPoiOptions.firstOrNull()?.label())
                }

                override fun onOutdoorDiscoveryError(summary: String) {
                    shouldCenterOutdoorMapAfterCurrentLocation = false
                    if (pendingExternalReturnCheck && externalNavigationSession != null) {
                        pendingExternalReturnCheck = false
                        viewModel.onAmapExternalNavigationReturnLocationFailed(summary)
                    } else {
                        viewModel.onAmapOutdoorError(summary)
                    }
                }
            },
        )
        restoreSettings()
        bindPersistence()
        bindActions()
        applyProductButtonStyling()
        bindMapCollapseActions()
        observeState()
        if (!CONFERENCE_INDOOR_ONLY_MODE) {
            initializeAmapNaviViewAfterFirstFrame(savedInstanceState)
        }
        val handledSmoke = handlePcBackendSmokeIntent(intent) || handleRokidVoiceSmokeIntent(intent)
        val handledExternalLocation = !handledSmoke && handleExternalLocationIntent(intent)
        if (CONFERENCE_INDOOR_ONLY_MODE) {
            startConferenceIndoorOnlyMode()
        } else if (savedInstanceState == null) {
            if (handledExternalLocation) {
                requestInitialOutdoorLocationForExternalIntent()
            } else {
                requestInitialOutdoorLocationIfNeeded()
            }
        } else {
            requestGlassesAutoConnect()
        }
    }

    private fun applyStatusBarSafeArea() {
        val searchBaseTopMargin = (binding.layoutTopSearchPanel.layoutParams as FrameLayout.LayoutParams).topMargin
        val statusBaseTopMargin = (binding.topStatusCard.layoutParams as FrameLayout.LayoutParams).topMargin
        val speedBaseTopMargin = (binding.textOutdoorSpeedBadge.layoutParams as FrameLayout.LayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safeTopInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
            ).top
            val contentTopInset = window.decorView.findViewById<View>(android.R.id.content)?.top ?: 0
            val extraTopInset = (safeTopInset - contentTopInset).coerceAtLeast(0)
            statusBarExtraTopInset = extraTopInset
            updateTopMargin(binding.layoutTopSearchPanel, searchBaseTopMargin + extraTopInset)
            updateTopMargin(binding.topStatusCard, statusBaseTopMargin + extraTopInset)
            updateTopMargin(binding.textOutdoorSpeedBadge, speedBaseTopMargin + extraTopInset)
            updateOutdoorSpeedBadgePosition()
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateTopMargin(view: View, topMargin: Int) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.topMargin == topMargin) {
            return
        }
        params.topMargin = topMargin
        view.layoutParams = params
    }

    private fun setupIndoorImageNavigation() {
        indoorImageNavigation = runCatching {
            ImageIndoorNavigationRepository.loadFromAssets(
                assetManager = assets,
                graphPath = if (CONFERENCE_INDOOR_ONLY_MODE) {
                    CONFERENCE_NAV_GRAPH_ASSET
                } else {
                    WUDAOKOU_NAV_GRAPH_ASSET
                },
                resolverPath = if (CONFERENCE_INDOOR_ONLY_MODE) {
                    CONFERENCE_POI_RESOLVER_ASSET
                } else {
                    WUDAOKOU_POI_RESOLVER_ASSET
                },
            )
        }.getOrNull()
        indoorImageNavEntrances = indoorImageNavigation?.graph?.entrances
            ?.filter { it.floorId == "F1" }
            ?.sortedBy { it.entranceType }
            .orEmpty()
        indoorImageNavEntranceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            indoorImageNavEntrances.map { it.displayLabel() }.ifEmpty { listOf("本地图纸路网未加载") },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerIndoorImageNavEntrance.adapter = indoorImageNavEntranceAdapter
        val defaultStartNodeId = if (CONFERENCE_INDOOR_ONLY_MODE) {
            CONFERENCE_DEFAULT_IMAGE_NAV_START_NODE_ID
        } else {
            DEFAULT_IMAGE_NAV_START_NODE_ID
        }
        val defaultStartIndex = indoorImageNavEntrances.indexOfFirst { it.routeNodeId == defaultStartNodeId }
        if (defaultStartIndex >= 0) {
            binding.spinnerIndoorImageNavEntrance.setSelection(defaultStartIndex)
        }
        binding.viewIndoorImageNavigation.onDisplayedCurrentPositionChanged = { floorId, x, y ->
            onConferenceDisplayedCurrentPositionChanged(floorId, x, y)
        }
        ensureIndoorImageNavSelectedFloor()
        renderIndoorImageFloorButtons()
        renderIndoorImageNavCandidates()
        renderIndoorImageNavSummary()
    }

    private fun onConferenceDisplayedCurrentPositionChanged(floorId: String?, x: Double?, y: Double?) {
        if (isConferenceWalkDemoRunning()) {
            return
        }
        conferenceDisplayedCurrentPosition = if (floorId != null && x != null && y != null) {
            IndoorMapPosition(floorId = floorId, x = x, y = y)
        } else {
            null
        }
        if (!CONFERENCE_INDOOR_ONLY_MODE || binding.viewIndoorImageNavigation.visibility != View.VISIBLE) {
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastConferenceDisplayedPositionHudAtMs < CONFERENCE_HUD_DISPLAYED_POSITION_MIN_INTERVAL_MS) {
            return
        }
        lastConferenceDisplayedPositionHudAtMs = nowMs
        val state = viewModel.uiState.value
        indoorImageNavPlan?.let { plan ->
            publishConferenceIndoorRouteHudIfPositionChanged(state, plan)
        } ?: publishConferenceIndoorIdleHudIfPositionChanged(state)
    }

    private fun startConferenceIndoorOnlyMode() {
        selectedOutdoorPoi = null
        hasSelectedMapPoiPendingNavigation = false
        externalNavigationSession = null
        pendingExternalReturnCheck = false
        startNavigationAfterRouteReady = false
        outdoorSearchUiState = SearchUiState.IDLE_HOME
        isDebugPanelExpanded = false
        isVerboseDebugPanelVisible = false
        val savedBaseUrl = prefs.getString(KEY_BASE_URL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        binding.editBaseUrl.setText(savedBaseUrl ?: CONFERENCE_BACKEND_BASE_URL)
        binding.editVenueId.setText(EXHIBITION_DEMO_VENUE_ID)
        binding.editFloorId.setText(CONFERENCE_DEFAULT_FLOOR_ID)
        binding.editTargetPoiId.setText("")
        binding.editDebugTarget.setText("")
        viewModel.selectProvider(CONFERENCE_PROVIDER_ID)
        syncProviderSpinner(CONFERENCE_PROVIDER_ID)
        viewModel.enterConferenceIndoorMode(
            floorId = CONFERENCE_DEFAULT_FLOOR_ID,
            venueId = EXHIBITION_DEMO_VENUE_ID,
            targetPoiId = "",
        )
        requestGlassesAutoConnect()
        startConferenceBackendAutoDiscovery()
        startRokidHttpAutoStream()
        renderScreen(viewModel.uiState.value)
    }

    private fun startConferenceBackendAutoDiscovery() {
        if (conferenceBackendDiscoveryJob != null) return
        conferenceBackendDiscoveryJob = lifecycleScope.launch {
            val foundBaseUrl = withContext(Dispatchers.IO) {
                PcBackendAutoDiscoveryClient().discover()
            }
            conferenceBackendDiscoveryJob = null
            if (foundBaseUrl.isNullOrBlank() || isFinishing || isDestroyed) {
                return@launch
            }
            binding.editBaseUrl.setText(foundBaseUrl)
            viewModel.checkHealth(foundBaseUrl)
        }
    }

    private fun startPcBackendPairingScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchPcBackendPairingScanner()
            return
        }
        pcBackendQrPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchPcBackendPairingScanner() {
        pcBackendPairingScanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("扫描 PC 后台 /debug/pairing 页面二维码")
                setBeepEnabled(false)
                setOrientationLocked(false)
            },
        )
    }

    private fun handlePcBackendPairingScanResult(contents: String?) {
        val pairingUrl = contents?.trim().orEmpty()
        if (pairingUrl.isBlank()) {
            viewModel.onPcBackendPairingInvalid("扫码已取消或未识别到二维码")
            return
        }
        if (!pairingUrl.isHttpUrl()) {
            viewModel.onPcBackendPairingInvalid("二维码内容不是 URL：$pairingUrl")
            return
        }
        viewModel.pairPcBackend(pairingUrl) { result ->
            val venueId = result.pairing.venueId ?: result.health.venueId ?: EXHIBITION_DEMO_VENUE_ID
            binding.editBaseUrl.setText(result.selectedBaseUrl)
            binding.editVenueId.setText(venueId)
            persistSettings()
            renderPcBackendStatus(viewModel.uiState.value)
            Toast.makeText(this, "已连接 PC 后台：${result.selectedBaseUrl}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRokidHttpAutoStream() {
        if (rokidHttpAutoStreamClient != null) return
        lateinit var client: RokidHttpAutoStreamClient
        client = RokidHttpAutoStreamClient(
            endpoint = CONFERENCE_ROKID_HTTP_ENDPOINT,
            onLog = { summary ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (summary.contains("stream_frame")) {
                            return@runOnUiThread
                        }
                        viewModel.onRokidHttpAutoStreamEvent(summary)
                        renderRokidConnectionStatus()
                    }
                }
            },
            onFrame = {
                maybeAutoLocateFromRokidHttpFrame()
            },
            onEndpointReady = { baseUrl ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        viewModel.onRokidHttpAutoStreamEvent("rokid_http_auto_endpoint_ready endpoint=$baseUrl")
                    }
                }
                RokidRuntimeBridge.latestHudPayload()?.let { payload ->
                    client.sendHud(payload)
                }
            },
        ).also { streamClient ->
            rokidHttpAutoStreamClient = streamClient
            RokidRuntimeBridge.setHttpHudCommandSender { payload ->
                streamClient.sendHud(payload)
            }
            streamClient.start()
        }
    }

    private fun openRokidBridgeFromPhone() {
        val token = RokidAuthManager(application).savedToken
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "请先在 Rokid 调试页完成授权", Toast.LENGTH_SHORT).show()
            viewModel.onAmapOutdoorNaviEvent("RokidBridge 手动打开跳过：暂无 Rokid 授权 token")
            return
        }
        val repository = rokidBridgeAutoLaunchRepository ?: RokidRepository(this).also {
            rokidBridgeAutoLaunchRepository = it
        }
        Toast.makeText(this, "正在尝试打开眼镜端", Toast.LENGTH_SHORT).show()
        viewModel.onAmapOutdoorNaviEvent("RokidBridge 手动打开：正在建立 CUSTOMAPP 会话")
        repository.connectCustomApp(token)
        listOf(1_000L, 3_000L, 6_000L).forEach { delayMs ->
            binding.root.postDelayed(
                {
                    if (!isFinishing && !isDestroyed) {
                        repository.openCustomApp()
                        viewModel.onAmapOutdoorNaviEvent("RokidBridge 手动打开尝试 delay_ms=$delayMs")
                        renderRokidConnectionStatus()
                    }
                },
                delayMs,
            )
        }
        binding.root.postDelayed({ renderRokidConnectionStatus() }, 8_000L)
    }

    private fun openRokidWifiFromPhone() {
        val token = RokidAuthManager(application).savedToken
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "请先在 Rokid 调试页完成授权", Toast.LENGTH_SHORT).show()
            viewModel.onAmapOutdoorNaviEvent("Rokid Wi‑Fi 打开跳过：暂无 Rokid 授权 token")
            return
        }
        val repository = rokidBridgeAutoLaunchRepository ?: RokidRepository(this).also {
            rokidBridgeAutoLaunchRepository = it
        }
        Toast.makeText(this, "正在请求眼镜打开 Wi‑Fi 设置", Toast.LENGTH_SHORT).show()
        viewModel.onAmapOutdoorNaviEvent("Rokid Wi‑Fi：准备通过 CUSTOMAPP 打开眼镜端 Wi‑Fi 设置")
        if (RokidRuntimeBridge.connectionStatus().customAppAvailable) {
            repository.openGlassWifiSettings()
            renderRokidConnectionStatus()
            return
        }
        repository.connectCustomApp(token)
        listOf(1_000L, 3_000L).forEach { delayMs ->
            binding.root.postDelayed(
                {
                    if (!isFinishing && !isDestroyed) {
                        repository.openCustomApp()
                        viewModel.onAmapOutdoorNaviEvent("Rokid Wi‑Fi：打开协同 App 尝试 delay_ms=$delayMs")
                        renderRokidConnectionStatus()
                    }
                },
                delayMs,
            )
        }
        listOf(5_000L, 8_000L).forEach { delayMs ->
            binding.root.postDelayed(
                {
                    if (!isFinishing && !isDestroyed) {
                        repository.openGlassWifiSettings()
                        viewModel.onAmapOutdoorNaviEvent("Rokid Wi‑Fi：发送打开 Wi‑Fi 设置 delay_ms=$delayMs")
                        renderRokidConnectionStatus()
                    }
                },
                delayMs,
            )
        }
    }

    private fun maybeAutoLocateFromRokidHttpFrame() {
        if (!CONFERENCE_INDOOR_ONLY_MODE) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastConferenceAutoLocateStartedAtMs < CONFERENCE_AUTO_LOCATE_INTERVAL_MS) {
            return
        }
        if (isConferenceAutoLocateInFlight) {
            return
        }
        lastConferenceAutoLocateStartedAtMs = nowMs
        isConferenceAutoLocateInFlight = true
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                isConferenceAutoLocateInFlight = false
                return@runOnUiThread
            }
            val baseUrl = binding.editBaseUrl.text.toString().trim()
            if (baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost", ignoreCase = true)) {
                isConferenceAutoLocateInFlight = false
                startConferenceBackendAutoDiscovery()
                return@runOnUiThread
            }
            viewModel.captureAndLocate(
                baseUrl = baseUrl,
                venueId = binding.editVenueId.text.toString().trim(),
                candidateFloorId = binding.editFloorId.text.toString().trim().ifBlank { null },
                targetPoiId = null,
                debugTarget = binding.editDebugTarget.text.toString().trim().ifBlank { null },
                allowProviderFallback = false,
                quietUi = true,
                onComplete = { isConferenceAutoLocateInFlight = false },
            )
        }
    }

    private fun initializeAmapNaviViewAfterFirstFrame(savedInstanceState: Bundle?) {
        if (shouldSkipEmbeddedAmapViewOnThisDevice()) {
            isEmbeddedAmapViewSkipped = true
            viewModel.onAmapOutdoorNaviEvent(
                "高德内置设置：当前设备 ABI=${Build.SUPPORTED_ABIS.joinToString()}，" +
                    "高德导航 native 库未提供 x86/x86_64 版本，已自动切换为 App 兜底预览；请用真机验证内嵌导航",
            )
            return
        }
        binding.amapNaviContainer.post {
            if (::outdoorNavigator.isInitialized || isFinishing || isDestroyed) return@post
            runCatching {
                AMapNaviView(this).also { naviView ->
                    amapNaviView = naviView
                    naviView.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            collapseBottomPanel()
                        }
                        false
                    }
                    binding.amapNaviContainer.addView(
                        naviView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    outdoorNavigator = createOutdoorNavigator(naviView)
                    outdoorNavigator.onCreate(savedInstanceState)
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        outdoorNavigator.onResume()
                    }
                    pendingOutdoorMapCenterPoint?.let { point ->
                        pendingOutdoorMapCenterPoint = null
                        outdoorNavigator.centerOnCurrentLocation(point)
                    }
                }
            }.onFailure { throwable ->
                viewModel.onAmapOutdoorError("高德地图视图初始化失败：${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    private fun shouldSkipEmbeddedAmapViewOnThisDevice(): Boolean {
        val supportedAbis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        val primaryAbi = supportedAbis.firstOrNull().orEmpty()
        if (primaryAbi.startsWith("x86")) {
            return true
        }
        val hasArmAbi = supportedAbis.any { abi ->
            abi == "arm64-v8a" || abi == "armeabi-v7a"
        }
        return !hasArmAbi
    }

    private fun createOutdoorNavigator(naviView: AMapNaviView): AmapOutdoorNavigator {
        return AmapOutdoorNavigator(
            context = this,
            naviView = naviView,
            listener = object : AmapOutdoorNavigator.Listener {
                override fun onOutdoorRouteReady(summary: String) {
                    viewModel.onAmapOutdoorRouteReady(summary)
                    if (startNavigationAfterRouteReady) {
                        startNavigationAfterRouteReady = false
                        startOutdoorNavigation()
                    }
                }

                override fun onOutdoorNavigationStarted(type: String, gpsStarted: Boolean) {
                    collapseDebugPanel()
                    viewModel.onAmapOutdoorNavigationStarted(type, gpsStarted)
                    navigationVoiceGuide.speak(NavigationVoicePrompts.outdoorStarted(type), flush = true)
                }

                override fun onOutdoorNavigationCanceled() {
                    viewModel.onAmapOutdoorNavigationCanceled()
                }

                override fun onOutdoorNaviInfo(summary: String) {
                    viewModel.onAmapOutdoorNaviInfo(summary)
                }

                override fun onOutdoorNaviEvent(summary: String) {
                    viewModel.onAmapOutdoorNaviEvent(summary)
                    NavigationVoicePrompts.outdoorEvent(summary)?.let { prompt ->
                        navigationVoiceGuide.speak(prompt)
                    }
                }

                override fun onOutdoorNavigationText(text: String) {
                    navigationVoiceGuide.speak(text)
                }

                override fun onOutdoorArriveDestination() {
                    viewModel.onAmapOutdoorArriveDestination()
                    navigationVoiceGuide.speak(NavigationVoicePrompts.outdoorArrived(), flush = true)
                }

                override fun onOutdoorNaviError(summary: String) {
                    startNavigationAfterRouteReady = false
                    lastCalculatedTravelMode = null
                    if (selectedOutdoorPoi != null || hasSelectedMapPoiPendingNavigation) {
                        viewModel.onAmapOutdoorRouteFailedWithSelection(summary)
                    } else {
                        viewModel.onAmapOutdoorError(summary)
                    }
                }

                override fun onOutdoorMapPoiSelected(poi: OutdoorPoiOption) {
                    selectMapPoiAsEntry(poi)
                }
            },
        )
    }

    private fun configureTopSearchInputs() {
        listOf(binding.editOutdoorSearchKeyword, binding.editOutdoorSearchCity).forEach { editText ->
            editText.setTextIsSelectable(false)
            editText.isLongClickable = false
            editText.setOnLongClickListener { true }
        }
        binding.editOutdoorSearchKeyword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && outdoorSearchUiState == SearchUiState.IDLE_HOME) {
                outdoorSearchUiState = SearchUiState.EDITING
                renderScreen(viewModel.uiState.value)
            } else if (hasFocus && outdoorSearchUiState == SearchUiState.DESTINATION_SELECTED) {
                clearSelectedOutdoorPoiForSearch()
                outdoorSearchUiState = SearchUiState.EDITING
                renderScreen(viewModel.uiState.value)
            }
        }
        binding.editOutdoorSearchKeyword.setOnEditorActionListener { _, actionId, event ->
            val isKeyboardSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_UP
            if (isKeyboardSearch || isEnterKey) {
                submitOutdoorPoiSearch()
                true
            } else {
                false
            }
        }
        binding.buttonOutdoorSearchBack.setOnClickListener {
            if (outdoorSearchUiState == SearchUiState.IDLE_HOME) {
                binding.editOutdoorSearchKeyword.requestFocus()
                outdoorSearchUiState = SearchUiState.EDITING
                renderScreen(viewModel.uiState.value)
            } else {
                cancelOutdoorSearch()
            }
        }
        binding.buttonOutdoorSearchClear.setOnClickListener {
            clearOutdoorSearchInput()
        }
        binding.buttonOutdoorSearchVoice.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_voice_search_reserved), Toast.LENGTH_SHORT).show()
        }
        binding.buttonOutdoorSearchVoice.isEnabled = true
        binding.buttonOutdoorSearchVoice.alpha = 0.72f
    }

    private fun submitOutdoorPoiSearch() {
        val keyword = binding.editOutdoorSearchKeyword.text.toString().trim()
        if (keyword.length < MIN_OUTDOOR_SEARCH_KEYWORD_LENGTH) {
            scheduleOutdoorPoiSearch()
            return
        }
        outdoorSearchJob?.cancel()
        clearSelectedOutdoorPoiForSearch()
        outdoorSearchUiState = SearchUiState.RESULTS_EXPANDED
        rawOutdoorPoiOptions = emptyList()
        outdoorPoiOptions = emptyList()
        renderOutdoorPoiResults(emptyList())
        renderScreen(viewModel.uiState.value)
        searchOutdoorPoi()
    }

    private fun handleExternalLocationIntent(intent: Intent?): Boolean {
        val payload = ExternalLocationIntentParser.parse(
            action = intent?.action,
            dataString = intent?.dataString,
            mimeType = intent?.type,
            sharedText = intent?.sharedLocationText(),
        ) ?: return false
        outdoorSearchJob?.cancel()
        isDebugPanelExpanded = false
        isVerboseDebugPanelVisible = false
        isManualControlSheetExpanded = false
        if (payload.point != null) {
            applyExternalLocationPoint(payload)
        } else {
            searchExternalLocationKeyword(payload)
        }
        return true
    }

    private fun Intent.sharedLocationText(): String? {
        val explicitText = getStringExtra(Intent.EXTRA_TEXT)
        if (!explicitText.isNullOrBlank()) {
            return explicitText
        }
        val subject = getStringExtra(Intent.EXTRA_SUBJECT)
        if (!subject.isNullOrBlank()) {
            return subject
        }
        return clipData?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this@MainActivity)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }

    private fun applyExternalLocationPoint(payload: ExternalLocationPayload) {
        val point = payload.point ?: return
        val title = payload.title.orEmpty()
            .ifBlank { payload.keyword }
            .ifBlank { "外部位置" }
        val indoorTarget = searchUnifiedIndoorTargets(payload.keyword).firstOrNull()
        val poi = OutdoorPoiOption(
            poiId = "external_${payload.sourceLabel}_${title}_${point.summary()}".hashCode().toString(),
            title = title,
            address = payload.sourceLabel,
            city = "",
            latitude = point.latitude,
            longitude = point.longitude,
            visualType = OutdoorPoiVisualType.STORE,
            directionHint = OutdoorPoiDirectionHint.FORWARD,
            indoorPoiId = indoorTarget?.poiId,
            indoorFloorId = indoorTarget?.floorId,
            indoorLabel = indoorTarget?.displayName(),
        )
        rawOutdoorPoiOptions = listOf(poi)
        outdoorPoiOptions = listOf(poi)
        applyOutdoorPoiToEntry(
            poi = poi,
            sourceLabel = payload.sourceLabel,
            previewOnMap = true,
            updateSearchText = true,
            collapseResults = true,
        )
        Toast.makeText(this, "已接收${payload.sourceLabel}", Toast.LENGTH_SHORT).show()
        viewModel.onAmapOutdoorNaviEvent("外部位置已接收 source=${payload.sourceLabel} title=$title point=${point.summary()}")
    }

    private fun searchExternalLocationKeyword(payload: ExternalLocationPayload) {
        val keyword = payload.keyword.trim()
        if (keyword.length < MIN_OUTDOOR_SEARCH_KEYWORD_LENGTH) {
            return
        }
        clearSelectedOutdoorPoiForSearch()
        suppressOutdoorSearchTextWatcher = true
        binding.editOutdoorSearchKeyword.setText(keyword)
        binding.editOutdoorSearchKeyword.setSelection(binding.editOutdoorSearchKeyword.text.length)
        suppressOutdoorSearchTextWatcher = false
        outdoorSearchUiState = SearchUiState.RESULTS_EXPANDED
        rawOutdoorPoiOptions = emptyList()
        outdoorPoiOptions = emptyList()
        renderOutdoorPoiResults(emptyList())
        renderScreen(viewModel.uiState.value)
        Toast.makeText(this, "正在搜索${payload.sourceLabel}", Toast.LENGTH_SHORT).show()
        viewModel.onAmapOutdoorNaviEvent("外部位置关键词已接收 source=${payload.sourceLabel} keyword=$keyword")
        searchOutdoorPoi()
    }

    private fun applyProductButtonStyling() {
        stylePrimaryButton(binding.buttonDockPrimary)
    }

    private fun stylePrimaryButton(button: Button) {
        button.backgroundTintList = null
        button.setBackgroundResource(R.drawable.bg_ui_primary_button)
        button.setTextColor(0xFFFFFFFF.toInt())
        button.textSize = 16f
    }

    private fun bindSearchTypeTabs() {
        binding.buttonSearchTypeStore.setOnClickListener { updateSearchType(SearchType.STORE) }
        binding.buttonSearchTypeMallEntrance.setOnClickListener { updateSearchType(SearchType.MALL_ENTRANCE) }
        binding.buttonSearchTypeOffice.setOnClickListener { updateSearchType(SearchType.OFFICE) }
        binding.buttonSearchTypeResidential.setOnClickListener { updateSearchType(SearchType.RESIDENTIAL) }
    }

    private fun bindTravelModePills() {
        binding.buttonTravelRide.setOnClickListener {
            selectOutdoorTravelMode(OutdoorTravelMode.RIDE)
        }
        binding.buttonTravelWalk.setOnClickListener {
            selectOutdoorTravelMode(OutdoorTravelMode.WALK)
        }
        binding.buttonTravelEbike.setOnClickListener {
            selectOutdoorTravelMode(OutdoorTravelMode.EBIKE)
        }
        binding.buttonTravelDrive.setOnClickListener {
            selectOutdoorTravelMode(OutdoorTravelMode.DRIVE)
        }
    }

    private fun selectOutdoorTravelMode(mode: OutdoorTravelMode) {
        val index = outdoorTravelModes.indexOf(mode)
        if (index >= 0) {
            binding.spinnerOutdoorTravelMode.setSelection(index, false)
        }
        persistSettings()
        renderScreen(viewModel.uiState.value)
    }

    private fun bindPrimaryDockActions() {
        binding.buttonDockPrimary.setOnClickListener {
            handleDockAction(currentDockModel.primary.action)
        }
        binding.buttonDockSecondaryLeading.setOnClickListener {
            currentDockModel.leading?.let { handleDockAction(it.action) }
        }
        binding.buttonDockSecondaryTrailing.setOnClickListener {
            currentDockModel.trailing?.let { handleDockAction(it.action) }
        }
    }

    private fun bindSearchIdleSuggestionDrag() {
        binding.layoutSearchIdleCollapsedHandle.setOnClickListener {
            setSearchIdleSuggestionsCollapsed(false)
        }
        binding.layoutSearchIdleCollapsedHandle.setOnTouchListener { _, event ->
            handleSearchIdleSuggestionDrag(event)
        }
        binding.layoutSearchIdleSuggestions.setOnTouchListener { _, event ->
            handleSearchIdleSuggestionDrag(event)
            false
        }
        binding.viewSearchIdleDragHandle.setOnTouchListener { _, event ->
            handleSearchIdleSuggestionDrag(event)
        }
    }

    private fun handleSearchIdleSuggestionDrag(event: MotionEvent): Boolean {
        if (!shouldShowSearchIdleSuggestions(viewModel.uiState.value)) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                searchIdleDragStartY = event.rawY
                return false
            }
            MotionEvent.ACTION_UP -> {
                val deltaY = event.rawY - searchIdleDragStartY
                if (deltaY > dp(40)) {
                    setSearchIdleSuggestionsCollapsed(true)
                    return true
                }
                if (deltaY < -dp(40)) {
                    setSearchIdleSuggestionsCollapsed(false)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> return false
        }
        return false
    }

    private fun setSearchIdleSuggestionsCollapsed(collapsed: Boolean) {
        if (isSearchIdleSuggestionsCollapsed == collapsed) {
            return
        }
        isSearchIdleSuggestionsCollapsed = collapsed
        renderScreen(viewModel.uiState.value)
    }

    private fun bindHiddenDebugReveal() {
        val listener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scheduleHiddenDebugReveal()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingHiddenDebugReveal()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        handleHiddenDebugQuickTap()
                    }
                }
            }
            false
        }
        binding.topStatusCard.setOnTouchListener(listener)
        binding.layoutBottomSearch.setOnTouchListener(listener)
        binding.buttonOutdoorSearchBack.setOnTouchListener(listener)
    }

    private fun scheduleHiddenDebugReveal() {
        val reveal = Runnable { revealHiddenDebugNow() }
        pendingHiddenDebugReveal = reveal
        binding.root.postDelayed(reveal, HIDDEN_DEBUG_REVEAL_DELAY_MS)
    }

    private fun cancelPendingHiddenDebugReveal() {
        pendingHiddenDebugReveal?.let { binding.root.removeCallbacks(it) }
        pendingHiddenDebugReveal = null
    }

    private fun handleHiddenDebugQuickTap() {
        val now = SystemClock.uptimeMillis()
        hiddenDebugTapCount = if (now - lastHiddenDebugTapAtMs <= HIDDEN_DEBUG_QUICK_TAP_WINDOW_MS) {
            hiddenDebugTapCount + 1
        } else {
            1
        }
        lastHiddenDebugTapAtMs = now
        if (hiddenDebugTapCount >= HIDDEN_DEBUG_QUICK_TAP_COUNT) {
            hiddenDebugTapCount = 0
            lastHiddenDebugTapAtMs = 0L
            revealHiddenDebugNow()
        }
    }

    private fun revealHiddenDebugNow() {
        if (isDebugPanelExpanded || forcesDebugCollapse(viewModel.uiState.value.navState)) {
            return
        }
        isDebugPanelExpanded = true
        isVerboseDebugPanelVisible = false
        isManualControlSheetExpanded = false
        Toast.makeText(this, getString(R.string.toast_debug_unlocked), Toast.LENGTH_SHORT).show()
        renderScreen(viewModel.uiState.value)
    }

    private fun updateSearchType(type: SearchType) {
        if (selectedSearchType == type) {
            return
        }
        selectedSearchType = type
        binding.editOutdoorSearchKeyword.hint = getString(type.hintResId)
        outdoorPoiOptions = filterOutdoorPoiOptions(rawOutdoorPoiOptions)
        renderScreen(viewModel.uiState.value)
        val keyword = binding.editOutdoorSearchKeyword.text.toString().trim()
        if (keyword.length >= MIN_OUTDOOR_SEARCH_KEYWORD_LENGTH) {
            scheduleOutdoorPoiSearch()
        }
    }

    private fun outdoorNavigatorOrReport(): AmapOutdoorNavigator? {
        if (::outdoorNavigator.isInitialized) return outdoorNavigator
        val summary = if (isEmbeddedAmapViewSkipped) {
            "模拟器环境已禁用内嵌高德视图以避免 ANR；请用真机验证内嵌导航，也可使用外部高德 App 或进入场馆继续演示"
        } else {
            "高德地图视图仍在初始化，请稍后重试；也可使用外部高德 App 或进入场馆继续演示"
        }
        viewModel.onAmapOutdoorError(summary)
        return null
    }

    private fun bindActions() {
        binding.buttonPrepareOutdoor.setOnClickListener {
            persistSettings()
            hasSelectedMapPoiPendingNavigation = false
            prepareOutdoorRoute()
        }
        binding.buttonStartOutdoor.setOnClickListener {
            persistSettings()
            if (viewModel.uiState.value.navState == NavState.OUTDOOR_NAVIGATING) {
                outdoorNavigatorOrReport()?.recenterToCurrentLocation()
                collapseDebugPanel()
                return@setOnClickListener
            }
            val selectedTravelMode = readOutdoorTravelMode()
            val useSimulation = binding.checkOutdoorSimulation.isChecked
            if (
                viewModel.uiState.value.navState == NavState.OUTDOOR_ROUTE_READY &&
                lastCalculatedTravelMode == selectedTravelMode &&
                lastNavigationSimulation == useSimulation
            ) {
                startOutdoorNavigation()
            } else {
                startNavigationAfterRouteReady = true
                hasSelectedMapPoiPendingNavigation = false
                prepareOutdoorRoute()
            }
        }
        binding.buttonRecenterOutdoor.setOnClickListener {
            val state = viewModel.uiState.value
            if (shouldShowIndoorMapHost(state.navState)) {
                if (!indoorBasemapController.recenterToCurrentLocation()) {
                    viewModel.onAmapOutdoorError("室内底图尚未准备好，暂时无法回到当前位置")
                }
            } else {
                outdoorNavigatorOrReport()?.recenterToCurrentLocation()
            }
        }
        binding.buttonToggleOutdoorHeading.setOnClickListener {
            outdoorNavigatorOrReport()?.toggleHeadingMode()
        }
        binding.buttonOverviewOutdoor.setOnClickListener {
            outdoorNavigatorOrReport()?.onScanViewButtonClick()
        }
        binding.buttonExitOutdoorNavi.setOnClickListener {
            exitOutdoorNavigation()
        }
        bindOptionalClick("buttonUseCurrentLocation") {
            requestCurrentOutdoorLocation()
        }
        bindOptionalClick("buttonSearchOutdoorPoi") {
            persistSettings()
            searchOutdoorPoi()
        }
        bindOptionalClick("buttonUseSelectedPoi") {
            if (applySelectedPoiToEntry()) {
                persistSettings()
            }
        }
        bindOptionalClick("buttonNavigateSelectedPoi") {
            if (applySelectedPoiToEntry()) {
                persistSettings()
                startNavigationAfterRouteReady = true
                prepareOutdoorRoute()
            }
        }
        binding.buttonEnterVenue.setOnClickListener {
            enterVenueIfIndoorTargetSupported()
        }
        binding.buttonExitIndoor.setOnClickListener {
            if (::outdoorNavigator.isInitialized) {
                outdoorNavigator.stopNavigation()
            }
            externalNavigationSession = null
            pendingExternalReturnCheck = false
            viewModel.exitIndoor()
        }
        binding.buttonHealth.setOnClickListener {
            persistSettings()
            viewModel.checkHealth(binding.editBaseUrl.text.toString())
        }
        binding.buttonPairPcBackend.setOnClickListener {
            startPcBackendPairingScan()
        }
        binding.buttonVenueMeta.setOnClickListener {
            persistSettings()
            viewModel.loadVenueMeta(
                baseUrl = binding.editBaseUrl.text.toString(),
                venueId = binding.editVenueId.text.toString(),
            )
        }
        binding.buttonConnect.setOnClickListener {
            viewModel.connectMock()
        }
        binding.buttonDisconnect.setOnClickListener {
            viewModel.disconnect()
        }
        binding.buttonFallback.setOnClickListener {
            viewModel.fallbackSelectedProvider("模拟图像来源失败")
            persistSettings()
        }
        binding.buttonLowConfidence.setOnClickListener {
            viewModel.simulateLowConfidence()
        }
        binding.buttonLocate.setOnClickListener {
            persistSettings()
            startLocate()
        }
        binding.buttonRoute.setOnClickListener {
            persistSettings()
            if (CONFERENCE_INDOOR_ONLY_MODE) {
                if (!planConferenceDefaultIndoorRoute()) {
                    Toast.makeText(this, "会场本地图纸路径规划失败", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            viewModel.requestRoute(
                baseUrl = binding.editBaseUrl.text.toString(),
                venueId = binding.editVenueId.text.toString(),
                targetPoiId = binding.editTargetPoiId.text.toString(),
            )
        }
        bindManualIndoorActions()
        bindIndoorAnnotationActions()
        bindIndoorImageNavigationActions()
        binding.buttonAbort.setOnClickListener {
            if (::outdoorNavigator.isInitialized) {
                outdoorNavigator.stopNavigation()
            }
            externalNavigationSession = null
            pendingExternalReturnCheck = false
            viewModel.abortDemo("手动中止")
        }
        binding.buttonClearLogs.setOnClickListener {
            viewModel.clearLogs()
        }
        binding.buttonCopyLogs.setOnClickListener {
            copyAppLogsToClipboard()
        }
        bindOptionalClick("buttonPrimaryUseCurrentLocation") {
            requestCurrentOutdoorLocation()
        }
        bindOptionalClick("buttonPrimarySearchPoi") {
            persistSettings()
            searchOutdoorPoi()
        }
        bindOptionalClick("buttonPrimaryUseSelectedPoi") {
            if (applySelectedPoiToEntry()) {
                persistSettings()
            }
        }
        bindOptionalClick("buttonPrimaryNavigateSelectedPoi") {
            if (applySelectedPoiToEntry()) {
                persistSettings()
                startNavigationAfterRouteReady = true
                prepareOutdoorRoute()
            }
        }
        bindOptionalClick("buttonPrimaryPrepareOutdoor") {
            binding.buttonPrepareOutdoor.performClick()
        }
        bindOptionalClick("buttonPrimaryStartOutdoor") {
            binding.buttonStartOutdoor.performClick()
        }
        bindOptionalClick("buttonPrimaryContinueNavigation") {
            val session = externalNavigationSession
            if (session != null) {
                openExternalAmapNavigation(session)
            } else {
                outdoorNavigatorOrReport()?.recenterToCurrentLocation()
                collapseDebugPanel()
            }
        }
        bindOptionalClick("buttonPrimaryOpenExternalAmap") {
            openExternalAmapNavigation()
        }
        bindOptionalClick("buttonPrimaryEnterVenue") {
            binding.buttonEnterVenue.performClick()
        }
        bindOptionalClick("buttonPrimaryExitIndoor") {
            binding.buttonExitIndoor.performClick()
        }
        bindOptionalClick("buttonPrimaryLocate") {
            binding.buttonLocate.performClick()
        }
        bindOptionalClick("buttonPrimaryRoute") {
            binding.buttonRoute.performClick()
        }
        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewModel.selectProvider(viewModel.providerIds[position])
                persistSettings()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.spinnerOutdoorTravelMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                persistSettings()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.checkOutdoorSimulation.setOnCheckedChangeListener { _, _ ->
            persistSettings()
        }
        bindDebugPanelToggle()
        bindHiddenDebugQuickActions()
        bindOptionalClick("buttonClearSearchRecent") {
            recentSearchHistory.clear()
            hideDefaultRecentSuggestions = true
            renderScreen(viewModel.uiState.value)
        }
    }

    private fun handleDockAction(action: DockAction) {
        when (action) {
            DockAction.NONE -> Unit
            DockAction.FOCUS_SEARCH -> {
                val keyword = binding.editOutdoorSearchKeyword.text.toString().trim()
                if (keyword.length >= MIN_OUTDOOR_SEARCH_KEYWORD_LENGTH) {
                    outdoorSearchUiState = SearchUiState.RESULTS_EXPANDED
                    persistSettings()
                    searchOutdoorPoi()
                    return
                }
                isDebugPanelExpanded = false
                isManualControlSheetExpanded = false
                binding.editOutdoorSearchKeyword.requestFocus()
                outdoorSearchUiState = SearchUiState.EDITING
                renderScreen(viewModel.uiState.value)
            }
            DockAction.USE_CURRENT_LOCATION -> requestCurrentOutdoorLocation()
            DockAction.PREPARE_OUTDOOR -> {
                persistSettings()
                hasSelectedMapPoiPendingNavigation = false
                prepareOutdoorRoute()
            }
            DockAction.START_OUTDOOR -> handleStartOutdoorAction()
            DockAction.CONTINUE_NAVIGATION -> binding.buttonPrimaryContinueNavigation.performClick()
            DockAction.OPEN_EXTERNAL_AMAP -> openExternalAmapNavigation(externalNavigationSession)
            DockAction.OVERVIEW -> binding.buttonOverviewOutdoor.performClick()
            DockAction.EXIT_OUTDOOR -> exitOutdoorNavigation()
            DockAction.ENTER_VENUE -> binding.buttonEnterVenue.performClick()
            DockAction.INDOOR_BACK -> viewModel.handleManualIndoorAction(ManualIndoorDemoAction.BACK)
            DockAction.INDOOR_CONTINUE -> continueIndoorPrimaryAction()
            DockAction.INDOOR_MORE -> {
                if (!SHOW_RECORDING_MARKER_FLOATING_PANEL) {
                    isManualControlSheetExpanded = false
                    renderScreen(viewModel.uiState.value)
                } else {
                    isManualControlSheetExpanded = !isManualControlSheetExpanded
                    renderScreen(viewModel.uiState.value)
                }
            }
            DockAction.RETRY_CAPTURE,
            DockAction.CAPTURE_AND_LOCATE -> binding.buttonLocate.performClick()
            DockAction.REQUEST_INDOOR_ROUTE -> binding.buttonRoute.performClick()
            DockAction.COMPLETE_ARRIVAL,
            DockAction.EXIT_INDOOR -> binding.buttonExitIndoor.performClick()
            DockAction.TOGGLE_DEBUG -> toggleDebugPanel()
        }
    }

    private fun exitOutdoorNavigation() {
        if (externalNavigationSession != null) {
            externalNavigationSession = null
            pendingExternalReturnCheck = false
            viewModel.onAmapExternalNavigationCanceled()
        } else {
            outdoorNavigatorOrReport()?.cancelNavigation()
        }
    }

    private fun continueIndoorPrimaryAction() {
        val state = viewModel.uiState.value
        if (state.indoorMode != IndoorNavigationMode.MANUAL_DEMO) {
            if (state.navState == NavState.INDOOR_LOW_CONFIDENCE) {
                binding.buttonLocate.performClick()
            } else if (state.navState == NavState.INDOOR_ROUTE_READY) {
                binding.buttonRoute.performClick()
            } else {
                binding.buttonLocate.performClick()
            }
            return
        }
        val expected = state.manualIndoorDemo.expectedAction
        when (expected) {
            null -> {
                if (state.manualIndoorDemo.arrived) {
                    binding.buttonExitIndoor.performClick()
                }
            }
            else -> viewModel.handleManualIndoorAction(expected)
        }
    }

    private fun handleStartOutdoorAction() {
        if (viewModel.uiState.value.navState == NavState.ERROR && (selectedOutdoorPoi != null || hasSelectedMapPoiPendingNavigation)) {
            startNavigationAfterRouteReady = true
            prepareOutdoorRoute()
            return
        }
        binding.buttonStartOutdoor.performClick()
    }

    override fun onResume() {
        super.onResume()
        if (::outdoorNavigator.isInitialized) {
            outdoorNavigator.onResume()
        }
        if (::indoorBasemapController.isInitialized) {
            indoorBasemapController.onResume()
        }
        checkExternalNavigationReturn()
        handlePendingRokidAuthorizationReturn()
        if (CONFERENCE_INDOOR_ONLY_MODE) {
            startRokidHttpAutoStream()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handlePcBackendSmokeIntent(intent) && !handleRokidVoiceSmokeIntent(intent)) {
            handleExternalLocationIntent(intent)
        }
    }

    private fun handlePcBackendSmokeIntent(intent: Intent?): Boolean {
        if (intent?.action != ACTION_PC_BACKEND_SMOKE) return false
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
            ?.trim()
            .orEmpty()
            .ifBlank { "http://127.0.0.1:8000" }
        val target = resolveExhibitionVoiceTarget(
            intent.getStringExtra(EXTRA_TARGET).orEmpty().ifBlank { "B17" },
        ) ?: ExhibitionVoiceTarget("poi_booth_b17", "B17", "B17 展台")
        binding.editBaseUrl.setText(baseUrl)
        binding.editVenueId.setText(EXHIBITION_DEMO_VENUE_ID)
        binding.editFloorId.setText(target.floorId)
        binding.editTargetPoiId.setText(target.poiId)
        binding.editDebugTarget.setText(target.debugTarget)
        viewModel.selectProvider("glasses_album_sync")
        syncProviderSpinner("glasses_album_sync")
        viewModel.loadVenueMeta(baseUrl, EXHIBITION_DEMO_VENUE_ID)
        isDebugPanelExpanded = true
        isVerboseDebugPanelVisible = true
        persistSettings()
        renderScreen(viewModel.uiState.value)
        viewModel.checkHealth(baseUrl)
        binding.root.postDelayed({ startLocate() }, 500L)
        Toast.makeText(this, "PC 后台联调：${target.label}", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun handleRokidVoiceSmokeIntent(intent: Intent?): Boolean {
        if (intent?.action != ACTION_ROKID_VOICE_SMOKE) return false
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
            ?.trim()
            .orEmpty()
            .ifBlank { "http://127.0.0.1:8000" }
        val target = resolveExhibitionVoiceTarget(
            intent.getStringExtra(EXTRA_TARGET).orEmpty().ifBlank { "B17" },
        ) ?: ExhibitionVoiceTarget("poi_booth_b17", "B17", "B17 展台")
        val nowMs = System.currentTimeMillis()
        val yawDeg = intent.getDoubleExtra(EXTRA_YAW_DEG, 90.0)
        RokidRuntimeBridge.onImuSample(
            RokidImuSample(
                imuTimestampMs = nowMs - 40L,
                yawDeg = yawDeg,
                pitchDeg = 0.0,
                rollDeg = 0.0,
                accuracy = "smoke",
            ),
        )
        val imageEvent = RokidRuntimeBridge.onImageReceived(
            bytes = "rokid_smoke_${target.debugTarget}_$nowMs".toByteArray(Charsets.UTF_8),
            width = 640,
            height = 480,
            captureLatencyMs = 25L,
            nowMs = nowMs,
        )
        binding.editBaseUrl.setText(baseUrl)
        binding.editVenueId.setText(EXHIBITION_DEMO_VENUE_ID)
        binding.editFloorId.setText(target.floorId)
        binding.editTargetPoiId.setText(target.poiId)
        binding.editDebugTarget.setText(target.debugTarget)
        viewModel.loadVenueMeta(baseUrl, EXHIBITION_DEMO_VENUE_ID)
        isDebugPanelExpanded = true
        isVerboseDebugPanelVisible = true
        persistSettings()
        renderScreen(viewModel.uiState.value)
        val command = RokidRuntimeBridge.onVoiceCommand(
            RokidVoiceCommandParser.parse(
                rawText = "Hi Rokid，我要去 ${target.debugTarget}",
                requestId = "voice_smoke_$nowMs",
            ),
        )
        handleRokidVoiceCommandIfNeeded(command)
        Toast.makeText(this, "Rokid 语音烟测：${target.label}", Toast.LENGTH_SHORT).show()
        viewModel.onAmapOutdoorNaviEvent("Rokid 语音烟测 capture_id=${imageEvent.captureId} imu_yaw=$yawDeg target=${target.label}")
        return true
    }

    private fun handlePendingRokidAuthorizationReturn() {
        if (!RokidAuthManager(application).consumeAuthorizationPending()) return
        binding.root.post {
            if (!isFinishing && !isDestroyed) {
                startActivity(RokidDebugActivity.createAuthorizationReturnIntent(this))
            }
        }
    }

    override fun onPause() {
        if (::indoorBasemapController.isInitialized) {
            indoorBasemapController.onPause()
        }
        if (::outdoorNavigator.isInitialized) {
            outdoorNavigator.onPause()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::outdoorNavigator.isInitialized) {
            outdoorNavigator.onSaveInstanceState(outState)
        }
        if (::indoorBasemapController.isInitialized) {
            indoorBasemapController.onSaveInstanceState(outState)
        }
    }

    private fun setupManualIndoorFloatingControls() {
        val root = binding.root as? FrameLayout ?: return
        val controls = binding.layoutManualIndoorControls
        (controls.parent as? ViewGroup)?.let { parent ->
            if (parent != root) {
                parent.removeView(controls)
                root.addView(
                    controls,
                    FrameLayout.LayoutParams(
                        dp(232),
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.END,
                    ).apply {
                        rightMargin = dp(12)
                        bottomMargin = dp(128)
                    },
                )
            }
        }
        controls.bringToFront()
        binding.buttonManualIndoorControlsExpand.bringToFront()
        binding.layoutManualIndoorControls.visibility = View.GONE
        binding.buttonManualIndoorControlsExpand.visibility = View.GONE
        listOf(binding.viewManualIndoorDragHandle, binding.layoutManualIndoorDragHeader).forEach { handle ->
            handle.setOnTouchListener { _, event ->
                handleManualFloatingDrag(controls, event)
            }
        }
        binding.buttonManualIndoorControlsExpand.setOnClickListener {
            if (!SHOW_RECORDING_MARKER_FLOATING_PANEL) return@setOnClickListener
            isManualControlSheetExpanded = true
            renderScreen(viewModel.uiState.value)
        }
    }

    private fun handleManualFloatingDrag(target: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                manualControlDragStartRawX = event.rawX
                manualControlDragStartRawY = event.rawY
                manualControlDragStartX = target.x
                manualControlDragStartY = target.y
                target.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - manualControlDragStartRawX
                val dy = event.rawY - manualControlDragStartRawY
                moveManualFloatingControl(target, manualControlDragStartX + dx, manualControlDragStartY + dy)
                true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                target.parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> false
        }
    }

    private fun positionManualFloatingControl(target: View) {
        target.post {
            val root = binding.root
            if (root.width <= 0 || root.height <= 0 || target.width <= 0 || target.height <= 0) {
                return@post
            }
            val defaultX = root.width - target.width - dp(12).toFloat()
            val defaultY = root.height - target.height - dp(148).toFloat()
            moveManualFloatingControl(
                target = target,
                desiredX = manualControlFloatingX ?: defaultX,
                desiredY = manualControlFloatingY ?: defaultY,
            )
            target.bringToFront()
        }
    }

    private fun moveManualFloatingControl(
        target: View,
        desiredX: Float,
        desiredY: Float,
    ) {
        val root = binding.root
        val minMargin = dp(8).toFloat()
        val maxX = (root.width - target.width - dp(8)).coerceAtLeast(dp(8)).toFloat()
        val maxY = (root.height - target.height - dp(8)).coerceAtLeast(dp(8)).toFloat()
        val clampedX = desiredX.coerceIn(minMargin, maxX)
        val clampedY = desiredY.coerceIn(minMargin, maxY)
        target.x = clampedX
        target.y = clampedY
        manualControlFloatingX = clampedX
        manualControlFloatingY = clampedY
    }

    private fun bindManualIndoorActions() {
        binding.textManualControlHint.setOnClickListener {
            isManualControlSheetExpanded = false
            renderScreen(viewModel.uiState.value)
        }
        bindOptionalClick("buttonManualIndoorUp") {
            recordIndoorVideoAnnotation(RecordingAnnotationAction.FORWARD)
        }
        bindOptionalClick("buttonManualIndoorLeft") {
            recordIndoorVideoAnnotation(RecordingAnnotationAction.TURN_LEFT)
        }
        bindOptionalClick("buttonManualIndoorRight") {
            recordIndoorVideoAnnotation(RecordingAnnotationAction.TURN_RIGHT)
        }
        bindOptionalClick("buttonManualIndoorDown") {
            undoIndoorVideoAnnotation()
        }
        bindOptionalClick("buttonManualIndoorFloorUp") {
            recordIndoorVideoAnnotation(RecordingAnnotationAction.FLOOR_UP)
        }
        bindOptionalClick("buttonManualIndoorFloorDown") {
            recordIndoorVideoAnnotation(RecordingAnnotationAction.FLOOR_DOWN)
        }
        bindOptionalClick("buttonManualIndoorReset") {
            toggleIndoorVideoAnnotationRecording()
        }
        bindOptionalClick("buttonManualIndoorUsbDebug") {
            openUsbCameraDebugPage()
        }
    }

    private fun toggleIndoorVideoAnnotationRecording() {
        val session = recordingAnnotationController.session
        if (session?.active == true) {
            val stopped = recordingAnnotationController.stop() ?: return
            when (session.deviceSource) {
                RecordingAnnotationDeviceSource.USB_CAMERA -> UsbCameraRecordingManager.stopVideo()
                RecordingAnnotationDeviceSource.AI_GLASSES -> HeyCyanGlassesManager.stopVideo()
            }
            val pending = recordingAnnotationStore.savePending(stopped)
            recordingAnnotationController.updateSession(pending)
            tryBindRecordingAnnotation(latestGlassesState.localMedia)
            tryBindUsbRecordingAnnotation(latestUsbCameraState.localMedia)
            renderManualRecordingAnnotationPanel()
            Toast.makeText(this, "已结束录像标记，动作 JSON 已保存", Toast.LENGTH_SHORT).show()
            return
        }
        if (shouldUseUsbCameraForAnnotation()) {
            startUsbRecordingAnnotationOrPrepare()
            return
        }
        if (!latestGlassesState.ready) {
            Toast.makeText(this, "请先连接 AI 眼镜并等待通道就绪", Toast.LENGTH_SHORT).show()
            autoConnectGlassesIfPossible()
            return
        }
        recordingAnnotationController.start(
            device = latestGlassesState.selectedDevice,
            existingVideoPaths = latestGlassesState.localMedia.videoPaths(),
        )
        HeyCyanGlassesManager.startVideo {
            recordingAnnotationController.confirmRecordingStarted()
            runOnUiThread { renderManualRecordingAnnotationPanel() }
        }
        renderManualRecordingAnnotationPanel()
        Toast.makeText(this, "已开始录像标记", Toast.LENGTH_SHORT).show()
    }

    private fun recordIndoorVideoAnnotation(action: RecordingAnnotationAction) {
        val session = recordingAnnotationController.session
        if (session?.active != true) {
            Toast.makeText(this, "请先点击“开始录像”", Toast.LENGTH_SHORT).show()
            return
        }
        if (!session.aligned) {
            Toast.makeText(this, "请等待录像开始确认后再记录", Toast.LENGTH_SHORT).show()
            return
        }
        recordingAnnotationController.record(action)
        renderManualRecordingAnnotationPanel()
    }

    private fun undoIndoorVideoAnnotation() {
        if (recordingAnnotationController.session?.active != true) {
            Toast.makeText(this, "请先点击“开始录像”", Toast.LENGTH_SHORT).show()
            return
        }
        recordingAnnotationController.undoLast()
        renderManualRecordingAnnotationPanel()
    }

    private fun tryBindRecordingAnnotation(media: List<GlassesMediaItem>) {
        val session = recordingAnnotationController.session
            ?.takeIf { !it.active && it.videoLocalPath == null }
            ?.takeIf { it.deviceSource == RecordingAnnotationDeviceSource.AI_GLASSES }
            ?: return
        val bound = recordingAnnotationStore.bindToNewestVideo(session, media)
        if (bound != session) {
            recordingAnnotationController.updateSession(bound)
        }
    }

    private fun tryBindUsbRecordingAnnotation(media: List<RecordingAnnotationVideoItem>) {
        val session = recordingAnnotationController.session
            ?.takeIf { !it.active && it.videoLocalPath == null }
            ?.takeIf { it.deviceSource == RecordingAnnotationDeviceSource.USB_CAMERA }
            ?: return
        val bound = recordingAnnotationStore.bindToNewestRecordingVideo(session, media)
        if (bound != session) {
            recordingAnnotationController.updateSession(bound)
        }
    }

    private fun shouldUseUsbCameraForAnnotation(): Boolean {
        return latestUsbCameraState.available || latestUsbCameraState.ready
    }

    private fun startUsbRecordingAnnotationOrPrepare() {
        val missingPermissions = missingUsbCameraPermissions()
        if (missingPermissions.isNotEmpty()) {
            pendingUsbAnnotationStart = true
            usbCameraPermissionLauncher.launch(missingPermissions.toTypedArray())
            Toast.makeText(this, "检测到 USB 相机，授权后将优先使用 USB 录像", Toast.LENGTH_SHORT).show()
            return
        }
        if (!latestUsbCameraState.ready) {
            pendingUsbAnnotationStart = true
            UsbCameraRecordingManager.connectFirstCamera()
            renderManualRecordingAnnotationPanel()
            Toast.makeText(this, "正在打开 USB 相机，打开后自动开始录像标记", Toast.LENGTH_SHORT).show()
            return
        }
        startUsbRecordingAnnotation()
    }

    private fun startUsbRecordingAnnotation() {
        if (recordingAnnotationController.session?.active == true) {
            return
        }
        recordingAnnotationController.start(
            source = RecordingAnnotationDeviceSource.USB_CAMERA,
            deviceName = latestUsbCameraState.selectedDeviceLabel ?: "USB 相机",
            deviceAddress = null,
            existingVideoPaths = latestUsbCameraState.localMedia.recordingVideoPaths(),
        )
        val requested = UsbCameraRecordingManager.startVideo {
            recordingAnnotationController.confirmRecordingStarted()
            runOnUiThread { renderManualRecordingAnnotationPanel() }
        }
        if (!requested) {
            recordingAnnotationController.discard()
            pendingUsbAnnotationStart = true
            UsbCameraRecordingManager.connectFirstCamera()
            renderManualRecordingAnnotationPanel()
            Toast.makeText(this, "USB 相机正在准备，请稍后", Toast.LENGTH_SHORT).show()
            return
        }
        renderManualRecordingAnnotationPanel()
        Toast.makeText(this, "已开始 USB 相机录像标记", Toast.LENGTH_SHORT).show()
    }

    private fun requestGlassesAutoConnect() {
        if (UsbCameraRecordingManager.state.value.available) {
            return
        }
        if (missingGlassesAutoConnectPermissions().isEmpty()) {
            autoConnectGlassesIfPossible()
            return
        }
        glassesPermissionLauncher.launch(missingGlassesAutoConnectPermissions().toTypedArray())
    }

    private fun autoConnectGlassesIfPossible() {
        if (UsbCameraRecordingManager.state.value.available) {
            return
        }
        if (missingGlassesAutoConnectPermissions().isNotEmpty()) {
            return
        }
        HeyCyanGlassesManager.autoConnectLastDevice(this)
    }

    private fun missingGlassesAutoConnectPermissions(): List<String> {
        return glassesAutoConnectPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun glassesAutoConnectPermissions(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun missingUsbCameraPermissions(): List<String> {
        return listOf(Manifest.permission.CAMERA).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun List<GlassesMediaItem>.videoPaths(): Set<String> {
        return filter { it.mimeType.startsWith("video/") }
            .map { it.filePath }
            .toSet()
    }

    private fun List<RecordingAnnotationVideoItem>.recordingVideoPaths(): Set<String> {
        return filter { it.mimeType.startsWith("video/") }
            .map { it.filePath }
            .toSet()
    }

    private fun bindIndoorAnnotationActions() {
        bindOptionalClick("buttonToggleIndoorCalibrationOverlay") {
            isIndoorCalibrationOverlayEnabled = !isIndoorCalibrationOverlayEnabled
            renderScreen(viewModel.uiState.value)
            val message = if (isIndoorCalibrationOverlayEnabled) {
                "已显示路线校准层"
            } else {
                "已隐藏路线校准层"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        bindOptionalClick("buttonResetIndoorCalibrationOverlay") {
            binding.viewIndoorCalibrationOverlay.resetTransform()
            renderIndoorCalibrationOverlaySummary()
            Toast.makeText(this, "校准层已重置", Toast.LENGTH_SHORT).show()
        }
        bindOptionalClick("buttonFlipIndoorCalibrationOverlay") {
            val mirrored = binding.viewIndoorCalibrationOverlay.toggleMirrorY()
            renderIndoorCalibrationOverlaySummary()
            Toast.makeText(this, if (mirrored) "校准层已上下翻转" else "校准层已恢复正常方向", Toast.LENGTH_SHORT).show()
        }
        bindOptionalClick("buttonRotateIndoorCalibrationLeft") {
            binding.viewIndoorCalibrationOverlay.rotateBy(-1.0f)
            renderIndoorCalibrationOverlaySummary()
        }
        bindOptionalClick("buttonRotateIndoorCalibrationRight") {
            binding.viewIndoorCalibrationOverlay.rotateBy(1.0f)
            renderIndoorCalibrationOverlaySummary()
        }
        bindOptionalClick("buttonScaleIndoorCalibrationXDown") {
            binding.viewIndoorCalibrationOverlay.scaleXBy(0.98f)
            renderIndoorCalibrationOverlaySummary()
        }
        bindOptionalClick("buttonScaleIndoorCalibrationXUp") {
            binding.viewIndoorCalibrationOverlay.scaleXBy(1.02f)
            renderIndoorCalibrationOverlaySummary()
        }
        bindOptionalClick("buttonScaleIndoorCalibrationYDown") {
            binding.viewIndoorCalibrationOverlay.scaleYBy(0.98f)
            renderIndoorCalibrationOverlaySummary()
        }
        bindOptionalClick("buttonScaleIndoorCalibrationYUp") {
            binding.viewIndoorCalibrationOverlay.scaleYBy(1.02f)
            renderIndoorCalibrationOverlaySummary()
        }
        bindOptionalClick("buttonCopyIndoorCalibrationResult") {
            copyIndoorCalibrationResult()
        }
        bindOptionalClick("buttonSaveIndoorCalibrationRoute") {
            saveIndoorCalibrationRoute()
        }
        bindOptionalClick("buttonRestoreDefaultIndoorCalibrationRoute") {
            restoreDefaultIndoorCalibrationRoute()
        }
        bindOptionalClick("buttonToggleIndoorAnnotationMode") {
            isIndoorAnnotationModeEnabled = !isIndoorAnnotationModeEnabled
            renderIndoorAnnotationPanel()
            renderMapCollapseTouchLayer()
            val message = if (isIndoorAnnotationModeEnabled) {
                "室内标注模式已开启，请点击高德室内底图记录点位"
            } else {
                "室内标注模式已关闭"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        bindOptionalClick("buttonCopyIndoorAnnotations") {
            copyIndoorAnnotations()
        }
        bindOptionalClick("buttonClearIndoorAnnotations") {
            indoorAnnotationRows.clear()
            renderIndoorAnnotationPanel()
        }
    }

    private fun bindIndoorImageNavigationActions() {
        bindOptionalClick("buttonIndoorImageNavSearch") {
            searchIndoorImageNavPois()
        }
        bindOptionalClick("buttonIndoorMainNavSearch") {
            searchIndoorImageNavPois()
        }
        bindOptionalClick("buttonIndoorMainNavExit") {
            exitConferenceIndoorRoute()
        }
        bindOptionalClick("buttonIndoorMainNavSimulation") {
            toggleConferenceWalkDemo()
        }
        bindOptionalClick("buttonIndoorMainOpenRokidBridge") {
            openRokidBridgeFromPhone()
        }
        bindOptionalClick("buttonIndoorMainPairPcBackend") {
            startPcBackendPairingScan()
        }
        bindOptionalClick("buttonIndoorMainOpenRokidWifi") {
            openRokidWifiFromPhone()
        }
        binding.editIndoorImageNavSearch.setOnEditorActionListener { _, _, _ ->
            searchIndoorImageNavPois()
            true
        }
        binding.editIndoorMainNavSearch.setOnEditorActionListener { _, _, _ ->
            searchIndoorImageNavPois()
            true
        }
    }

    private fun searchIndoorImageNavPois() {
        val repository = indoorImageNavigation
        if (repository == null) {
            setIndoorImageNavSummary("本地图纸路网未加载，无法搜索。")
            return
        }
        val query = currentIndoorImageNavQuery()
        syncIndoorImageNavSearchText(query)
        indoorImageNavCandidates = repository.searchPoi(query).take(MAX_IMAGE_NAV_CANDIDATES)
        if (CONFERENCE_INDOOR_ONLY_MODE) {
            indoorImageNavCandidates.firstOrNull { it.isExactIndoorQueryMatch(query) }?.let { target ->
                planIndoorImageNavRoute(target)
                return
            }
        }
        renderIndoorImageNavCandidates()
        renderIndoorImageNavSummary()
    }

    private fun renderIndoorImageNavCandidates() {
        renderIndoorImageNavCandidatesInto(binding.layoutIndoorImageNavCandidates)
        renderIndoorImageNavCandidatesInto(binding.layoutIndoorMainNavCandidates)
    }

    private fun renderIndoorImageNavCandidatesInto(container: LinearLayout) {
        container.removeAllViews()
        val candidates = indoorImageNavCandidates
        if (candidates.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "暂无候选 POI"
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 12f
                    setPadding(0, dp(4), 0, dp(4))
                },
            )
            return
        }
        candidates.forEach { candidate ->
            container.addView(buildIndoorImageNavCandidateRow(candidate))
        }
    }

    private fun buildIndoorImageNavCandidateRow(candidate: ImageIndoorPoiResolverItem): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(R.drawable.bg_demo_card)
            setOnClickListener { planIndoorImageNavRoute(candidate) }
            addView(
                TextView(context).apply {
                    text = "${candidate.displayName()} · ${candidate.floorId} · ${candidate.indoorAvailabilityLabel()}"
                    setTextColor(0xFF111827.toInt())
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(
                TextView(context).apply {
                    text = candidate.indoorResolverSubtitle()
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
    }

    private fun planIndoorImageNavRoute(target: ImageIndoorPoiResolverItem) {
        stopConferenceWalkDemo(updateSummary = false)
        val repository = indoorImageNavigation
        if (repository == null) {
            binding.textIndoorImageNavSummary.text = "本地图纸路网未加载，无法规划。"
            return
        }
        val defaultStartNodeId = if (CONFERENCE_INDOOR_ONLY_MODE) {
            CONFERENCE_DEFAULT_IMAGE_NAV_START_NODE_ID
        } else {
            DEFAULT_IMAGE_NAV_START_NODE_ID
        }
        val startNodeId = if (CONFERENCE_INDOOR_ONLY_MODE) {
            conferencePreferredRouteStartNodeId(repository) ?: defaultStartNodeId
        } else {
            selectedIndoorImageNavEntrance()?.routeNodeId ?: defaultStartNodeId
        }
        val plan = repository.planRoute(startNodeId, target.routeNodeId)
            ?: if (CONFERENCE_INDOOR_ONLY_MODE) {
                planConferenceRouteWithSnappedStart(repository, startNodeId, target.routeNodeId)
            } else {
                null
            }
        if (plan == null) {
            indoorImageNavPlan = null
            binding.viewIndoorImageNavigation.clear()
            binding.textIndoorImageNavSummary.text = "路径规划失败：${startNodeId} -> ${target.routeNodeId}"
            renderScreen(viewModel.uiState.value)
            return
        }
        indoorImageNavPlan = plan
        pendingIndoorImageFocusTarget = true
        indoorImageNavSelectedFloorId = plan.start.floorId
        restoreCurrentImageIndoorCalibrationPoints()
        indoorImageNavCandidates = emptyList()
        syncIndoorImageNavSearchText(target.displayName())
        binding.editTargetPoiId.setText(target.poiId)
        binding.editFloorId.setText(target.floorId)
        if (CONFERENCE_INDOOR_ONLY_MODE) {
            lastConferenceHudMiniMapPositionKey = null
            viewModel.enterConferenceIndoorMode(
                floorId = plan.start.floorId,
                venueId = binding.editVenueId.text.toString().trim().ifBlank { EXHIBITION_DEMO_VENUE_ID },
                targetPoiId = target.poiId,
            )
            publishConferenceIndoorRouteHud(plan, target)
            renderScreen(viewModel.uiState.value)
            return
        }
        viewModel.useManualIndoorDemoScript(
            ImageIndoorManualDemoScriptBuilder.build(
                plan = plan,
                venueId = binding.editVenueId.text.toString().trim().ifBlank { DEFAULT_WUDAOKOU_VENUE_ID },
                targetPoiId = target.poiId,
                targetLabel = target.displayName(),
            ),
        )
        renderScreen(viewModel.uiState.value)
    }

    private fun exitConferenceIndoorRoute() {
        if (!CONFERENCE_INDOOR_ONLY_MODE) {
            binding.buttonExitIndoor.performClick()
            return
        }
        stopConferenceWalkDemo(updateSummary = false)
        indoorImageNavPlan = null
        pendingIndoorImageFocusTarget = false
        lastConferenceHudMiniMapPositionKey = null
        binding.editTargetPoiId.setText("")
        setIndoorImageNavSummary("已退出导航，请搜索展台号开始室内导航。")
        viewModel.enterConferenceIndoorMode(
            floorId = binding.editFloorId.text.toString().trim().ifBlank { CONFERENCE_DEFAULT_FLOOR_ID },
            venueId = binding.editVenueId.text.toString().trim().ifBlank { EXHIBITION_DEMO_VENUE_ID },
            targetPoiId = "",
        )
        val hudPosition = conferenceIndoorHudCurrentPosition(viewModel.uiState.value)
        RokidRuntimeBridge.sendHudUpdate(
            RokidHudPayload(
                directionArrow = "↑",
                nextAction = "等待导航",
                targetName = "未选择目标",
                floorId = binding.editFloorId.text.toString().trim().ifBlank { CONFERENCE_DEFAULT_FLOOR_ID },
                distanceToNextActionMeters = null,
                remainingDistanceMeters = null,
                remainingDurationSeconds = null,
                currentLocationName = conferenceCurrentLocationName(viewModel.uiState.value, hudPosition),
                headingState = "heading_disabled",
                statusText = "请在手机搜索展台号",
                alertText = "",
                miniMapCurrent = hudPosition?.toConferenceHudMapPoint().orEmpty(),
            ),
        )
        lastConferenceHudMiniMapPositionKey = conferenceIdleHudMiniMapPositionKey(hudPosition)
        renderScreen(viewModel.uiState.value)
    }

    private fun ImageIndoorPoiResolverItem.isExactIndoorQueryMatch(query: String): Boolean {
        val normalizedQuery = query.normalizeLooseSearchText()
        if (normalizedQuery.isBlank()) return false
        return listOf(name, displayName).plus(aliases)
            .map { it.normalizeLooseSearchText() }
            .any { it == normalizedQuery }
    }

    private fun conferencePreferredRouteStartNodeId(repository: ImageIndoorNavigationRepository): String? {
        val current = conferenceIndoorHudCurrentPosition(viewModel.uiState.value) ?: return null
        return repository.graph.nodes
            .filter { node ->
                node.floorId == current.floorId &&
                    repository.graph.outgoingEdges(node.nodeId).isNotEmpty()
            }
            .minByOrNull { node ->
                val dx = node.x - current.x
                val dy = node.y - current.y
                dx * dx + dy * dy
            }
            ?.nodeId
    }

    private fun planConferenceRouteWithSnappedStart(
        repository: ImageIndoorNavigationRepository,
        preferredStartNodeId: String,
        targetNodeId: String,
    ): ImageIndoorRoutePlan? {
        val preferredStart = repository.graph.node(preferredStartNodeId) ?: return null
        val candidates = repository.graph.nodes
            .filter { node ->
                node.floorId == preferredStart.floorId &&
                    node.nodeId != preferredStartNodeId &&
                    repository.graph.outgoingEdges(node.nodeId).isNotEmpty()
            }
            .sortedBy { node ->
                val dx = node.x - preferredStart.x
                val dy = node.y - preferredStart.y
                dx * dx + dy * dy
            }
        candidates.forEach { candidate ->
            val plan = repository.planRoute(candidate.nodeId, targetNodeId)
            if (plan != null) {
                viewModel.onAmapOutdoorNaviEvent(
                    "conference_route_start_snapped from=$preferredStartNodeId to=${candidate.nodeId} target=$targetNodeId",
                )
                return plan
            }
        }
        return null
    }

    private fun toggleConferenceWalkDemo() {
        if (isConferenceWalkDemoRunning()) {
            stopConferenceWalkDemo(updateSummary = true)
            return
        }
        val plan = indoorImageNavPlan
        if (plan == null) {
            Toast.makeText(this, "请先完成路径规划", Toast.LENGTH_SHORT).show()
            return
        }
        val target = currentConferenceIndoorRouteTarget(plan)
        if (target == null) {
            Toast.makeText(this, "未找到当前导航目标", Toast.LENGTH_SHORT).show()
            return
        }
        val frames = buildConferenceWalkDemoFrames(plan)
        if (frames.isEmpty()) {
            Toast.makeText(this, "当前路线无法播放模拟步行", Toast.LENGTH_SHORT).show()
            return
        }
        lastConferenceHudMiniMapPositionKey = null
        conferenceWalkDemoJob = lifecycleScope.launch {
            runConferenceWalkDemo(plan, target, frames)
        }
        binding.buttonIndoorMainNavSimulation.text = getString(R.string.action_indoor_stop_walk_demo)
        setIndoorImageNavSummary("模拟步行已开始，眼镜端将同步刷新当前位置和提示。")
        viewModel.onAmapOutdoorNaviEvent("conference_walk_demo_start target_poi_id=${target.poiId} frames=${frames.size}")
        renderScreen(viewModel.uiState.value)
    }

    private suspend fun runConferenceWalkDemo(
        plan: ImageIndoorRoutePlan,
        target: ImageIndoorPoiResolverItem,
        frames: List<ConferenceWalkDemoFrame>,
    ) {
        try {
            frames.forEach { frame ->
                val progress = conferenceHudRouteProgress(plan, target, frame.position)
                val summary = "模拟步行：${progress.nextAction} · 剩余 ${progress.remainingDistanceMeters?.formatIndoorHudDistance() ?: "-"}"
                conferenceDisplayedCurrentPosition = frame.position
                viewModel.updateConferenceWalkDemoPosition(
                    floorId = frame.position.floorId,
                    x = frame.position.x,
                    y = frame.position.y,
                    routeSummary = summary,
                )
                binding.viewIndoorImageNavigation.setCurrentPosition(
                    floorId = frame.position.floorId,
                    x = frame.position.x,
                    y = frame.position.y,
                    confidence = 1.0,
                )
                publishConferenceIndoorRouteHud(plan, target, frame.position)
                setIndoorImageNavSummary(summary)
                delay(CONFERENCE_WALK_DEMO_FRAME_INTERVAL_MS)
            }
            val targetPosition = IndoorMapPosition(plan.target.floorId, plan.target.x, plan.target.y)
            conferenceDisplayedCurrentPosition = targetPosition
            viewModel.updateConferenceWalkDemoPosition(
                floorId = targetPosition.floorId,
                x = targetPosition.x,
                y = targetPosition.y,
                routeSummary = "模拟步行：已到达 ${target.displayName()}",
            )
            publishConferenceIndoorRouteHud(plan, target, targetPosition)
            setIndoorImageNavSummary("模拟步行完成：已到达 ${target.displayName()}。")
            viewModel.onAmapOutdoorNaviEvent("conference_walk_demo_complete target_poi_id=${target.poiId}")
        } finally {
            conferenceWalkDemoJob = null
            renderScreen(viewModel.uiState.value)
        }
    }

    private fun stopConferenceWalkDemo(updateSummary: Boolean) {
        val wasRunning = isConferenceWalkDemoRunning()
        conferenceWalkDemoJob?.cancel()
        conferenceWalkDemoJob = null
        if (updateSummary && wasRunning) {
            setIndoorImageNavSummary("模拟步行已停止，可再次点击开始模拟步行。")
            viewModel.onAmapOutdoorNaviEvent("conference_walk_demo_stopped")
            renderScreen(viewModel.uiState.value)
        }
    }

    private fun isConferenceWalkDemoRunning(): Boolean {
        return conferenceWalkDemoJob?.isActive == true
    }

    private fun buildConferenceWalkDemoFrames(plan: ImageIndoorRoutePlan): List<ConferenceWalkDemoFrame> {
        val metersPerFrame = CONFERENCE_WALK_DEMO_SPEED_MPS * (CONFERENCE_WALK_DEMO_FRAME_INTERVAL_MS / 1000.0)
        return buildList {
            plan.edges.forEachIndexed { index, edge ->
                val from = plan.nodes.getOrNull(index) ?: return@forEachIndexed
                val to = plan.nodes.getOrNull(index + 1) ?: return@forEachIndexed
                if (from.floorId != to.floorId) {
                    add(ConferenceWalkDemoFrame(IndoorMapPosition(to.floorId, to.x, to.y)))
                    return@forEachIndexed
                }
                val edgeMeters = conferenceEdgeDistanceMeters(edge).coerceAtLeast(0.1)
                val steps = kotlin.math.ceil(edgeMeters / metersPerFrame).toInt().coerceAtLeast(1)
                val startStep = if (index == 0) 0 else 1
                for (step in startStep..steps) {
                    val progress = (step.toDouble() / steps.toDouble()).coerceIn(0.0, 1.0)
                    add(
                        ConferenceWalkDemoFrame(
                            IndoorMapPosition(
                                floorId = from.floorId,
                                x = from.x + (to.x - from.x) * progress,
                                y = from.y + (to.y - from.y) * progress,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun publishConferenceIndoorRouteHud(
        plan: ImageIndoorRoutePlan,
        target: ImageIndoorPoiResolverItem,
        currentPositionOverride: IndoorMapPosition? = null,
    ) {
        val currentPosition = currentPositionOverride ?: conferenceIndoorHudCurrentPosition(viewModel.uiState.value)
        val progress = conferenceHudRouteProgress(plan, target, currentPosition)
        val durationSeconds = conferenceRouteDurationSeconds(progress.remainingDistanceMeters)
        val payload = RokidHudPayload(
            directionArrow = progress.directionArrow,
            nextAction = progress.nextAction,
            targetName = target.displayName(),
            floorId = currentPosition?.floorId ?: plan.start.floorId,
            distanceToNextActionMeters = progress.distanceToNextActionMeters,
            remainingDistanceMeters = progress.remainingDistanceMeters,
            remainingDurationSeconds = durationSeconds,
            currentLocationName = conferenceCurrentLocationName(viewModel.uiState.value, currentPosition),
            headingState = "heading_disabled",
            statusText = buildConferenceHudStatus(progress.remainingDistanceMeters, durationSeconds),
            miniMapRoute = buildConferenceHudMiniMapRoute(plan),
            miniMapCurrent = currentPosition?.toConferenceHudMapPoint() ?: plan.start.toConferenceHudMapPoint(),
            miniMapTarget = plan.target.toConferenceHudMapPoint(),
            mapHeadingDeg = null,
        )
        val sent = RokidRuntimeBridge.sendHudUpdate(payload)
        lastConferenceHudMiniMapPositionKey = conferenceHudMiniMapPositionKey(currentPosition, plan, target)
        viewModel.onAmapOutdoorNaviEvent("conference_hud_update target_poi_id=${target.poiId} sent=$sent ${payload.summary()}")
    }

    private fun publishConferenceIndoorRouteHudIfPositionChanged(
        state: PocUiState,
        plan: ImageIndoorRoutePlan,
    ) {
        val target = currentConferenceIndoorRouteTarget(plan) ?: return
        val currentPosition = conferenceIndoorHudCurrentPosition(state)
        val currentKey = conferenceHudMiniMapPositionKey(currentPosition, plan, target)
        if (currentKey == lastConferenceHudMiniMapPositionKey) return
        lastConferenceHudMiniMapPositionKey = currentKey
        publishConferenceIndoorRouteHud(plan, target)
    }

    private fun publishConferenceIndoorIdleHudIfPositionChanged(state: PocUiState) {
        val currentPosition = conferenceIndoorHudCurrentPosition(state)
        val currentKey = conferenceIdleHudMiniMapPositionKey(currentPosition)
        if (currentKey == lastConferenceHudMiniMapPositionKey) return
        val floorId = currentPosition?.floorId
            ?: binding.editFloorId.text.toString().trim().ifBlank { CONFERENCE_DEFAULT_FLOOR_ID }
        val payload = RokidHudPayload(
            directionArrow = "↑",
            nextAction = "等待导航",
            targetName = "未选择目标",
            floorId = floorId,
            distanceToNextActionMeters = null,
            remainingDistanceMeters = null,
            remainingDurationSeconds = null,
            currentLocationName = conferenceCurrentLocationName(state, currentPosition),
            headingState = "heading_disabled",
            statusText = if (currentPosition == null) "等待定位" else "已显示当前位置",
            alertText = "",
            miniMapRoute = "",
            miniMapCurrent = currentPosition?.toConferenceHudMapPoint().orEmpty(),
            miniMapTarget = "",
            mapHeadingDeg = null,
        )
        val sent = RokidRuntimeBridge.sendHudUpdate(payload)
        lastConferenceHudMiniMapPositionKey = currentKey
        viewModel.onAmapOutdoorNaviEvent("conference_hud_idle_update sent=$sent ${payload.summary()}")
    }

    private fun conferenceHudRouteProgress(
        plan: ImageIndoorRoutePlan,
        target: ImageIndoorPoiResolverItem,
        currentPosition: IndoorMapPosition?,
    ): ConferenceHudRouteProgress {
        if (plan.edges.isEmpty()) {
            return ConferenceHudRouteProgress("✓", "已到达", null, 0.0)
        }
        val projection = currentPosition?.let { conferenceNearestRouteProjection(plan, it) }
        val edgeIndex = projection?.edgeIndex ?: 0
        val edge = plan.edges.getOrNull(edgeIndex)
        val remainingGraphDistance = if (projection != null) {
            projection.distanceToEdgeEnd + plan.edges.drop(edgeIndex + 1).sumOf { it.distance }
        } else {
            plan.edges.sumOf { it.distance }
        }
        val remainingMeters = (remainingGraphDistance * CONFERENCE_INDOOR_METERS_PER_GRAPH_UNIT).coerceAtLeast(0.0)
        if (remainingMeters <= CONFERENCE_WALK_DEMO_ARRIVAL_THRESHOLD_M) {
            return ConferenceHudRouteProgress("✓", "已到达", null, 0.0)
        }
        val currentEdgeRemainingDistance = projection?.distanceToEdgeEnd ?: edge?.distance ?: 0.0
        val nextAction = conferenceHudNextAction(plan, target, edgeIndex, currentEdgeRemainingDistance)
        return ConferenceHudRouteProgress(
            directionArrow = nextAction.directionArrow,
            nextAction = nextAction.nextAction,
            distanceToNextActionMeters = nextAction.distanceToNextActionMeters,
            remainingDistanceMeters = remainingMeters,
        )
    }

    private fun conferenceHudNextAction(
        plan: ImageIndoorRoutePlan,
        target: ImageIndoorPoiResolverItem,
        edgeIndex: Int,
        currentEdgeRemainingDistance: Double,
    ): ConferenceHudNextAction {
        val currentEdge = plan.edges.getOrNull(edgeIndex)
            ?: return ConferenceHudNextAction("↑", conferenceTargetNearbyText(target), null)
        val currentNextNode = plan.nodes.getOrNull(edgeIndex + 1)
        if (conferenceIsVerticalEdge(plan, edgeIndex)) {
            return ConferenceHudNextAction(
                directionArrow = conferenceHudArrow(currentEdge),
                nextAction = conferenceHudAction(currentEdge, currentNextNode, target),
                distanceToNextActionMeters = currentEdgeRemainingDistance.toConferenceMeters(),
            )
        }

        var distanceToAction = currentEdgeRemainingDistance.coerceAtLeast(0.0)
        var previousVector = conferenceEdgeVector(plan, edgeIndex)
        for (nextEdgeIndex in (edgeIndex + 1) until plan.edges.size) {
            val nextEdge = plan.edges[nextEdgeIndex]
            val nextNode = plan.nodes.getOrNull(nextEdgeIndex + 1)
            if (conferenceIsVerticalEdge(plan, nextEdgeIndex)) {
                return ConferenceHudNextAction(
                    directionArrow = conferenceHudArrow(nextEdge),
                    nextAction = conferenceHudAction(nextEdge, nextNode, target),
                    distanceToNextActionMeters = distanceToAction.toConferenceMeters(),
                )
            }

            val nextEdgeMeters = conferenceEdgeDistanceMeters(nextEdge)
            val nextVector = conferenceEdgeVector(plan, nextEdgeIndex)
            if (
                previousVector != null &&
                nextVector != null &&
                nextEdgeMeters >= CONFERENCE_HUD_MIN_TURN_EDGE_METERS &&
                conferenceTurnAngleDegrees(previousVector, nextVector) in
                CONFERENCE_HUD_TURN_MIN_ANGLE_DEGREES..CONFERENCE_HUD_TURN_MAX_ANGLE_DEGREES
            ) {
                val turnLabel = conferenceTurnLabel(previousVector, nextVector)
                return ConferenceHudNextAction(
                    directionArrow = turnLabel.first,
                    nextAction = turnLabel.second,
                    distanceToNextActionMeters = distanceToAction.toConferenceMeters(),
                )
            }

            distanceToAction += nextEdge.distance
            if (nextEdgeMeters >= CONFERENCE_HUD_MIN_TURN_EDGE_METERS && nextVector != null) {
                previousVector = nextVector
            }
        }

        return ConferenceHudNextAction(
            directionArrow = conferenceHudArrow(currentEdge),
            nextAction = conferenceTargetNearbyText(target),
            distanceToNextActionMeters = distanceToAction.toConferenceMeters(),
        )
    }

    private fun conferenceNearestRouteProjection(
        plan: ImageIndoorRoutePlan,
        currentPosition: IndoorMapPosition,
    ): ConferenceRouteProjection? {
        return plan.edges.indices
            .mapNotNull { index ->
                val from = plan.nodes.getOrNull(index) ?: return@mapNotNull null
                val to = plan.nodes.getOrNull(index + 1) ?: return@mapNotNull null
                if (from.floorId != currentPosition.floorId || to.floorId != currentPosition.floorId) {
                    return@mapNotNull null
                }
                val dx = to.x - from.x
                val dy = to.y - from.y
                val lengthSquared = dx * dx + dy * dy
                val t = if (lengthSquared <= 0.0001) {
                    0.0
                } else {
                    (((currentPosition.x - from.x) * dx + (currentPosition.y - from.y) * dy) / lengthSquared)
                        .coerceIn(0.0, 1.0)
                }
                val projectedX = from.x + dx * t
                val projectedY = from.y + dy * t
                val score = (currentPosition.x - projectedX) * (currentPosition.x - projectedX) +
                    (currentPosition.y - projectedY) * (currentPosition.y - projectedY)
                ConferenceRouteProjection(
                    edgeIndex = index,
                    distanceToEdgeEnd = plan.edges[index].distance * (1.0 - t),
                    score = score,
                )
            }
            .minByOrNull { it.score }
    }

    private fun currentConferenceIndoorRouteTarget(plan: ImageIndoorRoutePlan): ImageIndoorPoiResolverItem? {
        val targetPoiId = binding.editTargetPoiId.text.toString().trim()
        return indoorImageNavigation?.resolverItem(targetPoiId)
            ?.takeIf { it.routeNodeId == plan.target.nodeId }
    }

    private fun conferenceCurrentLocationName(
        state: PocUiState,
        currentPosition: IndoorMapPosition?,
    ): String {
        state.lastMatchedLandmarkDisplayName
            ?.toConferenceLocationLabel()
            ?.let { return it }
        state.lastMatchedLandmarkPoiId
            ?.toConferenceLocationLabel()
            ?.let { return it }

        val repository = indoorImageNavigation ?: return ""
        state.lastMatchedLandmarkPoiId
            ?.let { repository.resolverItem(it) }
            ?.displayName()
            ?.toConferenceLocationLabel()
            ?.let { return it }

        val current = currentPosition ?: return ""
        return repository.resolverItems()
            .mapNotNull { item ->
                val node = repository.graph.node(item.routeNodeId) ?: return@mapNotNull null
                if (node.floorId != current.floorId) return@mapNotNull null
                val dx = node.x - current.x
                val dy = node.y - current.y
                item to (dx * dx + dy * dy)
            }
            .minByOrNull { it.second }
            ?.first
            ?.displayName()
            ?.toConferenceLocationLabel()
            .orEmpty()
    }

    private fun conferenceRouteSummaryStartLabel(
        state: PocUiState,
        currentPosition: IndoorMapPosition?,
        plan: ImageIndoorRoutePlan,
    ): String {
        conferenceCurrentLocationName(state, currentPosition)
            .takeIf { it.isNotBlank() }
            ?.let { return it }
        val repository = indoorImageNavigation ?: return "当前位置"
        return repository.resolverItems()
            .firstOrNull { item -> item.routeNodeId == plan.start.nodeId }
            ?.displayName()
            ?.toConferenceLocationLabel()
            ?: "当前位置"
    }

    private fun String.toConferenceLocationLabel(): String? {
        val text = trim()
        if (text.isBlank()) return null
        Regex("[A-Fa-f]\\d{1,2}").find(text)
            ?.value
            ?.uppercase(Locale.US)
            ?.let { return it }
        return when {
            text.contains("入口") || text.equals("entrance", ignoreCase = true) -> "入口"
            text.contains("出口") || text.equals("exit", ignoreCase = true) -> "出口"
            text.contains("二维码") || text.contains("QR", ignoreCase = true) -> "QR"
            text.contains("机器人") || text.contains("robot", ignoreCase = true) -> "机器人"
            else -> text.takeIf { it.length <= 8 }
        }
    }

    private fun conferenceHudMiniMapPositionKey(
        currentPosition: IndoorMapPosition?,
        plan: ImageIndoorRoutePlan,
        target: ImageIndoorPoiResolverItem,
    ): String {
        val positionKey = conferenceIndoorPositionKey(currentPosition) ?: "start|${plan.start.nodeId}"
        return "$positionKey|${plan.target.nodeId}|${target.poiId}"
    }

    private fun conferenceIdleHudMiniMapPositionKey(currentPosition: IndoorMapPosition?): String {
        return "idle|${conferenceIndoorPositionKey(currentPosition) ?: "none"}"
    }

    private fun conferenceIndoorPositionKey(currentPosition: IndoorMapPosition?): String? {
        return currentPosition?.let {
            "${it.floorId}|${it.x.formatOneDecimal()}|${it.y.formatOneDecimal()}"
        }
    }

    private fun conferenceRouteDistanceMeters(plan: ImageIndoorRoutePlan): Double? {
        val graphDistance = plan.edges.sumOf { edge -> edge.distance }
        return graphDistance.takeIf { it > 0.0 }?.let { it * CONFERENCE_INDOOR_METERS_PER_GRAPH_UNIT }
    }

    private fun conferenceEdgeDistanceMeters(edge: com.aiglasses.poc.indoor.ImageIndoorNavEdge): Double {
        return edge.distance * CONFERENCE_INDOOR_METERS_PER_GRAPH_UNIT
    }

    private fun Double.toConferenceMeters(): Double {
        return (this * CONFERENCE_INDOOR_METERS_PER_GRAPH_UNIT).coerceAtLeast(0.0)
    }

    private fun conferenceIsVerticalEdge(plan: ImageIndoorRoutePlan, edgeIndex: Int): Boolean {
        val edge = plan.edges.getOrNull(edgeIndex) ?: return false
        val from = plan.nodes.getOrNull(edgeIndex)
        val to = plan.nodes.getOrNull(edgeIndex + 1)
        return edge.travelMode != "walk" || (from != null && to != null && from.floorId != to.floorId)
    }

    private fun conferenceEdgeVector(plan: ImageIndoorRoutePlan, edgeIndex: Int): ConferenceRouteVector? {
        val from = plan.nodes.getOrNull(edgeIndex) ?: return null
        val to = plan.nodes.getOrNull(edgeIndex + 1) ?: return null
        val deltaX = to.x - from.x
        val deltaY = to.y - from.y
        val length = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
        if (length <= 0.0001) return null
        return ConferenceRouteVector(deltaX / length, deltaY / length)
    }

    private fun conferenceTurnAngleDegrees(
        previousVector: ConferenceRouteVector,
        nextVector: ConferenceRouteVector,
    ): Double {
        val dotProduct = (previousVector.deltaX * nextVector.deltaX + previousVector.deltaY * nextVector.deltaY)
            .coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(dotProduct))
    }

    private fun conferenceTurnLabel(
        previousVector: ConferenceRouteVector,
        nextVector: ConferenceRouteVector,
    ): Pair<String, String> {
        val crossProduct = previousVector.deltaX * nextVector.deltaY - previousVector.deltaY * nextVector.deltaX
        return if (crossProduct >= 0.0) {
            "→" to "向右转"
        } else {
            "←" to "向左转"
        }
    }

    private fun conferenceRouteDurationSeconds(distanceMeters: Double?): Double? {
        return distanceMeters?.takeIf { it > 0.0 }?.let { it / CONFERENCE_INDOOR_WALKING_SPEED_MPS }
    }

    private fun conferenceHudArrow(edge: com.aiglasses.poc.indoor.ImageIndoorNavEdge): String {
        val from = indoorImageNavigation?.graph?.node(edge.fromNodeId) ?: return "↑"
        val to = indoorImageNavigation?.graph?.node(edge.toNodeId) ?: return "↑"
        if (edge.travelMode != "walk" || from.floorId != to.floorId) {
            return if (to.floorId.floorRank() > from.floorId.floorRank()) "↥" else "↧"
        }
        val deltaX = to.x - from.x
        val deltaY = to.y - from.y
        return if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.2) {
            if (deltaX > 0.0) "→" else "←"
        } else {
            "↑"
        }
    }

    private fun conferenceHudAction(
        edge: com.aiglasses.poc.indoor.ImageIndoorNavEdge,
        nextNode: ImageIndoorNavNode?,
        target: ImageIndoorPoiResolverItem,
    ): String {
        val from = indoorImageNavigation?.graph?.node(edge.fromNodeId)
        val to = indoorImageNavigation?.graph?.node(edge.toNodeId)
        if (edge.travelMode != "walk" || (from != null && to != null && from.floorId != to.floorId)) {
            val toFloorId = to?.floorId ?: nextNode?.floorId ?: ""
            return if ((to?.floorId ?: "").floorRank() > (from?.floorId ?: "").floorRank()) {
                "上楼到 $toFloorId"
            } else {
                "下楼到 $toFloorId"
            }
        }
        val targetNearbyText = conferenceTargetNearbyText(target)
        val isApproachingTarget = nextNode?.nodeId == target.routeNodeId || to?.nodeId == target.routeNodeId
        return when (conferenceHudArrow(edge)) {
            "←" -> "向左转"
            "→" -> "向右转"
            else -> if (isApproachingTarget) targetNearbyText else "直行"
        }
    }

    private fun conferenceTargetNearbyText(target: ImageIndoorPoiResolverItem): String {
        val targetLabel = target.displayName().toConferenceLocationLabel() ?: target.displayName()
        return if (Regex("^[A-F]\\d{1,2}$").matches(targetLabel)) {
            "步行到 $targetLabel 展台附近"
        } else {
            "步行到 $targetLabel 附近"
        }
    }

    private fun buildConferenceHudStatus(
        remainingDistanceMeters: Double?,
        remainingDurationSeconds: Double?,
    ): String {
        val distance = remainingDistanceMeters?.formatIndoorHudDistance() ?: "距离待计算"
        val duration = remainingDurationSeconds?.formatIndoorHudDuration() ?: "时间待计算"
        return "剩余 $distance · 约 $duration"
    }

    private fun Double.formatIndoorHudDistance(): String {
        return if (this < 1000.0) {
            "${toInt()}米"
        } else {
            String.format(Locale.US, "%.1f公里", this / 1000.0)
        }
    }

    private fun Double.formatIndoorHudDuration(): String {
        val totalSeconds = toInt().coerceAtLeast(1)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}分${seconds}秒"
        } else {
            "${seconds}秒"
        }
    }

    private fun buildConferenceHudMiniMapRoute(plan: ImageIndoorRoutePlan): String {
        val points = plan.nodes.ifEmpty { listOf(plan.start, plan.target) }
        fun ImageIndoorNavNode.normalize(): String {
            return toConferenceHudMapPoint()
        }
        return points.map { it.normalize() }.removeAdjacentDuplicateStrings().joinToString(";")
    }

    private fun ImageIndoorNavNode.toConferenceHudMapPoint(): String {
        return "${normalizeConferenceHudMapX(x)},${normalizeConferenceHudMapY(y)}"
    }

    private fun IndoorMapPosition.toConferenceHudMapPoint(): String {
        return "${normalizeConferenceHudMapX(x)},${normalizeConferenceHudMapY(y)}"
    }

    private fun normalizeConferenceHudMapX(x: Double): Int {
        return ((x / CONFERENCE_MAP_CROP_WIDTH_PX) * 1000.0).toInt().coerceIn(0, 1000)
    }

    private fun normalizeConferenceHudMapY(y: Double): Int {
        return ((y / CONFERENCE_MAP_CROP_HEIGHT_PX) * 1000.0).toInt().coerceIn(0, 1000)
    }

    private fun List<String>.removeAdjacentDuplicateStrings(): List<String> {
        val result = mutableListOf<String>()
        forEach { value ->
            if (result.lastOrNull() != value) {
                result.add(value)
            }
        }
        return result
    }

    private fun String.floorRank(): Int {
        val normalized = trim().uppercase(Locale.US)
        return when {
            normalized.startsWith("B") -> -(normalized.drop(1).toIntOrNull() ?: 0)
            normalized.startsWith("F") -> normalized.drop(1).toIntOrNull() ?: 0
            else -> normalized.toIntOrNull() ?: 0
        }
    }

    private fun planConferenceDefaultIndoorRoute(): Boolean {
        if (!CONFERENCE_INDOOR_ONLY_MODE) return false
        val target = indoorImageNavigation?.resolverItem(CONFERENCE_DEFAULT_TARGET_POI_ID) ?: return false
        planIndoorImageNavRoute(target)
        return indoorImageNavPlan?.target?.nodeId == target.routeNodeId
    }

    private fun enterVenueIfIndoorTargetSupported() {
        val selectedPoi = selectedOutdoorPoi
        val indoorTarget = selectedPoi?.let { unifiedIndoorTargetFor(it) }
        if (selectedPoi != null && indoorTarget == null) {
            showUnsupportedIndoorNavigation(selectedPoi.title)
            return
        }
        if (selectedPoi == null && indoorImageNavPlan == null) {
            showUnsupportedIndoorNavigation(null)
            return
        }
        indoorTarget?.let { target ->
            if (indoorImageNavPlan?.target?.nodeId != target.routeNodeId) {
                planIndoorImageNavRoute(target)
            }
            if (indoorImageNavPlan?.target?.nodeId != target.routeNodeId) {
                showUnsupportedIndoorNavigation(target.displayName())
                return
            }
        }
        persistSettings()
        if (::outdoorNavigator.isInitialized) {
            outdoorNavigator.stopNavigation()
        }
        externalNavigationSession = null
        pendingExternalReturnCheck = false
        viewModel.enterVenue()
    }

    private fun showUnsupportedIndoorNavigation(targetName: String?) {
        val subject = targetName?.takeIf { it.isNotBlank() } ?: "该场馆"
        val message = "$subject 暂不支持室内导航"
        clearIndoorImageNavRoute()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        viewModel.onAmapOutdoorNaviEvent(message)
    }

    private fun selectIndoorImageNavFloor(floorId: String) {
        if (!isKnownIndoorImageFloor(floorId)) {
            Toast.makeText(this, "当前路网不包含 $floorId", Toast.LENGTH_SHORT).show()
            return
        }
        indoorImageNavSelectedFloorId = floorId
        restoreCurrentImageIndoorCalibrationPoints()
        renderScreen(viewModel.uiState.value)
    }

    private fun selectIndoorCalibrationFloor(floorId: String) {
        val hasFloorNodes = indoorImageNavigation?.graph?.nodes
            ?.any { it.floorId == floorId } == true
        if (!hasFloorNodes) {
            Toast.makeText(this, "当前路线没有 $floorId 校准点", Toast.LENGTH_SHORT).show()
            return
        }
        indoorImageNavSelectedFloorId = floorId
        pendingManualCalibrationFloorId = floorId
        restoreCurrentImageIndoorCalibrationPoints()
        renderScreen(viewModel.uiState.value)
        Toast.makeText(this, "校准层已切换到 $floorId", Toast.LENGTH_SHORT).show()
    }

    private fun ensureIndoorImageNavSelectedFloor() {
        val floorIds = indoorImageFloorIds()
        if (floorIds.isEmpty() || indoorImageNavSelectedFloorId in floorIds) {
            return
        }
        indoorImageNavSelectedFloorId = floorIds.firstOrNull { it == SAMPLE_ENTRY_FLOOR } ?: floorIds.first()
    }

    private fun isKnownIndoorImageFloor(floorId: String): Boolean {
        return indoorImageFloorIds().contains(floorId)
    }

    private fun indoorImageFloorIds(): List<String> {
        return indoorImageNavigation?.graph?.floors
            ?.map { it.floorId }
            .orEmpty()
    }

    private fun renderIndoorImageFloorButtons() {
        val floorIds = indoorImageFloorIds()
        renderIndoorFloorButtonRow(binding.layoutIndoorMainNavFloorRow, floorIds, ::selectIndoorImageNavFloor)
        renderIndoorFloorButtonRow(binding.layoutIndoorImageNavFloorRow, floorIds, ::selectIndoorImageNavFloor)
        renderIndoorFloorButtonRow(binding.layoutIndoorCalibrationFloorRow, floorIds, ::selectIndoorCalibrationFloor)
        styleIndoorImageFloorButtons()
    }

    private fun renderIndoorFloorButtonRow(
        row: LinearLayout,
        floorIds: List<String>,
        onFloorSelected: (String) -> Unit,
    ) {
        row.removeAllViews()
        floorIds.forEachIndexed { index, floorId ->
            row.addView(
                Button(this).apply {
                    text = floorId
                    textSize = 12f
                    minWidth = 0
                    minHeight = 0
                    minimumWidth = 0
                    minimumHeight = 0
                    setAllCaps(false)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { onFloorSelected(floorId) }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        dp(36),
                        1f,
                    ).apply {
                        if (index > 0) {
                            marginStart = dp(6)
                        }
                    }
                },
            )
        }
    }

    private fun currentIndoorImageNavQuery(): String {
        return binding.editIndoorMainNavSearch.text.toString().trim()
            .ifBlank { binding.editIndoorImageNavSearch.text.toString().trim() }
    }

    private fun syncIndoorImageNavSearchText(text: String) {
        binding.editIndoorMainNavSearch.setText(text)
        binding.editIndoorMainNavSearch.setSelection(binding.editIndoorMainNavSearch.text.length)
        binding.editIndoorImageNavSearch.setText(text)
        binding.editIndoorImageNavSearch.setSelection(binding.editIndoorImageNavSearch.text.length)
    }

    private fun selectedIndoorImageNavEntrance(): ImageIndoorEntrance? {
        val position = binding.spinnerIndoorImageNavEntrance.selectedItemPosition
        return indoorImageNavEntrances.getOrNull(position)
    }

    private fun scheduleOutdoorPoiSearch() {
        if (suppressOutdoorSearchTextWatcher) {
            return
        }
        val keyword = binding.editOutdoorSearchKeyword.text.toString().trim()
        if (keyword.length < MIN_OUTDOOR_SEARCH_KEYWORD_LENGTH) {
            outdoorSearchJob?.cancel()
            rawOutdoorPoiOptions = emptyList()
            outdoorPoiOptions = emptyList()
            renderOutdoorPoiResults(emptyList())
            outdoorSearchUiState = if (keyword.isBlank()) {
                SearchUiState.IDLE_HOME
            } else {
                SearchUiState.EDITING
            }
            renderScreen(viewModel.uiState.value)
            return
        }
        outdoorSearchUiState = SearchUiState.RESULTS_EXPANDED
        rawOutdoorPoiOptions = emptyList()
        outdoorPoiOptions = emptyList()
        renderOutdoorPoiResults(emptyList())
        renderScreen(viewModel.uiState.value)
        outdoorSearchJob?.cancel()
        outdoorSearchJob = lifecycleScope.launch {
            delay(OUTDOOR_SEARCH_DEBOUNCE_MS)
            if (!isFinishing && !isDestroyed) {
                searchOutdoorPoi()
            }
        }
    }

    private fun applyOutdoorPoiSearchResult(items: List<OutdoorPoiOption>) {
        val sortedItems = sortOutdoorPoiOptions(items)
        rawOutdoorPoiOptions = sortedItems
        outdoorPoiOptions = filterOutdoorPoiOptions(sortedItems)
        outdoorSearchUiState = SearchUiState.RESULTS_EXPANDED
        suppressOutdoorPoiSelectionCallback = true
        try {
            outdoorPoiAdapter.clear()
            if (outdoorPoiOptions.isEmpty()) {
                outdoorPoiAdapter.add(getString(R.string.outdoor_poi_empty))
            } else {
                outdoorPoiAdapter.add(OUTDOOR_POI_SELECT_PROMPT)
                outdoorPoiAdapter.addAll(outdoorPoiOptions.map { it.label() })
            }
            outdoorPoiAdapter.notifyDataSetChanged()
            binding.spinnerOutdoorPoi.setSelection(0, false)
        } finally {
            suppressOutdoorPoiSelectionCallback = false
        }
        renderOutdoorPoiResults(outdoorPoiOptions)
    }

    private fun renderOutdoorPoiResults(items: List<OutdoorPoiOption>) {
        val container = binding.layoutOutdoorPoiResultList
        container.removeAllViews()
        binding.textOutdoorSearchResultsTitle.text = getString(R.string.title_search_results)
        if (items.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(16), 0, dp(8))
                    text = "未找到合适结果，请换个关键词试试"
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 13f
                },
            )
            return
        }
        items.forEachIndexed { index, poi ->
            container.addView(buildOutdoorPoiResultRow(poi, index))
        }
        container.addView(
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(10), 0, dp(4))
                text = "共找到 ${items.size} 个结果"
                setTextColor(0xFF6B7280.toInt())
                textSize = 12f
            },
        )
    }

    private fun buildOutdoorPoiResultRow(
        poi: OutdoorPoiOption,
        index: Int,
    ): View {
        val visualType = resolveVisualSearchType(poi)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also {
                if (index > 0) {
                    it.topMargin = dp(0)
                }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                applyOutdoorPoiToEntry(
                    poi = poi,
                    sourceLabel = "搜索结果选择",
                    previewOnMap = true,
                    updateSearchText = true,
                    collapseResults = true,
                )
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            minimumHeight = dp(68)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }

        val badge = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = getSearchBadgeDrawable(visualType)
            setImageResource(visualType.badgeIconResId())
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(12), 0, dp(10), 0)
        }

        val titleView = TextView(this).apply {
            text = poi.title
            setTextColor(0xFF111827.toInt())
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val addressView = TextView(this).apply {
            text = poi.addressLine()
            setTextColor(0xFF6B7280.toInt())
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        titleColumn.addView(titleView)
        titleColumn.addView(addressView)
        poi.indoorInfoLine()?.let { indoorInfo ->
            titleColumn.addView(
                TextView(this).apply {
                    text = indoorInfo
                    setTextColor(0xFF0F766E.toInt())
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                },
            )
        }

        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val distanceView = TextView(this).apply {
            text = poi.distanceLabel(lastOutdoorLocationPoint())
            setTextColor(0xFF4B5563.toInt())
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val navHint = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            setImageResource(resolveDirectionIconRes(poi, visualType))
            setColorFilter(0xFF243B53.toInt())
        }
        rightColumn.addView(distanceView)
        rightColumn.addView(navHint.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(5)
        })

        content.addView(badge)
        content.addView(titleColumn)
        content.addView(rightColumn)

        root.addView(content)
        if (index < outdoorPoiOptions.lastIndex) {
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(0xFFE7E3DB.toInt())
            })
        }
        return root
    }

    override fun onDestroy() {
        rokidHttpAutoStreamClient?.stop()
        rokidHttpAutoStreamClient = null
        RokidRuntimeBridge.setHttpHudCommandSender(null)
        rokidBridgeAutoLaunchRepository?.release()
        rokidBridgeAutoLaunchRepository = null
        if (::indoorBasemapController.isInitialized) {
            indoorBasemapController.onDestroy()
        }
        if (::outdoorNavigator.isInitialized) {
            outdoorNavigator.onDestroy()
        }
        if (::outdoorDiscovery.isInitialized) {
            outdoorDiscovery.destroy()
        }
        if (::navigationVoiceGuide.isInitialized) {
            navigationVoiceGuide.shutdown()
        }
        super.onDestroy()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    syncProviderSpinner(state.selectedProviderId)
                    renderScreen(state)
                    announceManualIndoorNavigationIfNeeded(state)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HeyCyanGlassesManager.state.collect { glassesState ->
                    latestGlassesState = glassesState
                    tryBindRecordingAnnotation(glassesState.localMedia)
                    renderGlassesCornerStatus(glassesState)
                    renderManualRecordingAnnotationPanel()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbCameraRecordingManager.state.collect { usbState ->
                    latestUsbCameraState = usbState
                    if (usbState.available) {
                        enforceUsbCameraPriority()
                    }
                    if (pendingUsbAnnotationStart && usbState.ready) {
                        pendingUsbAnnotationStart = false
                        startUsbRecordingAnnotation()
                    }
                    tryBindUsbRecordingAnnotation(usbState.localMedia)
                    renderGlassesCornerStatus()
                    renderManualRecordingAnnotationPanel()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    handleRokidVoiceCommandIfNeeded(RokidRuntimeBridge.latestVoiceCommand())
                    delay(100L)
                }
            }
        }
    }

    private fun handleRokidVoiceCommandIfNeeded(command: VoiceNavigationCommand?) {
        command ?: return
        if (command.requestId == lastHandledRokidVoiceRequestId) return
        lastHandledRokidVoiceRequestId = command.requestId
        when (command.intent) {
            "navigate_to" -> handleRokidNavigateVoice(command)
            "relocalize" -> {
                Toast.makeText(this, "Rokid：重新定位", Toast.LENGTH_SHORT).show()
                val providerId = selectProviderForRokidVoiceLocate()
                viewModel.onAmapOutdoorNaviEvent("Rokid 语音重新定位：provider=$providerId")
                startLocate()
            }
            "exit_navigation" -> {
                Toast.makeText(this, "Rokid：退出导航", Toast.LENGTH_SHORT).show()
                viewModel.abortDemo("Rokid 语音退出导航")
            }
            "confirm" -> Toast.makeText(this, "Rokid：已确认", Toast.LENGTH_SHORT).show()
            "cancel" -> Toast.makeText(this, "Rokid：已取消", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleRokidNavigateVoice(command: VoiceNavigationCommand) {
        val target = resolveExhibitionVoiceTarget(command.targetText)
        if (target == null) {
            Toast.makeText(this, "Rokid：未找到目标 ${command.targetText.orEmpty()}", Toast.LENGTH_SHORT).show()
            viewModel.onAmapOutdoorNaviEvent("Rokid 语音目标未匹配：${command.rawText}")
            return
        }
        binding.editVenueId.setText(EXHIBITION_DEMO_VENUE_ID)
        binding.editFloorId.setText(target.floorId)
        binding.editTargetPoiId.setText(target.poiId)
        binding.editDebugTarget.setText(target.debugTarget)
        val providerId = selectProviderForRokidVoiceLocate()
        viewModel.loadVenueMeta(binding.editBaseUrl.text.toString().trim(), EXHIBITION_DEMO_VENUE_ID)
        persistSettings()
        Toast.makeText(this, "Rokid：导航到 ${target.label}", Toast.LENGTH_SHORT).show()
        viewModel.onAmapOutdoorNaviEvent("Rokid 语音目标：${target.label} (${target.poiId}) provider=$providerId")
        startLocate()
    }

    private fun selectProviderForRokidVoiceLocate(): String {
        val providerId = "rokid_glasses_frame"
        if (!RokidRuntimeBridge.hasCapturedFrame()) {
            Toast.makeText(this, "暂无 Rokid 图传画面，请先启动眼镜端图传", Toast.LENGTH_SHORT).show()
        }
        viewModel.selectProvider(providerId)
        syncProviderSpinner(providerId)
        return providerId
    }

    private fun resolveExhibitionVoiceTarget(targetText: String?): ExhibitionVoiceTarget? {
        val normalized = targetText
            .orEmpty()
            .lowercase(Locale.ROOT)
            .replace("\\s+".toRegex(), "")
        return when {
            normalized.contains("b17") || normalized.contains("b一七") -> {
                ExhibitionVoiceTarget("poi_booth_b17", "B17", "B17 展台")
            }
            normalized.contains("b10") || normalized.contains("b一零") || normalized.contains("b十") -> {
                ExhibitionVoiceTarget("poi_booth_b10", "B10", "B10 展台")
            }
            normalized.contains("厕所") || normalized.contains("卫生间") || normalized.contains("toilet") || normalized.contains("restroom") -> {
                ExhibitionVoiceTarget("poi_toilet_f1", "toilet", "厕所")
            }
            normalized.contains("报告厅") || normalized.contains("主报告厅") || normalized.contains("hall") -> {
                ExhibitionVoiceTarget("poi_hall_main", "hall", "主报告厅")
            }
            else -> null
        }
    }

    private fun announceManualIndoorNavigationIfNeeded(state: PocUiState) {
        if (state.navState !in indoorStates || state.indoorMode != IndoorNavigationMode.MANUAL_DEMO) {
            lastManualIndoorVoiceKey = null
            return
        }
        val demo = state.manualIndoorDemo
        val key = listOf(
            demo.routeId,
            demo.stepIndex,
            demo.currentFloorId,
            demo.expectedAction?.name.orEmpty(),
            demo.correction.orEmpty(),
            demo.arrived.toString(),
        ).joinToString("|")
        if (key == lastManualIndoorVoiceKey) {
            return
        }
        lastManualIndoorVoiceKey = key
        navigationVoiceGuide.speak(
            text = NavigationVoicePrompts.indoorInstruction(demo),
            flush = demo.arrived || demo.correction != null,
        )
    }

    private fun enforceUsbCameraPriority() {
        if (latestGlassesState.scanning) {
            HeyCyanGlassesManager.stopScan()
        }
        if (latestGlassesState.connected || latestGlassesState.ready) {
            HeyCyanGlassesManager.disconnect()
        }
    }

    private fun bindDebugPanelToggle() {
        bindOptionalClick("buttonToggleDebugPanel") { toggleDebugPanel() }
        bindOptionalClick("buttonDebugToggle") { toggleDebugPanel() }
        bindOptionalClick("textDebugToggle") { toggleDebugPanel() }
        binding.textState.setOnClickListener { toggleDebugPanel() }
    }

    private fun bindHiddenDebugQuickActions() {
        bindOptionalClick("buttonHiddenDebugManualDemo") {
            if (!SHOW_RECORDING_MARKER_FLOATING_PANEL) {
                Toast.makeText(this, "录像标记悬浮窗已临时屏蔽", Toast.LENGTH_SHORT).show()
                return@bindOptionalClick
            }
            isDebugPanelExpanded = false
            isVerboseDebugPanelVisible = false
            isManualControlSheetExpanded = true
            renderScreen(viewModel.uiState.value)
        }
        bindOptionalClick("buttonHiddenDebugCaptureLocate") {
            val state = viewModel.uiState.value
            if (state.navState in indoorStates || state.navState == NavState.ENTRY_HANDOFF_PENDING) {
                binding.buttonLocate.performClick()
            } else {
                Toast.makeText(this, getString(R.string.toast_debug_capture_placeholder), Toast.LENGTH_SHORT).show()
            }
        }
        bindOptionalClick("buttonHiddenDebugProviderSwitch") {
            cycleProviderSelection()
        }
        bindOptionalClick("buttonHiddenDebugGlasses") {
            startActivity(Intent(this, GlassesDebugActivity::class.java))
        }
        bindOptionalClick("buttonHiddenDebugUsbCamera") {
            openUsbCameraDebugPage()
        }
        bindOptionalClick("buttonHiddenDebugDualSensor") {
            startActivity(Intent(this, DualSensorRecordingActivity::class.java))
        }
        bindOptionalClick("buttonHiddenDebugRokid") {
            startActivity(Intent(this, RokidDebugActivity::class.java))
        }
        bindOptionalClick("buttonHiddenDebugMore") {
            isVerboseDebugPanelVisible = !isVerboseDebugPanelVisible
            renderScreen(viewModel.uiState.value)
        }
        bindOptionalClick("buttonHiddenDebugCollapse") {
            collapseDebugPanel()
        }
    }

    private fun bindMapCollapseActions() {
        binding.amapNaviContainer.setOnClickListener { collapseBottomPanel() }
        binding.amapIndoorMapContainer.setOnClickListener { collapseBottomPanel() }
        binding.mapCollapseTouchLayer.setOnClickListener { collapseBottomPanel() }
        binding.mapCollapseTouchLayer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                collapseBottomPanel()
            }
            true
        }
    }

    private fun renderScreen(state: PocUiState) {
        normalizeDebugPanelForState(state.navState)
        syncImageCalibrationFloorFromAmap(state)
        val model = buildRenderModel(state)
        renderMapHost(state)
        renderFallbackMaps(state)
        renderSearchSurface(state)
        renderMapCollapseTouchLayer()
        renderBottomSheetChrome(state)
        renderTopStatus(model, state)
        renderBottomActions(model, state)
        renderMapControls(model)
        renderOutdoorProgress(model)
        renderIndoorImageNavigation(state)
        renderIndoorPreview(state)
        renderDebugPanel(model, state.navState)
        renderStatusDetails(state)
        renderIndoorCalibrationOverlay(state)
        renderIndoorCalibrationOverlaySummary()
        renderIndoorAnnotationPanel()
    }

    private fun buildRenderModel(state: PocUiState): ScreenRenderModel {
        val showDebugPanel = !state.debugPanel.forceCollapseOnNavigation && isDebugPanelExpanded
        val showPendingPoiStartNavigation = hasSelectedMapPoiPendingNavigation &&
            state.navState in outdoorReadyStates
        val alertSummary = listOfNotNull(
            state.topCard.error,
            state.topCard.warning,
        ).joinToString(separator = "\n")
        return ScreenRenderModel(
            phaseTitle = state.topCard.title,
            statusHeadline = state.topCard.headline,
            statusSummary = state.topCard.detail,
            alertSummary = alertSummary,
            showRoutePreparation = state.bottomBar.showPrepareRoute,
            showStartNavigation = state.bottomBar.showStartNavigation || showPendingPoiStartNavigation,
            showExternalNavigation = state.bottomBar.showExternalNavigation,
            showContinueNavigation = state.bottomBar.showContinueNavigation,
            showEnterVenue = state.bottomBar.showEnterVenue,
            showExitIndoor = state.bottomBar.showExitIndoor,
            showIndoorActions = state.bottomBar.showCaptureAndLocate || state.bottomBar.showRequestRoute,
            showManualIndoorControls = state.bottomBar.showManualIndoorControls,
            showRecenter = state.mapChrome.showRecenter,
            showOrientationToggle = state.mapChrome.showOrientationToggle,
            showOverview = state.mapChrome.showOverview,
            showExitNavigation = state.mapChrome.showExitNavigation,
            showOutdoorProgress = state.mapChrome.showProgressLane,
            showDebugPanel = showDebugPanel,
            debugToggleLabel = when {
                showDebugPanel -> getString(R.string.action_hide_debug)
                else -> getString(R.string.action_expand_debug)
            },
        )
    }

    private fun renderMapHost(state: PocUiState) {
        if (CONFERENCE_INDOOR_ONLY_MODE) {
            binding.amapNaviContainer.visibility = View.GONE
            binding.amapIndoorMapContainer.visibility = View.GONE
            lastIndoorMapHostVisible = state.navState in indoorStates
            indoorBasemapController.deactivate()
            return
        }
        val showIndoorMap = shouldShowIndoorMapHost(state.navState)
        binding.amapNaviContainer.visibility = if (showIndoorMap) View.GONE else View.VISIBLE
        binding.amapIndoorMapContainer.visibility = if (showIndoorMap) View.VISIBLE else View.GONE
        lastIndoorMapHostVisible = showIndoorMap
        if (showIndoorMap) {
            indoorBasemapController.activate(
                config = buildIndoorBasemapConfig(state),
                overlay = buildIndoorBusinessOverlay(state),
            )
        } else {
            indoorBasemapController.deactivate()
        }
    }

    private fun renderFallbackMaps(state: PocUiState) {
        if (CONFERENCE_INDOOR_ONLY_MODE) {
            binding.viewOutdoorFallbackMap.visibility = View.GONE
            binding.viewIndoorFallbackMap.visibility = View.GONE
            return
        }
        val showIndoorMap = shouldShowIndoorMapHost(state.navState)
        val showOutdoorFallback = !showIndoorMap && isEmbeddedAmapViewSkipped
        binding.viewOutdoorFallbackMap.visibility = if (showOutdoorFallback) View.VISIBLE else View.GONE
        if (showOutdoorFallback) {
            val mode = when {
                shouldShowExpandedSearchResults(state) -> OutdoorMapBackdropView.Mode.SEARCH_RESULTS
                state.navState == NavState.OUTDOOR_ROUTE_READY -> OutdoorMapBackdropView.Mode.ROUTE_READY
                state.navState == NavState.OUTDOOR_NAVIGATING -> OutdoorMapBackdropView.Mode.NAVIGATING
                state.navState == NavState.ENTRY_HANDOFF_PENDING -> OutdoorMapBackdropView.Mode.HANDOFF
                selectedOutdoorPoi != null || hasSelectedMapPoiPendingNavigation -> OutdoorMapBackdropView.Mode.SELECTION
                else -> OutdoorMapBackdropView.Mode.SEARCH_HOME
            }
            binding.viewOutdoorFallbackMap.render(
                mode = mode,
                targetTitle = selectedOutdoorPoi?.title ?: binding.editOutdoorSearchKeyword.text.toString().trim().ifBlank { null },
                entranceLabel = binding.editVenueId.text.toString().trim().ifBlank { "推荐入口" },
            )
        }

        val showIndoorFallback = showIndoorMap && !state.indoorBasemap.available
        binding.viewIndoorFallbackMap.visibility = if (showIndoorFallback) View.VISIBLE else View.GONE
        if (showIndoorFallback) {
            renderIndoorFallbackMap(state)
        }
    }

    private fun renderIndoorImageNavigation(state: PocUiState) {
        if (CONFERENCE_INDOOR_ONLY_MODE && state.navState in indoorStates) {
            binding.viewIndoorImageNavigation.visibility = View.VISIBLE
            val floor = indoorImageNavigation?.graph?.floor(indoorImageNavSelectedFloorId)
            val plan = indoorImageNavPlan
            val currentPosition = conferenceIndoorCurrentPosition(state)
            binding.viewIndoorImageNavigation.setCurrentPosition(
                floorId = currentPosition?.floorId,
                x = currentPosition?.x,
                y = currentPosition?.y,
                confidence = state.lastLocalizationConfidence,
            )
            if (floor != null && plan != null) {
                binding.viewIndoorImageNavigation.render(floor, floor.image, plan)
                renderIndoorImageNavSummary()
                focusIndoorImageNavigationIfNeeded(state, plan)
                publishConferenceIndoorRouteHudIfPositionChanged(state, plan)
            } else {
                if (floor != null) {
                    binding.viewIndoorImageNavigation.renderBasemap(floor, floor.image)
                } else {
                    binding.viewIndoorImageNavigation.renderBasemap(CONFERENCE_BASEMAP_ASSET)
                }
                setIndoorImageNavSummary("会场室内地图已加载，Rokid HTTP 图传将自动接入。")
                publishConferenceIndoorIdleHudIfPositionChanged(state)
            }
            return
        }
        if (
            state.navState in indoorStates &&
            indoorImageNavPlan != null &&
            !isIndoorCalibrationOverlayEnabled &&
            indoorImageNavSelectedFloorId != state.manualIndoorDemo.currentFloorId
        ) {
            indoorImageNavSelectedFloorId = state.manualIndoorDemo.currentFloorId
            restoreCurrentImageIndoorCalibrationPoints()
        }
        binding.viewIndoorImageNavigation.visibility = View.GONE
        binding.viewIndoorImageNavigation.clear()
        renderIndoorImageNavSummary()
    }

    private fun conferenceIndoorCurrentPosition(state: PocUiState): IndoorMapPosition? {
        val floorId = state.lastFloorId
        val x = state.lastPositionX
        val y = state.lastPositionY
        if (floorId != null && x != null && y != null && !isConferenceBackendDemoCoordinate(x, y)) {
            conferenceCroppedMapPosition(floorId, x, y)?.let { return it }
        }
        val repository = indoorImageNavigation ?: return null
        val fallbackNode = conferenceLandmarkNode(state, repository)
            ?: repository.resolverItem(CONFERENCE_DEFAULT_TARGET_POI_ID)
            ?.routeNodeId
            ?.let { repository.graph.node(it) }
            ?: indoorImageNavPlan?.target
            ?: return null
        return IndoorMapPosition(
            floorId = fallbackNode.floorId,
            x = fallbackNode.x,
            y = fallbackNode.y,
        )
    }

    private fun conferenceIndoorHudCurrentPosition(state: PocUiState): IndoorMapPosition? {
        return conferenceDisplayedCurrentPosition
            ?: conferenceIndoorCurrentPosition(state)
    }

    private fun isConferenceBackendDemoCoordinate(x: Double, y: Double): Boolean {
        return CONFERENCE_INDOOR_ONLY_MODE &&
            x in 0.0..CONFERENCE_BACKEND_DEMO_COORDINATE_MAX &&
            y in 0.0..CONFERENCE_BACKEND_DEMO_COORDINATE_MAX
    }

    private fun conferenceCroppedMapPosition(
        floorId: String,
        x: Double,
        y: Double,
    ): IndoorMapPosition? {
        if (!CONFERENCE_INDOOR_ONLY_MODE) {
            return IndoorMapPosition(floorId = floorId, x = x, y = y)
        }
        if (
            x in -CONFERENCE_MAP_CROP_TOLERANCE_PX..(CONFERENCE_MAP_CROP_WIDTH_PX + CONFERENCE_MAP_CROP_TOLERANCE_PX) &&
            y in -CONFERENCE_MAP_CROP_TOLERANCE_PX..(CONFERENCE_MAP_CROP_HEIGHT_PX + CONFERENCE_MAP_CROP_TOLERANCE_PX)
        ) {
            return IndoorMapPosition(
                floorId = floorId,
                x = x.coerceIn(0.0, CONFERENCE_MAP_CROP_WIDTH_PX),
                y = y.coerceIn(0.0, CONFERENCE_MAP_CROP_HEIGHT_PX),
            )
        }
        if (
            x in (CONFERENCE_MAP_CROP_LEFT_PX - CONFERENCE_MAP_CROP_TOLERANCE_PX)..(CONFERENCE_MAP_CROP_RIGHT_PX + CONFERENCE_MAP_CROP_TOLERANCE_PX) &&
            y in (CONFERENCE_MAP_CROP_TOP_PX - CONFERENCE_MAP_CROP_TOLERANCE_PX)..(CONFERENCE_MAP_CROP_BOTTOM_PX + CONFERENCE_MAP_CROP_TOLERANCE_PX)
        ) {
            return IndoorMapPosition(
                floorId = floorId,
                x = (x - CONFERENCE_MAP_CROP_LEFT_PX).coerceIn(0.0, CONFERENCE_MAP_CROP_WIDTH_PX),
                y = (y - CONFERENCE_MAP_CROP_TOP_PX).coerceIn(0.0, CONFERENCE_MAP_CROP_HEIGHT_PX),
            )
        }
        return null
    }

    private fun conferenceLandmarkNode(
        state: PocUiState,
        repository: ImageIndoorNavigationRepository,
    ): ImageIndoorNavNode? {
        val labels = buildList {
            addAll(conferenceLandmarkSearchLabels(state.lastMatchedLandmarkPoiId))
            addAll(conferenceLandmarkSearchLabels(state.lastMatchedLandmarkDisplayName))
        }.distinct()
        labels.forEach { label ->
            val target = repository.searchPoi(label)
                .firstOrNull { it.isExactIndoorQueryMatch(label) }
                ?: repository.searchPoi(label).firstOrNull()
            val node = target?.routeNodeId?.let { repository.graph.node(it) }
            if (node != null) return node
        }
        return null
    }

    private fun conferenceLandmarkSearchLabels(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return emptyList()
        val labels = linkedSetOf(text)
        Regex("[A-Fa-f]\\d{1,2}").findAll(text)
            .map { it.value.uppercase(Locale.US) }
            .forEach(labels::add)
        Regex("poi_([1-6]\\d{2})", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(labels::add)
        return labels.toList()
    }

    private fun focusIndoorImageNavigationIfNeeded(
        state: PocUiState,
        plan: ImageIndoorRoutePlan,
    ) {
        if (pendingIndoorImageFocusTarget) {
            pendingIndoorImageFocusTarget = false
            binding.viewIndoorImageNavigation.post {
                binding.viewIndoorImageNavigation.focusRouteTarget()
            }
            return
        }
        val currentKey = listOfNotNull(
            state.lastFloorId,
            state.lastPositionX?.formatOneDecimal(),
            state.lastPositionY?.formatOneDecimal(),
        ).takeIf { it.size == 3 }?.joinToString("|")
        if (currentKey != null && currentKey != lastFocusedIndoorImageCurrentKey) {
            lastFocusedIndoorImageCurrentKey = currentKey
            if (state.lastFloorId == plan.start.floorId || state.lastFloorId == plan.target.floorId) {
                binding.viewIndoorImageNavigation.post {
                    binding.viewIndoorImageNavigation.focusCurrentPosition()
                }
            }
        }
    }

    private fun renderIndoorImageNavSummary() {
        ensureIndoorImageNavSelectedFloor()
        styleIndoorImageFloorButtons()
        val repository = indoorImageNavigation
        val plan = indoorImageNavPlan
        val hasDynamicFloors = indoorImageFloorIds().isNotEmpty()
        binding.layoutIndoorImageNavFloorRow.visibility =
            if (hasDynamicFloors && plan != null) View.VISIBLE else View.GONE
        binding.layoutIndoorCalibrationFloorRow.visibility =
            if (hasDynamicFloors) View.VISIBLE else View.GONE
        val summary = when {
            repository == null -> "本地图纸路网未加载。"
            plan == null -> getString(R.string.indoor_image_nav_summary_empty)
            CONFERENCE_INDOOR_ONLY_MODE -> if (isConferenceWalkDemoRunning()) {
                viewModel.uiState.value.routeSummary
                    .takeIf { it.startsWith("模拟步行：") }
                    ?: "模拟步行：正在播放室内导航"
            } else {
                val segmentCount = plan.walkSegmentsForFloor(indoorImageNavSelectedFloorId).size
                val targetLabel = currentIndoorImageNavQuery().ifBlank { binding.editTargetPoiId.text.toString().trim() }
                val currentPosition = conferenceIndoorCurrentPosition(viewModel.uiState.value)
                val startLabel = conferenceRouteSummaryStartLabel(viewModel.uiState.value, currentPosition, plan)
                val distanceText = conferenceRouteDistanceMeters(plan)?.formatIndoorHudDistance() ?: "距离待计算"
                val durationText = conferenceRouteDurationSeconds(conferenceRouteDistanceMeters(plan))
                    ?.formatIndoorHudDuration()
                    ?: "时间待计算"
                "会场路径：$startLabel -> $targetLabel，当前显示 $indoorImageNavSelectedFloorId，步行段 $segmentCount，约 $distanceText，预计 $durationText"
            }
            else -> {
                val vertical = plan.verticalSteps.joinToString(separator = "；").ifBlank { "同层步行" }
                val segmentCount = plan.walkSegmentsForFloor(indoorImageNavSelectedFloorId).size
                val amapStatus = imageIndoorAmapOverlayStatusForSelectedFloor()
                "已规划：${plan.start.floorId} -> ${plan.target.floorId}，当前显示 $indoorImageNavSelectedFloorId，步行段 $segmentCount，换层：$vertical，预计 ${plan.totalCostSeconds.toInt()} 秒；$amapStatus"
            }
        }
        setIndoorImageNavSummary(summary)
    }

    private fun styleIndoorImageFloorButtons() {
        val selectedColor = 0xFF2563EB.toInt()
        val normalColor = 0xFF374151.toInt()
        styleIndoorFloorButtonRow(binding.layoutIndoorImageNavFloorRow, selectedColor, normalColor)
        styleIndoorFloorButtonRow(binding.layoutIndoorMainNavFloorRow, selectedColor, normalColor)
        styleIndoorFloorButtonRow(binding.layoutIndoorCalibrationFloorRow, selectedColor, normalColor)
    }

    private fun styleIndoorFloorButtonRow(
        row: LinearLayout,
        selectedColor: Int,
        normalColor: Int,
    ) {
        for (index in 0 until row.childCount) {
            val button = row.getChildAt(index) as? Button ?: continue
            val floorId = button.text?.toString().orEmpty()
            button.setTextColor(if (floorId == indoorImageNavSelectedFloorId) selectedColor else normalColor)
        }
    }

    private fun setIndoorImageNavSummary(summary: String) {
        binding.textIndoorImageNavSummary.text = summary
        binding.textIndoorMainNavSummary.text = summary
    }

    private fun shouldShowIndoorMainRouteSearch(state: PocUiState): Boolean {
        return CONFERENCE_INDOOR_ONLY_MODE &&
            state.navState in indoorStates &&
            state.indoorMode == IndoorNavigationMode.CLOUD_RELOCALIZATION
    }

    private fun hasImageIndoorAmapOverlayForSelectedFloor(): Boolean {
        return imageIndoorCalibrationNodesForSelectedFloor()
            .count { savedImageIndoorCalibrationPoint(it.floorId, it.nodeId) != null } >= 2
    }

    private fun imageIndoorAmapOverlayStatusForSelectedFloor(): String {
        if (savedImageIndoorCalibrationPointsForFloor(indoorImageNavSelectedFloorId).isNotEmpty()) {
            return "高德底图叠加：已使用保存校准点"
        }
        val sharedAlignment = indoorImageNavigation?.graph?.sharedAmapAlignment
        if (sharedAlignment?.appliesToFloor(indoorImageNavSelectedFloorId) == true) {
            return "高德底图叠加：已复用同视角共享映射"
        }
        return "高德底图叠加：需显示路线校准层并保存当前楼层"
    }

    private fun renderSearchSurface(state: PocUiState) {
        val showSearch = shouldShowSearchSurface(state)
        binding.layoutTopSearchPanel.visibility = if (showSearch) View.VISIBLE else View.GONE
        if (!showSearch) {
            isSearchIdleSuggestionsCollapsed = false
            binding.layoutBottomPoiResult.visibility = View.GONE
            return
        }
        val showResults = shouldShowExpandedSearchResults(state)
        val keyword = binding.editOutdoorSearchKeyword.text.toString().trim()
        val showIdleSearchChrome = outdoorSearchUiState == SearchUiState.IDLE_HOME
        binding.editOutdoorSearchKeyword.hint = if (showIdleSearchChrome) {
            getString(R.string.hint_outdoor_search_destination)
        } else {
            getString(selectedSearchType.hintResId)
        }
        binding.textOutdoorSearchHelper.text = getString(selectedSearchType.helperResId)
        binding.textOutdoorSearchHelper.visibility =
            if (!showResults && keyword.isNotBlank() && !showIdleSearchChrome) View.VISIBLE else View.GONE
        binding.scrollSearchTypeTabs.visibility = if (showResults) View.GONE else View.VISIBLE
        binding.editOutdoorSearchKeyword.visibility = View.VISIBLE
        binding.textSearchResultsNavTitle.visibility = View.GONE
        binding.textOutdoorSearchResultsTitle.visibility = if (showResults) View.GONE else View.VISIBLE
        binding.layoutTravelModePills.visibility = View.GONE
        binding.buttonOutdoorSearchBack.setImageResource(
            if (showIdleSearchChrome) {
                R.drawable.ic_search_24
            } else {
                R.drawable.ic_arrow_back_24
            },
        )
        binding.buttonOutdoorSearchVoice.visibility = if (showIdleSearchChrome) View.VISIBLE else View.GONE
        binding.buttonOutdoorSearchClear.visibility =
            if (keyword.isNotBlank() || outdoorSearchUiState == SearchUiState.DESTINATION_SELECTED) View.VISIBLE else View.GONE
        renderSelectableChip(binding.buttonSearchTypeStore, selectedSearchType == SearchType.STORE)
        renderSelectableChip(binding.buttonSearchTypeMallEntrance, selectedSearchType == SearchType.MALL_ENTRANCE)
        renderSelectableChip(binding.buttonSearchTypeOffice, selectedSearchType == SearchType.OFFICE)
        renderSelectableChip(binding.buttonSearchTypeResidential, selectedSearchType == SearchType.RESIDENTIAL)
        renderSelectableChip(binding.buttonTravelRide, readOutdoorTravelMode() == OutdoorTravelMode.RIDE)
        renderSelectableChip(binding.buttonTravelWalk, readOutdoorTravelMode() == OutdoorTravelMode.WALK)
        renderSelectableChip(binding.buttonTravelEbike, readOutdoorTravelMode() == OutdoorTravelMode.EBIKE)
        renderSelectableChip(binding.buttonTravelDrive, readOutdoorTravelMode() == OutdoorTravelMode.DRIVE)
        binding.layoutBottomPoiResult.visibility = if (showResults) View.VISIBLE else View.GONE
        if (showResults) {
            renderOutdoorPoiResults(outdoorPoiOptions)
        }
        if (!showIdleSearchChrome) {
            isSearchIdleSuggestionsCollapsed = false
        }
    }

    private fun shouldShowSearchSurface(state: PocUiState): Boolean {
        if (state.navState != NavState.OUTDOOR_IDLE && state.navState != NavState.OUTDOOR_READY) {
            return false
        }
        return true
    }

    private fun shouldShowExpandedSearchResults(state: PocUiState): Boolean {
        return shouldShowSearchSurface(state) && outdoorSearchUiState == SearchUiState.RESULTS_EXPANDED
    }

    private fun shouldShowSearchIdleSuggestions(state: PocUiState): Boolean {
        return shouldShowSearchSurface(state) &&
            outdoorSearchUiState == SearchUiState.IDLE_HOME
    }

    private fun renderSelectableChip(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_ui_filter_chip_selected else R.drawable.bg_ui_filter_chip_unselected,
        )
        val tintColor = if (selected) 0xFFFFFFFF.toInt() else 0xFF374151.toInt()
        view.setTextColor(tintColor)
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(tintColor))
    }

    private fun getSearchBadgeDrawable(type: SearchType): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(type.badgeColor())
            setStroke(dp(1).coerceAtLeast(1), 0x26FFFFFF)
        }
    }

    private fun resolveVisualSearchType(poi: OutdoorPoiOption): SearchType {
        poi.visualType?.let { visualType ->
            return when (visualType) {
                OutdoorPoiVisualType.STORE -> SearchType.STORE
                OutdoorPoiVisualType.ENTRANCE -> SearchType.MALL_ENTRANCE
                OutdoorPoiVisualType.OFFICE -> SearchType.OFFICE
                OutdoorPoiVisualType.RESIDENTIAL -> SearchType.RESIDENTIAL
            }
        }
        val haystack = "${poi.title} ${poi.address} ${poi.city}"
        return when {
            listOf("入口", "东门", "西门", "南门", "北门", "连廊", "地铁").any { haystack.contains(it, ignoreCase = true) } -> SearchType.MALL_ENTRANCE
            listOf("小区", "社区", "家园", "公寓", "门岗").any { haystack.contains(it, ignoreCase = true) } -> SearchType.RESIDENTIAL
            listOf("优衣库", "星巴克", "麦当劳", "购物中心", "商场", "门店", "店", "地铁站").any { haystack.contains(it, ignoreCase = true) } -> SearchType.STORE
            listOf("写字楼", "大厦", "商务", "科技园", "办公", "园区", "创客", "中心").any { haystack.contains(it, ignoreCase = true) } -> SearchType.OFFICE
            else -> selectedSearchType
        }
    }

    private fun resolveDirectionIconRes(
        poi: OutdoorPoiOption,
        visualType: SearchType,
    ): Int {
        return when (poi.directionHint) {
            OutdoorPoiDirectionHint.FORWARD -> R.drawable.ic_result_direction_forward_18
            OutdoorPoiDirectionHint.SLIGHT_RIGHT -> R.drawable.ic_result_direction_slight_right_18
            OutdoorPoiDirectionHint.RIGHT -> R.drawable.ic_result_direction_right_18
            null -> when (visualType) {
                SearchType.MALL_ENTRANCE -> R.drawable.ic_result_direction_forward_18
                SearchType.OFFICE -> R.drawable.ic_result_direction_slight_right_18
                else -> R.drawable.ic_result_direction_right_18
            }
        }
    }

    private fun renderMapCollapseTouchLayer() {
        val params = binding.mapCollapseTouchLayer.layoutParams as FrameLayout.LayoutParams
        params.topMargin = 0
        params.bottomMargin = 0
        binding.mapCollapseTouchLayer.layoutParams = params
        val showOverlay = (isDebugPanelExpanded || (SHOW_RECORDING_MARKER_FLOATING_PANEL && isManualControlSheetExpanded)) &&
            !isIndoorAnnotationModeEnabled &&
            !isIndoorCalibrationOverlayEnabled
        binding.mapCollapseTouchLayer.visibility = if (showOverlay) View.VISIBLE else View.GONE
    }

    private fun handleIndoorMapTapped(tap: IndoorMapTap?) {
        if (!isIndoorAnnotationModeEnabled) {
            collapseBottomPanel()
            return
        }
        val point = tap ?: return
        val pointId = "manual_point_${(indoorAnnotationRows.size + 1).toString().padStart(3, '0')}"
        val remark = binding.editIndoorAnnotationLabel.text.toString().trim()
            .ifBlank { pointId }
        val row = listOf(
            pointId.toCsvCell(),
            "manual".toCsvCell(),
            point.floorId.ifBlank { "-" }.toCsvCell(),
            point.x.formatOneDecimal().toCsvCell(),
            point.y.formatOneDecimal().toCsvCell(),
            point.latitude.formatCoordinate().toCsvCell(),
            point.longitude.formatCoordinate().toCsvCell(),
            "".toCsvCell(),
            "".toCsvCell(),
            remark.toCsvCell(),
        ).joinToString(separator = ",")
        indoorAnnotationRows.add(row)
        renderIndoorAnnotationPanel()
        Toast.makeText(this, "已记录点位：$pointId", Toast.LENGTH_SHORT).show()
    }

    private fun renderIndoorAnnotationPanel() {
        val modeText = if (isIndoorAnnotationModeEnabled) {
            "关闭标注模式"
        } else {
            "开启标注模式"
        }
        setOptionalText("buttonToggleIndoorAnnotationMode", modeText)
        val output = if (indoorAnnotationRows.isEmpty()) {
            "尚未记录点位。开启后点击室内地图，可输出 point_id,type,floor_id,x,y,lat,lng,route_node_id,photo_ref,remark。"
        } else {
            listOf(INDOOR_ANNOTATION_HEADER)
                .plus(indoorAnnotationRows)
                .joinToString(separator = "\n")
        }
        setOptionalText("textIndoorAnnotationOutput", output)
    }

    private fun renderIndoorCalibrationOverlaySummary() {
        val pointCount = currentIndoorCalibrationPointCount()
        val savedCount = currentIndoorCalibrationSavedPoints().size
        val buttonText = if (isIndoorCalibrationOverlayEnabled) {
            "隐藏路线校准层"
        } else {
            getString(R.string.action_show_indoor_calibration_overlay)
        }
        setOptionalText("buttonToggleIndoorCalibrationOverlay", buttonText)
        val transformText = binding.viewIndoorCalibrationOverlay.transformSummary()
        val summary = if (isIndoorCalibrationOverlayEnabled) {
            "${getString(R.string.indoor_calibration_overlay_summary_on)} 当前路线点：$pointCount；已保存：$savedCount/$pointCount；$transformText"
        } else {
            "${getString(R.string.indoor_calibration_overlay_summary_off)} 当前路线点：$pointCount；已保存：$savedCount/$pointCount。"
        }
        setOptionalText("textIndoorCalibrationOverlaySummary", summary)
    }

    private fun copyIndoorCalibrationResult() {
        if (!isIndoorCalibrationOverlayEnabled) {
            Toast.makeText(this, "请先显示路线校准层并完成对齐", Toast.LENGTH_SHORT).show()
            return
        }
        val screenPoints = binding.viewIndoorCalibrationOverlay.currentScreenPoints()
        if (screenPoints.isEmpty()) {
            Toast.makeText(this, "暂无可复制的校准点", Toast.LENGTH_SHORT).show()
            return
        }
        val sourcePoints = currentIndoorCalibrationSourcePoints()
        val rows = screenPoints.mapIndexed { index, screenPoint ->
            val sourcePoint = sourcePoints.getOrNull(index)
            val latLng = indoorBasemapController.latLngFromScreenPoint(
                x = screenPoint.screenX,
                y = screenPoint.screenY,
            )
            listOf(
                "calibration_point_${screenPoint.index.toString().padStart(3, '0')}".toCsvCell(),
                "manual_screen_aligned".toCsvCell(),
                screenPoint.floorId.toCsvCell(),
                screenPoint.x.formatOneDecimal().toCsvCell(),
                screenPoint.y.formatOneDecimal().toCsvCell(),
                screenPoint.screenX.toDouble().formatOneDecimal().toCsvCell(),
                screenPoint.screenY.toDouble().formatOneDecimal().toCsvCell(),
                (latLng?.latitude?.formatCoordinate() ?: "").toCsvCell(),
                (latLng?.longitude?.formatCoordinate() ?: "").toCsvCell(),
                (sourcePoint?.nodeId.orEmpty()).toCsvCell(),
                screenPoint.label.toCsvCell(),
            ).joinToString(separator = ",")
        }
        val text = listOf(INDOOR_CALIBRATION_RESULT_HEADER)
            .plus(rows)
            .joinToString(separator = "\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("indoor_calibration_result", text))
        val latLngReady = rows.count { row ->
            val cells = row.split(",")
            cells.getOrNull(7)?.isNotBlank() == true && cells.getOrNull(8)?.isNotBlank() == true
        }
        Toast.makeText(this, "已复制校准结果：$latLngReady/${rows.size} 个点含经纬度", Toast.LENGTH_SHORT).show()
    }

    private fun saveIndoorCalibrationRoute() {
        val points = currentAlignedIndoorCalibrationPoints()
        val expectedCount = currentIndoorCalibrationPointCount()
        if (points.size != expectedCount) {
            Toast.makeText(this, "请先进入室内底图并完成路线点对齐，当前可保存 ${points.size}/$expectedCount 个点", Toast.LENGTH_LONG).show()
            return
        }
        val key = currentIndoorCalibrationScopeKey()
        if (isImageIndoorCalibrationActive()) {
            savedImageIndoorCalibrationPoints = points.associateBy { it.nodeId }
        } else {
            savedIndoorCalibrationPoints = points.associateBy { it.nodeId }
        }
        persistIndoorCalibrationPoints(key, points)
        renderScreen(viewModel.uiState.value)
        Toast.makeText(this, "已保存 $expectedCount 个室内路线点，后续路线将直接使用当前保存结果", Toast.LENGTH_LONG).show()
    }

    private fun restoreDefaultIndoorCalibrationRoute() {
        val key = currentIndoorCalibrationScopeKey()
        val defaultPoints = restoreDefaultIndoorCalibrationPoints(key)
        if (defaultPoints.isEmpty()) {
            Toast.makeText(this, "当前楼层没有内置默认校准", Toast.LENGTH_SHORT).show()
            return
        }
        val localStore = restoreLocalIndoorCalibrationStore()
        localStore.remove(key)
        prefs.edit()
            .putString(KEY_INDOOR_CALIBRATION_POINTS, localStore.toString())
            .apply()
        if (isImageIndoorCalibrationActive()) {
            savedImageIndoorCalibrationPoints = defaultPoints
        } else {
            savedIndoorCalibrationPoints = defaultPoints
        }
        renderScreen(viewModel.uiState.value)
        Toast.makeText(this, "已恢复为内置默认校准", Toast.LENGTH_SHORT).show()
    }

    private fun currentAlignedIndoorCalibrationPoints(): List<SavedIndoorCalibrationPoint> {
        val screenPoints = binding.viewIndoorCalibrationOverlay.currentScreenPoints()
        val sourcePoints = currentIndoorCalibrationSourcePoints()
        return screenPoints.mapIndexedNotNull { index, screenPoint ->
            val sourcePoint = sourcePoints.getOrNull(index) ?: return@mapIndexedNotNull null
            val nodeId = sourcePoint.nodeId.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val latLng = indoorBasemapController.latLngFromScreenPoint(
                x = screenPoint.screenX,
                y = screenPoint.screenY,
            ) ?: return@mapIndexedNotNull null
            SavedIndoorCalibrationPoint(
                nodeId = nodeId,
                floorId = screenPoint.floorId,
                label = screenPoint.label,
                latitude = latLng.latitude,
                longitude = latLng.longitude,
            )
        }
    }

    private fun persistIndoorCalibrationPoints(
        key: String,
        points: List<SavedIndoorCalibrationPoint>,
    ) {
        val store = restoreLocalIndoorCalibrationStore()
        store.put(key, points.toJsonArray())
        prefs.edit()
            .putString(KEY_INDOOR_CALIBRATION_POINTS, store.toString())
            .apply()
    }

    private fun restoreIndoorCalibrationPoints(
        key: String = currentManualDemoRouteKey(),
    ): Map<String, SavedIndoorCalibrationPoint> {
        val store = restoreIndoorCalibrationStore()
        val floorId = key.substringAfterLast("|", missingDelimiterValue = "")
        val points = store.optJSONArray(key)
            ?: legacyImageIndoorCalibrationKey(floorId)
                ?.takeIf { it != key }
                ?.let { store.optJSONArray(it) }
        return points
            ?.toSavedIndoorCalibrationPoints()
            .orEmpty()
            .associateBy { it.nodeId }
    }

    private fun restoreIndoorCalibrationStore(): JSONObject {
        val store = defaultIndoorCalibrationStore()
        val localStore = restoreLocalIndoorCalibrationStore()
        localStore.keys().forEach { key ->
            store.put(key, localStore.opt(key))
        }
        return store
    }

    private fun restoreLocalIndoorCalibrationStore(): JSONObject {
        val raw = prefs.getString(KEY_INDOOR_CALIBRATION_POINTS, null).orEmpty().trim()
        if (raw.isBlank()) {
            return JSONObject()
        }
        return runCatching {
            if (raw.startsWith("[")) {
                JSONObject().put(currentManualDemoRouteKey(), JSONArray(raw))
            } else {
                JSONObject(raw)
            }
        }.getOrElse {
            JSONObject()
        }
    }

    private fun restoreDefaultIndoorCalibrationPoints(
        key: String = currentIndoorCalibrationScopeKey(),
    ): Map<String, SavedIndoorCalibrationPoint> {
        val store = defaultIndoorCalibrationStore()
        val floorId = key.substringAfterLast("|", missingDelimiterValue = "")
        val points = store.optJSONArray(key)
            ?: legacyImageIndoorCalibrationKey(floorId)
                ?.takeIf { it != key }
                ?.let { store.optJSONArray(it) }
        return points
            ?.toSavedIndoorCalibrationPoints()
            .orEmpty()
            .associateBy { it.nodeId }
    }

    private fun defaultIndoorCalibrationStore(): JSONObject {
        val raw = defaultIndoorCalibrationStoreRaw?.trim().orEmpty()
        if (raw.isBlank()) {
            return JSONObject()
        }
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun readAssetText(path: String): String? {
        return runCatching {
            assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun List<SavedIndoorCalibrationPoint>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { point ->
            array.put(
                JSONObject()
                    .put("node_id", point.nodeId)
                    .put("floor_id", point.floorId)
                    .put("label", point.label)
                    .put("lat", point.latitude)
                    .put("lng", point.longitude),
            )
        }
        return array
    }

    private fun JSONArray.toSavedIndoorCalibrationPoints(): List<SavedIndoorCalibrationPoint> {
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    SavedIndoorCalibrationPoint(
                        nodeId = item.getString("node_id"),
                        floorId = item.optString("floor_id"),
                        label = item.optString("label"),
                        latitude = item.getDouble("lat"),
                        longitude = item.getDouble("lng"),
                    ),
                )
            }
        }
    }

    private fun currentManualDemoScript() = ManualIndoorDemoScripts.defaultScript

    private fun currentManualDemoPointCount(): Int = currentManualDemoScript().steps.size

    private fun currentManualDemoRouteKey(): String {
        val script = currentManualDemoScript()
        return listOf(script.routeId, script.venueId, script.targetPoiId).joinToString(separator = "|")
    }

    private fun currentIndoorCalibrationPointCount(): Int = currentIndoorCalibrationSourcePoints().size

    private fun currentIndoorCalibrationSavedPoints(): Map<String, SavedIndoorCalibrationPoint> {
        return if (isImageIndoorCalibrationActive()) {
            savedImageIndoorCalibrationPoints
        } else {
            savedIndoorCalibrationPoints
        }
    }

    private fun currentIndoorCalibrationScopeKey(): String {
        return if (isImageIndoorCalibrationActive()) {
            currentImageIndoorCalibrationKey()
        } else {
            currentManualDemoRouteKey()
        }
    }

    private fun currentImageIndoorCalibrationKey(): String {
        return imageIndoorCalibrationKey(indoorImageNavSelectedFloorId)
    }

    private fun imageIndoorCalibrationKey(floorId: String): String {
        return listOf(
            "image_nav",
            DEFAULT_WUDAOKOU_VENUE_ID,
            WUDAOKOU_IMAGE_CALIBRATION_SCOPE,
            floorId,
        ).joinToString(separator = "|")
    }

    private fun legacyImageIndoorCalibrationKey(floorId: String): String? {
        if (floorId.isBlank()) {
            return null
        }
        return listOf(
            "image_nav",
            DEFAULT_WUDAOKOU_VENUE_ID,
            "wudaokou_b1_f1",
            floorId,
        ).joinToString(separator = "|")
    }

    private fun isImageIndoorCalibrationActive(): Boolean {
        return imageIndoorCalibrationNodesForSelectedFloor().isNotEmpty()
    }

    private fun restoreCurrentImageIndoorCalibrationPoints() {
        savedImageIndoorCalibrationPoints = restoreIndoorCalibrationPoints(currentImageIndoorCalibrationKey())
    }

    private fun copyIndoorAnnotations() {
        val text = if (indoorAnnotationRows.isEmpty()) {
            ""
        } else {
            listOf(INDOOR_ANNOTATION_HEADER)
                .plus(indoorAnnotationRows)
                .joinToString(separator = "\n")
        }
        if (text.isBlank()) {
            Toast.makeText(this, "暂无可复制的室内标注点", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("indoor_annotations", text))
        Toast.makeText(this, "室内标注点已复制", Toast.LENGTH_SHORT).show()
    }

    private fun shouldShowIndoorMapHost(navState: NavState): Boolean {
        return when (navState) {
            in indoorStates -> true
            NavState.ERROR,
            NavState.ABORTED -> lastIndoorMapHostVisible
            else -> false
        }
    }

    private fun buildIndoorBasemapConfig(state: PocUiState): IndoorBasemapConfig {
        val displayFloorId = when {
            isIndoorCalibrationOverlayEnabled && isImageIndoorCalibrationActive() -> indoorImageNavSelectedFloorId
            state.indoorMode == IndoorNavigationMode.MANUAL_DEMO && state.navState in indoorStates -> state.manualIndoorDemo.currentFloorId
            else -> state.lastFloorId ?: binding.editFloorId.text.toString().trim().ifBlank { null }
        }
        val center = LatLng(
            binding.editOutdoorEntryLat.text.toString().trim().toDoubleOrNull() ?: DEFAULT_INDOOR_CENTER_LAT,
            binding.editOutdoorEntryLng.text.toString().trim().toDoubleOrNull() ?: DEFAULT_INDOOR_CENTER_LNG,
        )
        return IndoorBasemapConfig(
            venueId = binding.editVenueId.text.toString().trim(),
            center = center,
            floorId = displayFloorId,
        )
    }

    private fun buildIndoorBusinessOverlay(state: PocUiState): IndoorBusinessOverlay {
        if (state.indoorMode == IndoorNavigationMode.MANUAL_DEMO && state.navState in indoorStates) {
            buildImageIndoorBusinessOverlay(state)?.let { return it }
            buildImageIndoorPlaceholderOverlay(state)?.let { return it }
            val demo = state.manualIndoorDemo
            val amapOverlay = wudaokouAmapRouteOverlay?.takeIf {
                it.matches(demo.routeId, demo.venueId, demo.targetPoiId)
            }
            val current = demo.current.toOverlayPoint("当前位置：${demo.currentNodeLabel}", amapOverlay)
            val target = demo.target.toOverlayPoint(demo.targetNodeLabel, amapOverlay)
            val completedRoute = buildManualDemoOverlayRoute(
                floorId = demo.currentFloorId,
                route = demo.completedRoute,
                amapOverlay = amapOverlay,
            )
            val pendingRoute = buildManualDemoOverlayRoute(
                floorId = demo.currentFloorId,
                route = demo.pendingRoute,
                amapOverlay = amapOverlay,
            )
            val calibrationPoints = buildManualIndoorCalibrationPoints()
            val calibrationRouteSegments = buildManualIndoorCalibrationRouteSegments()
            return IndoorBusinessOverlay(
                current = current,
                target = target,
                route = completedRoute + pendingRoute.drop(1),
                completedRoute = completedRoute,
                pendingRoute = pendingRoute,
                calibrationPoints = calibrationPoints,
                calibrationRouteSegments = calibrationRouteSegments,
            )
        }
        val currentFloor = state.lastFloorId ?: binding.editFloorId.text.toString().trim().ifBlank { SAMPLE_ENTRY_FLOOR }
        val currentPoint = IndoorPreviewPoint(
            x = state.lastPositionX ?: SAMPLE_ENTRY_X,
            y = state.lastPositionY ?: SAMPLE_ENTRY_Y,
        )
        val target = sampleTargetFor(binding.editTargetPoiId.text.toString().trim())
        val route = buildIndoorPreviewRoute(state, currentPoint, target.point)
        return IndoorBusinessOverlay(
            current = currentPoint.toOverlayPoint("当前位置", currentFloor),
            target = target.point.toOverlayPoint(target.label, target.floorId),
            route = route.mapIndexed { index, point ->
                val floor = when {
                    index == route.lastIndex -> target.floorId
                    point.x == SAMPLE_F2_ESCALATOR_X && point.y == SAMPLE_F2_ESCALATOR_Y -> SAMPLE_TARGET_FLOOR
                    else -> currentFloor
                }
                point.toOverlayPoint("路径点${index + 1}", floor)
            },
        )
    }

    private fun buildImageIndoorPlaceholderOverlay(state: PocUiState): IndoorBusinessOverlay? {
        val plan = indoorImageNavPlan ?: return null
        val floorNodes = imageIndoorRouteNodesForFloor(displayIndoorRouteFloorId(state))
        val currentNode = floorNodes.firstOrNull() ?: plan.start
        val targetNode = floorNodes.lastOrNull() ?: plan.target
        val current = currentNode.toUncalibratedOverlayPoint("当前楼层未完成校准")
        val target = targetNode.toUncalibratedOverlayPoint("目标：${binding.editTargetPoiId.text.toString().trim().ifBlank { targetNode.nodeId }}")
        return IndoorBusinessOverlay(
            current = current,
            target = target,
            route = emptyList(),
            completedRoute = emptyList(),
            pendingRoute = emptyList(),
            calibrationPoints = currentIndoorCalibrationOverlayPoints(),
            calibrationRouteSegments = currentIndoorCalibrationRouteSegments(),
        )
    }

    private fun buildImageIndoorBusinessOverlay(state: PocUiState): IndoorBusinessOverlay? {
        val floorNodes = imageIndoorRouteNodesForFloor(displayIndoorRouteFloorId(state))
        val routePoints = floorNodes.mapNotNull { it.toSavedImageIndoorOverlayPoint() }
        if (routePoints.size < 2) {
            return null
        }
        val currentNodeId = state.manualIndoorDemo.current.nodeId
        val currentIndex = floorNodes.indexOfFirst { it.nodeId == currentNodeId }.takeIf { it >= 0 } ?: 0
        return IndoorBusinessOverlay(
            current = routePoints.getOrNull(currentIndex) ?: routePoints.first(),
            target = routePoints.last(),
            route = routePoints,
            completedRoute = routePoints.take(currentIndex + 1),
            pendingRoute = routePoints.drop(currentIndex),
            calibrationPoints = currentIndoorCalibrationOverlayPoints(),
            calibrationRouteSegments = currentIndoorCalibrationRouteSegments(),
        )
    }

    private fun ImageIndoorNavNode.toUncalibratedOverlayPoint(label: String): IndoorOverlayPoint {
        return IndoorOverlayPoint(
            label = label,
            floorId = floorId,
            x = x,
            y = y,
        )
    }

    private fun ImageIndoorNavNode.toSavedImageIndoorOverlayPoint(): IndoorOverlayPoint? {
        val savedPoint = savedImageIndoorCalibrationPoint(floorId, nodeId) ?: return null
        return IndoorOverlayPoint(
            label = savedPoint.label.ifBlank { nodeId },
            floorId = savedPoint.floorId.ifBlank { floorId },
            x = x,
            y = y,
            latitude = savedPoint.latitude,
            longitude = savedPoint.longitude,
        )
    }

    private fun buildManualDemoOverlayRoute(
        floorId: String,
        route: List<ManualIndoorDemoPoint>,
        amapOverlay: AmapIndoorRouteOverlay?,
    ): List<IndoorOverlayPoint> {
        val routeNodeIds = amapOverlay?.routeNodeIdsForFloor(floorId).orEmpty()
        if (routeNodeIds.isEmpty()) {
            return route.map { it.toOverlayPoint(it.label, amapOverlay) }
        }
        val selectedNodeIds = route.mapNotNull { it.nodeId }.toSet()
        return routeNodeIds
            .filter { it in selectedNodeIds }
            .mapNotNull { nodeId ->
                val point = route.firstOrNull { it.nodeId == nodeId }
                point?.let { it.toOverlayPoint(it.label, amapOverlay) }
                    ?: amapOverlay?.node(nodeId)?.toOverlayPoint(nodeId, null)
            }
    }

    private fun buildManualIndoorCalibrationPoints(): List<IndoorOverlayPoint> {
        if (!isIndoorCalibrationOverlayEnabled) {
            return emptyList()
        }
        return currentIndoorCalibrationOverlayPoints()
    }

    private fun buildManualIndoorCalibrationRouteSegments(): List<List<IndoorOverlayPoint>> {
        if (!isIndoorCalibrationOverlayEnabled) {
            return emptyList()
        }
        return currentIndoorCalibrationRouteSegments()
    }

    private fun currentIndoorCalibrationOverlayPoints(): List<IndoorOverlayPoint> {
        return currentIndoorCalibrationSourcePoints().mapIndexed { index, point ->
            point.toOverlayPoint(index + 1)
        }
    }

    private fun currentIndoorCalibrationRouteSegments(): List<List<IndoorOverlayPoint>> {
        if (isImageIndoorCalibrationActive()) {
            return imageIndoorCalibrationRouteSegmentsForSelectedFloor()
        }
        return currentIndoorCalibrationSourcePoints()
            .groupBy { it.floorId }
            .values
            .map { segment -> segment.mapIndexed { index, point -> point.toOverlayPoint(index + 1) } }
            .filter { it.size >= 2 }
    }

    private fun currentIndoorCalibrationSourcePoints(): List<IndoorCalibrationSourcePoint> {
        val imageNodes = imageIndoorCalibrationNodesForSelectedFloor()
        if (imageNodes.isNotEmpty()) {
            return imageNodes.mapIndexed { index, node ->
                IndoorCalibrationSourcePoint(
                    nodeId = node.nodeId,
                    label = node.nodeType.ifBlank { "${node.floorId} 标记点 ${index + 1}" },
                    floorId = node.floorId,
                    x = node.x,
                    y = node.y,
                )
            }
        }
        return currentManualDemoScript().steps.map { step ->
            val point = step.current
            IndoorCalibrationSourcePoint(
                nodeId = point.nodeId.orEmpty(),
                label = point.calibrationLabel(),
                floorId = point.floorId,
                x = point.x,
                y = point.y,
                latitude = point.latitude,
                longitude = point.longitude,
            )
        }
    }

    private fun imageIndoorCalibrationNodesForSelectedFloor(): List<ImageIndoorNavNode> {
        val repository = indoorImageNavigation ?: return emptyList()
        return repository.graph.nodes
            .filter { it.floorId == indoorImageNavSelectedFloorId }
            .sortedWith(compareBy<ImageIndoorNavNode> { it.y }.thenBy { it.x })
    }

    private fun imageIndoorCalibrationRouteSegmentsForSelectedFloor(): List<List<IndoorOverlayPoint>> {
        val repository = indoorImageNavigation ?: return emptyList()
        val pointsByNodeId = currentIndoorCalibrationSourcePoints().associateBy { it.nodeId }
        return repository.graph.edges.mapNotNull { edge ->
            if (edge.travelMode != "walk") {
                return@mapNotNull null
            }
            val from = pointsByNodeId[edge.fromNodeId] ?: return@mapNotNull null
            val to = pointsByNodeId[edge.toNodeId] ?: return@mapNotNull null
            listOf(from.toOverlayPoint(1), to.toOverlayPoint(2))
        }
    }

    private fun imageIndoorRouteNodesForSelectedFloor(): List<ImageIndoorNavNode> {
        return imageIndoorRouteNodesForFloor(indoorImageNavSelectedFloorId)
    }

    private fun imageIndoorRouteNodesForFloor(floorId: String): List<ImageIndoorNavNode> {
        val plan = indoorImageNavPlan ?: return emptyList()
        return plan.walkSegmentsForFloor(floorId)
            .flatten()
            .dedupeConsecutiveByNodeId()
    }

    private fun displayIndoorRouteFloorId(state: PocUiState): String {
        return if (isIndoorCalibrationOverlayEnabled && isImageIndoorCalibrationActive()) {
            indoorImageNavSelectedFloorId
        } else {
            state.manualIndoorDemo.currentFloorId
        }
    }

    private fun syncImageCalibrationFloorFromAmap(state: PocUiState) {
        if (!isIndoorCalibrationOverlayEnabled) {
            pendingManualCalibrationFloorId = null
            return
        }
        val amapFloorId = state.indoorBasemap.activeFloorName
            ?.toImageIndoorFloorId()
            ?: return
        pendingManualCalibrationFloorId?.let { pendingFloorId ->
            if (amapFloorId == pendingFloorId) {
                pendingManualCalibrationFloorId = null
            } else {
                return
            }
        }
        if (amapFloorId == indoorImageNavSelectedFloorId) {
            return
        }
        val hasFloorNodes = indoorImageNavigation?.graph?.nodes
            ?.any { it.floorId == amapFloorId } == true
        if (!hasFloorNodes) {
            return
        }
        indoorImageNavSelectedFloorId = amapFloorId
        restoreCurrentImageIndoorCalibrationPoints()
    }

    private fun String.toImageIndoorFloorId(): String? {
        val normalized = trim().uppercase()
            .replace(" ", "")
            .replace("层", "F")
        return when {
            normalized == "B1" ||
                normalized == "B1F" ||
                normalized == "-1" ||
                normalized == "负1F" ||
                normalized == "地下1F" ||
                normalized == "地下一F" -> "B1"
            normalized == "F1" ||
                normalized == "1F" ||
                normalized == "1" -> "F1"
            normalized.matches(Regex("B\\d+F?")) -> normalized.removeSuffix("F")
            normalized.matches(Regex("F\\d+")) -> normalized
            normalized.matches(Regex("\\d+F")) -> "F${normalized.removeSuffix("F")}"
            else -> null
        }
    }

    private fun ManualIndoorDemoPoint.toOverlayPoint(
        label: String,
        amapOverlay: AmapIndoorRouteOverlay? = null,
    ): IndoorOverlayPoint {
        val savedPoint = nodeId?.let { savedIndoorCalibrationPoints[it] }
        if (savedPoint != null) {
            return IndoorOverlayPoint(
                label = label,
                floorId = floorId,
                x = x,
                y = y,
                latitude = savedPoint.latitude,
                longitude = savedPoint.longitude,
            )
        }
        if (latitude != null && longitude != null) {
            return IndoorOverlayPoint(
                label = label,
                floorId = floorId,
                x = x,
                y = y,
                latitude = latitude,
                longitude = longitude,
            )
        }
        val overlayNode = amapOverlay?.node(nodeId)
        if (overlayNode != null) {
            return overlayNode.toOverlayPoint(label, this)
        }
        return IndoorOverlayPoint(
            label = label,
            floorId = floorId,
            x = x,
            y = y,
            latitude = latitude,
            longitude = longitude,
        )
    }

    private fun AmapIndoorRouteNode.toCalibrationOverlayPoint(index: Int): IndoorOverlayPoint {
        return IndoorOverlayPoint(
            label = "校准点 $index ${floorId} ${label}",
            floorId = floorId,
            x = 0.0,
            y = 0.0,
            latitude = latitude,
            longitude = longitude,
        )
    }

    private fun ManualIndoorDemoPoint.toCalibrationOverlayPoint(index: Int): IndoorOverlayPoint {
        return IndoorOverlayPoint(
            label = calibrationLabel().ifBlank { "点位 $index" },
            floorId = floorId,
            x = x,
            y = y,
            latitude = latitude,
            longitude = longitude,
        )
    }

    private fun ManualIndoorDemoPoint.calibrationLabel(): String {
        return when (nodeId) {
            "node_f1_west_gate_demo" -> "西门入口"
            "node_f1_west_corridor_turn_demo" -> "F1 西侧通道转角"
            "node_f1_escalator_up_demo_01" -> "F1 上行扶梯口"
            "node_f2_escalator_out_demo_01" -> "F2 扶梯出口"
            "node_f2_tata_corridor_turn_01_demo" -> "F2 TATA 连廊转角 01"
            "node_f2_tata_corridor_turn_02_demo" -> "F2 TATA 连廊转角 02"
            "node_f2_tata_door_demo" -> "2F TATA 店铺门口"
            else -> label
        }
    }

    private fun IndoorCalibrationSourcePoint.toOverlayPoint(index: Int): IndoorOverlayPoint {
        return IndoorOverlayPoint(
            label = label.ifBlank { "路线点 $index" },
            floorId = floorId,
            x = x,
            y = y,
            latitude = latitude,
            longitude = longitude,
        )
    }

    private fun AmapIndoorRouteNode.toOverlayPoint(
        label: String,
        previewPoint: ManualIndoorDemoPoint?,
    ): IndoorOverlayPoint {
        return IndoorOverlayPoint(
            label = label,
            floorId = floorId,
            x = previewPoint?.x ?: 0.0,
            y = previewPoint?.y ?: 0.0,
            latitude = latitude,
            longitude = longitude,
        )
    }

    private fun renderIndoorCalibrationOverlay(state: PocUiState) {
        val show = isIndoorCalibrationOverlayEnabled &&
            state.indoorMode == IndoorNavigationMode.MANUAL_DEMO &&
            state.navState in indoorStates
        binding.viewIndoorCalibrationOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val overlay = buildIndoorBusinessOverlay(state)
            binding.viewIndoorCalibrationOverlay.render(
                points = overlay.calibrationPoints,
                routeSegments = overlay.calibrationRouteSegments,
            )
        }
        renderIndoorCalibrationOverlaySummary()
    }

    private fun renderTopStatus(model: ScreenRenderModel, state: PocUiState) {
        val showTopStatus = !shouldShowSearchSurface(state) && !shouldShowExpandedSearchResults(state)
        binding.topStatusCard.visibility = if (showTopStatus) View.VISIBLE else View.GONE
        if (!showTopStatus) {
            return
        }
        val compactManualStatus = shouldUseCompactManualTopStatus(state)
        val targetPoiId = binding.editTargetPoiId.text.toString().trim().ifBlank { "-" }
        val topMeta = buildTopStatusMeta(state)
        val warningText = state.topCard.warning.orEmpty()
        val errorText = state.topCard.error.orEmpty()
        binding.textOutdoor.text = listOf(
            model.statusHeadline,
            model.statusSummary,
            "目标：$targetPoiId",
            "Provider：${state.selectedProviderId}",
        ).joinToString(separator = "\n")
        binding.textState.text = "阶段：${model.phaseTitle} | ${state.navState} | ${state.navState.displayHint()}"
        binding.textError.text = listOfNotNull(
            warningText.takeIf { it.isNotBlank() },
            errorText.takeIf { it.isNotBlank() },
        ).joinToString(separator = "\n")

        binding.textTopStatusPhase.text = topMeta.phaseLabel
        binding.textTopStatusTarget.text = topMeta.secondaryLabel.orEmpty()
        binding.textTopStatusTarget.visibility = if (topMeta.secondaryLabel != null) View.VISIBLE else View.GONE
        binding.layoutTopStatusMeta.visibility = if (compactManualStatus) View.GONE else View.VISIBLE
        val headlineText = if (compactManualStatus) {
            buildManualTopHeadline(state.manualIndoorDemo)
        } else {
            buildCompactTopSummary(state)
        }
        val summaryText = if (compactManualStatus) {
            buildManualTopSummary(state.manualIndoorDemo)
        } else {
            topMeta.detail
        }
        binding.layoutTopStatusCompactRow.visibility = if (compactManualStatus) View.VISIBLE else View.GONE
        binding.layoutTopStatusMeta.visibility = if (compactManualStatus) View.GONE else View.VISIBLE
        binding.textTopStatusHeadline.visibility = if (compactManualStatus) View.GONE else View.VISIBLE
        binding.textTopStatusSummary.visibility =
            if (compactManualStatus) View.GONE else if (summaryText.isBlank()) View.GONE else View.VISIBLE
        binding.textTopStatusCompactHeadline.text = headlineText
        binding.textTopStatusCompactSummary.text = summaryText
        binding.textTopStatusCompactSummary.visibility = if (summaryText.isBlank()) View.GONE else View.VISIBLE
        binding.textTopStatusCompactFloor.text = state.manualIndoorDemo.currentFloorId
        binding.imageTopStatusCompactIcon.setImageResource(resolveManualTopStatusIconRes(state.manualIndoorDemo))
        binding.topStatusCard.setPadding(
            dp(16),
            dp(if (compactManualStatus) 12 else 14),
            dp(16),
            dp(if (compactManualStatus) 12 else 14),
        )
        (binding.textTopStatusPhase.layoutParams as LinearLayout.LayoutParams).height = dp(if (compactManualStatus) 26 else 28)
        binding.textTopStatusPhase.minWidth = dp(if (compactManualStatus) 58 else 64)
        binding.textTopStatusPhase.textSize = if (compactManualStatus) 11f else 12f
        (binding.textTopStatusTarget.layoutParams as LinearLayout.LayoutParams).apply {
            height = dp(if (compactManualStatus) 26 else 28)
            marginStart = dp(if (compactManualStatus) 6 else 8)
        }
        binding.textTopStatusTarget.minWidth = dp(if (compactManualStatus) 44 else 48)
        binding.textTopStatusTarget.textSize = if (compactManualStatus) 11f else 12f
        binding.textTopStatusHeadline.text = headlineText
        binding.textTopStatusHeadline.textSize = if (compactManualStatus) 15f else 18f
        binding.textTopStatusHeadline.maxLines = if (compactManualStatus) 1 else 3
        binding.textTopStatusSummary.text = summaryText
        binding.textTopStatusSummary.textSize = if (compactManualStatus) 11.5f else 13f
        binding.textTopStatusSummary.maxLines = if (compactManualStatus) 1 else 3
        binding.textTopStatusAlert.text = warningText
        binding.textTopStatusAlert.visibility = if (warningText.isNotBlank()) View.VISIBLE else View.GONE
        binding.textTopStatusError.text = errorText
        binding.textTopStatusError.visibility = if (errorText.isNotBlank()) View.VISIBLE else View.GONE
        binding.textTopStatusProvider.text = buildProviderStatusText(state)
        binding.textTopStatusProvider.visibility = if (isVerboseDebugPanelVisible) View.VISIBLE else View.GONE
    }

    private fun renderBottomActions(model: ScreenRenderModel, state: PocUiState) {
        val showSearchOverlay = shouldShowExpandedSearchResults(state)
        val showMinimalIndoorDock = shouldShowMinimalIndoorDock(state)
        val showSearchHome = shouldShowSearchIdleSuggestions(state) && !isDebugPanelExpanded
        val showSearchHomeSuggestions = showSearchHome && !isSearchIdleSuggestionsCollapsed
        val showSearchHomeCollapsed = showSearchHome && isSearchIdleSuggestionsCollapsed
        val showManualFloating = SHOW_RECORDING_MARKER_FLOATING_PANEL && !showSearchOverlay
        val showManualSheet = showManualFloating && isManualControlSheetExpanded
        val showHiddenDebugDrawer = isDebugPanelExpanded && !showSearchOverlay && !showManualSheet
        val showVerboseDebugControls = showHiddenDebugDrawer && isVerboseDebugPanelVisible
        val showIndoorMainRouteSearch = shouldShowIndoorMainRouteSearch(state) &&
            !showSearchOverlay &&
            !showManualSheet &&
            !showHiddenDebugDrawer
        currentDockModel = buildDockModel(state, model)

        binding.textBottomActionTitle.text = currentDockModel.title
        binding.textBottomActionSubtitle.text = currentDockModel.subtitle
        binding.textBottomActionTitle.visibility =
            if (showSearchOverlay || showMinimalIndoorDock || showSearchHome || showHiddenDebugDrawer || showIndoorMainRouteSearch) {
                View.GONE
            } else {
                View.VISIBLE
            }
        binding.textBottomActionSubtitle.visibility =
            if (showSearchOverlay || showMinimalIndoorDock || showSearchHome || showHiddenDebugDrawer || showIndoorMainRouteSearch || currentDockModel.subtitle.isBlank()) {
                View.GONE
            } else {
                View.VISIBLE
            }
        val hasVisibleDockButton = currentDockModel.primary.isVisibleDockButton() ||
            currentDockModel.leading.isVisibleDockButton() ||
            currentDockModel.trailing.isVisibleDockButton()
        binding.layoutPrimaryDock.visibility =
            if (showSearchOverlay || showSearchHome || showHiddenDebugDrawer || !hasVisibleDockButton) {
                View.GONE
            } else {
                View.VISIBLE
            }

        binding.layoutSearchIdleSuggestions.visibility = if (showSearchHomeSuggestions) View.VISIBLE else View.GONE
        binding.layoutSearchIdleCollapsedHandle.visibility = if (showSearchHomeCollapsed) View.VISIBLE else View.GONE
        if (showSearchHomeSuggestions) {
            renderSearchSuggestionRows(binding.layoutRecentSearchRows, currentRecentSearchSuggestions())
            renderSearchSuggestionRows(binding.layoutSuggestedSearchRows, currentSuggestedSearchSuggestions())
            binding.buttonClearSearchRecent.visibility = if (currentRecentSearchSuggestions().isEmpty()) View.GONE else View.VISIBLE
        }

        binding.layoutHiddenDebugDrawer.visibility = if (showHiddenDebugDrawer) View.VISIBLE else View.GONE
        binding.layoutIndoorMainRouteSearch.visibility = if (showIndoorMainRouteSearch) View.VISIBLE else View.GONE
        val indoorMainRouteActive = showIndoorMainRouteSearch && indoorImageNavPlan != null
        binding.textIndoorMainRouteSearchTitle.visibility =
            if (showIndoorMainRouteSearch && !indoorMainRouteActive) View.VISIBLE else View.GONE
        binding.layoutIndoorMainNavSearchRow.visibility =
            if (showIndoorMainRouteSearch && !indoorMainRouteActive) View.VISIBLE else View.GONE
        binding.layoutIndoorMainNavCandidates.visibility =
            if (showIndoorMainRouteSearch && !indoorMainRouteActive) View.VISIBLE else View.GONE
        binding.layoutIndoorMainNavFloorRow.visibility = if (showIndoorMainRouteSearch && !indoorMainRouteActive && indoorImageNavPlan != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.buttonIndoorMainOpenRokidBridge.visibility =
            if (showIndoorMainRouteSearch) View.VISIBLE else View.GONE
        binding.buttonIndoorMainPairPcBackend.visibility =
            if (showIndoorMainRouteSearch) View.VISIBLE else View.GONE
        binding.buttonIndoorMainOpenRokidWifi.visibility =
            if (showIndoorMainRouteSearch) View.VISIBLE else View.GONE
        binding.layoutIndoorMainRokidStatus.visibility =
            if (showIndoorMainRouteSearch) View.VISIBLE else View.GONE
        renderRokidConnectionStatus()
        binding.buttonIndoorMainNavSimulation.visibility =
            if (indoorMainRouteActive) View.VISIBLE else View.GONE
        binding.buttonIndoorMainNavSimulation.text =
            if (isConferenceWalkDemoRunning()) {
                getString(R.string.action_indoor_stop_walk_demo)
            } else {
                getString(R.string.action_indoor_start_walk_demo)
            }
        binding.buttonIndoorMainNavExit.visibility =
            if (indoorMainRouteActive) View.VISIBLE else View.GONE
        if (showIndoorMainRouteSearch) {
            renderIndoorImageNavCandidates()
            renderIndoorImageNavSummary()
        }

        renderDockButton(binding.buttonDockPrimary, currentDockModel.primary, isPrimary = true)
        renderDockButton(binding.buttonDockSecondaryLeading, currentDockModel.leading)
        renderDockButton(binding.buttonDockSecondaryTrailing, currentDockModel.trailing)

        binding.layoutManualIndoorControls.visibility = if (showManualSheet) View.VISIBLE else View.GONE
        if (showManualSheet) {
            positionManualFloatingControl(binding.layoutManualIndoorControls)
            renderManualRecordingAnnotationPanel()
        }
        binding.buttonManualIndoorControlsExpand.visibility =
            if (showManualFloating && !isManualControlSheetExpanded && !showHiddenDebugDrawer) View.VISIBLE else View.GONE
        if (binding.buttonManualIndoorControlsExpand.visibility == View.VISIBLE) {
            positionManualFloatingControl(binding.buttonManualIndoorControlsExpand)
        }
        binding.buttonToggleDebugPanel.visibility =
            if (showVerboseDebugControls) View.VISIBLE else View.GONE
        binding.buttonToggleDebugPanel.text = model.debugToggleLabel

        binding.layoutBottomOutdoorOptions.visibility = View.GONE
        binding.layoutBottomOutdoorActions.visibility =
            if (showVerboseDebugControls && model.showRoutePreparation) View.VISIBLE else View.GONE
        binding.layoutBottomRouteActions.visibility =
            if (showVerboseDebugControls && (model.showStartNavigation || model.showContinueNavigation)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        binding.layoutBottomExternalActions.visibility =
            if (showVerboseDebugControls && model.showExternalNavigation) View.VISIBLE else View.GONE
        binding.layoutBottomIndoorActions.visibility =
            if (showVerboseDebugControls && (model.showEnterVenue || model.showIndoorActions || model.showExitIndoor)) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.buttonPrepareOutdoor.visibility = if (showVerboseDebugControls && model.showRoutePreparation) View.VISIBLE else View.GONE
        binding.buttonStartOutdoor.visibility =
            if (showVerboseDebugControls && (model.showStartNavigation || model.showContinueNavigation)) View.VISIBLE else View.GONE
        binding.buttonStartOutdoor.text = if (model.showContinueNavigation) {
            getString(R.string.action_continue_navigation)
        } else {
            getString(R.string.action_start_outdoor)
        }
        binding.buttonEnterVenue.visibility = if (showVerboseDebugControls && model.showEnterVenue) View.VISIBLE else View.GONE
        binding.buttonEnterVenue.text = if (externalNavigationSession != null) {
            getString(R.string.action_confirm_entry_handoff)
        } else {
            getString(R.string.action_enter_venue)
        }
        binding.buttonExitIndoor.visibility = if (showVerboseDebugControls && model.showExitIndoor) View.VISIBLE else View.GONE
        val showIndoorRouteRequestButtons = showVerboseDebugControls &&
            model.showIndoorActions &&
            !CONFERENCE_INDOOR_ONLY_MODE
        binding.buttonLocate.visibility = if (showVerboseDebugControls && model.showIndoorActions) View.VISIBLE else View.GONE
        binding.buttonRoute.visibility = if (showIndoorRouteRequestButtons) View.VISIBLE else View.GONE
        binding.buttonPrimaryRoute.visibility = if (showIndoorRouteRequestButtons) View.VISIBLE else View.GONE

        binding.buttonPrimaryContinueNavigation.text = if (externalNavigationSession != null) {
            getString(R.string.action_continue_external_amap_short)
        } else {
            getString(R.string.action_continue_navigation)
        }
        binding.buttonPrimaryEnterVenue.text = if (externalNavigationSession != null) {
            getString(R.string.action_confirm_entry_handoff)
        } else {
            getString(R.string.action_enter_venue)
        }
    }

    private fun renderManualRecordingAnnotationPanel() {
        val session = recordingAnnotationController.session
        val active = session?.active == true
        val aligned = session?.aligned == true
        val usbReady = latestUsbCameraState.ready
        val usbAvailable = latestUsbCameraState.available
        binding.buttonManualIndoorReset.text = if (active) "结束录像" else "开始录像"
        binding.buttonManualIndoorDown.text = "撤销"
        binding.textManualControlHint.text = if (active) {
            val lastElapsed = session?.events?.lastOrNull()?.elapsedMs?.let(::formatAnnotationElapsed)
            buildString {
                append("${session?.events?.size ?: 0} 条")
                if (lastElapsed != null) {
                    append(" · ")
                    append(lastElapsed)
                }
            }
        } else {
            "拖动面板 · 点击此处收起"
        }
        binding.buttonManualIndoorReset.isEnabled = latestGlassesState.ready || usbReady || usbAvailable || active
        binding.buttonManualIndoorUsbDebug.isEnabled = !active
        binding.buttonManualIndoorUsbDebug.alpha = if (binding.buttonManualIndoorUsbDebug.isEnabled) 1f else 0.45f
        listOf(
            binding.buttonManualIndoorUp,
            binding.buttonManualIndoorLeft,
            binding.buttonManualIndoorRight,
            binding.buttonManualIndoorFloorUp,
            binding.buttonManualIndoorFloorDown,
            binding.buttonManualIndoorDown,
        ).forEach { button ->
            button.isEnabled = active && aligned
            button.alpha = if (button.isEnabled) 1f else 0.45f
        }
        binding.textManualControlHint.contentDescription = session?.events
            ?.takeLast(3)
            ?.joinToString("; ") { event -> "${formatAnnotationElapsed(event.elapsedMs)} ${event.action.label}" }
            ?: "录像标记面板"
    }

    private fun renderGlassesCornerStatus(state: GlassesDebugState = latestGlassesState) {
        val usbState = latestUsbCameraState
        val connected = usbState.available || state.connected || state.ready
        binding.textGlassesCornerStatus.text = buildString {
            if (usbState.available) {
                append("📷")
                append(
                    when {
                        usbState.recording -> " USB录像中"
                        usbState.ready -> " USB已连"
                        usbState.opening -> " USB打开中"
                        else -> " USB已检测"
                    },
                )
            } else {
                val battery = state.batterySummary
                    .substringAfter("电量：", missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() && it != "未同步" }
                append(if (state.connected || state.ready) "👓" else "◌")
                append(if (state.ready) " 已连" else if (state.connected) " 连接中" else " 未连")
                if (battery != null) {
                    append(" · ")
                    append(battery)
                }
            }
        }
        binding.textGlassesCornerStatus.alpha = if (connected) 0.82f else 0.58f
    }

    private fun openUsbCameraDebugPage() {
        val session = recordingAnnotationController.session
        if (session?.active == true && session.deviceSource == RecordingAnnotationDeviceSource.USB_CAMERA) {
            Toast.makeText(this, "请先结束 USB 录像标记，再进入 USB 调试", Toast.LENGTH_SHORT).show()
            return
        }
        if (!UsbCameraRecordingManager.releaseCameraForDebug()) {
            Toast.makeText(this, "USB 相机正在录像，暂不能进入调试页", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, UsbCameraDebugActivity::class.java))
    }

    private fun renderSearchSuggestionRows(container: LinearLayout, labels: List<String>) {
        container.removeAllViews()
        labels.chunked(2).forEachIndexed { rowIndex, rowLabels ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also {
                    if (rowIndex > 0) {
                        it.topMargin = dp(8)
                    }
                }
            }
            rowLabels.forEachIndexed { index, label ->
                row.addView(
                    buildSearchSuggestionChip(label),
                    LinearLayout.LayoutParams(0, dp(32), 1f).also {
                        if (index > 0) {
                            it.marginStart = dp(8)
                        }
                    },
                )
            }
            if (rowLabels.size == 1) {
                row.addView(
                    View(this),
                    LinearLayout.LayoutParams(0, dp(32), 1f).also {
                        it.marginStart = dp(8)
                    },
                )
            }
            container.addView(row)
        }
    }

    private fun buildSearchSuggestionChip(label: String): TextView {
        return TextView(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_ui_search_suggestion_chip)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            minHeight = dp(32)
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(0xFF374151.toInt())
            text = label
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            isClickable = true
            isFocusable = true
            setOnClickListener { startQuickSearchFromChip(label) }
        }
    }

    private fun currentRecentSearchSuggestions(): List<String> {
        val defaults = if (hideDefaultRecentSuggestions) {
            emptyList()
        } else {
            defaultRecentSearchSuggestions(selectedSearchType)
        }
        return recentSearchHistory.plus(defaults).distinct().take(4)
    }

    private fun currentSuggestedSearchSuggestions(): List<String> {
        return defaultSuggestedSearchSuggestions(selectedSearchType).take(4)
    }

    private fun defaultRecentSearchSuggestions(type: SearchType): List<String> {
        return when (type) {
            SearchType.STORE -> listOf("优衣库（五道口店）", "五道口购物中心", "北侧入口", "星巴克（中心店）")
            SearchType.MALL_ENTRANCE -> listOf("北侧入口", "商场入口（北侧）", "东1门", "地铁连廊入口")
            SearchType.OFFICE -> listOf("华清商务大厦", "中关村创客中心", "清华科技园 C 座", "汇文中心")
            SearchType.RESIDENTIAL -> listOf("华清嘉园", "中关新园", "五道口家园", "汇文家园")
        }
    }

    private fun defaultSuggestedSearchSuggestions(type: SearchType): List<String> {
        return when (type) {
            SearchType.STORE -> listOf("五道口购物中心", "华清嘉园商场", "清华科技园", "麦当劳（五道口店）")
            SearchType.MALL_ENTRANCE -> listOf("北侧入口", "南侧入口", "东1门", "写字楼主入口")
            SearchType.OFFICE -> listOf("华清商务大厦", "创业大厦", "中关村创客中心", "华清联合办公")
            SearchType.RESIDENTIAL -> listOf("华清嘉园", "广场北里", "学院路公寓", "和平里社区")
        }
    }

    private fun startQuickSearchFromChip(label: String) {
        hideDefaultRecentSuggestions = false
        rememberRecentSearch(label)
        isDebugPanelExpanded = false
        isVerboseDebugPanelVisible = false
        isManualControlSheetExpanded = false
        binding.editOutdoorSearchKeyword.setText(label)
        binding.editOutdoorSearchKeyword.setSelection(label.length)
        binding.editOutdoorSearchKeyword.requestFocus()
        outdoorSearchUiState = SearchUiState.RESULTS_EXPANDED
        scheduleOutdoorPoiSearch()
        renderScreen(viewModel.uiState.value)
    }

    private fun rememberRecentSearch(label: String) {
        if (label.isBlank()) {
            return
        }
        hideDefaultRecentSuggestions = false
        recentSearchHistory.remove(label)
        recentSearchHistory.add(0, label)
        while (recentSearchHistory.size > 6) {
            recentSearchHistory.removeAt(recentSearchHistory.lastIndex)
        }
    }

    private fun cycleProviderSelection() {
        if (viewModel.providerIds.isEmpty()) {
            return
        }
        val currentIndex = binding.spinnerProvider.selectedItemPosition.coerceAtLeast(0)
        val nextIndex = (currentIndex + 1) % viewModel.providerIds.size
        binding.spinnerProvider.setSelection(nextIndex, true)
        Toast.makeText(this, "Provider：${viewModel.providerLabels[nextIndex]}", Toast.LENGTH_SHORT).show()
    }

    private fun shouldShowMinimalIndoorDock(state: PocUiState): Boolean {
        return state.indoorMode == IndoorNavigationMode.MANUAL_DEMO &&
            state.navState in indoorStates &&
            !state.manualIndoorDemo.arrived &&
            !isManualControlSheetExpanded
    }

    private fun renderIndoorPreview(_state: PocUiState) {
        setOptionalVisibility("layoutIndoorPreview", false)
    }

    private fun renderIndoorFallbackMap(state: PocUiState) {
        if (state.indoorMode == IndoorNavigationMode.MANUAL_DEMO && state.navState in indoorStates) {
            val demo = state.manualIndoorDemo
            binding.viewIndoorFallbackMap.render(
                current = demo.current.toPreviewPoint(),
                target = demo.target.toPreviewPoint(),
                completedRoute = demo.completedRoute.map { it.toPreviewPoint() },
                pendingRoute = demo.pendingRoute.map { it.toPreviewPoint() },
                floorLabel = demo.currentFloorId,
                targetLabel = normalizeManualTargetLabel(demo.targetNodeLabel),
            )
            return
        }
        buildExhibitionFallbackRoute(state)?.let { route ->
            binding.viewIndoorFallbackMap.render(
                current = route.current,
                target = route.target,
                completedRoute = emptyList(),
                pendingRoute = route.route,
                floorLabel = route.floorLabel,
                targetLabel = route.targetLabel,
            )
            return
        }

        val currentPoint = IndoorPreviewPoint(
            x = state.lastPositionX ?: SAMPLE_ENTRY_X,
            y = state.lastPositionY ?: SAMPLE_ENTRY_Y,
        )
        val target = sampleTargetFor(binding.editTargetPoiId.text.toString().trim())
        binding.viewIndoorFallbackMap.render(
            current = currentPoint,
            target = target.point,
            completedRoute = emptyList(),
            pendingRoute = buildIndoorPreviewRoute(state, currentPoint, target.point),
            floorLabel = state.lastFloorId ?: binding.editFloorId.text.toString().trim().ifBlank { SAMPLE_ENTRY_FLOOR },
            targetLabel = target.label,
        )
    }

    private fun buildExhibitionFallbackRoute(state: PocUiState): ExhibitionFallbackRoute? {
        if (state.exhibitionRouteNodes.isEmpty()) return null
        val floorId = state.lastFloorId ?: binding.editFloorId.text.toString().trim().ifBlank { "F1" }
        val current = IndoorPreviewPoint(
            x = state.lastPositionX ?: state.exhibitionRouteNodes.values.firstOrNull()?.x ?: return null,
            y = state.lastPositionY ?: state.exhibitionRouteNodes.values.firstOrNull()?.y ?: return null,
        )
        val routePoints = state.exhibitionActiveRouteNodeIds
            .mapNotNull { state.exhibitionRouteNodes[it] }
            .filter { it.floorId == floorId }
            .map { IndoorPreviewPoint(it.x, it.y) }
        val targetPoi = state.exhibitionActiveTargetPoiId?.let { state.exhibitionPois[it] }
        val target = targetPoi?.let { IndoorPreviewPoint(it.x, it.y) }
            ?: routePoints.lastOrNull()
            ?: current
        val route = if (routePoints.isNotEmpty()) {
            listOf(current) + routePoints
        } else {
            listOf(current, target)
        }
        return ExhibitionFallbackRoute(
            current = current,
            target = target,
            route = route,
            floorLabel = floorId,
            targetLabel = targetPoi?.displayName ?: state.exhibitionActiveTargetPoiId ?: binding.editTargetPoiId.text.toString().trim().ifBlank { "展馆目标" },
        )
    }

    private fun renderManualIndoorPreview(state: PocUiState) {
        val demo = state.manualIndoorDemo
        binding.viewIndoorRoutePreview.render(
            current = demo.current.toPreviewPoint(),
            target = demo.target.toPreviewPoint(),
            completedRoute = demo.completedRoute.map { it.toPreviewPoint() },
            pendingRoute = demo.pendingRoute.map { it.toPreviewPoint() },
        )
        binding.textIndoorPreviewSummary.text = listOfNotNull(
            "模式：室内手动演示（${state.indoorMode.id}）",
            "楼层：${demo.currentFloorId}",
            "当前位置：${demo.currentNodeLabel} x=${demo.current.x.formatOneDecimal()}, y=${demo.current.y.formatOneDecimal()}",
            "目标：${demo.targetNodeLabel}",
            "步骤：${demo.currentStepNumber}/${demo.totalSteps}",
            "提示：${demo.instruction}",
            "期望动作：${demo.expectedAction?.label.orDash()}",
            demo.correction?.let { "纠错：$it" },
            if (demo.arrived) "状态：已到达店铺门口" else "状态：演示中",
            state.indoorBasemap.statusSummary,
            state.indoorBasemap.mismatchWarning ?: "高德室内底图楼层：${state.indoorBasemap.activeFloorName.orDash()}",
        ).joinToString(separator = "\n")
    }

    private fun ManualIndoorDemoPoint.toPreviewPoint(): IndoorPreviewPoint {
        return IndoorPreviewPoint(x = x, y = y)
    }

    private fun renderMapControls(model: ScreenRenderModel) {
        val state = viewModel.uiState.value
        val hideForSearchOverlay = shouldShowExpandedSearchResults(state)
        val showSearchHome = shouldShowSearchIdleSuggestions(state) && !isSearchIdleSuggestionsCollapsed
        val hideForManualSheet = isManualControlSheetExpanded
        val params = binding.mapControlPanel.layoutParams as FrameLayout.LayoutParams
        params.bottomMargin = when {
            showSearchHome -> dp(232)
            isManualControlSheetExpanded -> dp(320)
            else -> dp(172)
        }
        binding.mapControlPanel.layoutParams = params

        val showRecenter = model.showRecenter && !CONFERENCE_INDOOR_ONLY_MODE && !hideForSearchOverlay && !hideForManualSheet
        val showHeading = model.showOrientationToggle && !hideForSearchOverlay && !showSearchHome && !hideForManualSheet
        val showOverview = model.showOverview && !hideForSearchOverlay && !showSearchHome && !hideForManualSheet
        val showExitNavigation = false
        val anyVisible = showRecenter || showHeading || showOverview || showExitNavigation

        binding.mapControlPanel.visibility = if (anyVisible) View.VISIBLE else View.GONE
        binding.buttonRecenterOutdoor.visibility = if (showRecenter) View.VISIBLE else View.GONE
        binding.buttonToggleOutdoorHeading.visibility = if (showHeading) View.VISIBLE else View.GONE
        binding.buttonOverviewOutdoor.visibility = if (showOverview) View.VISIBLE else View.GONE
        binding.buttonExitOutdoorNavi.visibility = if (showExitNavigation) View.VISIBLE else View.GONE

        setOptionalVisibility("buttonMapRecenter", showRecenter)
        setOptionalVisibility("buttonMapHeading", showHeading)
        setOptionalVisibility("buttonMapOverview", showOverview)
        setOptionalVisibility("buttonMapExitNavigation", showExitNavigation)
    }

    private fun renderOutdoorProgress(model: ScreenRenderModel) {
        val showOutdoorProgress = model.showOutdoorProgress
        val speedText = model.statusSummary.extractValueAfter("速度=") ?: "--"
        setOptionalVisibility("textOutdoorSpeedBadge", showOutdoorProgress)
        setOptionalText("textOutdoorSpeedBadge", speedText.toSpeedBadgeText())
        updateOutdoorSpeedBadgePosition()
        setOptionalVisibility("layoutOutdoorProgressBadge", false)
        binding.progressOutdoorRoute.progress = 0
    }

    private fun updateOutdoorSpeedBadgePosition() {
        val speedBadge = binding.textOutdoorSpeedBadge
        if (speedBadge.visibility != View.VISIBLE) {
            return
        }
        speedBadge.post {
            val targetTop = if (binding.topStatusCard.isShown && binding.topStatusCard.height > 0) {
                binding.topStatusCard.bottom + dp(8)
            } else {
                statusBarExtraTopInset + dp(16)
            }
            val params = speedBadge.layoutParams as? FrameLayout.LayoutParams ?: return@post
            val targetStart = dp(12)
            if (params.topMargin == targetTop && params.leftMargin == targetStart) {
                return@post
            }
            params.topMargin = targetTop
            params.leftMargin = targetStart
            speedBadge.layoutParams = params
        }
    }

    private fun renderDebugPanel(model: ScreenRenderModel, navState: NavState) {
        if (forcesDebugCollapse(navState)) {
            isDebugPanelExpanded = false
        }
        val debugPanelParams = binding.scrollDebugPanel.layoutParams
        debugPanelParams.height = if (navState in indoorStates) dp(180) else dp(260)
        binding.scrollDebugPanel.layoutParams = debugPanelParams
        val showVerboseDebugScroll = isDebugPanelExpanded && isVerboseDebugPanelVisible
        binding.scrollDebugPanel.visibility =
            if (binding.bottomSheet.visibility == View.VISIBLE && model.showDebugPanel && showVerboseDebugScroll) {
                View.VISIBLE
            } else {
                View.GONE
            }

        setOptionalText("textDebugToggle", model.debugToggleLabel)
        binding.buttonToggleDebugPanel.text = model.debugToggleLabel
    }

    private fun normalizeDebugPanelForState(navState: NavState) {
        if (forcesDebugCollapse(navState)) {
            isDebugPanelExpanded = false
        }
        if (!isDebugPanelExpanded) {
            isVerboseDebugPanelVisible = false
        }
        if (navState !in outdoorReadyStates) {
            binding.editOutdoorSearchKeyword.clearFocus()
        }
        lastRenderedNavState = navState
    }

    private fun renderBottomSheetChrome(state: PocUiState) {
        val showSearchOverlay = shouldShowExpandedSearchResults(state)
        val showIdleSearchHome = shouldShowSearchIdleSuggestions(state) && !isDebugPanelExpanded
        val showIdleSearchHomeCollapsed = showIdleSearchHome && isSearchIdleSuggestionsCollapsed
        val showDebugDrawer = isDebugPanelExpanded && !showSearchOverlay && !isManualControlSheetExpanded
        val isTypingSearch = shouldShowSearchSurface(state) &&
            binding.editOutdoorSearchKeyword.hasFocus() &&
            binding.editOutdoorSearchKeyword.text.toString().trim().isNotBlank() &&
            outdoorPoiOptions.isEmpty() &&
            !isDebugPanelExpanded
        val hideBottomSheet = showSearchOverlay || isTypingSearch
        binding.bottomSheet.visibility = if (hideBottomSheet) View.GONE else View.VISIBLE
        if (hideBottomSheet) {
            return
        }
        val params = binding.bottomSheet.layoutParams as FrameLayout.LayoutParams
        val useIndoorManualDock = state.indoorMode == IndoorNavigationMode.MANUAL_DEMO &&
            state.navState in indoorStates &&
            !state.manualIndoorDemo.arrived &&
            !showDebugDrawer
        params.gravity = Gravity.BOTTOM
        val useSheetChrome = showIdleSearchHome || showDebugDrawer
        if (showIdleSearchHomeCollapsed) {
            params.width = dp(108)
            params.leftMargin = 0
            params.rightMargin = 0
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.leftMargin = if (useIndoorManualDock) dp(96) else dp(16)
            params.rightMargin = dp(16)
            params.gravity = Gravity.BOTTOM
        }
        params.bottomMargin = dp(12)
        binding.bottomSheet.setBackgroundResource(
            if (useSheetChrome) {
                R.drawable.bg_ui_results_sheet
            } else {
                R.drawable.bg_ui_bottom_dock
            },
        )
        binding.bottomSheet.elevation = if (useSheetChrome) dp(12).toFloat() else dp(10).toFloat()
        binding.bottomSheet.setPadding(
            dp(12),
            if (showIdleSearchHomeCollapsed) dp(8) else if (useSheetChrome) dp(10) else dp(12),
            dp(12),
            dp(12),
        )
        binding.bottomSheet.layoutParams = params
    }

    private fun renderRokidConnectionStatus() {
        val status = RokidRuntimeBridge.connectionStatus()
        val green = Color.parseColor("#059669")
        val amber = Color.parseColor("#D97706")
        val gray = Color.parseColor("#9CA3AF")
        binding.textIndoorMainRokidCxrStatus.text =
            if (status.cxrConnected) "● CXR 已连" else "● CXR 未连"
        binding.textIndoorMainRokidCxrStatus.setTextColor(
            if (status.cxrConnected) green else gray,
        )
        binding.textIndoorMainRokidHttpStatus.text = when {
            status.httpConnected -> "● HTTP 已连"
            status.httpConfigured -> "● HTTP ${status.httpStatusLabel}"
            else -> "● HTTP 未启"
        }
        binding.textIndoorMainRokidHttpStatus.setTextColor(
            when {
                status.httpConnected -> green
                status.httpConfigured -> amber
                else -> gray
            },
        )
        renderPcBackendStatus(viewModel.uiState.value)
    }

    private fun renderPcBackendStatus(state: PocUiState) {
        val green = Color.parseColor("#059669")
        val gray = Color.parseColor("#9CA3AF")
        binding.textIndoorMainPcBackendStatus.text =
            if (state.pcBackendConnected) "● PC 已连" else "● PC 未连"
        binding.textIndoorMainPcBackendStatus.setTextColor(
            if (state.pcBackendConnected) green else gray,
        )
    }

    private fun renderDockButton(
        view: TextView,
        model: DockButtonModel?,
        isPrimary: Boolean = false,
    ) {
        if (!model.isVisibleDockButton()) {
            view.visibility = View.GONE
            return
        }
        model ?: return
        view.visibility = View.VISIBLE
        view.text = model.label
        view.isEnabled = model.action != DockAction.NONE
        view.alpha = if (view.isEnabled) 1f else 0.45f
        if (!isPrimary) {
            view.setBackgroundResource(R.drawable.bg_ui_secondary_button)
            view.setTextColor(0xFF374151.toInt())
        }
    }

    private fun DockButtonModel?.isVisibleDockButton(): Boolean {
        return this != null && (label.isNotBlank() || action != DockAction.NONE)
    }

    private fun buildDockModel(
        state: PocUiState,
        model: ScreenRenderModel,
    ): DockModel {
        val hasSelection = selectedOutdoorPoi != null || hasSelectedMapPoiPendingNavigation
        if (state.navState in indoorStates || (state.navState == NavState.ERROR && lastIndoorMapHostVisible)) {
            return buildIndoorDockModel(state)
        }
        return when (state.navState) {
            NavState.OUTDOOR_NAVIGATING -> {
                val primary = if (externalNavigationSession != null) {
                    DockButtonModel(getString(R.string.action_continue_external_amap_short), DockAction.OPEN_EXTERNAL_AMAP)
                } else {
                    DockButtonModel(getString(R.string.action_continue_navigation), DockAction.CONTINUE_NAVIGATION)
                }
                DockModel(
                    title = getString(R.string.title_bottom_dock_outdoor_nav),
                    subtitle = getString(R.string.subtitle_bottom_dock_outdoor_nav),
                    primary = primary,
                    leading = DockButtonModel(getString(R.string.action_exit_outdoor_navi), DockAction.EXIT_OUTDOOR)
                        .takeIf { model.showExitNavigation },
                    trailing = DockButtonModel(getString(R.string.action_enter_venue), DockAction.ENTER_VENUE).takeIf { model.showEnterVenue },
                )
            }
            NavState.ENTRY_HANDOFF_PENDING -> DockModel(
                title = getString(R.string.title_bottom_dock_handoff),
                subtitle = getString(R.string.subtitle_bottom_dock_handoff),
                primary = DockButtonModel(getString(R.string.action_enter_venue), DockAction.ENTER_VENUE),
                leading = if (externalNavigationSession != null) {
                    DockButtonModel(getString(R.string.action_continue_external_amap_short), DockAction.OPEN_EXTERNAL_AMAP)
                } else if (selectedOutdoorPoi != null || hasSelectedMapPoiPendingNavigation) {
                    DockButtonModel(getString(R.string.action_open_external_amap_short), DockAction.OPEN_EXTERNAL_AMAP)
                } else {
                    null
                },
            )
            NavState.OUTDOOR_ROUTE_READY -> DockModel(
                title = getString(R.string.title_bottom_dock_route_ready),
                subtitle = getString(R.string.subtitle_bottom_dock_route_ready),
                primary = DockButtonModel(getString(R.string.action_start_outdoor), DockAction.START_OUTDOOR),
                leading = DockButtonModel(
                    if (externalNavigationSession != null) {
                        getString(R.string.action_continue_external_amap_short)
                    } else {
                        getString(R.string.action_open_external_amap_short)
                    },
                    DockAction.OPEN_EXTERNAL_AMAP,
                ),
            )
            NavState.ERROR -> {
                if (hasSelection) {
                    DockModel(
                        title = getString(R.string.title_bottom_dock_selection),
                        subtitle = getString(R.string.subtitle_bottom_dock_selection),
                        primary = DockButtonModel(getString(R.string.action_go_to_entrance), DockAction.START_OUTDOOR),
                        leading = DockButtonModel(getString(R.string.action_external_amap_short), DockAction.OPEN_EXTERNAL_AMAP),
                    )
                } else {
                    DockModel(
                        title = getString(R.string.title_bottom_dock_search_idle),
                        subtitle = getString(R.string.subtitle_bottom_dock_search_idle),
                        primary = DockButtonModel(getString(R.string.action_search_outdoor_poi), DockAction.FOCUS_SEARCH),
                        leading = DockButtonModel(getString(R.string.action_current_location_short), DockAction.USE_CURRENT_LOCATION),
                    )
                }
            }
            else -> {
                if (hasSelection) {
                    DockModel(
                        title = getString(R.string.title_bottom_dock_selection),
                        subtitle = getString(R.string.subtitle_bottom_dock_selection),
                        primary = DockButtonModel(getString(R.string.action_go_to_entrance), DockAction.START_OUTDOOR),
                        leading = DockButtonModel(getString(R.string.action_external_amap_short), DockAction.OPEN_EXTERNAL_AMAP),
                    )
                } else {
                    DockModel(
                        title = getString(R.string.title_bottom_dock_search_idle),
                        subtitle = getString(R.string.subtitle_bottom_dock_search_idle),
                        primary = DockButtonModel(getString(R.string.action_search_outdoor_poi), DockAction.FOCUS_SEARCH),
                        leading = DockButtonModel(getString(R.string.action_current_location_short), DockAction.USE_CURRENT_LOCATION),
                    )
                }
            }
        }
    }

    private fun buildIndoorDockModel(state: PocUiState): DockModel {
        if (state.indoorMode == IndoorNavigationMode.MANUAL_DEMO) {
            if (state.manualIndoorDemo.arrived) {
                return DockModel(
                    title = getString(R.string.title_bottom_dock_arrived),
                    subtitle = getString(R.string.subtitle_bottom_dock_arrived),
                    primary = DockButtonModel(getString(R.string.action_complete_arrival), DockAction.COMPLETE_ARRIVAL),
                )
            }
            return DockModel(
                title = getString(R.string.title_bottom_dock_indoor),
                subtitle = getString(R.string.subtitle_bottom_dock_indoor),
                primary = DockButtonModel(getString(R.string.action_continue_indoor), DockAction.INDOOR_CONTINUE),
                leading = DockButtonModel(getString(R.string.action_previous_step), DockAction.INDOOR_BACK),
                trailing = DockButtonModel(getString(R.string.action_more), DockAction.INDOOR_MORE)
                    .takeUnless { CONFERENCE_INDOOR_ONLY_MODE },
            )
        }
        if (CONFERENCE_INDOOR_ONLY_MODE) {
            return when (state.navState) {
                NavState.INDOOR_LOW_CONFIDENCE -> DockModel(
                    title = "定位不够稳定",
                    subtitle = "请面向测试卡或展台，Rokid 图传会自动刷新定位。",
                    primary = DockButtonModel("", DockAction.NONE),
                )
                NavState.INDOOR_ROUTE_READY -> DockModel(
                    title = getString(R.string.title_bottom_dock_indoor),
                    subtitle = "当前使用 Rokid 眼镜图传定位，搜索展台号后会自动规划路线。",
                    primary = DockButtonModel("", DockAction.NONE),
                )
                else -> DockModel(
                    title = getString(R.string.title_bottom_dock_indoor),
                    subtitle = "请保持 Rokid 眼镜图传在线，定位会自动刷新。",
                    primary = DockButtonModel("", DockAction.NONE),
                )
            }
        }
        return when (state.navState) {
            NavState.INDOOR_LOW_CONFIDENCE -> DockModel(
                title = "定位不够稳定",
                subtitle = "请面向测试卡或展台，让 Rokid 图传画面稳定后重新定位。",
                primary = DockButtonModel(getString(R.string.action_retry_capture), DockAction.CAPTURE_AND_LOCATE),
                trailing = DockButtonModel(getString(R.string.action_more), DockAction.TOGGLE_DEBUG),
            )
            NavState.INDOOR_ROUTE_READY -> DockModel(
                title = getString(R.string.title_bottom_dock_indoor),
                subtitle = "当前使用 Rokid 眼镜图传定位，可继续请求路径或重新定位。",
                primary = DockButtonModel(getString(R.string.action_request_route), DockAction.REQUEST_INDOOR_ROUTE),
                leading = DockButtonModel(getString(R.string.action_retry_capture), DockAction.CAPTURE_AND_LOCATE),
                trailing = DockButtonModel(getString(R.string.action_more), DockAction.TOGGLE_DEBUG),
            )
            else -> DockModel(
                title = getString(R.string.title_bottom_dock_indoor),
                subtitle = "请保持 Rokid 眼镜图传在线，先重新定位再请求路径。",
                primary = DockButtonModel(getString(R.string.action_retry_capture), DockAction.CAPTURE_AND_LOCATE),
                leading = DockButtonModel(getString(R.string.action_request_route), DockAction.REQUEST_INDOOR_ROUTE),
                trailing = DockButtonModel(getString(R.string.action_more), DockAction.TOGGLE_DEBUG),
            )
        }
    }

    private fun buildCompactTopSummary(state: PocUiState): String {
        return when {
            state.indoorMode == IndoorNavigationMode.MANUAL_DEMO && state.navState in indoorStates && state.manualIndoorDemo.arrived -> {
                "已到达店铺门口"
            }
            state.indoorMode == IndoorNavigationMode.MANUAL_DEMO && state.navState in indoorStates -> {
                state.manualIndoorDemo.instruction
            }
            state.navState == NavState.ENTRY_HANDOFF_PENDING -> "已接近入口"
            state.navState == NavState.OUTDOOR_ROUTE_READY && selectedOutdoorPoi != null -> "入口路线已就绪"
            state.navState == NavState.OUTDOOR_NAVIGATING && externalNavigationSession != null -> "继续前往入口"
            selectedOutdoorPoi != null && state.navState in outdoorReadyStates -> selectedOutdoorPoi?.title.orEmpty()
            else -> state.topCard.headline
        }
    }

    private fun shouldUseCompactManualTopStatus(state: PocUiState): Boolean {
        return state.indoorMode == IndoorNavigationMode.MANUAL_DEMO && state.navState in indoorStates
    }

    private fun buildManualTopHeadline(demo: ManualIndoorDemoState): String {
        return when (demo.expectedAction) {
            ManualIndoorDemoAction.UP -> "继续直行"
            ManualIndoorDemoAction.LEFT -> "前方左转"
            ManualIndoorDemoAction.RIGHT -> "前方右转"
            ManualIndoorDemoAction.FLOOR_UP -> "上楼"
            ManualIndoorDemoAction.FLOOR_DOWN -> "下楼"
            ManualIndoorDemoAction.BACK -> "回退一步"
            null -> "已到达"
        }
    }

    private fun buildManualTopSummary(demo: ManualIndoorDemoState): String {
        val targetLabel = normalizeManualTargetLabel(demo.targetNodeLabel)
        if (demo.arrived) {
            return "已到达 $targetLabel"
        }
        return "预计剩余 ${demo.remainingDurationSeconds.formatEtaDuration()} · ${demo.remainingDistanceMeters.formatRemainingDistance()}"
    }

    private fun resolveManualTopStatusIconRes(demo: ManualIndoorDemoState): Int {
        return when {
            demo.arrived -> R.drawable.ic_top_status_arrived_24
            demo.expectedAction == ManualIndoorDemoAction.LEFT -> R.drawable.ic_top_status_left_24
            demo.expectedAction == ManualIndoorDemoAction.RIGHT -> R.drawable.ic_top_status_right_24
            demo.expectedAction == ManualIndoorDemoAction.FLOOR_UP -> R.drawable.ic_top_status_floor_up_24
            demo.expectedAction == ManualIndoorDemoAction.FLOOR_DOWN -> R.drawable.ic_top_status_floor_down_24
            demo.expectedAction == ManualIndoorDemoAction.BACK -> R.drawable.ic_top_status_left_24
            else -> R.drawable.ic_top_status_forward_24
        }
    }

    private fun normalizeManualTargetLabel(label: String): String {
        return label.replace(Regex("^\\s*\\d+F\\s+"), "").ifBlank { label }
    }

    private fun Double.formatEtaDuration(): String {
        val totalSeconds = kotlin.math.ceil(coerceAtLeast(0.0)).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}分钟${seconds}秒"
    }

    private fun Double.formatRemainingDistance(): String {
        return "${kotlin.math.ceil(coerceAtLeast(0.0)).toInt()}米"
    }

    private fun buildTopStatusMeta(state: PocUiState): TopStatusMeta {
        val phaseLabel = when (state.topCard.phase) {
            DemoPhase.OUTDOOR -> "室外"
            DemoPhase.HANDOFF -> "交接"
            DemoPhase.INDOOR -> "室内"
            DemoPhase.ERROR -> "异常"
            DemoPhase.ABORTED -> "中止"
        }
        val secondaryLabel = when {
            state.navState in indoorStates && state.indoorMode == IndoorNavigationMode.MANUAL_DEMO -> {
                state.manualIndoorDemo.currentFloorId
            }
            state.navState in indoorStates -> state.lastFloorId ?: binding.editFloorId.text.toString().trim().ifBlank { null }
            state.navState == NavState.ENTRY_HANDOFF_PENDING -> "入口"
            state.navState in outdoorReadyStates || state.navState == NavState.OUTDOOR_NAVIGATING -> readOutdoorTravelMode().displayName
            else -> null
        }
        val detail = when {
            state.indoorMode == IndoorNavigationMode.MANUAL_DEMO && state.navState in indoorStates && state.manualIndoorDemo.arrived -> {
                "你已到达店铺门口。"
            }
            state.indoorMode == IndoorNavigationMode.MANUAL_DEMO && state.navState in indoorStates -> {
                "楼层 ${state.manualIndoorDemo.currentFloorId} · ${state.manualIndoorDemo.currentNodeLabel}"
            }
            state.navState == NavState.OUTDOOR_ROUTE_READY -> getString(R.string.subtitle_bottom_dock_route_ready)
            state.navState == NavState.OUTDOOR_NAVIGATING -> getString(R.string.subtitle_bottom_dock_outdoor_nav)
            state.navState == NavState.ENTRY_HANDOFF_PENDING -> getString(R.string.subtitle_bottom_dock_handoff)
            selectedOutdoorPoi != null && state.navState in outdoorReadyStates -> getString(R.string.subtitle_bottom_dock_selection)
            else -> state.topCard.detail.lineSequence().firstOrNull().orEmpty()
        }
        return TopStatusMeta(
            phaseLabel = phaseLabel,
            secondaryLabel = secondaryLabel,
            detail = detail,
        )
    }

    private fun renderStatusDetails(state: PocUiState) {
        val connectionSummary = "连接：${state.connectionState.displayLabel()}（${state.connectionState}）"
        val serviceDetails = listOf(state.pcBackendSummary, state.serviceSummary)
            .distinct()
            .joinToString(separator = "\n")
        binding.textService.text = serviceDetails
        binding.textVenue.text = state.venueSummary
        binding.textConnection.text = connectionSummary
        binding.textProvider.text = state.providerSummary
        binding.textRequest.text = state.requestSummary
        binding.textLocalization.text = state.localizationSummary
        binding.textRoute.text = state.routeSummary
        binding.textLogs.text = state.logs.joinToString(separator = "\n")

        setOptionalText("textDebugService", serviceDetails)
        setOptionalText("textDebugVenue", state.venueSummary)
        setOptionalText("textDebugConnection", connectionSummary)
        setOptionalText("textDebugProvider", state.providerSummary)
        setOptionalText("textDebugRequest", state.requestSummary)
        setOptionalText("textDebugLocalization", state.localizationSummary)
        setOptionalText("textDebugRoute", state.routeSummary)
        setOptionalText("textDebugLogs", binding.textLogs.text.toString())
        renderPcBackendStatus(state)
    }

    private fun copyAppLogsToClipboard() {
        val logText = viewModel.fullSessionLog()
        if (logText.isBlank()) {
            Toast.makeText(this, R.string.text_app_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VisionRoute app logs", logText))
        Toast.makeText(
            this,
            getString(R.string.status_app_log_copied, logText.lineSequence().count()),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun buildProviderStatusText(state: PocUiState): String {
        val fallback = if (state.uiSignals.fallbackActive) {
            "降级：${state.uiSignals.fallbackFromProviderId.orDash()} -> ${state.uiSignals.fallbackToProviderId.orDash()}"
        } else {
            "降级：无"
        }
        val lastSuccess = "最近成功：${state.lastSuccessfulProviderId.orDash()}"
        val lastFailure = "最近失败：${state.lastFailedProviderId.orDash()} ${state.lastProviderFailureReason.orDash()}"
        return listOf(
            "Provider：${state.selectedProviderId}",
            fallback,
            lastSuccess,
            lastFailure,
        ).joinToString(separator = " | ")
    }

    private fun sampleTargetFor(targetPoiId: String): IndoorPreviewTarget {
        val label = when {
            targetPoiId == "poi_f2_tata_door_demo" -> "2F TATA 店铺门口（poi_f2_tata_door_demo）"
            targetPoiId.isBlank() -> "2F TATA 店铺门口（poi_f2_tata_door_demo）"
            else -> "$targetPoiId（样例目标点）"
        }
        return IndoorPreviewTarget(
            label = label,
            floorId = SAMPLE_TARGET_FLOOR,
            point = IndoorPreviewPoint(SAMPLE_TARGET_X, SAMPLE_TARGET_Y),
        )
    }

    private fun buildIndoorPreviewRoute(
        state: PocUiState,
        currentPoint: IndoorPreviewPoint,
        targetPoint: IndoorPreviewPoint,
    ): List<IndoorPreviewPoint> {
        return if (state.lastFloorId == SAMPLE_TARGET_FLOOR) {
            listOf(currentPoint, targetPoint)
        } else {
            listOf(
                currentPoint,
                IndoorPreviewPoint(SAMPLE_F1_ESCALATOR_X, SAMPLE_F1_ESCALATOR_Y),
                IndoorPreviewPoint(SAMPLE_F2_ESCALATOR_X, SAMPLE_F2_ESCALATOR_Y),
                targetPoint,
            )
        }
    }

    private fun formatIndoorPoint(point: IndoorPreviewPoint, isLocated: Boolean): String {
        val prefix = if (isLocated) "定位点" else "样例入口"
        return "$prefix x=${point.x.formatOneDecimal()}, y=${point.y.formatOneDecimal()}"
    }

    private fun IndoorPreviewPoint.toOverlayPoint(label: String, floorId: String): IndoorOverlayPoint {
        return IndoorOverlayPoint(
            label = label,
            floorId = floorId,
            x = x,
            y = y,
        )
    }

    private fun indoorNextAction(state: PocUiState): String {
        val route = state.routeSummary.takeUnless { it == "路径：暂无结果" }
        if (route != null) {
            return route
        }
        if (state.lastPositionX == null || state.lastPositionY == null) {
            return "先执行采图并定位获取当前定位"
        }
        return if (state.lastFloorId == SAMPLE_TARGET_FLOOR) {
            "沿蓝色路径前往目标点"
        } else {
            "前往扶梯上行至 $SAMPLE_TARGET_FLOOR 后继续"
        }
    }

    private fun toggleDebugPanel() {
        val state = viewModel.uiState.value
        if (forcesDebugCollapse(state.navState)) {
            isDebugPanelExpanded = false
        } else {
            isDebugPanelExpanded = !isDebugPanelExpanded
        }
        if (isDebugPanelExpanded) {
            isVerboseDebugPanelVisible = false
            isManualControlSheetExpanded = false
            binding.editOutdoorSearchKeyword.clearFocus()
            collapseOutdoorPoiResults()
        } else {
            isVerboseDebugPanelVisible = false
        }
        renderScreen(state)
    }

    private fun collapseDebugPanel() {
        if (!isDebugPanelExpanded) {
            return
        }
        isDebugPanelExpanded = false
        isVerboseDebugPanelVisible = false
        renderScreen(viewModel.uiState.value)
    }

    private fun collapseBottomPanel() {
        val state = viewModel.uiState.value
        val hadSearchOverlay = shouldShowExpandedSearchResults(state)
        if (!hadSearchOverlay && !isDebugPanelExpanded && !isManualControlSheetExpanded) {
            return
        }
        isDebugPanelExpanded = false
        isVerboseDebugPanelVisible = false
        isManualControlSheetExpanded = false
        if (hadSearchOverlay) {
            binding.editOutdoorSearchKeyword.clearFocus()
            collapseOutdoorPoiResults()
        }
        renderScreen(state)
    }

    private fun forcesDebugCollapse(navState: NavState): Boolean {
        return false
    }

    private fun statusHeadlineFor(navState: NavState): String {
        return when (navState) {
            NavState.OUTDOOR_IDLE -> "室外准备中"
            NavState.OUTDOOR_READY -> "可准备室外路线"
            NavState.OUTDOOR_ROUTE_READY -> "室外路线已就绪"
            NavState.OUTDOOR_NAVIGATING -> "室外导航进行中"
            NavState.ENTRY_HANDOFF_PENDING -> "等待进入场馆交接"
            NavState.INDOOR_READY -> "室内联调已就绪"
            NavState.INDOOR_CAPTURING -> "正在采图"
            NavState.INDOOR_LOCATING -> "正在定位"
            NavState.INDOOR_LOW_CONFIDENCE -> "定位置信度较低"
            NavState.INDOOR_ROUTING -> "正在请求室内路径"
            NavState.INDOOR_ROUTE_READY -> "室内路径已就绪"
            NavState.ERROR -> "当前流程异常"
            NavState.ABORTED -> "流程已中止"
        }
    }

    private fun bindPersistence() {
        binding.editOutdoorStartLat.doAfterTextChanged { persistSettings() }
        binding.editOutdoorStartLng.doAfterTextChanged { persistSettings() }
        binding.editOutdoorEntryLat.doAfterTextChanged { persistSettings() }
        binding.editOutdoorEntryLng.doAfterTextChanged { persistSettings() }
        binding.editOutdoorSearchKeyword.doAfterTextChanged {
            if (!suppressOutdoorSearchTextWatcher && outdoorSearchUiState == SearchUiState.DESTINATION_SELECTED) {
                clearSelectedOutdoorPoiForSearch()
                outdoorSearchUiState = SearchUiState.EDITING
            }
            persistSettings()
            scheduleOutdoorPoiSearch()
        }
        binding.editOutdoorSearchCity.doAfterTextChanged { persistSettings() }
        binding.editBaseUrl.doAfterTextChanged { persistSettings() }
        binding.editVenueId.doAfterTextChanged { persistSettings() }
        binding.editFloorId.doAfterTextChanged { persistSettings() }
        binding.editTargetPoiId.doAfterTextChanged { persistSettings() }
        binding.editDebugTarget.doAfterTextChanged { persistSettings() }
    }

    private fun restoreSettings() {
        restoreOutdoorPoint(KEY_OUTDOOR_START_LAT, KEY_OUTDOOR_START_LNG, binding.editOutdoorStartLat, binding.editOutdoorStartLng)
        restoreOutdoorPoint(KEY_OUTDOOR_ENTRY_LAT, KEY_OUTDOOR_ENTRY_LNG, binding.editOutdoorEntryLat, binding.editOutdoorEntryLng)
        migrateLegacyWudaokouEntryPoint()
        binding.editOutdoorSearchKeyword.setText(prefs.getString(KEY_OUTDOOR_SEARCH_KEYWORD, binding.editOutdoorSearchKeyword.text.toString()))
        binding.editOutdoorSearchCity.setText(prefs.getString(KEY_OUTDOOR_SEARCH_CITY, binding.editOutdoorSearchCity.text.toString()))
        binding.editBaseUrl.setText(prefs.getString(KEY_BASE_URL, binding.editBaseUrl.text.toString()))
        val storedVenueId = prefs.getString(KEY_VENUE_ID, binding.editVenueId.text.toString()) ?: binding.editVenueId.text.toString()
        val storedPoiId = prefs.getString(KEY_POI_ID, binding.editTargetPoiId.text.toString()) ?: binding.editTargetPoiId.text.toString()
        val venueId = migrateDemoVenueId(storedVenueId)
        val poiId = migrateDemoTargetPoiId(storedPoiId)
        binding.editVenueId.setText(venueId)
        binding.editFloorId.setText(prefs.getString(KEY_FLOOR_ID, binding.editFloorId.text.toString()))
        binding.editTargetPoiId.setText(poiId)
        binding.editDebugTarget.setText(prefs.getString(KEY_DEBUG_TARGET, binding.editDebugTarget.text.toString()))
        if (venueId != storedVenueId || poiId != storedPoiId) {
            prefs.edit()
                .putString(KEY_VENUE_ID, venueId)
                .putString(KEY_POI_ID, poiId)
                .apply()
        }
        val providerId = prefs.getString(KEY_PROVIDER_ID, viewModel.providerIds.first()) ?: viewModel.providerIds.first()
        val providerPosition = viewModel.providerIds.indexOf(providerId)
        if (providerPosition >= 0) {
            binding.spinnerProvider.setSelection(providerPosition, false)
            viewModel.selectProvider(providerId)
        }
        val travelModeId = prefs.getString(KEY_OUTDOOR_TRAVEL_MODE, OutdoorTravelMode.RIDE.id) ?: OutdoorTravelMode.RIDE.id
        val travelModePosition = outdoorTravelModes.indexOfFirst { it.id == travelModeId }
        if (travelModePosition >= 0) {
            binding.spinnerOutdoorTravelMode.setSelection(travelModePosition, false)
        }
        savedIndoorCalibrationPoints = restoreIndoorCalibrationPoints()
        savedImageIndoorCalibrationPoints = restoreIndoorCalibrationPoints(currentImageIndoorCalibrationKey())
        binding.checkOutdoorSimulation.isChecked = prefs.getBoolean(KEY_OUTDOOR_SIMULATION, true)
    }

    private fun migrateDemoVenueId(venueId: String): String {
        return if (venueId == LEGACY_WUDAOKOU_VENUE_ID) {
            DEFAULT_WUDAOKOU_VENUE_ID
        } else {
            venueId
        }
    }

    private fun migrateDemoTargetPoiId(poiId: String): String {
        return if (poiId == LEGACY_WUDAOKOU_POI_ID) {
            DEFAULT_WUDAOKOU_POI_ID
        } else {
            poiId
        }
    }

    private fun migrateLegacyWudaokouEntryPoint() {
        val lat = binding.editOutdoorEntryLat.text.toString().trim().toDoubleOrNull() ?: return
        val lng = binding.editOutdoorEntryLng.text.toString().trim().toDoubleOrNull() ?: return
        if (!lat.sameCoordinateAs(LEGACY_WUDAOKOU_ENTRY_LAT) || !lng.sameCoordinateAs(LEGACY_WUDAOKOU_ENTRY_LNG)) {
            return
        }
        binding.editOutdoorEntryLat.setText(DEFAULT_WUDAOKOU_ENTRY_LAT.toString())
        binding.editOutdoorEntryLng.setText(DEFAULT_WUDAOKOU_ENTRY_LNG.toString())
        prefs.edit()
            .putString(KEY_OUTDOOR_ENTRY_LAT, DEFAULT_WUDAOKOU_ENTRY_LAT.toString())
            .putString(KEY_OUTDOOR_ENTRY_LNG, DEFAULT_WUDAOKOU_ENTRY_LNG.toString())
            .apply()
    }

    private fun Double.sameCoordinateAs(other: Double): Boolean {
        return kotlin.math.abs(this - other) < 0.000001
    }

    private fun persistSettings() {
        prefs.edit()
            .putString(KEY_OUTDOOR_START_LAT, binding.editOutdoorStartLat.text.toString().trim())
            .putString(KEY_OUTDOOR_START_LNG, binding.editOutdoorStartLng.text.toString().trim())
            .putString(KEY_OUTDOOR_ENTRY_LAT, binding.editOutdoorEntryLat.text.toString().trim())
            .putString(KEY_OUTDOOR_ENTRY_LNG, binding.editOutdoorEntryLng.text.toString().trim())
            .putString(KEY_OUTDOOR_SEARCH_KEYWORD, binding.editOutdoorSearchKeyword.text.toString().trim())
            .putString(KEY_OUTDOOR_SEARCH_CITY, binding.editOutdoorSearchCity.text.toString().trim())
            .putString(KEY_BASE_URL, binding.editBaseUrl.text.toString().trim())
            .putString(KEY_VENUE_ID, binding.editVenueId.text.toString().trim())
            .putString(KEY_FLOOR_ID, binding.editFloorId.text.toString().trim())
            .putString(KEY_POI_ID, binding.editTargetPoiId.text.toString().trim())
            .putString(KEY_DEBUG_TARGET, binding.editDebugTarget.text.toString().trim())
            .putString(KEY_PROVIDER_ID, viewModel.uiState.value.selectedProviderId)
            .putString(KEY_OUTDOOR_TRAVEL_MODE, readOutdoorTravelMode().id)
            .putBoolean(KEY_OUTDOOR_SIMULATION, binding.checkOutdoorSimulation.isChecked)
            .apply()
    }

    private fun restoreOutdoorPoint(
        latKey: String,
        lngKey: String,
        latEditText: EditText,
        lngEditText: EditText,
    ) {
        val lat = prefs.getString(latKey, null)?.toDoubleOrNull()
        val lng = prefs.getString(lngKey, null)?.toDoubleOrNull()
        if (lat == null || lng == null) {
            return
        }
        val point = OutdoorPoint(lat, lng)
        if (!point.isLikelyMainlandChina()) {
            return
        }
        latEditText.setText(lat.toString())
        lngEditText.setText(lng.toString())
    }

    private fun prepareOutdoorRoute() {
        val input = readOutdoorInput() ?: return
        val travelMode = readOutdoorTravelMode()
        if ((selectedOutdoorPoi != null || hasSelectedMapPoiPendingNavigation) &&
            input.start.distanceTo(input.end) > EMBEDDED_AMAP_LONG_ROUTE_FALLBACK_METERS
        ) {
            startNavigationAfterRouteReady = false
            lastCalculatedTravelMode = null
            viewModel.onAmapOutdoorRouteFailedWithSelection(
                "内嵌高德${travelMode.displayName}不适合跨城长距离路线（约${input.start.distanceTo(input.end).formatRouteDistance()}）",
            )
            Toast.makeText(this, "请使用高德 App 导航到入口", Toast.LENGTH_SHORT).show()
            return
        }
        lastCalculatedTravelMode = travelMode
        lastNavigationSimulation = binding.checkOutdoorSimulation.isChecked
        if (isEmbeddedAmapViewSkipped) {
            viewModel.prepareOutdoorRoute(
                venueId = binding.editVenueId.text.toString(),
                targetPoiId = binding.editTargetPoiId.text.toString(),
            )
            return
        }
        val navigator = outdoorNavigatorOrReport() ?: return
        viewModel.onAmapOutdoorRouteCalculating(travelMode.displayName, input.start.summary(), input.end.summary())
        navigator.calculateRoute(travelMode, input.start, input.end)
    }

    private fun startOutdoorNavigation() {
        collapseDebugPanel()
        hasSelectedMapPoiPendingNavigation = false
        if (isEmbeddedAmapViewSkipped) {
            viewModel.startOutdoorNavigation()
            binding.bottomSheet.postDelayed(
                {
                    if (viewModel.uiState.value.navState == NavState.OUTDOOR_NAVIGATING) {
                        viewModel.onAmapOutdoorArriveDestination()
                    }
                },
                480L,
            )
            return
        }
        val useSimulation = binding.checkOutdoorSimulation.isChecked
        if (useSimulation) {
            outdoorNavigatorOrReport()?.startNavigation(useSimulation = true)
        } else {
            withLocationPermission(LocationPermissionPurpose.START_OUTDOOR_NAVIGATION) {
                outdoorNavigatorOrReport()?.startNavigation(useSimulation = false)
            }
        }
    }

    private fun openExternalAmapNavigation(existingSession: ExternalNavigationSession? = null) {
        persistSettings()
        val session = existingSession ?: buildExternalNavigationSession() ?: return
        val result = externalNavigationLauncher.openNavigation(
            AmapExternalNavigationRequest(
                entranceName = session.entranceName,
                latitude = session.entrancePoint.latitude,
                longitude = session.entrancePoint.longitude,
                travelMode = session.travelMode,
            ),
        )
        if (result.success) {
            externalNavigationSession = session.apply {
                lastReturnCheckAtMs = 0L
            }
            pendingExternalReturnCheck = false
            collapseDebugPanel()
            viewModel.onAmapExternalNavigationStarted(
                entranceName = session.entranceName,
                entrancePoint = session.entrancePoint.summary(),
                mode = session.travelMode.displayName,
                launchedUri = result.launchedUri,
                installed = result.installed,
            )
        } else {
            externalNavigationSession = null
            pendingExternalReturnCheck = false
            viewModel.onAmapExternalNavigationFailed(
                entranceName = session.entranceName,
                entrancePoint = session.entrancePoint.summary(),
                mode = session.travelMode.displayName,
                installed = result.installed,
                reason = result.message,
            )
        }
    }

    private fun buildExternalNavigationSession(): ExternalNavigationSession? {
        val entrancePoint = readOutdoorEntryPoint() ?: return null
        val entranceName = listOf(
            binding.editOutdoorSearchKeyword.text.toString().trim(),
            binding.editVenueId.text.toString().trim(),
            binding.editTargetPoiId.text.toString().trim(),
        ).firstOrNull { it.isNotBlank() } ?: "场馆入口"
        return ExternalNavigationSession(
            entranceName = entranceName,
            entrancePoint = entrancePoint,
            travelMode = readOutdoorTravelMode(),
        )
    }

    private fun checkExternalNavigationReturn() {
        val session = externalNavigationSession ?: return
        val now = System.currentTimeMillis()
        if (now - session.lastReturnCheckAtMs < EXTERNAL_RETURN_CHECK_INTERVAL_MS) {
            return
        }
        session.lastReturnCheckAtMs = now
        pendingExternalReturnCheck = true
        viewModel.onAmapExternalNavigationReturnChecking(
            entranceName = session.entranceName,
            entrancePoint = session.entrancePoint.summary(),
        )
        withLocationPermission(LocationPermissionPurpose.EXTERNAL_RETURN_CHECK) {
            outdoorDiscovery.requestCurrentLocation()
        }
    }

    private fun handleExternalReturnLocation(currentPoint: OutdoorPoint) {
        val session = externalNavigationSession ?: return
        if (!pendingExternalReturnCheck) {
            return
        }
        pendingExternalReturnCheck = false
        val distanceMeters = currentPoint.distanceTo(session.entrancePoint)
        if (distanceMeters <= EXTERNAL_ENTRY_DISTANCE_THRESHOLD_METERS) {
            viewModel.onAmapExternalNavigationReturnNear(
                distanceMeters = distanceMeters,
                thresholdMeters = EXTERNAL_ENTRY_DISTANCE_THRESHOLD_METERS,
            )
        } else {
            viewModel.onAmapExternalNavigationReturnFar(
                distanceMeters = distanceMeters,
                thresholdMeters = EXTERNAL_ENTRY_DISTANCE_THRESHOLD_METERS,
            )
        }
    }

    private fun searchOutdoorPoi() {
        val keyword = binding.editOutdoorSearchKeyword.text.toString().trim()
        if (keyword.length < MIN_OUTDOOR_SEARCH_KEYWORD_LENGTH) {
            return
        }
        val indoorTargets = searchUnifiedIndoorTargets(keyword)
        val requestKeyword = selectedSearchType.decorateKeyword(amapSearchKeyword(keyword, indoorTargets))
        lastOutdoorSearchKeyword = keyword
        outdoorSearchUiState = SearchUiState.RESULTS_EXPANDED
        val around = readStartPointOrNull()
        viewModel.onAmapPoiSearchStarted(requestKeyword, "", around?.summary())
        val indoorItems = buildUnifiedIndoorPoiResults(indoorTargets)
        if (shouldUseDemoSearchFallback()) {
            val demoItems = if (indoorItems.isEmpty()) {
                buildDemoOutdoorPoiResults(keyword, selectedSearchType)
            } else {
                emptyList()
            }
            applyOutdoorPoiSearchResult(indoorItems + demoItems)
            viewModel.onAmapPoiSearchResult(outdoorPoiOptions.size, outdoorPoiOptions.firstOrNull()?.label())
            return
        }
        outdoorDiscovery.searchPoi(requestKeyword, around)
    }

    private fun sortOutdoorPoiOptions(items: List<OutdoorPoiOption>): List<OutdoorPoiOption> {
        val keyword = lastOutdoorSearchKeyword.orEmpty()
        val around = lastOutdoorLocationPoint()
        val searchIntent = inferOutdoorSearchIntent(keyword)
        return items.sortedWith(
            compareByDescending<OutdoorPoiOption> { poi ->
                poi.searchIntentScore(keyword, searchIntent)
            }.thenBy { poi ->
                when {
                    poi.indoorPoiId != null || isUnifiedIndoorPoi(poi) -> 0
                    keyword.isBlank() -> 1
                    poi.title.contains(keyword, ignoreCase = true) -> 1
                    poi.address.contains(keyword, ignoreCase = true) -> 2
                    else -> 2
                }
            }.thenBy { poi ->
                poi.distanceOverrideMeters ?: around?.distanceTo(poi.point()) ?: Float.MAX_VALUE
            },
        )
    }

    private fun filterOutdoorPoiOptions(items: List<OutdoorPoiOption>): List<OutdoorPoiOption> {
        if (items.isEmpty()) {
            return emptyList()
        }
        val indoorItems = items.filter { it.indoorPoiId != null || isUnifiedIndoorPoi(it) }
        val remainingItems = items.filterNot { it.indoorPoiId != null || isUnifiedIndoorPoi(it) }
        if (remainingItems.isEmpty()) {
            return indoorItems.take(MAX_DEMO_SEARCH_RESULTS)
        }
        val keyword = lastOutdoorSearchKeyword.orEmpty()
        val searchIntent = inferOutdoorSearchIntent(keyword)
        val scored = remainingItems.map { it to it.searchIntentScore(keyword, searchIntent) }
            .sortedWith(
                compareByDescending<Pair<OutdoorPoiOption, Int>> { it.second }
                    .thenBy { poiWithScore -> lastOutdoorLocationPoint()?.distanceTo(poiWithScore.first.point()) ?: Float.MAX_VALUE }
            )
        val highestScore = scored.maxOfOrNull { it.second } ?: 0
        val prioritized = if (highestScore <= 0) {
            scored.map { it.first }
        } else {
            scored.filter { it.second >= highestScore - 1 }.map { it.first }
        }
        if (indoorItems.isEmpty() && prioritized.size >= minOf(scored.size, MAX_DEMO_SEARCH_RESULTS)) {
            return prioritized.take(MAX_DEMO_SEARCH_RESULTS)
        }
        val remaining = scored.map { it.first }
            .filterNot { candidate -> prioritized.any { it.poiId == candidate.poiId } }
        return (indoorItems + prioritized + remaining)
            .distinctBy { it.poiId }
            .take(MAX_DEMO_SEARCH_RESULTS)
    }

    private fun mergeUnifiedIndoorPoiResults(
        keyword: String,
        outdoorItems: List<OutdoorPoiOption>,
    ): List<OutdoorPoiOption> {
        val indoorTargets = searchUnifiedIndoorTargets(keyword)
        if (indoorTargets.isEmpty()) {
            pendingUnifiedIndoorTargets = emptyMap()
            return outdoorItems
        }
        val matchedTargets = mutableMapOf<String, ImageIndoorPoiResolverItem>()
        val annotatedOutdoorItems = outdoorItems.map { poi ->
            val target = indoorTargetForOutdoorPoi(poi, indoorTargets)
            if (target == null) {
                poi
            } else {
                matchedTargets[outdoorPoiMatchKey(poi)] = target
                poi.withIndoorTarget(target)
            }
        }
        val syntheticItems = if (annotatedOutdoorItems.any { it.indoorPoiId != null }) {
            emptyList()
        } else {
            buildUnifiedIndoorPoiResults(indoorTargets)
        }
        pendingUnifiedIndoorTargets = matchedTargets +
            syntheticItems.mapNotNull { item ->
                unifiedIndoorTargetForSynthetic(item)?.let { target -> outdoorPoiMatchKey(item) to target }
            }.toMap()
        return (annotatedOutdoorItems + syntheticItems)
            .distinctBy { it.poiId.ifBlank { it.label() } }
    }

    private fun buildUnifiedIndoorPoiResults(keyword: String): List<OutdoorPoiOption> {
        return buildUnifiedIndoorPoiResults(searchUnifiedIndoorTargets(keyword))
    }

    private fun buildUnifiedIndoorPoiResults(targets: List<ImageIndoorPoiResolverItem>): List<OutdoorPoiOption> {
        if (indoorImageNavigation == null) {
            return emptyList()
        }
        val entrance = selectedIndoorImageNavEntrance()
            ?: indoorImageNavEntrances.firstOrNull { it.routeNodeId == DEFAULT_IMAGE_NAV_START_NODE_ID }
            ?: indoorImageNavEntrances.firstOrNull()
        val entryLabel = entrance?.shortLabel().orEmpty().ifBlank { "西门" }
        val items = targets.map { target ->
            val handoff = target.outdoorHandoff
            val entryPoint = handoff?.preferredEntranceGcj02?.let { OutdoorPoint(it.latitude, it.longitude) }
                ?: entrance?.let { calibratedIndoorEntrancePoint(it) }
                ?: OutdoorPoint(DEFAULT_WUDAOKOU_ENTRY_LAT, DEFAULT_WUDAOKOU_ENTRY_LNG)
            val venueName = handoff?.venueName.orEmpty()
                .ifBlank { target.venueName }
                .ifBlank { "五道口购物中心" }
            val venueAddress = handoff?.venueAddress.orEmpty()
                .ifBlank { target.venueAddress }
            OutdoorPoiOption(
                poiId = unifiedIndoorPoiId(target.poiId),
                title = target.displayName(),
                address = buildString {
                    append("$venueName · ${target.floorId} · ${target.indoorAvailabilityLabel()}")
                    if (venueAddress.isNotBlank()) {
                        append(" · $venueAddress")
                    }
                    append(" · 室外到达点：$entryLabel")
                },
                city = "北京市",
                latitude = entryPoint.latitude,
                longitude = entryPoint.longitude,
                visualType = OutdoorPoiVisualType.STORE,
                directionHint = OutdoorPoiDirectionHint.FORWARD,
                indoorPoiId = target.poiId,
                indoorFloorId = target.floorId,
                indoorLabel = target.displayName(),
            )
        }
        pendingUnifiedIndoorTargets = targets.associateBy { unifiedIndoorPoiId(it.poiId) }
        return items
    }

    private fun searchUnifiedIndoorTargets(keyword: String): List<ImageIndoorPoiResolverItem> {
        val targets = indoorImageNavigation?.searchPoi(keyword).orEmpty()
        if (!shouldUseUnifiedIndoorTargets(keyword, targets)) {
            return emptyList()
        }
        return targets.take(MAX_IMAGE_NAV_CANDIDATES)
    }

    private fun amapSearchKeyword(
        keyword: String,
        indoorTargets: List<ImageIndoorPoiResolverItem>,
    ): String {
        if (!shouldScopeAmapSearchToWudaokou(keyword)) {
            return keyword
        }
        val indoorName = indoorTargets.firstOrNull()
            ?.amapSearchName(keyword)
            ?.takeIf { it.isNotBlank() && it != keyword }
            ?: return keyword
        if (isWudaokouVenueText(indoorName.normalizeLooseSearchText())) {
            return indoorName
        }
        return "$indoorName 五道口购物中心"
    }

    private fun indoorTargetForOutdoorPoi(
        poi: OutdoorPoiOption,
        targets: List<ImageIndoorPoiResolverItem>,
    ): ImageIndoorPoiResolverItem? {
        val poiText = "${poi.title} ${poi.address} ${poi.city}".normalizeLooseSearchText()
        val venueMatched = isWudaokouShoppingCenterText(poiText)
        val targetByPoiText = targets.firstOrNull { target ->
            target.matchesLooseText(poiText)
        }
        if (targetByPoiText != null && venueMatched) {
            return targetByPoiText
        }
        return null
    }

    private fun OutdoorPoiOption.withIndoorTarget(target: ImageIndoorPoiResolverItem): OutdoorPoiOption {
        return copy(
            indoorPoiId = target.poiId,
            indoorFloorId = target.floorId,
            indoorLabel = target.displayName(),
        )
    }

    private fun unifiedIndoorTargetForSynthetic(poi: OutdoorPoiOption): ImageIndoorPoiResolverItem? {
        val indoorPoiId = poi.indoorPoiId ?: poi.poiId.removePrefix(INDOOR_UNIFIED_POI_PREFIX)
        return indoorImageNavigation?.resolverItem(indoorPoiId)
    }

    private fun calibratedIndoorEntrancePoint(entrance: ImageIndoorEntrance): OutdoorPoint {
        val savedPoint = savedImageIndoorCalibrationPoint(
            floorId = entrance.floorId,
            nodeId = entrance.routeNodeId,
        )
        return if (savedPoint == null) {
            OutdoorPoint(DEFAULT_WUDAOKOU_ENTRY_LAT, DEFAULT_WUDAOKOU_ENTRY_LNG)
        } else {
            OutdoorPoint(savedPoint.latitude, savedPoint.longitude)
        }
    }

    private fun savedImageIndoorCalibrationPoint(
        floorId: String,
        nodeId: String,
    ): SavedIndoorCalibrationPoint? {
        val savedPoints = savedImageIndoorCalibrationPointsForFloor(floorId)
        if (savedPoints.isNotEmpty()) {
            return savedPoints[nodeId]
        }
        return sharedImageIndoorCalibrationPoint(floorId, nodeId)
    }

    private fun savedImageIndoorCalibrationPointsForFloor(
        floorId: String,
    ): Map<String, SavedIndoorCalibrationPoint> {
        return if (floorId == indoorImageNavSelectedFloorId) {
            savedImageIndoorCalibrationPoints
        } else {
            restoreIndoorCalibrationPoints(imageIndoorCalibrationKey(floorId))
        }
    }

    private fun sharedImageIndoorCalibrationPoint(
        floorId: String,
        nodeId: String,
    ): SavedIndoorCalibrationPoint? {
        val graph = indoorImageNavigation?.graph ?: return null
        val node = graph.node(nodeId)?.takeIf { it.floorId == floorId } ?: return null
        val alignment = graph.sharedAmapAlignment?.takeIf { it.appliesToFloor(floorId) } ?: return null
        val gcj02 = alignment.project(node.x, node.y)
        return SavedIndoorCalibrationPoint(
            nodeId = node.nodeId,
            floorId = node.floorId,
            label = node.nodeType.ifBlank { node.nodeId },
            latitude = gcj02.latitude,
            longitude = gcj02.longitude,
        )
    }

    private fun unifiedIndoorTargetFor(poi: OutdoorPoiOption): ImageIndoorPoiResolverItem? {
        poi.indoorPoiId?.let { indoorPoiId ->
            indoorImageNavigation?.resolverItem(indoorPoiId)?.let { return it }
        }
        return pendingUnifiedIndoorTargets[outdoorPoiMatchKey(poi)]
    }

    private fun isUnsupportedWudaokouIndoorStore(poi: OutdoorPoiOption): Boolean {
        if (unifiedIndoorTargetFor(poi) != null) {
            return false
        }
        val titleText = poi.title.normalizeLooseSearchText()
        val fullText = "${poi.title} ${poi.address} ${poi.city}".normalizeLooseSearchText()
        if (!isWudaokouShoppingCenterText(fullText)) {
            return false
        }
        return !isWudaokouVenueSubjectTitle(titleText)
    }

    private fun isUnifiedIndoorPoi(poi: OutdoorPoiOption): Boolean {
        return poi.poiId.startsWith(INDOOR_UNIFIED_POI_PREFIX)
    }

    private fun unifiedIndoorPoiId(poiId: String): String {
        return "$INDOOR_UNIFIED_POI_PREFIX$poiId"
    }

    private fun outdoorPoiMatchKey(poi: OutdoorPoiOption): String {
        return poi.poiId.ifBlank { poi.label() }
    }

    private fun OutdoorPoiOption.indoorInfoLine(): String? {
        val floor = indoorFloorId ?: return null
        val label = indoorLabel.orEmpty().ifBlank { indoorPoiId.orEmpty() }
        return "已找到室内位置 · $floor · $label"
    }

    private fun inferOutdoorSearchIntent(keyword: String): OutdoorSearchIntent {
        val normalized = keyword.normalizeLooseSearchText()
        return when {
            hasAnyNormalizedTerm(normalized, OUTDOOR_ENTRANCE_INTENT_TERMS) -> OutdoorSearchIntent.ENTRANCE
            hasAnyNormalizedTerm(normalized, OUTDOOR_VENUE_INTENT_TERMS) &&
                searchUnifiedIndoorTargets(keyword).isEmpty() -> OutdoorSearchIntent.VENUE
            else -> OutdoorSearchIntent.SELECTED_TYPE
        }
    }

    private fun OutdoorPoiOption.searchIntentScore(
        keyword: String,
        intent: OutdoorSearchIntent,
    ): Int {
        val normalizedKeyword = keyword.normalizeLooseSearchText()
        val normalizedTitle = title.normalizeLooseSearchText()
        val normalizedAddress = address.normalizeLooseSearchText()
        var score = 0
        if (normalizedKeyword.isNotBlank()) {
            score += when {
                normalizedTitle == normalizedKeyword -> 1000
                normalizedTitle.contains(normalizedKeyword) -> 300
                normalizedAddress.contains(normalizedKeyword) -> 80
                else -> 0
            }
        }
        score += when (intent) {
            OutdoorSearchIntent.VENUE -> venueSubjectScore(normalizedTitle, normalizedAddress)
            OutdoorSearchIntent.ENTRANCE -> SearchType.MALL_ENTRANCE.relevanceScore(this)
            OutdoorSearchIntent.SELECTED_TYPE -> selectedSearchType.relevanceScore(this)
        }
        return score
    }

    private fun venueSubjectScore(
        normalizedTitle: String,
        normalizedAddress: String,
    ): Int {
        var score = 0
        if (hasAnyNormalizedTerm(normalizedTitle, OUTDOOR_VENUE_INTENT_TERMS)) {
            score += 120
        }
        if (hasAnyNormalizedTerm(normalizedAddress, OUTDOOR_VENUE_INTENT_TERMS)) {
            score += 30
        }
        if (hasAnyNormalizedTerm(normalizedTitle, OUTDOOR_STORE_IN_VENUE_PENALTY_TERMS)) {
            score -= 90
        }
        return score
    }

    private fun hasAnyNormalizedTerm(text: String, terms: List<String>): Boolean {
        return terms
            .map { it.normalizeLooseSearchText() }
            .any { it.isNotBlank() && text.contains(it) }
    }

    private fun shouldUseUnifiedIndoorTargets(
        keyword: String,
        targets: List<ImageIndoorPoiResolverItem>,
    ): Boolean {
        if (targets.isEmpty()) {
            return false
        }
        val normalized = keyword.normalizeLooseSearchText()
        return isWudaokouVenueText(normalized) ||
            isWudaokouIndoorSearchContext() ||
            targets.any { it.matchesNameOrAliasKeyword(normalized) }
    }

    private fun shouldScopeAmapSearchToWudaokou(keyword: String): Boolean {
        val normalized = keyword.normalizeLooseSearchText()
        return isWudaokouVenueText(normalized) || isWudaokouIndoorSearchContext()
    }

    private fun isWudaokouIndoorSearchContext(): Boolean {
        val state = viewModel.uiState.value
        return state.navState in indoorStates &&
            state.indoorMode == IndoorNavigationMode.MANUAL_DEMO &&
            binding.editVenueId.text.toString().trim().ifBlank { DEFAULT_WUDAOKOU_VENUE_ID } == DEFAULT_WUDAOKOU_VENUE_ID
    }

    private fun ImageIndoorPoiResolverItem.amapSearchName(keyword: String): String {
        val normalizedKeyword = keyword.normalizeLooseSearchText()
        val candidates = listOf(name, displayName).plus(aliases)
            .filter { it.isNotBlank() }
        val matchingChineseName = candidates
            .filter { candidate ->
                val normalized = candidate.normalizeLooseSearchText()
                candidate.any { it.code > 127 } &&
                    normalized !in INDOOR_GENERIC_CATEGORY_TERMS_NORMALIZED &&
                    (
                        normalized.contains(normalizedKeyword) ||
                            normalizedKeyword.contains(normalized)
                        )
            }
            .maxByOrNull { it.normalizeLooseSearchText().length }
        return matchingChineseName ?: displayName()
    }

    private fun ImageIndoorPoiResolverItem.matchesLooseText(text: String): Boolean {
        return listOf(name, displayName, venueName, venueAddress, subtitle).plus(aliases)
            .map { it.normalizeLooseSearchText() }
            .filter { it.isNotBlank() }
            .any { normalized ->
                text.contains(normalized) || normalized.contains(text)
            }
    }

    private fun ImageIndoorPoiResolverItem.matchesNameOrAliasKeyword(normalizedKeyword: String): Boolean {
        if (normalizedKeyword.isBlank()) {
            return false
        }
        return listOf(name, displayName).plus(aliases)
            .map { it.normalizeLooseSearchText() }
            .filter { it.isNotBlank() }
            .any { normalized ->
                normalized.contains(normalizedKeyword) || normalizedKeyword.contains(normalized)
            }
    }

    private fun isWudaokouVenueText(text: String): Boolean {
        return listOf("五道口购物中心", "五道口", "wudaokou")
            .map { it.normalizeLooseSearchText() }
            .any { text.contains(it) }
    }

    private fun isWudaokouShoppingCenterText(text: String): Boolean {
        return listOf("五道口购物中心", "五道口购物", "wudaokoushopping")
            .map { it.normalizeLooseSearchText() }
            .any { text.contains(it) }
    }

    private fun isWudaokouVenueSubjectTitle(text: String): Boolean {
        return text == "五道口购物中心".normalizeLooseSearchText() ||
            text == "wudaokoushoppingcenter"
    }

    private fun String.normalizeLooseSearchText(): String {
        return lowercase().filter { it.isLetterOrDigit() || it.code > 127 }
    }

    private fun ImageIndoorPoiResolverItem.displayName(): String {
        return this.displayName.takeIf { it.isNotBlank() }
            ?: aliases.firstOrNull { alias -> alias.any { it.code > 127 } }
            ?: name
    }

    private fun ImageIndoorPoiResolverItem.indoorAvailabilityLabel(): String {
        return if (isIndoorOnly()) "仅室内点位" else "已关联高德"
    }

    private fun ImageIndoorPoiResolverItem.indoorResolverSubtitle(): String {
        val venue = outdoorHandoff?.venueName.orEmpty()
            .ifBlank { venueName }
            .ifBlank { "五道口购物中心" }
        val address = outdoorHandoff?.venueAddress.orEmpty()
            .ifBlank { venueAddress }
        val badgeText = badges
            .filter { it.isNotBlank() }
            .joinToString(separator = " / ")
        return listOf(
            venue,
            address,
            badgeText,
        ).filter { it.isNotBlank() }
            .joinToString(separator = " · ")
    }

    private fun ImageIndoorPoiResolverItem.isIndoorOnly(): Boolean {
        return badges.any { it.equals("indoor-only", ignoreCase = true) } ||
            externalRefs?.amapSearchable == false
    }

    private fun ImageIndoorEntrance.shortLabel(): String {
        return when (entranceType) {
            "west_gate" -> "F1 西门"
            "north_gate" -> "F1 北门"
            "east_gate" -> "F1 东门"
            else -> "$floorId $entranceType"
        }
    }

    private fun lastOutdoorLocationPoint(): OutdoorPoint? {
        return readStartPointOrNull()
    }

    private fun clearOutdoorSearchInput() {
        suppressOutdoorSearchTextWatcher = true
        binding.editOutdoorSearchKeyword.setText("")
        suppressOutdoorSearchTextWatcher = false
        rawOutdoorPoiOptions = emptyList()
        clearSelectedOutdoorPoiForSearch()
        outdoorSearchUiState = SearchUiState.IDLE_HOME
        collapseOutdoorPoiResults()
        persistSettings()
        renderScreen(viewModel.uiState.value)
    }

    private fun cancelOutdoorSearch() {
        if (outdoorSearchUiState == SearchUiState.DESTINATION_SELECTED) {
            clearSelectedOutdoorPoiForSearch()
        }
        binding.editOutdoorSearchKeyword.clearFocus()
        outdoorSearchUiState = if (binding.editOutdoorSearchKeyword.text.toString().trim().isBlank()) {
            SearchUiState.IDLE_HOME
        } else {
            SearchUiState.EDITING
        }
        collapseOutdoorPoiResults()
        renderScreen(viewModel.uiState.value)
    }

    private fun clearSelectedOutdoorPoiForSearch() {
        selectedOutdoorPoi = null
        hasSelectedMapPoiPendingNavigation = false
        startNavigationAfterRouteReady = false
        lastCalculatedTravelMode = null
        pendingUnifiedIndoorTargets = emptyMap()
    }

    private fun collapseOutdoorPoiResults() {
        outdoorSearchJob?.cancel()
        rawOutdoorPoiOptions = emptyList()
        outdoorPoiOptions = emptyList()
        renderOutdoorPoiResults(emptyList())
        binding.layoutBottomPoiResult.visibility = View.GONE
    }

    private fun requestCurrentOutdoorLocation() {
        shouldCenterOutdoorMapAfterCurrentLocation = true
        withLocationPermission(LocationPermissionPurpose.CURRENT_LOCATION) {
            viewModel.onAmapCurrentLocationRequesting()
            outdoorDiscovery.requestCurrentLocation()
        }
    }

    private fun requestInitialOutdoorLocationIfNeeded() {
        if (hasRequestedInitialOutdoorLocation) {
            return
        }
        hasRequestedInitialOutdoorLocation = true
        shouldCenterOutdoorMapAfterCurrentLocation = true
        withLocationPermission(
            purpose = LocationPermissionPurpose.INITIAL_LOCATION,
            afterHandled = ::requestGlassesAutoConnect,
        ) {
            viewModel.onAmapCurrentLocationRequesting()
            outdoorDiscovery.requestCurrentLocation()
        }
    }

    private fun requestInitialOutdoorLocationForExternalIntent() {
        if (hasRequestedInitialOutdoorLocation) {
            return
        }
        hasRequestedInitialOutdoorLocation = true
        shouldCenterOutdoorMapAfterCurrentLocation = false
        withLocationPermission(
            purpose = LocationPermissionPurpose.INITIAL_LOCATION,
            afterHandled = ::requestGlassesAutoConnect,
        ) {
            viewModel.onAmapCurrentLocationRequesting()
            outdoorDiscovery.requestCurrentLocation()
        }
    }

    private fun centerOutdoorMapOnCurrentLocation(point: OutdoorPoint) {
        if (::outdoorNavigator.isInitialized) {
            outdoorNavigator.centerOnCurrentLocation(point)
        } else {
            pendingOutdoorMapCenterPoint = point
        }
    }

    private fun applySelectedPoiToEntry(): Boolean {
        val poi = selectedOutdoorPoiForSpinnerPosition(binding.spinnerOutdoorPoi.selectedItemPosition)
        if (poi == null) {
            viewModel.onAmapOutdoorError("高德地点选择被拦截：未选择地点")
            return false
        }
        applyOutdoorPoiToEntry(
            poi = poi,
            sourceLabel = "搜索结果选择",
            previewOnMap = true,
            updateSearchText = false,
            collapseResults = true,
        )
        return true
    }

    private fun selectMapPoiAsEntry(poi: OutdoorPoiOption) {
        suppressOutdoorPoiSelectionCallback = true
        outdoorPoiOptions = listOf(poi)
        try {
            outdoorPoiAdapter.clear()
            outdoorPoiAdapter.add(poi.label())
            outdoorPoiAdapter.notifyDataSetChanged()
            binding.spinnerOutdoorPoi.setSelection(0, false)
        } finally {
            suppressOutdoorPoiSelectionCallback = false
        }
        applyOutdoorPoiToEntry(
            poi = poi,
            sourceLabel = "地图点选",
            previewOnMap = false,
            updateSearchText = true,
            collapseResults = true,
        )
    }

    private fun applyOutdoorPoiToEntry(
        poi: OutdoorPoiOption,
        sourceLabel: String,
        previewOnMap: Boolean,
        updateSearchText: Boolean = false,
        collapseResults: Boolean = true,
    ) {
        val indoorTarget = unifiedIndoorTargetFor(poi)
        if (updateSearchText) {
            suppressOutdoorSearchTextWatcher = true
            binding.editOutdoorSearchKeyword.setText(poi.title)
            suppressOutdoorSearchTextWatcher = false
        }
        rememberRecentSearch(poi.title)
        binding.editOutdoorEntryLat.setText(poi.latitude.toString())
        binding.editOutdoorEntryLng.setText(poi.longitude.toString())
        selectedOutdoorPoi = poi
        hasSelectedMapPoiPendingNavigation = true
        lastCalculatedTravelMode = null
        startNavigationAfterRouteReady = false
        if (indoorTarget == null) {
            clearIndoorImageNavRoute()
        } else {
            planIndoorImageNavRoute(indoorTarget)
        }
        persistSettings()
        if (previewOnMap) {
            previewOutdoorPoiOnMap(poi)
        }
        if (collapseResults) {
            binding.editOutdoorSearchKeyword.clearFocus()
            collapseOutdoorPoiResults()
        }
        outdoorSearchUiState = SearchUiState.DESTINATION_SELECTED
        viewModel.onAmapPoiSelected("$sourceLabel：${poi.label()}，已定位到地图，可继续选择或点击开始导航")
        renderScreen(viewModel.uiState.value)
    }

    private fun clearIndoorImageNavRoute() {
        stopConferenceWalkDemo(updateSummary = false)
        indoorImageNavPlan = null
        indoorImageNavCandidates = emptyList()
        binding.viewIndoorImageNavigation.clear()
        binding.textIndoorImageNavSummary.text = "当前地点暂不支持室内导航"
        binding.textIndoorMainNavSummary.text = "当前地点暂不支持室内导航"
    }

    private fun previewOutdoorPoiOnMap(poi: OutdoorPoiOption) {
        if (::outdoorNavigator.isInitialized) {
            outdoorNavigator.previewPoiSelection(poi)
        } else {
            pendingOutdoorMapCenterPoint = poi.point()
            viewModel.onAmapOutdoorNaviEvent("高德地图尚未初始化，已回填地点但未移动地图")
        }
    }

    private fun selectedOutdoorPoiForSpinnerPosition(position: Int): OutdoorPoiOption? {
        if (outdoorPoiOptions.isEmpty()) {
            return null
        }
        val hasPromptItem = outdoorPoiAdapter.count > outdoorPoiOptions.size
        val index = if (hasPromptItem) position - 1 else position
        return outdoorPoiOptions.getOrNull(index)
    }

    private fun startLocate() {
        val input = readLocateInput()
        if (CONFERENCE_INDOOR_ONLY_MODE) {
            viewModel.selectProvider(CONFERENCE_PROVIDER_ID)
            syncProviderSpinner(CONFERENCE_PROVIDER_ID)
            viewModel.captureAndLocate(
                baseUrl = input.baseUrl,
                venueId = input.venueId,
                candidateFloorId = input.floorId.ifBlank { null },
                targetPoiId = null,
                debugTarget = input.debugTarget.ifBlank { null },
                allowProviderFallback = false,
            )
            return
        }
        if (viewModel.uiState.value.selectedProviderId == MainViewModel.PHONE_PROVIDER_ID) {
            pendingPhoneLocateInput = input
            viewModel.beginPhoneCameraCapture(input.floorId.ifBlank { null })
            try {
                phoneCameraLauncher.launch(null)
            } catch (throwable: Throwable) {
                pendingPhoneLocateInput = null
                viewModel.onPhoneCaptureFailed("相机启动失败：${throwable.message}")
            }
            return
        }
        viewModel.captureAndLocate(
            baseUrl = input.baseUrl,
            venueId = input.venueId,
            candidateFloorId = input.floorId.ifBlank { null },
            targetPoiId = input.targetPoiId.ifBlank { null },
            debugTarget = input.debugTarget.ifBlank { null },
        )
    }

    private fun readLocateInput(): LocateInput {
        return LocateInput(
            baseUrl = binding.editBaseUrl.text.toString().trim(),
            venueId = binding.editVenueId.text.toString().trim(),
            floorId = binding.editFloorId.text.toString().trim(),
            targetPoiId = binding.editTargetPoiId.text.toString().trim(),
            debugTarget = binding.editDebugTarget.text.toString().trim(),
        )
    }

    private fun readOutdoorInput(): OutdoorInput? {
        val startLat = parseCoordinate(binding.editOutdoorStartLat.text.toString(), "室外起点纬度", -90.0, 90.0) ?: return null
        val startLng = parseCoordinate(binding.editOutdoorStartLng.text.toString(), "室外起点经度", -180.0, 180.0) ?: return null
        val endLat = parseCoordinate(binding.editOutdoorEntryLat.text.toString(), "场馆入口纬度", -90.0, 90.0) ?: return null
        val endLng = parseCoordinate(binding.editOutdoorEntryLng.text.toString(), "场馆入口经度", -180.0, 180.0) ?: return null
        val start = OutdoorPoint(startLat, startLng)
        val end = OutdoorPoint(endLat, endLng)
        if (!start.isLikelyMainlandChina() || !end.isLikelyMainlandChina()) {
            viewModel.onAmapOutdoorError("室外坐标无效：高德 Demo 仅支持中国大陆 GCJ-02 坐标，请勿使用模拟器默认海外定位")
            return null
        }
        if (start == end) {
            viewModel.onAmapOutdoorError("室外坐标无效：起点和场馆入口不能相同")
            return null
        }
        return OutdoorInput(start, end)
    }

    private fun readOutdoorEntryPoint(): OutdoorPoint? {
        val entryLat = parseCoordinate(binding.editOutdoorEntryLat.text.toString(), "场馆入口纬度", -90.0, 90.0) ?: return null
        val entryLng = parseCoordinate(binding.editOutdoorEntryLng.text.toString(), "场馆入口经度", -180.0, 180.0) ?: return null
        val entrance = OutdoorPoint(entryLat, entryLng)
        if (!entrance.isLikelyMainlandChina()) {
            viewModel.onAmapOutdoorError("室外坐标无效：外部高德导航仅支持中国大陆 GCJ-02 场馆入口坐标")
            return null
        }
        return entrance
    }

    private fun readStartPointOrNull(): OutdoorPoint? {
        val lat = binding.editOutdoorStartLat.text.toString().trim().toDoubleOrNull()
        val lng = binding.editOutdoorStartLng.text.toString().trim().toDoubleOrNull()
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            return null
        }
        return OutdoorPoint(lat, lng).takeIf { it.isLikelyMainlandChina() }
    }

    private fun readOutdoorTravelMode(): OutdoorTravelMode {
        return outdoorTravelModes.getOrElse(binding.spinnerOutdoorTravelMode.selectedItemPosition) {
            OutdoorTravelMode.RIDE
        }
    }

    private fun parseCoordinate(value: String, label: String, min: Double, max: Double): Double? {
        val parsed = value.trim().toDoubleOrNull()
        if (parsed == null || parsed < min || parsed > max) {
            viewModel.onAmapOutdoorError("室外坐标无效：$label=$value")
            return null
        }
        return parsed
    }

    private fun withLocationPermission(
        purpose: LocationPermissionPurpose = LocationPermissionPurpose.INTERACTIVE,
        afterHandled: (() -> Unit)? = null,
        action: () -> Unit,
    ) {
        if (hasLocationPermission()) {
            action()
            afterHandled?.invoke()
            return
        }
        pendingLocationPermissionAction = action
        pendingLocationPermissionPurpose = purpose
        pendingLocationPermissionAfterHandled = afterHandled
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleLocationPermissionDenied(purpose: LocationPermissionPurpose) {
        shouldCenterOutdoorMapAfterCurrentLocation = false
        val summary = "未授予定位权限；可重新授权后继续室外导航，或使用高德地图导航到入口/手动进入场馆"
        when (purpose) {
            LocationPermissionPurpose.EXTERNAL_RETURN_CHECK -> {
                pendingExternalReturnCheck = false
                viewModel.onAmapExternalNavigationReturnLocationFailed("外部高德导航返回检查失败：未授予定位权限")
            }
            else -> {
                viewModel.onAmapLocationPermissionDenied(summary)
            }
        }
    }

    private fun syncProviderSpinner(providerId: String) {
        val position = viewModel.providerIds.indexOf(providerId)
        if (position >= 0 && binding.spinnerProvider.selectedItemPosition != position) {
            binding.spinnerProvider.setSelection(position, false)
        }
    }

    private fun bindOptionalClick(idName: String, action: () -> Unit) {
        findOptionalView<View>(idName)?.setOnClickListener { action() }
    }

    private fun setOptionalText(idName: String, value: String) {
        findOptionalView<TextView>(idName)?.text = value
        if (findOptionalView<TextView>(idName) == null) {
            findOptionalView<Button>(idName)?.text = value
        }
    }

    private fun setOptionalVisibility(idName: String, visible: Boolean) {
        findOptionalView<View>(idName)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private inline fun <reified T : View> findOptionalView(idName: String): T? {
        val viewId = resources.getIdentifier(idName, "id", packageName)
        if (viewId == 0) {
            return null
        }
        return findViewById(viewId) as? T
    }

    private fun Bitmap.toCapturedFrame(candidateFloorId: String): CapturedFrame {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 85, output)
        return CapturedFrame(
            providerId = MainViewModel.PHONE_PROVIDER_ID,
            bytes = output.toByteArray(),
            fileName = "phone_camera_${System.currentTimeMillis()}.jpg",
            candidateFloorId = candidateFloorId.ifBlank { null },
            width = width,
            height = height,
        )
    }

    private fun NavState.phaseLabel(): String {
        return when (this) {
            NavState.OUTDOOR_IDLE,
            NavState.OUTDOOR_READY,
            NavState.OUTDOOR_ROUTE_READY,
            NavState.OUTDOOR_NAVIGATING -> "室外"
            NavState.ENTRY_HANDOFF_PENDING -> "交接"
            NavState.INDOOR_READY,
            NavState.INDOOR_CAPTURING,
            NavState.INDOOR_LOCATING,
            NavState.INDOOR_LOW_CONFIDENCE,
            NavState.INDOOR_ROUTING,
            NavState.INDOOR_ROUTE_READY -> "室内"
            NavState.ERROR -> "错误"
            NavState.ABORTED -> "已中止"
        }
    }

    private fun NavState.displayHint(): String {
        return when (this) {
            NavState.OUTDOOR_IDLE -> "等待室外算路或直接联调操作"
            NavState.OUTDOOR_READY -> "室外壳子已就绪"
            NavState.OUTDOOR_ROUTE_READY -> "高德室外路线已就绪"
            NavState.OUTDOOR_NAVIGATING -> "高德 GPS 导航运行中"
            NavState.ENTRY_HANDOFF_PENDING -> "手动进入场馆交接中"
            NavState.INDOOR_READY -> "室内上下文已就绪，可采图或请求路径"
            NavState.INDOOR_CAPTURING -> "正在从选定图像来源采图"
            NavState.INDOOR_LOCATING -> "正在上传图像并等待定位"
            NavState.INDOOR_LOW_CONFIDENCE -> "定位置信度较低，暂停强导航提示"
            NavState.INDOOR_ROUTING -> "正在请求室内路径"
            NavState.INDOOR_ROUTE_READY -> "室内路径结果已就绪"
            NavState.ERROR -> "请查看错误面板和最新日志"
            NavState.ABORTED -> "演示已手动中止或因严重错误中止"
        }
    }

    private fun ConnectionState.displayLabel(): String {
        return when (this) {
            ConnectionState.DISCONNECTED -> "未连接"
            ConnectionState.CONNECTING -> "连接中"
            ConnectionState.CONNECTED -> "已连接"
            ConnectionState.FAILED -> "失败"
        }
    }

    private data class LocateInput(
        val baseUrl: String,
        val venueId: String,
        val floorId: String,
        val targetPoiId: String,
        val debugTarget: String,
    )

    private data class ExhibitionVoiceTarget(
        val poiId: String,
        val debugTarget: String,
        val label: String,
        val floorId: String = "F1",
    )

    private data class ExhibitionFallbackRoute(
        val current: IndoorPreviewPoint,
        val target: IndoorPreviewPoint,
        val route: List<IndoorPreviewPoint>,
        val floorLabel: String,
        val targetLabel: String,
    )

    private data class IndoorMapPosition(
        val floorId: String,
        val x: Double,
        val y: Double,
    )

    private data class ConferenceWalkDemoFrame(
        val position: IndoorMapPosition,
    )

    private data class ConferenceRouteProjection(
        val edgeIndex: Int,
        val distanceToEdgeEnd: Double,
        val score: Double,
    )

    private data class ConferenceRouteVector(
        val deltaX: Double,
        val deltaY: Double,
    )

    private data class ConferenceHudNextAction(
        val directionArrow: String,
        val nextAction: String,
        val distanceToNextActionMeters: Double?,
    )

    private data class ConferenceHudRouteProgress(
        val directionArrow: String,
        val nextAction: String,
        val distanceToNextActionMeters: Double?,
        val remainingDistanceMeters: Double?,
    )

    private data class OutdoorInput(
        val start: OutdoorPoint,
        val end: OutdoorPoint,
    )

    private data class ExternalNavigationSession(
        val entranceName: String,
        val entrancePoint: OutdoorPoint,
        val travelMode: OutdoorTravelMode,
        var lastReturnCheckAtMs: Long = 0L,
    )

    private data class SavedIndoorCalibrationPoint(
        val nodeId: String,
        val floorId: String,
        val label: String,
        val latitude: Double,
        val longitude: Double,
    )

    private data class IndoorCalibrationSourcePoint(
        val nodeId: String,
        val label: String,
        val floorId: String,
        val x: Double,
        val y: Double,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    private data class IndoorPreviewTarget(
        val label: String,
        val floorId: String,
        val point: IndoorPreviewPoint,
    )

    private data class ScreenRenderModel(
        val phaseTitle: String,
        val statusHeadline: String,
        val statusSummary: String,
        val alertSummary: String,
        val showRoutePreparation: Boolean,
        val showStartNavigation: Boolean,
        val showExternalNavigation: Boolean,
        val showContinueNavigation: Boolean,
        val showEnterVenue: Boolean,
        val showExitIndoor: Boolean,
        val showIndoorActions: Boolean,
        val showManualIndoorControls: Boolean,
        val showRecenter: Boolean,
        val showOrientationToggle: Boolean,
        val showOverview: Boolean,
        val showExitNavigation: Boolean,
        val showOutdoorProgress: Boolean,
        val showDebugPanel: Boolean,
        val debugToggleLabel: String,
    )

    private data class TopStatusMeta(
        val phaseLabel: String,
        val secondaryLabel: String?,
        val detail: String,
    )

    private data class DemoPoiSeed(
        val poiId: String,
        val title: String,
        val address: String,
        val city: String,
        val latitudeOffset: Double,
        val longitudeOffset: Double,
        val distanceMeters: Float,
        val keywords: List<String>,
        val visualType: OutdoorPoiVisualType = OutdoorPoiVisualType.STORE,
        val directionHint: OutdoorPoiDirectionHint = OutdoorPoiDirectionHint.RIGHT,
    )

    private enum class SearchUiState {
        IDLE_HOME,
        EDITING,
        RESULTS_EXPANDED,
        DESTINATION_SELECTED,
    }

    private enum class LocationPermissionPurpose {
        INITIAL_LOCATION,
        CURRENT_LOCATION,
        START_OUTDOOR_NAVIGATION,
        EXTERNAL_RETURN_CHECK,
        INTERACTIVE,
    }

    private enum class OutdoorSearchIntent {
        SELECTED_TYPE,
        VENUE,
        ENTRANCE,
    }

    private enum class SearchType(
        val labelResId: Int,
        val hintResId: Int,
        val helperResId: Int,
        private val keywordSuffix: String,
        private val relevanceHints: List<String>,
        private val badgeColor: Int,
        private val badgeIconResId: Int,
    ) {
        STORE(
            labelResId = R.string.label_search_type_store,
            hintResId = R.string.hint_outdoor_search_keyword_store,
            helperResId = R.string.text_search_helper_store,
            keywordSuffix = "",
            relevanceHints = listOf("店", "门店", "商铺", "品牌", "专卖", "馆"),
            badgeColor = 0xFFF28B39.toInt(),
            badgeIconResId = R.drawable.ic_search_badge_store_18,
        ),
        MALL_ENTRANCE(
            labelResId = R.string.label_search_type_mall_entrance,
            hintResId = R.string.hint_outdoor_search_keyword_mall_entrance,
            helperResId = R.string.text_search_helper_mall_entrance,
            keywordSuffix = "入口",
            relevanceHints = listOf("入口", "出入口", "东门", "西门", "南门", "北门", "门"),
            badgeColor = 0xFF178FA0.toInt(),
            badgeIconResId = R.drawable.ic_search_badge_entrance_18,
        ),
        OFFICE(
            labelResId = R.string.label_search_type_office,
            hintResId = R.string.hint_outdoor_search_keyword_office,
            helperResId = R.string.text_search_helper_office,
            keywordSuffix = "写字楼",
            relevanceHints = listOf("写字楼", "大厦", "中心", "商务", "办公", "园区", "广场"),
            badgeColor = 0xFF6C7A89.toInt(),
            badgeIconResId = R.drawable.ic_search_badge_office_18,
        ),
        RESIDENTIAL(
            labelResId = R.string.label_search_type_residential,
            hintResId = R.string.hint_outdoor_search_keyword_residential,
            helperResId = R.string.text_search_helper_residential,
            keywordSuffix = "小区",
            relevanceHints = listOf("小区", "社区", "公寓", "花园", "家园", "门岗", "门"),
            badgeColor = 0xFF5B8C6A.toInt(),
            badgeIconResId = R.drawable.ic_search_badge_home_18,
        ),
        ;

        fun decorateKeyword(keyword: String): String {
            return if (keywordSuffix.isBlank()) {
                keyword
            } else {
                "$keyword $keywordSuffix"
            }
        }

        fun relevanceScore(poi: OutdoorPoiOption): Int {
            val haystack = listOf(poi.title, poi.address, poi.city).joinToString(separator = " ")
            return relevanceHints.fold(0) { score, hint ->
                score + if (haystack.contains(hint, ignoreCase = true)) 2 else 0
            } + if (keywordSuffix.isNotBlank() && poi.title.contains(keywordSuffix, ignoreCase = true)) {
                3
            } else {
                0
            }
        }

        fun badgeColor(): Int = badgeColor

        fun badgeIconResId(): Int = badgeIconResId
    }

    private enum class DockAction {
        NONE,
        FOCUS_SEARCH,
        USE_CURRENT_LOCATION,
        PREPARE_OUTDOOR,
        START_OUTDOOR,
        CONTINUE_NAVIGATION,
        OPEN_EXTERNAL_AMAP,
        OVERVIEW,
        EXIT_OUTDOOR,
        ENTER_VENUE,
        INDOOR_BACK,
        INDOOR_CONTINUE,
        INDOOR_MORE,
        RETRY_CAPTURE,
        CAPTURE_AND_LOCATE,
        REQUEST_INDOOR_ROUTE,
        COMPLETE_ARRIVAL,
        EXIT_INDOOR,
        TOGGLE_DEBUG,
    }

    private data class DockButtonModel(
        val label: String,
        val action: DockAction,
    )

    private data class DockModel(
        val title: String,
        val subtitle: String,
        val primary: DockButtonModel,
        val leading: DockButtonModel? = null,
        val trailing: DockButtonModel? = null,
    ) {
        companion object {
            fun empty(): DockModel {
                return DockModel(
                    title = "",
                    subtitle = "",
                    primary = DockButtonModel("", DockAction.NONE),
                )
            }
        }
    }

    private fun OutdoorPoint.summary(): String = "$latitude,$longitude"

    private fun OutdoorPoint.distanceTo(other: OutdoorPoint): Float {
        val results = FloatArray(1)
        Location.distanceBetween(latitude, longitude, other.latitude, other.longitude, results)
        return results[0]
    }

    private fun Float.formatRouteDistance(): String {
        return if (this >= 1000f) {
            String.format(java.util.Locale.US, "%.1fkm", this / 1000f)
        } else {
            "${this.toInt()}m"
        }
    }

    private fun OutdoorPoiOption.addressLine(): String {
        return address.trim().ifBlank { city.trim() }.ifBlank { "$latitude,$longitude" }
    }

    private fun ImageIndoorEntrance.displayLabel(): String {
        val gate = when (entranceType) {
            "west_gate" -> "F1 西门"
            "north_gate" -> "F1 北门"
            "east_gate" -> "F1 东门"
            else -> "$floorId $entranceType"
        }
        return "$gate · $routeNodeId"
    }

    private fun OutdoorPoiOption.distanceLabel(from: OutdoorPoint?): String {
        val distanceMeters = distanceOverrideMeters ?: from?.distanceTo(point()) ?: return "-"
        return if (distanceMeters < 1000f) {
            "${distanceMeters.toInt()}米"
        } else {
            "${String.format(java.util.Locale.US, "%.1f", distanceMeters / 1000f)}km"
        }
    }

    private fun List<ImageIndoorNavNode>.dedupeConsecutiveByNodeId(): List<ImageIndoorNavNode> {
        return fold(mutableListOf()) { result, node ->
            if (result.lastOrNull()?.nodeId != node.nodeId) {
                result.add(node)
            }
            result
        }
    }

    private fun shouldUseDemoSearchFallback(): Boolean {
        return isEmbeddedAmapViewSkipped
    }

    private fun buildDemoOutdoorPoiResults(
        keyword: String,
        type: SearchType,
    ): List<OutdoorPoiOption> {
        val normalizedKeyword = keyword.trim().lowercase()
        val seeds = when (type) {
            SearchType.STORE -> {
                if (normalizedKeyword.contains("tianjie") || keyword.contains("天街")) {
                    demoStoreTianjieSeeds
                } else {
                    demoStoreSeeds
                }
            }
            SearchType.MALL_ENTRANCE -> demoEntranceSeeds
            SearchType.OFFICE -> demoOfficeSeeds
            SearchType.RESIDENTIAL -> demoResidentialSeeds
        }
        val filteredSeeds = seeds.filter { seed ->
            normalizedKeyword.isBlank() || seed.keywords.any { it.contains(normalizedKeyword) || normalizedKeyword.contains(it) }
        }
        val resolvedSeeds = when {
            filteredSeeds.isEmpty() -> seeds
            filteredSeeds.size >= MAX_DEMO_SEARCH_RESULTS -> filteredSeeds
            else -> filteredSeeds + seeds.filterNot { seed ->
                filteredSeeds.any { it.poiId == seed.poiId }
            }
        }
        return resolvedSeeds.take(MAX_DEMO_SEARCH_RESULTS).map { seed ->
            OutdoorPoiOption(
                poiId = seed.poiId,
                title = seed.title,
                address = seed.address,
                city = seed.city,
                latitude = DEFAULT_WUDAOKOU_ENTRY_LAT + seed.latitudeOffset,
                longitude = DEFAULT_WUDAOKOU_ENTRY_LNG + seed.longitudeOffset,
                distanceOverrideMeters = seed.distanceMeters,
                visualType = seed.visualType,
                directionHint = seed.directionHint,
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun formatAnnotationElapsed(elapsedMs: Long): String {
        val safeElapsed = elapsedMs.coerceAtLeast(0L)
        val totalSeconds = safeElapsed / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val millis = safeElapsed % 1000L
        return "%02d:%02d.%03d".format(Locale.US, minutes, seconds, millis)
    }

    private fun Any?.orDash(): String = this?.toString()?.takeIf { it.isNotBlank() } ?: "-"

    private fun Double.formatOneDecimal(): String = String.format(java.util.Locale.US, "%.1f", this)

    private fun Double.formatCoordinate(): String = String.format(java.util.Locale.US, "%.6f", this)

    private fun String.toCsvCell(): String {
        if (indexOfAny(charArrayOf(',', '"', '\n')) < 0) {
            return this
        }
        return buildString {
            append('"')
            append(replace("\"", "\"\""))
            append('"')
        }
    }

    private fun String.extractValueAfter(prefix: String): String? {
        val start = indexOf(prefix)
        if (start < 0) {
            return null
        }
        return substring(start + prefix.length)
            .takeWhile { !it.isWhitespace() && it != '|' }
            .takeIf { it.isNotBlank() }
    }

    private fun String.toSpeedBadgeText(): String {
        val value = removeSuffix("km/h").ifBlank { "--" }
        return "$value\nkm/h"
    }

    private fun String.isHttpUrl(): Boolean {
        val parsed = runCatching { Uri.parse(this) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.US)
        return scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()
    }

    private fun OutdoorPoint.isLikelyMainlandChina(): Boolean {
        return latitude in 18.0..54.0 && longitude in 73.0..135.0
    }

    companion object {
        private val outdoorReadyStates = setOf(
            NavState.OUTDOOR_IDLE,
            NavState.OUTDOOR_READY,
            NavState.OUTDOOR_ROUTE_READY,
        )

        private val indoorStates = setOf(
            NavState.INDOOR_READY,
            NavState.INDOOR_CAPTURING,
            NavState.INDOOR_LOCATING,
            NavState.INDOOR_LOW_CONFIDENCE,
            NavState.INDOOR_ROUTING,
            NavState.INDOOR_ROUTE_READY,
        )

        private const val PREFS_NAME = "ai_glasses_poc_config"
        private const val KEY_OUTDOOR_START_LAT = "outdoor_start_lat"
        private const val KEY_OUTDOOR_START_LNG = "outdoor_start_lng"
        private const val KEY_OUTDOOR_ENTRY_LAT = "outdoor_entry_lat"
        private const val KEY_OUTDOOR_ENTRY_LNG = "outdoor_entry_lng"
        private const val KEY_OUTDOOR_SEARCH_KEYWORD = "outdoor_search_keyword"
        private const val KEY_OUTDOOR_SEARCH_CITY = "outdoor_search_city"
        private const val KEY_OUTDOOR_TRAVEL_MODE = "outdoor_travel_mode"
        private const val KEY_OUTDOOR_SIMULATION = "outdoor_simulation"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_VENUE_ID = "venue_id"
        private const val KEY_FLOOR_ID = "floor_id"
        private const val KEY_POI_ID = "poi_id"
        private const val KEY_DEBUG_TARGET = "debug_target"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_INDOOR_CALIBRATION_POINTS = "indoor_calibration_points_v1"
        private const val ACTION_PC_BACKEND_SMOKE = "com.aiglasses.poc.action.PC_BACKEND_SMOKE"
        private const val ACTION_ROKID_VOICE_SMOKE = "com.aiglasses.poc.action.ROKID_VOICE_SMOKE"
        private const val EXTRA_BASE_URL = "base_url"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_YAW_DEG = "yaw_deg"
        private const val DEFAULT_INDOOR_CALIBRATION_POINTS_ASSET =
            "indoor_calibration/default_indoor_calibration_points_v1.json"
        private const val LEGACY_WUDAOKOU_VENUE_ID = "venue_bj_wudaokou_mall_001"
        private const val LEGACY_WUDAOKOU_POI_ID = "poi_nike_2f"
        private const val DEFAULT_WUDAOKOU_VENUE_ID = "venue_bj_wudaokou_shopping_center_demo"
        private const val DEFAULT_WUDAOKOU_POI_ID = "poi_f2_tata_door_demo"
        private const val EXHIBITION_DEMO_VENUE_ID = "venue_exhibition_demo"
        private const val CONFERENCE_INDOOR_ONLY_MODE = true
        private const val CONFERENCE_BASEMAP_ASSET = "mapping/conference/huichang.jpg"
        private const val CONFERENCE_IMAGE_ASSET_DIR = "mapping/conference"
        private const val CONFERENCE_NAV_GRAPH_ASSET = "$CONFERENCE_IMAGE_ASSET_DIR/huichang_app_nav_graph.json"
        private const val CONFERENCE_POI_RESOLVER_ASSET = "$CONFERENCE_IMAGE_ASSET_DIR/huichang_poi_resolver_app_ready.json"
        private const val CONFERENCE_BACKEND_BASE_URL = "http://127.0.0.1:8000"
        private const val CONFERENCE_DEFAULT_FLOOR_ID = "F1"
        private const val CONFERENCE_DEFAULT_IMAGE_NAV_START_NODE_ID = "node_0"
        private const val CONFERENCE_DEFAULT_TARGET_POI_ID = "poi_217"
        private const val CONFERENCE_DEFAULT_DEBUG_TARGET = "B17"
        private const val CONFERENCE_PROVIDER_ID = "rokid_glasses_frame"
        private const val CONFERENCE_ROKID_HTTP_ENDPOINT = "http://127.0.0.1:18080"
        private const val CONFERENCE_ROKID_HTTP_RETRY_MS = 3_000L
        private const val CONFERENCE_AUTO_LOCATE_INTERVAL_MS = 250L
        private const val CONFERENCE_HUD_DISPLAYED_POSITION_MIN_INTERVAL_MS = 120L
        private const val CONFERENCE_INDOOR_METERS_PER_GRAPH_UNIT = 0.066
        private const val CONFERENCE_INDOOR_WALKING_SPEED_MPS = 1.2
        private const val CONFERENCE_WALK_DEMO_FRAME_INTERVAL_MS = 350L
        private const val CONFERENCE_WALK_DEMO_SPEED_MPS = 2.4
        private const val CONFERENCE_WALK_DEMO_ARRIVAL_THRESHOLD_M = 0.7
        private const val CONFERENCE_HUD_MIN_TURN_EDGE_METERS = 0.6
        private const val CONFERENCE_HUD_TURN_MIN_ANGLE_DEGREES = 65.0
        private const val CONFERENCE_HUD_TURN_MAX_ANGLE_DEGREES = 115.0
        private const val SHOW_RECORDING_MARKER_FLOATING_PANEL = false
        private const val CONFERENCE_MAP_CROP_LEFT_PX = 780.0
        private const val CONFERENCE_MAP_CROP_TOP_PX = 2250.0
        private const val CONFERENCE_MAP_CROP_RIGHT_PX = 1720.0
        private const val CONFERENCE_MAP_CROP_BOTTOM_PX = 3480.0
        private const val CONFERENCE_MAP_CROP_WIDTH_PX = 940.0
        private const val CONFERENCE_MAP_CROP_HEIGHT_PX = 1230.0
        private const val CONFERENCE_MAP_CROP_TOLERANCE_PX = 24.0
        private const val CONFERENCE_BACKEND_DEMO_COORDINATE_MAX = 80.0
        private const val OUTDOOR_POI_SELECT_PROMPT = "请选择搜索结果"
        private const val INDOOR_ANNOTATION_HEADER = "point_id,type,floor_id,x,y,lat,lng,route_node_id,photo_ref,remark"
        private const val INDOOR_CALIBRATION_RESULT_HEADER = "point_id,type,floor_id,x,y,screen_x,screen_y,lat,lng,route_node_id,remark"
        private const val MIN_OUTDOOR_SEARCH_KEYWORD_LENGTH = 2
        private const val OUTDOOR_SEARCH_DEBOUNCE_MS = 450L
        private const val MAX_DEMO_SEARCH_RESULTS = 10
        private const val HIDDEN_DEBUG_REVEAL_DELAY_MS = 350L
        private const val HIDDEN_DEBUG_QUICK_TAP_WINDOW_MS = 650L
        private const val HIDDEN_DEBUG_QUICK_TAP_COUNT = 2
        private const val EXTERNAL_RETURN_CHECK_INTERVAL_MS = 3_000L
        private const val EXTERNAL_ENTRY_DISTANCE_THRESHOLD_METERS = 50f
        private const val EMBEDDED_AMAP_LONG_ROUTE_FALLBACK_METERS = 80_000f
        private const val WUDAOKOU_TATA_AMAP_OVERLAY_ASSET = "indoor_routes/wudaokou_tata_amap_gcj02_overlay.json"
        private const val WUDAOKOU_IMAGE_ASSET_DIR = "mapping/wudaokou2"
        private const val WUDAOKOU_NAV_GRAPH_ASSET = "$WUDAOKOU_IMAGE_ASSET_DIR/wudaokou_all_floors_app_nav_graph.json"
        private const val WUDAOKOU_POI_RESOLVER_ASSET = "$WUDAOKOU_IMAGE_ASSET_DIR/wudaokou_all_floors_poi_resolver_app_ready.json"
        private const val WUDAOKOU_IMAGE_CALIBRATION_SCOPE = "wudaokou2_all_floors"
        private const val DEFAULT_IMAGE_NAV_START_NODE_ID = "node_entrance_f1_west_gate_access"
        private const val INDOOR_UNIFIED_POI_PREFIX = "indoor:"
        private const val MAX_IMAGE_NAV_CANDIDATES = 6
        private const val LEGACY_WUDAOKOU_ENTRY_LAT = 31.231706
        private const val LEGACY_WUDAOKOU_ENTRY_LNG = 121.475644
        private const val DEFAULT_WUDAOKOU_ENTRY_LAT = 39.991583
        private const val DEFAULT_WUDAOKOU_ENTRY_LNG = 116.338965
        private const val DEFAULT_INDOOR_CENTER_LAT = DEFAULT_WUDAOKOU_ENTRY_LAT
        private const val DEFAULT_INDOOR_CENTER_LNG = DEFAULT_WUDAOKOU_ENTRY_LNG
        private const val SAMPLE_ENTRY_FLOOR = "F1"
        private const val SAMPLE_TARGET_FLOOR = "F2"
        private const val SAMPLE_ENTRY_X = 6.0
        private const val SAMPLE_ENTRY_Y = 23.7
        private const val SAMPLE_F1_ESCALATOR_X = 28.9
        private const val SAMPLE_F1_ESCALATOR_Y = 9.3
        private const val SAMPLE_F2_ESCALATOR_X = 28.4
        private const val SAMPLE_F2_ESCALATOR_Y = 6.0
        private const val SAMPLE_TARGET_X = 42.0
        private const val SAMPLE_TARGET_Y = 26.0

        private val OUTDOOR_VENUE_INTENT_TERMS = listOf(
            "购物中心",
            "商场",
            "百货",
            "商业中心",
            "mall",
            "plaza",
        )
        private val OUTDOOR_ENTRANCE_INTENT_TERMS = listOf(
            "入口",
            "出入口",
            "东门",
            "西门",
            "南门",
            "北门",
            "门口",
        )
        private val OUTDOOR_STORE_IN_VENUE_PENALTY_TERMS = listOf(
            "店",
            "门店",
            "专卖",
            "体验",
            "服务",
            "b1",
            "b2",
            "f1",
            "f2",
            "层",
        )
        private val INDOOR_GENERIC_CATEGORY_TERMS_NORMALIZED = listOf(
            "麻辣香锅",
            "火锅",
            "烧烤",
            "餐厅",
            "美食",
            "奶茶",
            "咖啡",
            "服装",
            "手机",
            "超市",
        )

        private val demoStoreSeeds = listOf(
            DemoPoiSeed("poi_demo_store_01", "五道口购物中心", "五道口", "北京", 0.0007, 0.0003, 120f, listOf("五道口", "购物中心", "center"), directionHint = OutdoorPoiDirectionHint.FORWARD),
            DemoPoiSeed("poi_demo_store_02", "优衣库（五道口店）", "五道口购物中心 F2", "北京", 0.0005, 0.0006, 150f, listOf("优衣库", "uniqlo", "五道口"), directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_store_03", "星巴克（中心店）", "五道口购物中心 F1", "北京", 0.0002, 0.0009, 180f, listOf("星巴克", "starbucks", "五道口"), directionHint = OutdoorPoiDirectionHint.SLIGHT_RIGHT),
            DemoPoiSeed("poi_demo_store_04", "华清嘉园商场", "成府路 45 号", "北京", -0.0004, 0.0010, 260f, listOf("华清", "商场", "嘉园"), directionHint = OutdoorPoiDirectionHint.FORWARD),
            DemoPoiSeed("poi_demo_store_05", "麦当劳（五道口店）", "成府路 28 号", "北京", -0.0005, 0.0012, 310f, listOf("麦当劳", "mcdonald", "五道口"), directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_store_06", "五道口地铁站", "13 号线", "北京", -0.0010, 0.0008, 350f, listOf("地铁", "五道口", "station"), visualType = OutdoorPoiVisualType.ENTRANCE, directionHint = OutdoorPoiDirectionHint.FORWARD),
            DemoPoiSeed("poi_demo_store_07", "清华科技园", "中关村东路", "北京", 0.0012, -0.0006, 420f, listOf("清华", "科技园", "office"), visualType = OutdoorPoiVisualType.OFFICE, directionHint = OutdoorPoiDirectionHint.SLIGHT_RIGHT),
        )

        private val demoStoreTianjieSeeds = listOf(
            DemoPoiSeed("poi_demo_tianjie_01", "天街一面(恒基·旭辉中心南区店)", "申虹路1088弄36号楼1层33号", "上海", 0.0007, 0.0003, 15200f, listOf("天街", "tianjie", "恒基", "旭辉")),
            DemoPoiSeed("poi_demo_tianjie_02", "龙湖天街城市展厅", "申长路869号龙湖虹桥天街购物中心", "上海", 0.0009, 0.0005, 15900f, listOf("天街", "tianjie", "龙湖")),
            DemoPoiSeed("poi_demo_tianjie_03", "戴尔x外星人电脑", "申长路869号龙湖虹桥天街A馆B区", "上海", 0.0010, 0.0008, 15900f, listOf("天街", "tianjie", "外星人", "戴尔")),
            DemoPoiSeed("poi_demo_tianjie_04", "天街珑珠会员服务中心", "申长路869号龙湖虹桥天街购物中心", "上海", 0.0011, 0.0010, 15900f, listOf("天街", "tianjie", "珑珠")),
            DemoPoiSeed("poi_demo_tianjie_05", "虹桥天街工作室", "申长路869号龙湖虹桥天街", "上海", 0.0012, 0.0012, 15900f, listOf("天街", "tianjie", "虹桥")),
            DemoPoiSeed("poi_demo_tianjie_06", "优衣库（五道口店）", "五道口购物中心 F2", "北京", 0.0005, 0.0006, 150f, listOf("优衣库", "uniqlo", "五道口")),
            DemoPoiSeed("poi_demo_tianjie_07", "星巴克（中心店）", "五道口购物中心 F1", "北京", 0.0002, 0.0009, 180f, listOf("星巴克", "starbucks", "五道口")),
        )

        private val demoEntranceSeeds = listOf(
            DemoPoiSeed("poi_demo_entry_01", "商场入口（北侧）", "五道口购物中心", "北京", 0.0007, 0.0003, 120f, listOf("入口", "北侧", "north", "五道口"), visualType = OutdoorPoiVisualType.ENTRANCE, directionHint = OutdoorPoiDirectionHint.FORWARD),
            DemoPoiSeed("poi_demo_entry_02", "北侧入口", "五道口购物中心", "北京", 0.0006, 0.0005, 210f, listOf("入口", "北侧", "north"), visualType = OutdoorPoiVisualType.ENTRANCE, directionHint = OutdoorPoiDirectionHint.FORWARD),
            DemoPoiSeed("poi_demo_entry_03", "南侧入口", "五道口购物中心", "北京", -0.0006, 0.0005, 340f, listOf("入口", "南侧", "south"), visualType = OutdoorPoiVisualType.ENTRANCE, directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_entry_04", "东1门", "华清嘉园商场", "北京", 0.0004, 0.0011, 260f, listOf("入口", "东门", "east"), visualType = OutdoorPoiVisualType.ENTRANCE, directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_entry_05", "西1门", "华清嘉园商场", "北京", -0.0004, -0.0004, 290f, listOf("入口", "西门", "west"), visualType = OutdoorPoiVisualType.ENTRANCE, directionHint = OutdoorPoiDirectionHint.SLIGHT_RIGHT),
            DemoPoiSeed("poi_demo_entry_06", "写字楼主入口", "华清商务大厦", "北京", 0.0010, -0.0003, 320f, listOf("写字楼", "入口", "office"), visualType = OutdoorPoiVisualType.OFFICE, directionHint = OutdoorPoiDirectionHint.SLIGHT_RIGHT),
            DemoPoiSeed("poi_demo_entry_07", "地铁连廊入口", "五道口地铁站上盖", "北京", -0.0010, 0.0008, 360f, listOf("入口", "连廊", "地铁"), visualType = OutdoorPoiVisualType.ENTRANCE, directionHint = OutdoorPoiDirectionHint.FORWARD),
        )

        private val demoOfficeSeeds = listOf(
            DemoPoiSeed("poi_demo_office_01", "华清商务大厦", "成府路 28 号", "北京", 0.0008, -0.0002, 140f, listOf("华清", "写字楼", "商务", "office"), visualType = OutdoorPoiVisualType.OFFICE, directionHint = OutdoorPoiDirectionHint.FORWARD),
            DemoPoiSeed("poi_demo_office_02", "中关村创客中心", "中关村东路 1 号", "北京", 0.0010, 0.0002, 210f, listOf("中关村", "中心", "office"), visualType = OutdoorPoiVisualType.OFFICE, directionHint = OutdoorPoiDirectionHint.SLIGHT_RIGHT),
            DemoPoiSeed("poi_demo_office_03", "清华科技园 C 座", "清华科技园", "北京", 0.0012, -0.0006, 260f, listOf("清华", "科技园", "office"), visualType = OutdoorPoiVisualType.OFFICE, directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_office_04", "华腾写字楼", "华清嘉园西侧", "北京", 0.0004, -0.0010, 310f, listOf("写字楼", "华腾"), visualType = OutdoorPoiVisualType.OFFICE, directionHint = OutdoorPoiDirectionHint.SLIGHT_RIGHT),
            DemoPoiSeed("poi_demo_office_05", "汇文中心", "汇文路 18 号", "北京", -0.0008, -0.0005, 360f, listOf("汇文", "中心"), visualType = OutdoorPoiVisualType.OFFICE, directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_office_06", "创业大厦", "中关村大街 77 号", "北京", -0.0010, 0.0004, 420f, listOf("创业", "大厦"), visualType = OutdoorPoiVisualType.OFFICE, directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_office_07", "华清联合办公", "五道口购物中心北侧", "北京", 0.0005, 0.0010, 460f, listOf("联合办公", "office"), visualType = OutdoorPoiVisualType.OFFICE, directionHint = OutdoorPoiDirectionHint.SLIGHT_RIGHT),
        )

        private val demoResidentialSeeds = listOf(
            DemoPoiSeed("poi_demo_home_01", "华清嘉园", "中关村东路", "北京", 0.0006, 0.0001, 160f, listOf("华清", "嘉园", "home", "小区"), visualType = OutdoorPoiVisualType.RESIDENTIAL, directionHint = OutdoorPoiDirectionHint.FORWARD),
            DemoPoiSeed("poi_demo_home_02", "中关新园", "成府路", "北京", 0.0011, 0.0001, 230f, listOf("中关新园", "home", "小区"), visualType = OutdoorPoiVisualType.RESIDENTIAL, directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_home_03", "五道口家园", "五道口地铁站东侧", "北京", -0.0002, 0.0010, 280f, listOf("五道口", "家园", "小区"), visualType = OutdoorPoiVisualType.RESIDENTIAL, directionHint = OutdoorPoiDirectionHint.SLIGHT_RIGHT),
            DemoPoiSeed("poi_demo_home_04", "和平里社区", "和平大道", "北京", -0.0011, -0.0002, 350f, listOf("和平", "社区"), visualType = OutdoorPoiVisualType.RESIDENTIAL, directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_home_05", "广场北里", "广场北路", "北京", 0.0002, -0.0010, 390f, listOf("广场", "北里"), visualType = OutdoorPoiVisualType.RESIDENTIAL, directionHint = OutdoorPoiDirectionHint.RIGHT),
            DemoPoiSeed("poi_demo_home_06", "学院路公寓", "清华南路", "北京", 0.0014, -0.0008, 450f, listOf("公寓", "学院路"), visualType = OutdoorPoiVisualType.RESIDENTIAL, directionHint = OutdoorPoiDirectionHint.SLIGHT_RIGHT),
            DemoPoiSeed("poi_demo_home_07", "汇文家园", "汇文路", "北京", -0.0009, -0.0007, 520f, listOf("汇文", "家园"), visualType = OutdoorPoiVisualType.RESIDENTIAL, directionHint = OutdoorPoiDirectionHint.RIGHT),
        )
    }
}
