package com.aiglasses.poc.glasses

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aiglasses.poc.R
import com.aiglasses.poc.databinding.ActivityGlassesDebugBinding
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

class GlassesDebugActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGlassesDebugBinding
    private lateinit var annotationStore: RecordingAnnotationSessionStore
    private val manager = HeyCyanGlassesManager
    private val annotationController = RecordingAnnotationController(
        elapsedTimeMs = { SystemClock.elapsedRealtime() },
        wallTimeMs = { System.currentTimeMillis() },
    )
    private val mediaController by lazy { MediaController(this) }
    private val hudHandler = Handler(Looper.getMainLooper())
    private val videoHudTicker = object : Runnable {
        override fun run() {
            updateVideoHudOverlay()
            hudHandler.postDelayed(this, VIDEO_HUD_TICK_MS)
        }
    }
    private var renderedDevices: List<GlassesDevice>? = null
    private var renderedMedia: List<GlassesMediaItem>? = null
    private var selectedMediaPath: String? = null
    private var renderedSelectedMediaPath: String? = null
    private var renderedThumbnailPath: String? = null
    private var albumExpanded = false
    private var annotationPanelExpanded = false
    private var videoHudActions: List<VideoHudAction> = emptyList()
    private var currentAnnotationJsonPath: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val denied = result.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            Toast.makeText(this, R.string.status_glasses_permissions_granted, Toast.LENGTH_SHORT).show()
            maybeAutoConnectLastDevice()
        } else {
            Toast.makeText(
                this,
                getString(R.string.status_glasses_permissions_denied, denied.joinToString()),
                Toast.LENGTH_LONG,
            ).show()
        }
        render(manager.state.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlassesDebugBinding.inflate(layoutInflater)
        annotationStore = RecordingAnnotationSessionStore(application)
        setContentView(binding.root)
        applySystemBarInsets()
        manager.initialize(application)
        bindActions()
        requestMissingPermissions()
        maybeAutoConnectLastDevice()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.state.collect { render(it) }
            }
        }
    }

    private fun applySystemBarInsets() {
        val initialLeft = binding.root.paddingLeft
        val initialTop = binding.root.paddingTop
        val initialRight = binding.root.paddingRight
        val initialBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun bindActions() {
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonRequestPermissions.setOnClickListener { requestMissingPermissions() }
        binding.buttonScan.setOnClickListener { requestMissingPermissions(); manager.startScan(this) }
        binding.buttonStopScan.setOnClickListener { manager.stopScan() }
        binding.buttonDisconnect.setOnClickListener { manager.disconnect() }
        binding.buttonInitDevice.setOnClickListener { manager.initDeviceSession() }
        binding.buttonDeviceInfo.setOnClickListener { manager.syncDeviceInfo() }
        binding.buttonBattery.setOnClickListener { manager.syncBattery() }
        binding.buttonMediaCount.setOnClickListener { manager.requestMediaCount() }
        binding.buttonTakePhoto.setOnClickListener { manager.takePhoto() }
        binding.buttonThumbnail.setOnClickListener { manager.requestThumbnail() }
        binding.buttonStartVideo.setOnClickListener { manager.startVideo() }
        binding.buttonStopVideo.setOnClickListener { manager.stopVideo() }
        binding.buttonToggleAnnotationPanel.setOnClickListener {
            annotationPanelExpanded = !annotationPanelExpanded
            renderAnnotationPanel()
        }
        binding.buttonStartAnnotatedVideo.setOnClickListener { startAnnotatedRecording() }
        binding.buttonStopAnnotatedVideo.setOnClickListener { stopAnnotatedRecording() }
        binding.buttonAnnotateForward.setOnClickListener { recordAnnotationAction(RecordingAnnotationAction.FORWARD) }
        binding.buttonAnnotateLeft.setOnClickListener { recordAnnotationAction(RecordingAnnotationAction.TURN_LEFT) }
        binding.buttonAnnotateRight.setOnClickListener { recordAnnotationAction(RecordingAnnotationAction.TURN_RIGHT) }
        binding.buttonAnnotateFloorUp.setOnClickListener { recordAnnotationAction(RecordingAnnotationAction.FLOOR_UP) }
        binding.buttonAnnotateFloorDown.setOnClickListener { recordAnnotationAction(RecordingAnnotationAction.FLOOR_DOWN) }
        binding.buttonAnnotateUndo.setOnClickListener { undoAnnotationAction() }
        binding.buttonExportAnnotation.setOnClickListener { exportCurrentAnnotationJson() }
        binding.buttonSyncAlbum.setOnClickListener { manager.syncAlbum() }
        binding.buttonRefreshMedia.setOnClickListener { manager.refreshLocalMedia() }
        binding.buttonToggleAlbum.setOnClickListener {
            albumExpanded = !albumExpanded
            renderAlbumExpansion()
        }
        binding.buttonCopyLog.setOnClickListener { copyFullLogToClipboard() }
        binding.videoPreview.setOnClickListener {
            if (binding.videoPreview.isPlaying) {
                binding.videoPreview.pause()
                updateVideoHudOverlay()
            } else {
                binding.videoPreview.start()
                startVideoHudTicker()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopVideoHudTicker()
    }

    private fun render(state: GlassesDebugState) {
        binding.textStatus.text = buildString {
            appendLine(state.status)
            appendLine("连接：${if (state.connected) "已连接" else "未连接"} / 通道：${if (state.ready) "就绪" else "未就绪"}")
            appendLine("设备：${state.selectedDevice?.let { "${it.name} ${it.address}" } ?: "未选择"}")
            appendLine(state.batterySummary)
            appendLine(state.deviceInfoSummary)
            append(state.mediaCountSummary)
        }
        binding.textConnectionChip.text = if (state.connected) "已连接" else "未连接"
        binding.textChannelChip.text = if (state.ready) "通道就绪" else if (state.connected) "等待通道" else "未连接通道"
        binding.textRememberedDevice.text = state.rememberedDeviceSummary
        binding.textConnectionChip.setTextColor(ContextCompat.getColor(this, if (state.connected) android.R.color.holo_green_dark else android.R.color.darker_gray))
        binding.textConnectionChip.setBackgroundResource(if (state.connected) R.drawable.bg_ui_top_status_icon_badge else R.drawable.bg_ui_status_capsule)
        binding.textChannelChip.setTextColor(ContextCompat.getColor(this, if (state.ready) android.R.color.holo_blue_dark else android.R.color.darker_gray))
        binding.buttonRequestPermissions.visibility = if (hasMissingRuntimePermissions()) View.VISIBLE else View.GONE
        binding.buttonScan.text = if (state.scanning) getString(R.string.action_glasses_scanning) else getString(R.string.action_glasses_scan)
        binding.buttonScan.visibility = if (state.connected) View.GONE else View.VISIBLE
        binding.buttonStopScan.visibility = if (state.connected || !state.scanning) View.GONE else View.VISIBLE
        binding.buttonDisconnect.visibility = if (state.connected || state.ready || state.selectedDevice != null) View.VISIBLE else View.GONE
        binding.buttonInitDevice.visibility = if (state.connected && !state.ready) View.VISIBLE else View.GONE
        listOf(
            binding.buttonDeviceInfo,
            binding.buttonBattery,
            binding.buttonMediaCount,
            binding.buttonTakePhoto,
            binding.buttonThumbnail,
            binding.buttonStartVideo,
            binding.buttonStopVideo,
            binding.buttonSyncAlbum,
            binding.buttonRefreshMedia,
        ).forEach { button ->
            button.isEnabled = state.connected || state.ready
        }
        binding.buttonTakePhoto.isEnabled = state.ready
        binding.buttonThumbnail.isEnabled = state.ready
        binding.buttonStartVideo.isEnabled = state.ready && !state.videoRecording
        binding.buttonStopVideo.isEnabled = state.videoRecording
        binding.buttonSyncAlbum.isEnabled = state.ready
        binding.buttonRefreshMedia.isEnabled = true
        renderAlbumExpansion()
        binding.layoutDeviceRows.visibility = if (!state.connected && (state.scanning || state.devices.isNotEmpty())) View.VISIBLE else View.GONE
        if (state.connected) {
            binding.layoutDeviceRows.removeAllViews()
            renderedDevices = emptyList()
        } else if (state.devices != renderedDevices) {
            renderDevices(state.devices)
        }
        if (state.localMedia != renderedMedia) {
            renderMedia(state.localMedia)
        }
        tryBindAnnotationSession(state.localMedia)
        renderAnnotationPanel()
        renderAiThumbnail(state.latestThumbnailPath)
        if (selectedMediaPath != null && state.localMedia.none { it.filePath == selectedMediaPath }) {
            selectedMediaPath = null
        }
        binding.textLog.text = state.logs.takeLast(50).joinToString("\n").ifBlank { getString(R.string.text_glasses_log_empty) }
        (selectedMediaPath ?: state.latestPreviewPath)
            ?.let { path -> state.localMedia.firstOrNull { it.filePath == path } }
            ?.takeIf { it.filePath != renderedSelectedMediaPath }
            ?.let { renderSelectedMedia(it) }
    }

    private fun startAnnotatedRecording() {
        val state = manager.state.value
        if (!state.ready) {
            Toast.makeText(this, R.string.status_annotation_requires_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (annotationController.session?.active == true) return
        annotationController.start(
            device = state.selectedDevice,
            existingVideoPaths = state.localMedia.videoPaths(),
        )
        manager.startVideo {
            annotationController.confirmRecordingStarted()
            runOnUiThread { renderAnnotationPanel() }
        }
        renderAnnotationPanel()
    }

    private fun stopAnnotatedRecording() {
        val stopped = annotationController.stop()
        if (stopped == null || stopped.active) {
            Toast.makeText(this, R.string.status_annotation_not_started, Toast.LENGTH_SHORT).show()
            return
        }
        manager.stopVideo()
        val pending = annotationStore.savePending(stopped)
        annotationController.updateSession(pending)
        tryBindAnnotationSession(manager.state.value.localMedia)
        renderAnnotationPanel()
    }

    private fun recordAnnotationAction(action: RecordingAnnotationAction) {
        if (annotationController.session?.active != true) {
            Toast.makeText(this, R.string.status_annotation_not_started, Toast.LENGTH_SHORT).show()
            return
        }
        if (annotationController.session?.aligned != true) {
            Toast.makeText(this, R.string.status_annotation_not_aligned, Toast.LENGTH_SHORT).show()
            return
        }
        annotationController.record(action)
        renderAnnotationPanel()
    }

    private fun undoAnnotationAction() {
        if (annotationController.session?.active != true) {
            Toast.makeText(this, R.string.status_annotation_not_started, Toast.LENGTH_SHORT).show()
            return
        }
        annotationController.undoLast()
        renderAnnotationPanel()
    }

    private fun tryBindAnnotationSession(media: List<GlassesMediaItem>) {
        val session = annotationController.session
            ?.takeIf { !it.active && it.videoLocalPath == null }
            ?: return
        val bound = annotationStore.bindToNewestVideo(session, media)
        if (bound != session) {
            annotationController.updateSession(bound)
        }
    }

    private fun renderAnnotationPanel() {
        val session = annotationController.session
        val active = session?.active == true
        val actionEnabled = session?.let { it.active && it.aligned } == true
        binding.layoutAnnotationFloatingPanel.visibility = if (annotationPanelExpanded) View.VISIBLE else View.GONE
        binding.buttonToggleAnnotationPanel.setText(
            if (annotationPanelExpanded) {
                R.string.action_annotation_panel_collapse
            } else {
                R.string.action_annotation_panel_expand
            },
        )
        binding.buttonStartAnnotatedVideo.isEnabled = manager.state.value.ready && !active
        binding.buttonStopAnnotatedVideo.isEnabled = active
        listOf(
            binding.buttonAnnotateForward,
            binding.buttonAnnotateLeft,
            binding.buttonAnnotateRight,
            binding.buttonAnnotateFloorUp,
            binding.buttonAnnotateFloorDown,
            binding.buttonAnnotateUndo,
        ).forEach { button ->
            button.isEnabled = actionEnabled
        }
        binding.textAnnotationStatus.text = when {
            session == null -> getString(R.string.text_annotation_idle)
            active && !session.aligned -> getString(R.string.text_annotation_waiting_alignment)
            active -> getString(
                R.string.text_annotation_recording,
                session.timeAlignment,
                session.events.size,
            )
            session.videoLocalPath != null -> getString(
                R.string.text_annotation_bound,
                session.videoFileName ?: "-",
                session.jsonLocalPath ?: "-",
            )
            else -> getString(R.string.text_annotation_waiting_video, session.jsonLocalPath ?: "-")
        }
        binding.buttonExportAnnotation.isEnabled = currentAnnotationJsonText() != null
        binding.textAnnotationEvents.text = session?.events
            ?.takeLast(8)
            ?.joinToString("\n") { event ->
                "${formatElapsed(event.elapsedMs)}  ${event.action.label}"
            }
            ?.ifBlank { getString(R.string.text_annotation_no_events) }
            ?: getString(R.string.text_annotation_no_events)
    }

    private fun exportCurrentAnnotationJson() {
        val json = currentAnnotationJsonText()
        if (json == null) {
            Toast.makeText(this, R.string.status_annotation_no_json, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VisionRoute navigation annotation", json))
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "VisionRoute 导航动作 JSON")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.title_annotation_export_chooser)))
        Toast.makeText(this, R.string.status_annotation_json_copied, Toast.LENGTH_SHORT).show()
    }

    private fun currentAnnotationJsonText(): String? {
        annotationController.session?.let { return it.toJsonString() }
        return currentAnnotationJsonPath
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.readText()
    }

    private fun renderDevices(devices: List<GlassesDevice>) {
        renderedDevices = devices
        binding.layoutDeviceRows.removeAllViews()
        if (devices.isEmpty()) {
            binding.layoutDeviceRows.addView(simpleRow(getString(R.string.text_glasses_no_devices)))
            return
        }
        devices.forEach { device ->
            val button = simpleRow("${device.name}\n${device.address}    RSSI ${device.rssi}")
            button.setOnClickListener { manager.connect(device) }
            binding.layoutDeviceRows.addView(button)
        }
    }

    private fun renderAlbumExpansion() {
        binding.layoutAlbumContent.visibility = if (albumExpanded) View.VISIBLE else View.GONE
        binding.buttonToggleAlbum.setText(
            if (albumExpanded) {
                R.string.action_glasses_collapse_album
            } else {
                R.string.action_glasses_expand_album
            },
        )
    }

    private fun renderMedia(media: List<GlassesMediaItem>) {
        renderedMedia = media
        binding.layoutMediaRows.removeAllViews()
        binding.layoutMediaRows.columnCount = 3
        if (media.isEmpty()) {
            binding.layoutMediaRows.addView(emptyGalleryText())
            return
        }
        media.take(40).forEach { item ->
            binding.layoutMediaRows.addView(galleryTile(item), galleryTileParams())
        }
    }

    private fun renderSelectedMedia(item: GlassesMediaItem) {
        renderedSelectedMediaPath = item.filePath
        val isAiThumbnail = item.isAiThumbnail()
        binding.textSelectedMedia.text = if (GlassesMediaClassifier.isPreviewVideo(item.fileName)) {
            "${item.fileName}\n${item.filePath}\n视频预览：点击预览区域播放/暂停"
        } else if (isAiThumbnail) {
            "${item.fileName}\nAI缩略图预览"
        } else {
            "${item.fileName}\n${item.filePath}\n图片预览"
        }
        binding.videoPreview.stopPlayback()
        binding.videoPreview.setOnPreparedListener(null)
        binding.videoPreview.setOnErrorListener(null)
        binding.imagePreview.visibility = View.GONE
        binding.videoPreview.visibility = View.GONE
        binding.videoPreviewFrame.visibility = View.GONE
        binding.textVideoHudOverlay.visibility = View.GONE
        stopVideoHudTicker()
        videoHudActions = emptyList()
        currentAnnotationJsonPath = null
        binding.imagePreview.setOnLongClickListener(null)
        binding.videoPreview.setOnLongClickListener(null)
        if (GlassesMediaClassifier.isPreviewImage(item.fileName)) {
            binding.imagePreview.layoutParams = binding.imagePreview.layoutParams.apply {
                height = if (isAiThumbnail) dp(200) else dp(260)
            }
            binding.imagePreview.scaleType = if (isAiThumbnail) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
            binding.imagePreview.setBackgroundColor(if (isAiThumbnail) 0xFF111827.toInt() else 0xFFE5E7EB.toInt())
            binding.imagePreview.setImageBitmap(decodeSampledBitmap(item.filePath, dp(900)))
            binding.imagePreview.setOnLongClickListener {
                saveMediaToSystemGallery(item)
                true
            }
            binding.imagePreview.visibility = View.VISIBLE
        } else if (GlassesMediaClassifier.isPreviewVideo(item.fileName)) {
            val file = File(item.filePath)
            if (!file.exists() || file.length() <= 0L) {
                binding.textSelectedMedia.text = "${item.fileName}\n${item.filePath}\n视频文件不存在或为空，无法播放"
                return
            }
            videoHudActions = loadVideoHudActions(item)
            binding.textSelectedMedia.text = buildString {
                appendLine(binding.textSelectedMedia.text)
                if (videoHudActions.isNotEmpty()) {
                    append("已加载 HUD 动作：${videoHudActions.size} 条")
                } else {
                    append("未找到同名动作 JSON，视频仅普通预览")
                }
            }
            binding.videoPreview.setMediaController(mediaController)
            mediaController.setAnchorView(binding.videoPreview)
            binding.videoPreview.setOnErrorListener { _, what, extra ->
                binding.videoPreview.visibility = View.GONE
                binding.videoPreviewFrame.visibility = View.GONE
                binding.textSelectedMedia.text = "${item.fileName}\n${item.filePath}\n视频播放失败：播放器不支持或文件未完整同步（what=$what extra=$extra）"
                Toast.makeText(this, "视频无法播放，请重新同步眼镜相册后再试", Toast.LENGTH_SHORT).show()
                true
            }
            binding.videoPreview.setOnPreparedListener { player ->
                binding.videoPreviewFrame.visibility = View.VISIBLE
                binding.videoPreview.visibility = View.VISIBLE
                player.isLooping = false
                binding.videoPreview.start()
                binding.videoPreview.requestFocus()
                mediaController.show(1500)
                startVideoHudTicker()
            }
            binding.videoPreviewFrame.visibility = View.VISIBLE
            binding.videoPreview.visibility = View.VISIBLE
            binding.videoPreview.setOnLongClickListener {
                saveMediaToSystemGallery(item)
                true
            }
            binding.videoPreview.setVideoPath(file.absolutePath)
        }
    }

    private fun loadVideoHudActions(item: GlassesMediaItem): List<VideoHudAction> {
        val jsonFile = annotationStore.resolveAnnotationJsonForVideo(item)
            ?: return emptyList()
        currentAnnotationJsonPath = jsonFile.absolutePath
        return runCatching {
            val actions = JSONObject(jsonFile.readText()).getJSONArray("actions")
            buildList {
                for (index in 0 until actions.length()) {
                    val action = actions.getJSONObject(index)
                    val type = action.optString("type")
                    val label = action.optString("label").ifBlank { type }
                    add(
                        VideoHudAction(
                            type = type,
                            label = label,
                            elapsedMs = action.optLong("elapsed_ms"),
                            durationMs = hudDurationMs(type),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun startVideoHudTicker() {
        hudHandler.removeCallbacks(videoHudTicker)
        hudHandler.post(videoHudTicker)
    }

    private fun stopVideoHudTicker() {
        hudHandler.removeCallbacks(videoHudTicker)
        binding.textVideoHudOverlay.visibility = View.GONE
    }

    private fun updateVideoHudOverlay() {
        if (videoHudActions.isEmpty() || binding.videoPreview.visibility != View.VISIBLE) {
            binding.textVideoHudOverlay.visibility = View.GONE
            return
        }
        val currentMs = binding.videoPreview.currentPosition.toLong()
        val activeAction = videoHudActions.lastOrNull { action ->
            currentMs in action.elapsedMs..(action.elapsedMs + action.durationMs)
        }
        if (activeAction == null) {
            binding.textVideoHudOverlay.visibility = View.GONE
        } else {
            binding.textVideoHudOverlay.text = "${hudIcon(activeAction.type)}\n${activeAction.label}"
            binding.textVideoHudOverlay.visibility = View.VISIBLE
        }
    }

    private fun hudDurationMs(type: String): Long {
        return when (type) {
            RecordingAnnotationAction.FLOOR_UP.jsonValue,
            RecordingAnnotationAction.FLOOR_DOWN.jsonValue -> 4_000L
            else -> 2_500L
        }
    }

    private fun hudIcon(type: String): String {
        return when (type) {
            RecordingAnnotationAction.FORWARD.jsonValue -> "↑"
            RecordingAnnotationAction.TURN_LEFT.jsonValue -> "↰"
            RecordingAnnotationAction.TURN_RIGHT.jsonValue -> "↱"
            RecordingAnnotationAction.FLOOR_UP.jsonValue -> "⇧"
            RecordingAnnotationAction.FLOOR_DOWN.jsonValue -> "⇩"
            else -> "◆"
        }
    }

    private fun renderAiThumbnail(path: String?) {
        if (path == renderedThumbnailPath) return
        renderedThumbnailPath = path
        val file = path?.let { File(it) }
        if (file != null && file.exists()) {
            binding.imageAiThumbnailPreview.setImageBitmap(decodeSampledBitmap(file.absolutePath, dp(600)))
            binding.imageAiThumbnailPreview.visibility = View.VISIBLE
            binding.textAiThumbnailStatus.text = "已获取：${file.name}"
        } else {
            binding.imageAiThumbnailPreview.setImageDrawable(null)
            binding.imageAiThumbnailPreview.visibility = View.GONE
            binding.textAiThumbnailStatus.setText(R.string.text_glasses_ai_thumbnail_empty)
        }
    }

    private fun galleryTile(item: GlassesMediaItem): View {
        val isVideo = GlassesMediaClassifier.isPreviewVideo(item.fileName)
        val isAiThumbnail = item.isAiThumbnail()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@GlassesDebugActivity, R.drawable.bg_ui_secondary_button)
            isClickable = true
            isFocusable = true
            val padding = if (isAiThumbnail) dp(2) else dp(6)
            setPadding(padding, padding, padding, padding)
            setOnClickListener {
                selectedMediaPath = item.filePath
                renderSelectedMedia(item)
            }
            setOnLongClickListener {
                saveMediaToSystemGallery(item)
                true
            }
        }
        val previewFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(86),
            )
            setBackgroundColor(if (isAiThumbnail) 0xFF111827.toInt() else 0xFFE5E7EB.toInt())
        }
        val previewImage = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = if (isAiThumbnail) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            contentDescription = if (isVideo) {
                getString(R.string.content_glasses_video_preview)
            } else {
                getString(R.string.content_glasses_image_preview)
            }
            val bitmap = if (isVideo) videoFrame(item.filePath) else decodeSampledBitmap(item.filePath, dp(180))
            if (bitmap != null) {
                setImageBitmap(bitmap)
            } else {
                setImageResource(if (isVideo) android.R.drawable.ic_media_play else android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }
        previewFrame.addView(previewImage)
        if (isVideo) {
            previewFrame.addView(
                TextView(this).apply {
                    text = "▶"
                    textSize = 20f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    background = ContextCompat.getDrawable(this@GlassesDebugActivity, R.drawable.bg_ui_debug_icon_badge)
                    layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
                },
            )
        }
        container.addView(previewFrame)
        container.addView(
            TextView(this).apply {
                text = item.fileName
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF111827.toInt())
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(5) }
            },
        )
        container.addView(
            TextView(this).apply {
                text = if (isVideo) "视频" else "图片"
                setTextColor(0xFF6B7280.toInt())
                textSize = 10f
            },
        )
        return container
    }

    private fun emptyGalleryText(): TextView {
        return TextView(this).apply {
            text = getString(R.string.text_glasses_no_media)
            setTextColor(0xFF6B7280.toInt())
            textSize = 12f
            setPadding(dp(8), dp(12), dp(8), dp(12))
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(0, 3),
            )
        }
    }

    private fun galleryTileParams(): GridLayout.LayoutParams {
        return GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
        ).apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(dp(3), dp(3), dp(3), dp(5))
        }
    }

    private fun decodeSampledBitmap(path: String, targetSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize)
        options.inJustDecodeBounds = false
        return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
    }

    private fun videoFrame(path: String): Bitmap? {
        return runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(path)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dp(180), dp(120))
                } else {
                    retriever.frameAtTime
                }
            }
        }.getOrNull()
    }

    private fun saveMediaToSystemGallery(item: GlassesMediaItem) {
        val source = File(item.filePath)
        if (!source.exists() || source.length() <= 0L) {
            Toast.makeText(this, "文件不存在或为空，无法保存", Toast.LENGTH_SHORT).show()
            return
        }
        val isVideo = GlassesMediaClassifier.isPreviewVideo(item.fileName)
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        var uri: Uri? = null
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                put(MediaStore.MediaColumns.SIZE, source.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/VisionRoute")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    val baseDir = Environment.getExternalStoragePublicDirectory(
                        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
                    )
                    val targetFile = File(File(baseDir, "VisionRoute").apply { mkdirs() }, source.name)
                    put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
                }
            }
            uri = contentResolver.insert(collection, values)
                ?: error("MediaStore insert failed")
            contentResolver.openOutputStream(uri!!)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("MediaStore output stream failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(
                    uri!!,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }.onSuccess {
            Toast.makeText(this, "已保存到系统相册：VisionRoute", Toast.LENGTH_SHORT).show()
        }.onFailure { throwable ->
            uri?.let { contentResolver.delete(it, null, null) }
            Toast.makeText(this, "保存失败：${throwable.message ?: throwable.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun GlassesMediaItem.isAiThumbnail(): Boolean {
        return mimeType.startsWith("image/") && fileName.startsWith("thumbnail_", ignoreCase = true)
    }

    private fun List<GlassesMediaItem>.videoPaths(): Set<String> {
        return filter { it.mimeType.startsWith("video/") }
            .map { it.filePath }
            .toSet()
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val safeElapsed = elapsedMs.coerceAtLeast(0L)
        val totalSeconds = safeElapsed / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val millis = safeElapsed % 1000L
        return "%02d:%02d.%03d".format(Locale.US, minutes, seconds, millis)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        var halfHeight = options.outHeight / 2
        var halfWidth = options.outWidth / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun simpleRow(text: String): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            setTextColor(ContextCompat.getColor(this@GlassesDebugActivity, android.R.color.black))
            background = ContextCompat.getDrawable(this@GlassesDebugActivity, R.drawable.bg_ui_secondary_button)
            val padding = resources.getDimensionPixelSize(R.dimen.glasses_debug_row_padding)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
    }

    private fun copyFullLogToClipboard() {
        val logText = manager.fullSessionLog()
        if (logText.isBlank()) {
            Toast.makeText(this, R.string.text_glasses_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VisionRoute glasses logs", logText))
        Toast.makeText(
            this,
            getString(R.string.status_glasses_log_copied, logText.lineSequence().count()),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun maybeAutoConnectLastDevice() {
        if (!hasMissingRuntimePermissions()) {
            manager.autoConnectLastDevice(this)
        }
    }

    private fun requestMissingPermissions() {
        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasMissingRuntimePermissions(): Boolean {
        return missingRuntimePermissions().isNotEmpty()
    }

    private fun missingRuntimePermissions(): List<String> {
        return runtimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun runtimePermissions(): List<String> {
        return buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private data class VideoHudAction(
        val type: String,
        val label: String,
        val elapsedMs: Long,
        val durationMs: Long,
    )

    companion object {
        private const val VIDEO_HUD_TICK_MS = 200L
    }
}
