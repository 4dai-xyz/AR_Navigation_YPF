package com.aiglasses.poc.usb

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aiglasses.poc.R
import com.aiglasses.poc.databinding.ActivityUsbCameraDebugBinding
import com.aiglasses.poc.glasses.RecordingAnnotationAction
import com.aiglasses.poc.glasses.RecordingAnnotationJsonItem
import com.aiglasses.poc.glasses.RecordingAnnotationSessionStore
import com.aiglasses.poc.glasses.RecordingAnnotationVideoItem
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.herohan.uvcapp.VideoCapture
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCControl
import com.serenegiant.usb.UVCCamera
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsbCameraDebugActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUsbCameraDebugBinding
    private lateinit var annotationStore: RecordingAnnotationSessionStore
    private var cameraHelper: ICameraHelper? = null
    private var uvcDevices: List<UsbDevice> = emptyList()
    private var selectedDevice: UsbDevice? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewTextureAdded = false
    private val usbMediaController by lazy { MediaController(this) }
    private var selectedUsbMediaPath: String? = null
    private var selectedUsbJsonPath: String? = null
    private var pendingUsbJsonExportPath: String? = null
    private var renderedUsbMediaPath: String? = null
    private var usbSettingsExpanded = false
    private var suppressUsbFormatSelection = false
    private var usbFormatOptions: List<UsbFormatOption> = emptyList()
    private var selectedUsbPreviewSize: Size? = null
    private var usbPhotoQuality = DEFAULT_PHOTO_QUALITY
    private var usbVideoFrameRate = DEFAULT_VIDEO_FPS
    private var usbVideoBitRateMbps = DEFAULT_VIDEO_BITRATE_MBPS
    private var usbIFrameInterval = DEFAULT_IFRAME_INTERVAL_SECONDS
    private var usbRecordAudio = DEFAULT_RECORD_AUDIO
    private var usbAudioBitRateKbps = DEFAULT_AUDIO_BITRATE_KBPS
    private var usbAudioSampleRate = DEFAULT_AUDIO_SAMPLE_RATE
    private var usbAudioChannels = DEFAULT_AUDIO_CHANNELS
    private var usbAudioMinBuffer = DEFAULT_AUDIO_MIN_BUFFER
    private var pendingUsbPreviewReopenSize: Size? = null
    private var pendingUsbPreviewFallbackSize: Size? = null
    private var pendingUsbPreviewReopenScheduled = false
    private var lastUsbPreviewFrameAtMs = 0L
    private var usbPreviewValidationGeneration = 0
    private var autoOpenAttemptedDeviceName: String? = null
    private var cachedUsbSupportedSizes: List<Size> = emptyList()
    private val failedUsbFormatKeys = mutableSetOf<String>()
    private val previewRecoveryReopenKeys = mutableSetOf<String>()
    private val usbVideoHudHandler = Handler(Looper.getMainLooper())
    private val usbVideoHudTicker = object : Runnable {
        override fun run() {
            updateUsbVideoHudOverlay()
            usbVideoHudHandler.postDelayed(this, USB_VIDEO_HUD_TICK_MS)
        }
    }
    private var usbVideoHudActions: List<UsbVideoHudAction> = emptyList()
    private val usbImageValueInputs = mutableMapOf<String, EditText>()
    private val usbImageCheckInputs = mutableMapOf<String, CheckBox>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            renderPermissionState()
            refreshCameras()
        }

    private val exportJsonLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            exportSelectedJsonToUri(uri)
        }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            previewTexture = surface
            selectedUsbPreviewSize?.let { surface.setDefaultBufferSize(it.width, it.height) }
            addPreviewSurfaceIfNeeded()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            removePreviewSurfaceIfNeeded(surface)
            previewTexture = null
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            lastUsbPreviewFrameAtMs = SystemClock.elapsedRealtime()
        }
    }

    private data class UsbCameraMediaItem(
        val file: File,
        val mimeType: String,
        val lastModified: Long,
        val mediaInfo: UsbCameraMediaInfo,
    ) {
        val fileName: String get() = file.name
        val filePath: String get() = file.absolutePath
        val isVideo: Boolean get() = mimeType.startsWith("video/")
    }

    private data class UsbCameraMediaInfo(
        val width: Int,
        val height: Int,
        val frameRate: Float? = null,
        val durationMs: Long? = null,
    ) {
        val hasResolution: Boolean get() = width > 0 && height > 0
    }

    private data class UsbFormatOption(
        val size: Size,
        val label: String,
    )

    private data class UsbControlRange(
        val min: Int,
        val max: Int,
        val default: Int,
    )

    private data class UsbIntControlSpec(
        val key: String,
        val label: String,
        val autoKey: String? = null,
        val isSupported: UVCControl.() -> Boolean,
        val updateRange: UVCControl.() -> IntArray,
        val getValue: UVCControl.() -> Int,
        val setValue: UVCControl.(Int) -> Unit,
        val resetValue: UVCControl.() -> Unit,
    )

    private data class UsbBoolControlSpec(
        val key: String,
        val label: String,
        val isSupported: UVCControl.() -> Boolean,
        val getValue: UVCControl.() -> Boolean,
        val setValue: UVCControl.(Boolean) -> Unit,
        val resetValue: UVCControl.() -> Unit,
    )

    private val cameraStateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            if (device.isLikelyUvcCamera()) {
                refreshCameras()
                binding.textUsbCameraStatus.text = "检测到 UVC 摄像头：vendor=${device.vendorId} product=${device.productId}"
            }
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            val size = preferredPreviewSize()
            Log.i(TAG, "onDeviceOpen device=${device.deviceName} preferred=$size")
            binding.textUsbCameraStatus.text = "USB 授权成功，正在打开摄像头：${size.width}x${size.height}"
            cameraHelper?.openCamera(size)
        }

        override fun onCameraOpen(device: UsbDevice) {
            val helper = cameraHelper ?: return
            val reopenedSize = pendingUsbPreviewReopenSize?.clone()
            val fallbackSize = pendingUsbPreviewFallbackSize?.clone()
            val isRecoveryReopen = reopenedSize?.let { previewRecoveryReopenKeys.contains(it.formatKey()) } == true
            pendingUsbPreviewReopenSize = null
            pendingUsbPreviewReopenScheduled = false
            helper.startPreview()
            Log.i(TAG, "onCameraOpen device=${device.deviceName} previewSize=${helper.previewSize}")
            helper.previewSize?.let {
                selectedUsbPreviewSize = it.clone()
                resizeUsbPreviewView(it)
                preparePreviewTextureForSize(it)
            }
            refreshUsbFormatOptions()
            applyCaptureConfigs(helper)
            addPreviewSurfaceIfNeeded()
            renderUsbImageControls()
            if (reopenedSize != null) {
                binding.textUsbCameraStatus.text = "UVC 预览已重启，正在验证 ${reopenedSize.formatLabel()}..."
                scheduleUsbPreviewFrameValidation(
                    appliedSize = reopenedSize,
                    fallbackSize = fallbackSize,
                    hasRetriedCamera = isRecoveryReopen,
                    markFormatFailedOnTimeout = false,
                )
                scheduleUsbPreviewSurfaceSettleRecovery(reopenedSize)
            } else {
                binding.textUsbCameraStatus.text = "UVC 直连预览中：${device.deviceName}"
                helper.previewSize?.let {
                    scheduleUsbPreviewFrameValidation(
                        appliedSize = it.clone(),
                        fallbackSize = null,
                        markFormatFailedOnTimeout = false,
                    )
                }
            }
            renderActionState()
        }

        override fun onCameraClose(device: UsbDevice) {
            removePreviewSurfaceIfNeeded()
            binding.textUsbCameraStatus.text = if (pendingUsbPreviewReopenSize != null) {
                "正在重启 USB 预览..."
            } else {
                "USB 摄像头已关闭"
            }
            if (pendingUsbPreviewReopenSize != null) {
                schedulePendingUsbPreviewReopen()
            }
            renderUsbImageControls()
            renderActionState()
        }

        override fun onDeviceClose(device: UsbDevice) {
            binding.textUsbCameraStatus.text = "USB 设备已关闭"
            renderActionState()
        }

        override fun onDetach(device: UsbDevice) {
            if (selectedDevice?.deviceName == device.deviceName) {
                selectedDevice = null
            }
            refreshCameras()
            binding.textUsbCameraStatus.text = "USB 摄像头已拔出"
        }

        override fun onCancel(device: UsbDevice) {
            binding.textUsbCameraStatus.text = "USB 摄像头授权已取消"
            renderActionState()
        }

        override fun onError(device: UsbDevice?, e: CameraException?) {
            Log.e(TAG, "onError device=${device?.deviceName}", e)
            pendingUsbPreviewReopenSize = null
            binding.textUsbCameraStatus.text = "UVC 摄像头错误：${e?.message ?: "未知错误"}"
            renderActionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsbCameraDebugBinding.inflate(layoutInflater)
        annotationStore = RecordingAnnotationSessionStore(application)
        UsbCameraRecordingManager.releaseCameraForDebug()
        setContentView(binding.root)
        applySystemBarInsets()
        bindActions()
        binding.videoUsbMediaPreview.setMediaController(usbMediaController)
        binding.viewUsbCameraPreview.surfaceTextureListener = textureListener
        renderUsbSettingsPanel()
        initCameraHelper()
        renderPermissionState()
        refreshCameras()
        refreshUsbMedia()
        refreshAnnotationJsonList()
    }

    override fun onResume() {
        super.onResume()
        refreshCameras()
        refreshUsbMedia()
        refreshAnnotationJsonList()
    }

    override fun onPause() {
        stopUsbVideoHudTicker()
        stopRecordingIfNeeded()
        super.onPause()
    }

    override fun onDestroy() {
        stopUsbVideoHudTicker()
        removePreviewSurfaceIfNeeded()
        binding.viewUsbCameraPreview.surfaceTextureListener = null
        cameraHelper?.release()
        cameraHelper = null
        super.onDestroy()
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
        binding.buttonRequestPermission.setOnClickListener { requestCameraPermissions() }
        binding.buttonRefreshUsbCamera.setOnClickListener { refreshCameras() }
        binding.buttonOpenUsbCamera.setOnClickListener { openSelectedCamera() }
        binding.buttonCaptureUsbPhoto.setOnClickListener { captureUsbPhoto() }
        binding.buttonStartUsbVideo.setOnClickListener { startRecording() }
        binding.buttonStopUsbVideo.setOnClickListener { stopRecordingIfNeeded() }
        binding.buttonRefreshUsbAlbum.setOnClickListener { refreshUsbMedia() }
        binding.buttonRefreshUsbJson.setOnClickListener { refreshAnnotationJsonList() }
        binding.buttonExportUsbJson.setOnClickListener { requestExportSelectedJson() }
        binding.buttonToggleUsbSettings.setOnClickListener {
            usbSettingsExpanded = !usbSettingsExpanded
            renderUsbSettingsPanel()
        }
        binding.buttonApplyUsbSettings.setOnClickListener { applyUsbSettingsFromPanel() }
        binding.buttonResetUsbSettings.setOnClickListener { resetUsbSettings() }
        binding.checkUsbRecordAudio.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutUsbAudioSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.spinnerUsbVideoFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressUsbFormatSelection) return
                val option = usbFormatOptions.getOrNull(position) ?: return
                selectedUsbPreviewSize = option.size.clone()
                usbVideoFrameRate = option.size.fps.coerceAtLeast(DEFAULT_VIDEO_FPS)
                binding.editUsbVideoFrameRate.setText(usbVideoFrameRate.toString())
                updateUsbSettingsSummary()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun initCameraHelper() {
        if (cameraHelper != null) return
        cameraHelper = CameraHelper().apply {
            setStateCallback(cameraStateCallback)
        }
        applyCaptureConfigs(cameraHelper)
    }

    private fun renderPermissionState() {
        val cameraGranted = hasCameraPermission()
        val audioGranted = hasAudioPermission()
        val storageGranted = hasLegacyWritePermission()
        binding.textUsbCameraPermission.text = buildString {
            append("权限：相机 ${if (cameraGranted) "已授权" else "未授权"}")
            append(" / 麦克风 ${if (audioGranted) "已授权" else "未授权"}")
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                append(" / 存储 ${if (storageGranted) "已授权" else "未授权"}")
            }
        }
        binding.buttonRequestPermission.visibility =
            if (cameraGranted && audioGranted && storageGranted) View.GONE else View.VISIBLE
        renderActionState()
    }

    private fun refreshCameras() {
        uvcDevices = readUsbDevices().filter { it.isLikelyUvcCamera() }
        if (uvcDevices.none { it.deviceName == selectedDevice?.deviceName }) {
            selectedDevice = null
            autoOpenAttemptedDeviceName = null
            cachedUsbSupportedSizes = emptyList()
            failedUsbFormatKeys.clear()
        }
        val labels = if (uvcDevices.isEmpty()) {
            listOf("未发现 UVC USB 摄像头")
        } else {
            uvcDevices.mapIndexed { index, device ->
                "UVC摄像头 ${index + 1} vendor=${device.vendorId} product=${device.productId}"
            }
        }
        binding.spinnerUsbCamera.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        binding.textUsbDeviceSummary.text = summarizeUsbDevices()
        if (cameraHelper?.isCameraOpened == true) {
            binding.textUsbCameraStatus.text = "UVC 直连预览中，可拍照或录像。"
        } else if (uvcDevices.isNotEmpty()) {
            binding.textUsbCameraStatus.text = "发现 ${uvcDevices.size} 个 UVC USB 摄像头。请选择后点击打开预览。"
        } else {
            binding.textUsbCameraStatus.text = "未发现可直连的 UVC 摄像头。若 OTG 已检测到设备，请确认摄像头是标准 UVC 协议。"
        }
        renderActionState()
        maybeAutoOpenUsbCamera()
    }

    private fun maybeAutoOpenUsbCamera() {
        if (!hasCameraPermission()) return
        if (cameraHelper?.isCameraOpened == true) return
        if (pendingUsbPreviewReopenSize != null || pendingUsbPreviewReopenScheduled) return
        val device = uvcDevices.getOrNull(binding.spinnerUsbCamera.selectedItemPosition)
            ?: uvcDevices.firstOrNull()
            ?: return
        if (autoOpenAttemptedDeviceName == device.deviceName) return
        autoOpenAttemptedDeviceName = device.deviceName
        binding.textUsbCameraStatus.text = "检测到 UVC 摄像头，正在自动打开预览..."
        binding.viewUsbCameraPreview.post {
            if (isFinishing || isDestroyed) return@post
            if (cameraHelper?.isCameraOpened == true) return@post
            if (pendingUsbPreviewReopenSize != null || pendingUsbPreviewReopenScheduled) return@post
            openSelectedCamera(autoTriggered = true)
        }
    }

    private fun openSelectedCamera(autoTriggered: Boolean = false) {
        if (!hasCameraPermission()) {
            if (autoTriggered) return
            requestCameraPermissions()
            return
        }
        val helper = cameraHelper
        if (helper?.isCameraOpened == true) {
            val size = helper.previewSize?.clone() ?: selectedUsbPreviewSize
            if (size != null) {
                resizeUsbPreviewView(size)
                refreshPreviewSurface(size)
                scheduleUsbPreviewFrameValidation(
                    appliedSize = size,
                    fallbackSize = null,
                    markFormatFailedOnTimeout = false,
                )
            } else {
                addPreviewSurfaceIfNeeded()
            }
            binding.textUsbCameraStatus.text = "USB 摄像头已在预览中，无需重复打开。"
            renderActionState()
            return
        }
        val device = uvcDevices.getOrNull(binding.spinnerUsbCamera.selectedItemPosition)
        if (device == null) {
            Toast.makeText(this, "未发现可打开的 UVC USB 摄像头", Toast.LENGTH_SHORT).show()
            refreshCameras()
            return
        }
        if (selectedDevice?.deviceName != device.deviceName) {
            failedUsbFormatKeys.clear()
            cachedUsbSupportedSizes = emptyList()
        }
        selectedDevice = device
        binding.textUsbCameraStatus.text = if (autoTriggered) {
            "正在自动请求 USB 授权并打开预览..."
        } else {
            "正在请求 USB 授权并打开预览..."
        }
        removePreviewSurfaceIfNeeded()
        Log.i(TAG, "selectDevice device=${device.deviceName} vendor=${device.vendorId} product=${device.productId}")
        cameraHelper?.selectDevice(device)
        renderActionState()
    }

    private fun addPreviewSurfaceIfNeeded() {
        val helper = cameraHelper ?: return
        val texture = previewTexture ?: binding.viewUsbCameraPreview.surfaceTexture ?: return
        if (!helper.isCameraOpened || previewTextureAdded) return
        previewTexture = texture
        selectedUsbPreviewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }
        helper.addSurface(texture, false)
        previewTextureAdded = true
        Log.i(TAG, "preview texture added")
    }

    private fun removePreviewSurfaceIfNeeded(texture: SurfaceTexture? = previewTexture) {
        if (!previewTextureAdded || texture == null) return
        runCatching {
            cameraHelper?.removeSurface(texture)
        }.onFailure { throwable ->
            Log.w(TAG, "remove preview texture failed", throwable)
        }
        previewTextureAdded = false
        Log.i(TAG, "preview texture removed")
    }

    private fun refreshPreviewSurface(size: Size) {
        removePreviewSurfaceIfNeeded()
        preparePreviewTextureForSize(size)
        binding.viewUsbCameraPreview.postDelayed({
            addPreviewSurfaceIfNeeded()
        }, USB_PREVIEW_SURFACE_REATTACH_DELAY_MS)
    }

    private fun preparePreviewTextureForSize(size: Size) {
        val texture = previewTexture ?: binding.viewUsbCameraPreview.surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        previewTexture = texture
    }

    private fun preferredPreviewSize(): Size {
        return selectedUsbPreviewSize?.clone()
            ?: supportedUsbFormatOptions()
                .firstOrNull { it.size.isPreferredPreviewConfig() }
                ?.size
                ?.clone()
            ?: defaultPreviewSize()
    }

    private fun captureUsbPhoto() {
        val helper = cameraHelper
        if (helper?.isCameraOpened != true) {
            Toast.makeText(this, "请先打开 USB 摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        val file = outputFile(prefix = "usb_photo", extension = "jpg")
        val options = IImageCapture.OutputFileOptions.Builder(file).build()
        binding.textUsbCameraStatus.text = "正在保存照片..."
        helper.takePicture(
            options,
            object : IImageCapture.OnImageCaptureCallback {
                override fun onImageSaved(outputFileResults: IImageCapture.OutputFileResults) {
                    handleCapturedMediaSaved(
                        file = file,
                        mimeType = "image/jpeg",
                        label = "照片已保存",
                    )
                }

                override fun onError(imageCaptureError: Int, message: String, cause: Throwable?) {
                    runOnUiThread {
                        binding.textUsbCameraStatus.text = "照片保存失败：$message"
                        renderActionState()
                    }
                }
            },
        )
    }

    private fun startRecording() {
        val helper = cameraHelper
        if (helper?.isCameraOpened != true) {
            Toast.makeText(this, "请先打开 USB 摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        if (helper.isRecording) return
        if (!hasAudioPermission()) {
            requestCameraPermissions()
            return
        }
        val output = outputFile(prefix = "usb_video", extension = "mp4")
        val options = VideoCapture.OutputFileOptions.Builder(output).build()
        helper.startRecording(
            options,
            object : VideoCapture.OnVideoCaptureCallback {
                override fun onStart() {
                    binding.textUsbCameraStatus.text = "录像中：${output.absolutePath}"
                    renderActionState()
                }

                override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                    handleCapturedMediaSaved(
                        file = output,
                        mimeType = "video/mp4",
                        label = "录像已保存",
                    )
                }

                override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                    runOnUiThread {
                        binding.textUsbCameraStatus.text = "录像失败：$message"
                        renderActionState()
                    }
                }
            },
        )
        renderActionState()
    }

    private fun stopRecordingIfNeeded() {
        val helper = cameraHelper ?: return
        if (!helper.isRecording) return
        helper.stopRecording()
        binding.textUsbCameraStatus.text = "正在停止录像并保存文件..."
        renderActionState()
    }

    private fun handleCapturedMediaSaved(file: File, mimeType: String, label: String) {
        runOnUiThread {
            binding.textUsbCameraStatus.text = "$label：${file.absolutePath}，正在同步到系统相册..."
            renderActionState()
        }
        Thread {
            val synced = saveMediaToSystemGallery(file, mimeType)
            runOnUiThread {
                selectedUsbMediaPath = file.absolutePath
                binding.textUsbCameraStatus.text = if (synced) {
                    "${label}并同步到系统相册：${file.absolutePath}"
                } else {
                    "${label}：${file.absolutePath}（系统相册同步失败，已保留在本机目录）"
                }
                if (!synced) {
                    Toast.makeText(this, "已保存在本机目录，但系统相册同步失败", Toast.LENGTH_SHORT).show()
                }
                refreshUsbMedia(file.absolutePath)
                refreshAnnotationJsonList(file.absolutePath)
                renderActionState()
            }
        }.start()
    }

    private fun refreshUsbMedia(preferredSelectionPath: String? = selectedUsbMediaPath) {
        val media = scanUsbMedia()
        renderUsbMediaTimeline(media)
        val selectedItem = when {
            preferredSelectionPath != null -> media.firstOrNull { it.filePath == preferredSelectionPath }
            selectedUsbMediaPath != null -> media.firstOrNull { it.filePath == selectedUsbMediaPath }
            media.isNotEmpty() -> media.first()
            else -> null
        }
        if (selectedItem == null) {
            selectedUsbMediaPath = null
            clearUsbMediaPreview()
            return
        }
        selectedUsbMediaPath = selectedItem.filePath
        if (selectedItem.filePath != renderedUsbMediaPath) {
            renderSelectedUsbMedia(selectedItem)
        }
    }

    private fun renderUsbMediaTimeline(media: List<UsbCameraMediaItem>) {
        binding.layoutUsbMediaTimeline.removeAllViews()
        binding.layoutUsbMediaTimeline.columnCount = 3
        if (media.isEmpty()) {
            binding.layoutUsbMediaTimeline.addView(emptyUsbGalleryText())
            return
        }
        media.take(60).forEach { item ->
            binding.layoutUsbMediaTimeline.addView(createUsbGalleryTile(item), usbGalleryTileParams())
        }
    }

    private fun createUsbGalleryTile(item: UsbCameraMediaItem): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@UsbCameraDebugActivity, R.drawable.bg_ui_secondary_button)
            isClickable = true
            isFocusable = true
            alpha = if (item.filePath == selectedUsbMediaPath) 1.0f else 0.96f
            val padding = dp(5)
            setPadding(padding, padding, padding, padding)
            setOnClickListener {
                selectedUsbMediaPath = item.filePath
                renderSelectedUsbMedia(item)
            }
        }
        val previewFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(88),
            )
            setBackgroundColor(0xFFE5E7EB.toInt())
        }
        val previewImage = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = if (item.isVideo) {
                getString(R.string.content_usb_video_preview)
            } else {
                getString(R.string.content_usb_image_preview)
            }
            val bitmap = if (item.isVideo) videoFrame(item.filePath) else decodeSampledBitmap(item.filePath, dp(180))
            if (bitmap != null) {
                setImageBitmap(bitmap)
            } else {
                setImageResource(if (item.isVideo) android.R.drawable.ic_media_play else android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }
        previewFrame.addView(previewImage)
        if (item.isVideo) {
            previewFrame.addView(
                TextView(this).apply {
                    text = "▶"
                    textSize = 18f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    background = ContextCompat.getDrawable(this@UsbCameraDebugActivity, R.drawable.bg_ui_debug_icon_badge)
                    layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
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
                text = item.mediaInfoLine()
                setTextColor(0xFF6B7280.toInt())
                textSize = 10f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        return container
    }

    private fun renderSelectedUsbMedia(item: UsbCameraMediaItem) {
        renderedUsbMediaPath = item.filePath
        stopUsbVideoHudTicker()
        usbVideoHudActions = emptyList()
        binding.textUsbVideoHudOverlay.visibility = View.GONE
        binding.textUsbSelectedMedia.text = buildString {
            appendLine(item.fileName)
            appendLine("拍摄时间：${formatUsbTimestamp(item.lastModified)}")
            appendLine("媒体参数：${item.mediaDetailLine()}")
            append(if (item.isVideo) "视频预览：点击预览区播放/暂停" else "图片预览：点击缩略图可切换")
        }
        binding.imageUsbMediaPreview.setImageDrawable(null)
        binding.videoUsbMediaPreview.stopPlayback()
        binding.videoUsbMediaPreview.setOnPreparedListener(null)
        binding.videoUsbMediaPreview.setOnErrorListener(null)
        binding.imageUsbMediaPreview.visibility = View.GONE
        binding.videoUsbMediaPreview.visibility = View.GONE
        binding.imageUsbMediaPreview.setOnClickListener(null)
        binding.videoUsbMediaPreview.setOnClickListener(null)
        if (item.isVideo) {
            val file = item.file
            if (!file.exists() || file.length() <= 0L) {
                binding.textUsbSelectedMedia.text = "${item.fileName}\n文件不存在或为空，无法播放"
                return
            }
            usbVideoHudActions = loadUsbVideoHudActions(item)
            refreshAnnotationJsonList(item.filePath)
            binding.textUsbSelectedMedia.text = buildString {
                appendLine(item.fileName)
                appendLine("拍摄时间：${formatUsbTimestamp(item.lastModified)}")
                appendLine("媒体参数：${item.mediaDetailLine()}")
                if (usbVideoHudActions.isNotEmpty()) {
                    appendLine("HUD 动作：${usbVideoHudActions.size} 条")
                } else {
                    appendLine("未找到同名动作 JSON，视频仅普通预览")
                }
                append("视频预览：点击预览区播放/暂停")
            }
            binding.videoUsbMediaPreview.visibility = View.VISIBLE
            binding.videoUsbMediaPreview.setOnErrorListener { _, what, extra ->
                binding.videoUsbMediaPreview.visibility = View.GONE
                binding.textUsbVideoHudOverlay.visibility = View.GONE
                stopUsbVideoHudTicker()
                binding.textUsbSelectedMedia.text = "${item.fileName}\n视频播放失败：文件可能尚未完整写入或格式不受支持（what=$what extra=$extra）"
                true
            }
            binding.videoUsbMediaPreview.setOnPreparedListener { player ->
                player.isLooping = false
                binding.videoUsbMediaPreview.start()
                usbMediaController.show(1500)
                startUsbVideoHudTicker()
            }
            binding.videoUsbMediaPreview.setOnClickListener {
                if (binding.videoUsbMediaPreview.isPlaying) {
                    binding.videoUsbMediaPreview.pause()
                    updateUsbVideoHudOverlay()
                } else {
                    binding.videoUsbMediaPreview.start()
                    startUsbVideoHudTicker()
                }
            }
            usbMediaController.setAnchorView(binding.videoUsbMediaPreview)
            binding.videoUsbMediaPreview.setVideoPath(file.absolutePath)
        } else {
            binding.imageUsbMediaPreview.setImageBitmap(decodeSampledBitmap(item.filePath, dp(900)))
            binding.imageUsbMediaPreview.visibility = View.VISIBLE
            binding.imageUsbMediaPreview.setOnClickListener {
                selectedUsbMediaPath = item.filePath
                renderSelectedUsbMedia(item)
            }
        }
    }

    private fun clearUsbMediaPreview() {
        renderedUsbMediaPath = null
        stopUsbVideoHudTicker()
        usbVideoHudActions = emptyList()
        binding.textUsbSelectedMedia.text = getString(R.string.text_usb_selected_media_empty)
        binding.imageUsbMediaPreview.setImageDrawable(null)
        binding.imageUsbMediaPreview.visibility = View.GONE
        binding.videoUsbMediaPreview.stopPlayback()
        binding.videoUsbMediaPreview.visibility = View.GONE
        binding.textUsbVideoHudOverlay.visibility = View.GONE
    }

    private fun refreshAnnotationJsonList(preferredVideoPath: String? = null) {
        val jsonItems = annotationStore.listAnnotationJsonFiles()
        val preferredJsonPath = preferredVideoPath
            ?.let(::File)
            ?.let { videoFile -> File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.navigation.json") }
            ?.takeIf { it.exists() }
            ?.absolutePath
        val selectedItem = when {
            preferredJsonPath != null -> jsonItems.firstOrNull { it.filePath == preferredJsonPath }
            selectedUsbJsonPath != null -> jsonItems.firstOrNull { it.filePath == selectedUsbJsonPath }
            jsonItems.isNotEmpty() -> jsonItems.first()
            else -> null
        }
        selectedUsbJsonPath = selectedItem?.filePath
        renderAnnotationJsonList(jsonItems)
        renderSelectedAnnotationJson(selectedItem)
    }

    private fun renderAnnotationJsonList(items: List<RecordingAnnotationJsonItem>) {
        binding.layoutUsbAnnotationJsonList.removeAllViews()
        if (items.isEmpty()) {
            binding.layoutUsbAnnotationJsonList.addView(emptyAnnotationJsonText())
            return
        }
        items.take(80).forEach { item ->
            binding.layoutUsbAnnotationJsonList.addView(createAnnotationJsonRow(item))
        }
    }

    private fun createAnnotationJsonRow(item: RecordingAnnotationJsonItem): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@UsbCameraDebugActivity, R.drawable.bg_ui_secondary_button)
            isClickable = true
            isFocusable = true
            alpha = if (item.filePath == selectedUsbJsonPath) 1.0f else 0.82f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            setOnClickListener {
                selectedUsbJsonPath = item.filePath
                renderAnnotationJsonList(annotationStore.listAnnotationJsonFiles())
                renderSelectedAnnotationJson(item)
            }
            addView(
                TextView(this@UsbCameraDebugActivity).apply {
                    text = item.fileName
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF111827.toInt())
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                },
            )
            addView(
                TextView(this@UsbCameraDebugActivity).apply {
                    text = item.annotationJsonInfoLine()
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 11f
                },
            )
        }
    }

    private fun renderSelectedAnnotationJson(item: RecordingAnnotationJsonItem?) {
        binding.buttonExportUsbJson.isEnabled = item != null
        binding.buttonExportUsbJson.alpha = if (item != null) 1.0f else 0.45f
        binding.textUsbSelectedJson.text = if (item == null) {
            getString(R.string.text_usb_annotation_json_none_selected)
        } else {
            buildString {
                appendLine(item.fileName)
                appendLine("来源：${item.sourceLabel} · 动作 ${item.actionCount} 条 · ${formatFileSize(item.sizeBytes)}")
                appendLine("修改时间：${formatUsbTimestamp(item.lastModified)}")
                append("路径：${item.filePath}")
            }
        }
    }

    private fun requestExportSelectedJson() {
        val path = selectedUsbJsonPath
        val file = path?.let(::File)?.takeIf { it.exists() && it.isFile }
        if (file == null) {
            Toast.makeText(this, "请先选择一个 JSON 文件", Toast.LENGTH_SHORT).show()
            refreshAnnotationJsonList()
            return
        }
        pendingUsbJsonExportPath = file.absolutePath
        exportJsonLauncher.launch(file.name)
    }

    private fun exportSelectedJsonToUri(uri: Uri?) {
        val targetUri = uri ?: return
        val source = pendingUsbJsonExportPath?.let(::File)?.takeIf { it.exists() && it.isFile }
        if (source == null) {
            Toast.makeText(this, "JSON 文件不存在，无法导出", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            contentResolver.openOutputStream(targetUri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法打开目标文件")
        }.onSuccess {
            Toast.makeText(this, "JSON 已导出：${source.name}", Toast.LENGTH_SHORT).show()
        }.onFailure { throwable ->
            Log.e(TAG, "export annotation json failed path=${source.absolutePath}", throwable)
            Toast.makeText(this, "JSON 导出失败：${throwable.message ?: throwable.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUsbVideoHudActions(item: UsbCameraMediaItem): List<UsbVideoHudAction> {
        val jsonFile = annotationStore.resolveAnnotationJsonForVideo(item.toRecordingAnnotationVideoItem())
            ?: return emptyList()
        return runCatching {
            val actions = org.json.JSONObject(jsonFile.readText()).getJSONArray("actions")
            buildList {
                for (index in 0 until actions.length()) {
                    val action = actions.getJSONObject(index)
                    val type = action.optString("type")
                    val label = action.optString("label").ifBlank { type }
                    add(
                        UsbVideoHudAction(
                            type = type,
                            label = label,
                            elapsedMs = action.optLong("elapsed_ms"),
                            durationMs = usbHudDurationMs(type),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun startUsbVideoHudTicker() {
        usbVideoHudHandler.removeCallbacks(usbVideoHudTicker)
        usbVideoHudHandler.post(usbVideoHudTicker)
    }

    private fun stopUsbVideoHudTicker() {
        usbVideoHudHandler.removeCallbacks(usbVideoHudTicker)
        binding.textUsbVideoHudOverlay.visibility = View.GONE
    }

    private fun updateUsbVideoHudOverlay() {
        if (usbVideoHudActions.isEmpty() || binding.videoUsbMediaPreview.visibility != View.VISIBLE) {
            binding.textUsbVideoHudOverlay.visibility = View.GONE
            return
        }
        val currentMs = binding.videoUsbMediaPreview.currentPosition.toLong()
        val activeAction = usbVideoHudActions.lastOrNull { action ->
            currentMs in action.elapsedMs..(action.elapsedMs + action.durationMs)
        }
        if (activeAction == null) {
            binding.textUsbVideoHudOverlay.visibility = View.GONE
        } else {
            binding.textUsbVideoHudOverlay.text = "${usbHudIcon(activeAction.type)}\n${activeAction.label}"
            binding.textUsbVideoHudOverlay.visibility = View.VISIBLE
        }
    }

    private fun usbHudDurationMs(type: String): Long {
        return when (type) {
            RecordingAnnotationAction.FLOOR_UP.jsonValue,
            RecordingAnnotationAction.FLOOR_DOWN.jsonValue -> 4_000L
            else -> 2_500L
        }
    }

    private fun usbHudIcon(type: String): String {
        return when (type) {
            RecordingAnnotationAction.FORWARD.jsonValue -> "↑"
            RecordingAnnotationAction.TURN_LEFT.jsonValue -> "↰"
            RecordingAnnotationAction.TURN_RIGHT.jsonValue -> "↱"
            RecordingAnnotationAction.FLOOR_UP.jsonValue -> "⇧"
            RecordingAnnotationAction.FLOOR_DOWN.jsonValue -> "⇩"
            else -> "◆"
        }
    }

    private fun UsbCameraMediaItem.toRecordingAnnotationVideoItem(): RecordingAnnotationVideoItem {
        return RecordingAnnotationVideoItem(
            fileName = fileName,
            filePath = filePath,
            mimeType = mimeType,
            lastModified = lastModified,
        )
    }

    private fun renderUsbSettingsPanel() {
        binding.layoutUsbSettingsPanel.visibility = if (usbSettingsExpanded) View.VISIBLE else View.GONE
        binding.buttonToggleUsbSettings.setText(
            if (usbSettingsExpanded) {
                R.string.action_hide_usb_camera_settings
            } else {
                R.string.action_show_usb_camera_settings
            },
        )
        binding.editUsbPhotoQuality.setText(usbPhotoQuality.toString())
        binding.editUsbVideoFrameRate.setText(usbVideoFrameRate.toString())
        binding.editUsbVideoBitRate.setText(formatMbps(usbVideoBitRateMbps))
        binding.editUsbIFrameInterval.setText(usbIFrameInterval.toString())
        binding.checkUsbRecordAudio.isChecked = usbRecordAudio
        binding.editUsbAudioBitRate.setText(usbAudioBitRateKbps.toString())
        binding.editUsbAudioSampleRate.setText(usbAudioSampleRate.toString())
        binding.editUsbAudioChannels.setText(usbAudioChannels.toString())
        binding.editUsbAudioMinBuffer.setText(usbAudioMinBuffer.toString())
        binding.layoutUsbAudioSettings.visibility = if (usbRecordAudio) View.VISIBLE else View.GONE
        refreshUsbFormatOptions()
        renderUsbImageControls()
        updateUsbSettingsSummary()
    }

    private fun refreshUsbFormatOptions() {
        val options = supportedUsbFormatOptions()
        usbFormatOptions = options
        suppressUsbFormatSelection = true
        binding.spinnerUsbVideoFormat.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { it.label },
        )
        val selectedIndex = selectedUsbPreviewSize?.let { selected ->
            options.indexOfFirst { it.size.hasSameConfig(selected) }
        }?.takeIf { it >= 0 }
            ?: preferredUsbFormatOptionIndex(options)
            ?: 0
        if (options.isNotEmpty()) {
            binding.spinnerUsbVideoFormat.setSelection(selectedIndex)
            selectedUsbPreviewSize = options[selectedIndex].size.clone()
        }
        suppressUsbFormatSelection = false
        updateUsbSettingsSummary()
    }

    private fun supportedUsbFormatOptions(): List<UsbFormatOption> {
        val liveSupported = runCatching {
            cameraHelper?.getSupportedSizeList().orEmpty()
        }.getOrDefault(emptyList())
        if (liveSupported.isNotEmpty()) {
            cachedUsbSupportedSizes = liveSupported.map { it.clone() }
        }
        val source = liveSupported
            .ifEmpty { cachedUsbSupportedSizes }
            .ifEmpty { listOf(selectedUsbPreviewSize ?: defaultPreviewSize()) }
        val options = source.flatMap { size ->
            size.safeFpsList().map { fps ->
                val optionSize = Size(size.type, size.width, size.height, fps, listOf(fps))
                UsbFormatOption(optionSize, optionSize.formatLabel())
            }
        }.distinctBy { "${it.size.type}:${it.size.width}:${it.size.height}:${it.size.fps}" }
            .sortedWith(
                compareByDescending<UsbFormatOption> { it.size.width * it.size.height }
                    .thenByDescending { it.size.fps }
                    .thenBy { it.size.formatName() },
            )
        val availableOptions = options.filterNot { failedUsbFormatKeys.contains(it.size.formatKey()) }
        return availableOptions.ifEmpty { options }
    }

    private fun renderUsbImageControls() {
        usbImageValueInputs.clear()
        usbImageCheckInputs.clear()
        binding.layoutUsbImageControls.removeAllViews()
        val control = cameraHelper
            ?.takeIf { it.isCameraOpened }
            ?.getUVCControl()
        if (control == null) {
            binding.textUsbImageControlSummary.text = "打开 USB 摄像头后显示白平衡、曝光、亮度等设备支持项。"
            return
        }
        val boolSpecs = usbBoolControlSpecs().filter { spec ->
            runCatching { spec.isSupported(control) }.getOrDefault(false)
        }
        val intSpecs = usbIntControlSpecs().filter { spec ->
            runCatching { spec.isSupported(control) }.getOrDefault(false)
        }
        if (boolSpecs.isEmpty() && intSpecs.isEmpty()) {
            binding.textUsbImageControlSummary.text = "当前摄像头未暴露可调图像参数。"
            return
        }
        binding.textUsbImageControlSummary.text =
            "已发现 ${boolSpecs.size + intSpecs.size} 个可调图像参数；不支持的参数会自动隐藏。"
        boolSpecs.forEach { spec -> addUsbBoolControlRow(control, spec) }
        intSpecs.forEach { spec -> addUsbIntControlRow(control, spec) }
        updateUsbImageControlInputEnabled()
    }

    private fun addUsbBoolControlRow(
        control: UVCControl,
        spec: UsbBoolControlSpec,
    ) {
        val checkbox = CheckBox(this).apply {
            text = spec.label
            textSize = 12f
            setTextColor(0xFF374151.toInt())
            isChecked = runCatching { spec.getValue(control) }.getOrDefault(false)
            setOnCheckedChangeListener { _, _ -> updateUsbImageControlInputEnabled() }
        }
        usbImageCheckInputs[spec.key] = checkbox
        binding.layoutUsbImageControls.addView(checkbox)
    }

    private fun addUsbIntControlRow(
        control: UVCControl,
        spec: UsbIntControlSpec,
    ) {
        val range = runCatching { spec.updateRange(control).toUsbControlRange(spec.getValue(control)) }
            .getOrElse { UsbControlRange(0, 0, 0) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        row.addView(
            TextView(this).apply {
                text = "${spec.label}\n${range.min}-${range.max} 默认${range.default}"
                setTextColor(0xFF374151.toInt())
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val input = EditText(this).apply {
            setText(runCatching { spec.getValue(control).toString() }.getOrDefault(range.default.toString()))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            maxLines = 1
            setSingleLine(true)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(0xFF111827.toInt())
            textSize = 12f
            background = ContextCompat.getDrawable(this@UsbCameraDebugActivity, R.drawable.bg_ui_status_capsule)
            layoutParams = LinearLayout.LayoutParams(dp(116), dp(40))
        }
        usbImageValueInputs[spec.key] = input
        row.addView(input)
        binding.layoutUsbImageControls.addView(row)
    }

    private fun applyUsbImageControls(helper: ICameraHelper?) {
        val control = helper?.takeIf { it.isCameraOpened }?.getUVCControl() ?: return
        usbBoolControlSpecs().forEach { spec ->
            val checkbox = usbImageCheckInputs[spec.key] ?: return@forEach
            if (runCatching { spec.isSupported(control) }.getOrDefault(false)) {
                runCatching { spec.setValue(control, checkbox.isChecked) }
                    .onFailure { Log.w(TAG, "apply image bool control failed key=${spec.key}", it) }
            }
        }
        usbIntControlSpecs().forEach { spec ->
            val input = usbImageValueInputs[spec.key] ?: return@forEach
            if (!input.isEnabled || !runCatching { spec.isSupported(control) }.getOrDefault(false)) {
                return@forEach
            }
            val range = runCatching { spec.updateRange(control).toUsbControlRange(spec.getValue(control)) }
                .getOrElse { UsbControlRange(Int.MIN_VALUE, Int.MAX_VALUE, 0) }
            val value = parseIntSetting(input.text?.toString(), range.min, range.max, spec.label) ?: return@forEach
            runCatching { spec.setValue(control, value) }
                .onFailure { Log.w(TAG, "apply image int control failed key=${spec.key}", it) }
        }
    }

    private fun resetUsbImageControls(helper: ICameraHelper?) {
        val control = helper?.takeIf { it.isCameraOpened }?.getUVCControl() ?: return
        usbBoolControlSpecs().forEach { spec ->
            if (runCatching { spec.isSupported(control) }.getOrDefault(false)) {
                runCatching { spec.resetValue(control) }
                    .onFailure { Log.w(TAG, "reset image bool control failed key=${spec.key}", it) }
            }
        }
        usbIntControlSpecs().forEach { spec ->
            if (runCatching { spec.isSupported(control) }.getOrDefault(false)) {
                runCatching { spec.resetValue(control) }
                    .onFailure { Log.w(TAG, "reset image int control failed key=${spec.key}", it) }
            }
        }
        renderUsbImageControls()
    }

    private fun updateUsbImageControlInputEnabled() {
        setUsbImageValueEnabled(KEY_WHITE_BALANCE, usbImageCheckInputs[KEY_WHITE_BALANCE_AUTO]?.isChecked != true)
        setUsbImageValueEnabled(KEY_EXPOSURE_TIME, usbImageCheckInputs[KEY_EXPOSURE_AUTO]?.isChecked != true)
        setUsbImageValueEnabled(KEY_FOCUS, usbImageCheckInputs[KEY_FOCUS_AUTO]?.isChecked != true)
    }

    private fun setUsbImageValueEnabled(key: String, enabled: Boolean) {
        usbImageValueInputs[key]?.let { input ->
            input.isEnabled = enabled
            input.alpha = if (enabled) 1.0f else 0.42f
        }
    }

    private fun usbBoolControlSpecs(): List<UsbBoolControlSpec> {
        return listOf(
            UsbBoolControlSpec(
                key = KEY_WHITE_BALANCE_AUTO,
                label = "自动白平衡",
                isSupported = { isWhiteBalanceAutoEnable() },
                getValue = { getWhiteBalanceAuto() },
                setValue = { setWhiteBalanceAuto(it) },
                resetValue = { resetWhiteBalanceAuto() },
            ),
            UsbBoolControlSpec(
                key = KEY_EXPOSURE_AUTO,
                label = "自动曝光",
                isSupported = { isAutoExposureModeEnable() },
                getValue = { isExposureTimeAuto() },
                setValue = { setExposureTimeAuto(it) },
                resetValue = { resetAutoExposureMode() },
            ),
            UsbBoolControlSpec(
                key = KEY_FOCUS_AUTO,
                label = "自动对焦",
                isSupported = { isFocusAutoEnable() },
                getValue = { getFocusAuto() },
                setValue = { setFocusAuto(it) },
                resetValue = { resetFocusAuto() },
            ),
        )
    }

    private fun usbIntControlSpecs(): List<UsbIntControlSpec> {
        return listOf(
            UsbIntControlSpec(
                key = KEY_WHITE_BALANCE,
                label = "白平衡色温",
                autoKey = KEY_WHITE_BALANCE_AUTO,
                isSupported = { isWhiteBalanceEnable() },
                updateRange = { updateWhiteBalanceLimit() },
                getValue = { getWhiteBalance() },
                setValue = { setWhiteBalance(it) },
                resetValue = { resetWhiteBalance() },
            ),
            UsbIntControlSpec(
                key = KEY_EXPOSURE_TIME,
                label = "曝光时间",
                autoKey = KEY_EXPOSURE_AUTO,
                isSupported = { isExposureTimeAbsoluteEnable() },
                updateRange = { updateExposureTimeAbsoluteLimit() },
                getValue = { getExposureTimeAbsolute() },
                setValue = { setExposureTimeAbsolute(it) },
                resetValue = { resetExposureTimeAbsolute() },
            ),
            UsbIntControlSpec(
                key = KEY_BRIGHTNESS,
                label = "亮度",
                isSupported = { isBrightnessEnable() },
                updateRange = { updateBrightnessLimit() },
                getValue = { getBrightness() },
                setValue = { setBrightness(it) },
                resetValue = { resetBrightness() },
            ),
            UsbIntControlSpec(
                key = KEY_CONTRAST,
                label = "对比度",
                isSupported = { isContrastEnable() },
                updateRange = { updateContrastLimit() },
                getValue = { getContrast() },
                setValue = { setContrast(it) },
                resetValue = { resetContrast() },
            ),
            UsbIntControlSpec(
                key = KEY_SATURATION,
                label = "饱和度",
                isSupported = { isSaturationEnable() },
                updateRange = { updateSaturationLimit() },
                getValue = { getSaturation() },
                setValue = { setSaturation(it) },
                resetValue = { resetSaturation() },
            ),
            UsbIntControlSpec(
                key = KEY_GAIN,
                label = "增益",
                isSupported = { isGainEnable() },
                updateRange = { updateGainLimit() },
                getValue = { getGain() },
                setValue = { setGain(it) },
                resetValue = { resetGain() },
            ),
            UsbIntControlSpec(
                key = KEY_SHARPNESS,
                label = "锐度",
                isSupported = { isSharpnessEnable() },
                updateRange = { updateSharpnessLimit() },
                getValue = { getSharpness() },
                setValue = { setSharpness(it) },
                resetValue = { resetSharpness() },
            ),
            UsbIntControlSpec(
                key = KEY_GAMMA,
                label = "Gamma",
                isSupported = { isGammaEnable() },
                updateRange = { updateGammaLimit() },
                getValue = { getGamma() },
                setValue = { setGamma(it) },
                resetValue = { resetGamma() },
            ),
            UsbIntControlSpec(
                key = KEY_BACKLIGHT,
                label = "背光补偿",
                isSupported = { isBacklightCompEnable() },
                updateRange = { updateBacklightCompLimit() },
                getValue = { getBacklightComp() },
                setValue = { setBacklightComp(it) },
                resetValue = { resetBacklightComp() },
            ),
            UsbIntControlSpec(
                key = KEY_FOCUS,
                label = "焦距",
                autoKey = KEY_FOCUS_AUTO,
                isSupported = { isFocusAbsoluteEnable() },
                updateRange = { updateFocusAbsoluteLimit() },
                getValue = { getFocusAbsolute() },
                setValue = { setFocusAbsolute(it) },
                resetValue = { resetFocusAbsolute() },
            ),
            UsbIntControlSpec(
                key = KEY_ZOOM,
                label = "变焦",
                isSupported = { isZoomAbsoluteEnable() },
                updateRange = { updateZoomAbsoluteLimit() },
                getValue = { getZoomAbsolute() },
                setValue = { setZoomAbsolute(it) },
                resetValue = { resetZoomAbsolute() },
            ),
            UsbIntControlSpec(
                key = KEY_POWERLINE,
                label = "防频闪",
                isSupported = { isPowerlineFrequencyEnable() },
                updateRange = { updatePowerlineFrequencyLimit() },
                getValue = { getPowerlineFrequency() },
                setValue = { setPowerlineFrequency(it) },
                resetValue = { resetPowerlineFrequency() },
            ),
        )
    }

    private fun applyUsbSettingsFromPanel() {
        val helper = cameraHelper
        val selectedOption = usbFormatOptions.getOrNull(binding.spinnerUsbVideoFormat.selectedItemPosition)
        val nextSize = selectedOption?.size?.clone() ?: selectedUsbPreviewSize ?: defaultPreviewSize()
        val nextPhotoQuality = parseIntSetting(binding.editUsbPhotoQuality.text?.toString(), 1, 100, "照片质量") ?: return
        val nextVideoFrameRate = parseIntSetting(binding.editUsbVideoFrameRate.text?.toString(), 1, 120, "录像帧率") ?: return
        val nextVideoBitRateMbps = parseFloatSetting(binding.editUsbVideoBitRate.text?.toString(), 0.1f, 200f, "录像码率") ?: return
        val nextIFrameInterval = parseIntSetting(binding.editUsbIFrameInterval.text?.toString(), 1, 30, "I 帧间隔") ?: return
        val nextRecordAudio = binding.checkUsbRecordAudio.isChecked
        val nextAudioBitRate = parseIntSetting(binding.editUsbAudioBitRate.text?.toString(), 8, 512, "音频码率") ?: return
        val nextAudioSampleRate = parseIntSetting(binding.editUsbAudioSampleRate.text?.toString(), 8000, 192000, "音频采样率") ?: return
        val nextAudioChannels = parseIntSetting(binding.editUsbAudioChannels.text?.toString(), 1, 2, "音频声道") ?: return
        val nextAudioMinBuffer = parseIntSetting(binding.editUsbAudioMinBuffer.text?.toString(), 256, 65536, "音频缓冲") ?: return
        if (helper?.isRecording == true) {
            Toast.makeText(this, "录像中不能切换拍摄参数，请先停止录像", Toast.LENGTH_SHORT).show()
            return
        }

        val activeSize = helper?.takeIf { it.isCameraOpened }?.previewSize?.clone()
            ?: selectedUsbPreviewSize
        selectedUsbPreviewSize = nextSize
        usbPhotoQuality = nextPhotoQuality
        usbVideoFrameRate = nextVideoFrameRate
        usbVideoBitRateMbps = nextVideoBitRateMbps
        usbIFrameInterval = nextIFrameInterval
        usbRecordAudio = nextRecordAudio
        usbAudioBitRateKbps = nextAudioBitRate
        usbAudioSampleRate = nextAudioSampleRate
        usbAudioChannels = nextAudioChannels
        usbAudioMinBuffer = nextAudioMinBuffer
        applyCaptureConfigs(helper)
        applyUsbImageControls(helper)
        if (helper?.isCameraOpened == true && activeSize?.hasSameConfig(nextSize) != true) {
            if (!reopenUsbPreviewForSize(helper, nextSize, activeSize)) {
                return
            }
        } else {
            preparePreviewTextureForSize(nextSize)
            resizeUsbPreviewView(nextSize)
        }
        updateUsbSettingsSummary()
        binding.textUsbCameraStatus.text = if (pendingUsbPreviewReopenSize != null) {
            "拍摄参数已保存，正在按 ${nextSize.formatLabel()} 重启预览..."
        } else {
            "拍摄参数已应用：${nextSize.formatLabel()} / JPEG ${usbPhotoQuality} / 录像 ${formatMbps(usbVideoBitRateMbps)} Mbps"
        }
        renderActionState()
    }

    private fun reopenUsbPreviewForSize(
        helper: ICameraHelper,
        size: Size,
        fallbackSize: Size? = null,
    ): Boolean {
        return runCatching {
            pendingUsbPreviewReopenSize = size.clone()
            pendingUsbPreviewFallbackSize = fallbackSize?.clone()
                ?.takeUnless { it.hasSameConfig(size) }
            lastUsbPreviewFrameAtMs = 0L
            removePreviewSurfaceIfNeeded()
            preparePreviewTextureForSize(size)
            resizeUsbPreviewView(size)
            helper.closeCamera()
            schedulePendingUsbPreviewReopen()
        }.onFailure { throwable ->
            Log.e(TAG, "apply preview size failed", throwable)
            pendingUsbPreviewReopenSize = null
            pendingUsbPreviewFallbackSize = null
            pendingUsbPreviewReopenScheduled = false
            binding.textUsbCameraStatus.text =
                "参数已保存，但切换预览失败：${throwable.message ?: throwable.javaClass.simpleName}"
            renderActionState()
        }.isSuccess
    }

    private fun scheduleUsbPreviewFrameValidation(
        appliedSize: Size,
        fallbackSize: Size?,
        hasRetriedSurface: Boolean = false,
        hasRetriedCamera: Boolean = false,
        markFormatFailedOnTimeout: Boolean = true,
    ) {
        val generation = ++usbPreviewValidationGeneration
        val validationStartAt = SystemClock.elapsedRealtime()
        binding.viewUsbCameraPreview.postDelayed({
            if (generation != usbPreviewValidationGeneration || isFinishing || isDestroyed) {
                return@postDelayed
            }
            if (lastUsbPreviewFrameAtMs >= validationStartAt) {
                pendingUsbPreviewFallbackSize = null
                previewRecoveryReopenKeys.remove(appliedSize.formatKey())
                binding.textUsbCameraStatus.text = "UVC 直连预览中：${appliedSize.formatLabel()}"
                renderActionState()
                return@postDelayed
            }
            if (!hasRetriedSurface && cameraHelper?.isCameraOpened == true) {
                binding.textUsbCameraStatus.text =
                    "${appliedSize.formatLabel()} 暂未出图，正在自动恢复预览 Surface..."
                refreshPreviewSurface(appliedSize)
                scheduleUsbPreviewFrameValidation(
                    appliedSize = appliedSize,
                    fallbackSize = fallbackSize,
                    hasRetriedSurface = true,
                    hasRetriedCamera = hasRetriedCamera,
                    markFormatFailedOnTimeout = markFormatFailedOnTimeout,
                )
                return@postDelayed
            }
            val helper = cameraHelper
            if (!hasRetriedCamera && helper?.isCameraOpened == true) {
                previewRecoveryReopenKeys.add(appliedSize.formatKey())
                binding.textUsbCameraStatus.text =
                    "${appliedSize.formatLabel()} 暂未出图，正在自动重启同一模式..."
                reopenUsbPreviewForSize(helper, appliedSize, fallbackSize)
                return@postDelayed
            }
            if (!markFormatFailedOnTimeout) {
                pendingUsbPreviewFallbackSize = null
                binding.textUsbCameraStatus.text = "${appliedSize.formatLabel()} 暂未出图，可等待或重新打开预览。"
                renderActionState()
                return@postDelayed
            }
            failedUsbFormatKeys.add(appliedSize.formatKey())
            if (helper?.isCameraOpened == true && fallbackSize != null) {
                selectedUsbPreviewSize = fallbackSize.clone()
                refreshUsbFormatOptions()
                binding.textUsbCameraStatus.text =
                    "${appliedSize.formatLabel()} 未输出预览帧，已临时隐藏并回退到 ${fallbackSize.formatLabel()}。"
                Toast.makeText(this, "该分辨率当前无法出图，已自动回退", Toast.LENGTH_SHORT).show()
                reopenUsbPreviewForSize(helper, fallbackSize)
            } else {
                refreshUsbFormatOptions()
                pendingUsbPreviewFallbackSize = null
                binding.textUsbCameraStatus.text = "${appliedSize.formatLabel()} 未输出预览帧，已临时隐藏该模式。"
                Toast.makeText(this, "该分辨率当前无法出图，已从列表临时隐藏", Toast.LENGTH_SHORT).show()
                renderActionState()
            }
        }, USB_PREVIEW_FRAME_TIMEOUT_MS)
    }

    private fun scheduleUsbPreviewSurfaceSettleRecovery(size: Size) {
        val generation = usbPreviewValidationGeneration
        val validationStartAt = SystemClock.elapsedRealtime()
        binding.viewUsbCameraPreview.postDelayed({
            if (generation != usbPreviewValidationGeneration || isFinishing || isDestroyed) {
                return@postDelayed
            }
            if (cameraHelper?.isCameraOpened != true || lastUsbPreviewFrameAtMs >= validationStartAt) {
                return@postDelayed
            }
            binding.textUsbCameraStatus.text = "${size.formatLabel()} 正在自动恢复预览画面..."
            refreshPreviewSurface(size)
        }, USB_PREVIEW_SURFACE_SETTLE_DELAY_MS)
    }

    private fun schedulePendingUsbPreviewReopen() {
        if (pendingUsbPreviewReopenScheduled) return
        pendingUsbPreviewReopenScheduled = true
        binding.viewUsbCameraPreview.postDelayed({
            pendingUsbPreviewReopenScheduled = false
            val pendingSize = pendingUsbPreviewReopenSize ?: return@postDelayed
            val currentHelper = cameraHelper ?: return@postDelayed
            if (isFinishing || isDestroyed) {
                return@postDelayed
            }
            runCatching {
                binding.textUsbCameraStatus.text = "正在打开 ${pendingSize.formatLabel()} 预览..."
                currentHelper.openCamera(pendingSize)
            }.onFailure { throwable ->
                Log.e(TAG, "reopen preview failed", throwable)
                pendingUsbPreviewReopenSize = null
                pendingUsbPreviewReopenScheduled = false
                binding.textUsbCameraStatus.text =
                    "参数已保存，但重启预览失败：${throwable.message ?: throwable.javaClass.simpleName}"
                renderActionState()
            }
        }, USB_PREVIEW_REOPEN_DELAY_MS)
    }

    private fun resetUsbSettings() {
        selectedUsbPreviewSize = defaultPreviewSize()
        usbPhotoQuality = DEFAULT_PHOTO_QUALITY
        usbVideoFrameRate = DEFAULT_VIDEO_FPS
        usbVideoBitRateMbps = DEFAULT_VIDEO_BITRATE_MBPS
        usbIFrameInterval = DEFAULT_IFRAME_INTERVAL_SECONDS
        usbRecordAudio = DEFAULT_RECORD_AUDIO
        usbAudioBitRateKbps = DEFAULT_AUDIO_BITRATE_KBPS
        usbAudioSampleRate = DEFAULT_AUDIO_SAMPLE_RATE
        usbAudioChannels = DEFAULT_AUDIO_CHANNELS
        usbAudioMinBuffer = DEFAULT_AUDIO_MIN_BUFFER
        applyCaptureConfigs(cameraHelper)
        resetUsbImageControls(cameraHelper)
        val helper = cameraHelper
        if (helper?.isCameraOpened == true && !helper.isRecording) {
            reopenUsbPreviewForSize(helper, selectedUsbPreviewSize ?: defaultPreviewSize())
        }
        renderUsbSettingsPanel()
        Toast.makeText(this, "已恢复默认拍摄参数", Toast.LENGTH_SHORT).show()
    }

    private fun applyCaptureConfigs(helper: ICameraHelper?) {
        helper ?: return
        runCatching {
            helper.setImageCaptureConfig(
                helper.getImageCaptureConfig()
                    .setJpegCompressionQuality(usbPhotoQuality),
            )
            helper.setVideoCaptureConfig(
                helper.getVideoCaptureConfig()
                    .setVideoFrameRate(usbVideoFrameRate)
                    .setBitRate((usbVideoBitRateMbps * 1024 * 1024).toInt())
                    .setIFrameInterval(usbIFrameInterval)
                    .setAudioCaptureEnable(usbRecordAudio)
                    .setAudioBitRate(usbAudioBitRateKbps * 1000)
                    .setAudioSampleRate(usbAudioSampleRate)
                    .setAudioChannelCount(usbAudioChannels)
                    .setAudioMinBufferSize(usbAudioMinBuffer),
            )
        }.onFailure { throwable ->
            Log.e(TAG, "apply capture config failed", throwable)
        }
    }

    private fun updateUsbSettingsSummary() {
        val size = selectedUsbPreviewSize ?: defaultPreviewSize()
        binding.textUsbSettingsSummary.text = buildString {
            appendLine("当前：${size.formatLabel()}，照片 JPEG $usbPhotoQuality")
            appendLine("录像：${usbVideoFrameRate}fps / ${formatMbps(usbVideoBitRateMbps)}Mbps / I帧 ${usbIFrameInterval}s")
            append("音频：${if (usbRecordAudio) "${usbAudioBitRateKbps}kbps ${usbAudioSampleRate}Hz ${usbAudioChannels}ch" else "不录音"}")
        }
    }

    private fun resizeUsbPreviewView(size: Size) {
        binding.viewUsbCameraPreview.post {
            val width = binding.viewUsbCameraPreview.width.takeIf { it > 0 } ?: return@post
            val nextHeight = (width * size.height.toFloat() / size.width.toFloat()).toInt()
                .coerceIn(dp(180), dp(420))
            val params = binding.viewUsbCameraPreview.layoutParams
            if (params.height != nextHeight) {
                params.height = nextHeight
                binding.viewUsbCameraPreview.layoutParams = params
            }
        }
    }

    private fun parseIntSetting(raw: String?, min: Int, max: Int, label: String): Int? {
        val value = raw?.trim()?.toIntOrNull()
        if (value == null || value !in min..max) {
            Toast.makeText(this, "$label 需要在 $min-$max 之间", Toast.LENGTH_SHORT).show()
            return null
        }
        return value
    }

    private fun parseFloatSetting(raw: String?, min: Float, max: Float, label: String): Float? {
        val value = raw?.trim()?.toFloatOrNull()
        if (value == null || value < min || value > max) {
            Toast.makeText(this, "$label 需要在 ${formatMbps(min)}-${formatMbps(max)} 之间", Toast.LENGTH_SHORT).show()
            return null
        }
        return value
    }

    private fun renderActionState() {
        val hasCamera = cameraHelper?.isCameraOpened == true
        val recording = cameraHelper?.isRecording == true
        val restartingPreview = pendingUsbPreviewReopenSize != null || pendingUsbPreviewReopenScheduled
        binding.buttonOpenUsbCamera.isEnabled = hasCameraPermission() && uvcDevices.isNotEmpty() && !recording && !hasCamera && !restartingPreview
        binding.buttonCaptureUsbPhoto.isEnabled = hasCamera && !recording
        binding.buttonStartUsbVideo.isEnabled = hasCamera && !recording
        binding.buttonStopUsbVideo.isEnabled = recording
        binding.buttonRefreshUsbCamera.isEnabled = !recording
        binding.buttonRefreshUsbAlbum.isEnabled = true
        binding.buttonApplyUsbSettings.isEnabled = !recording && !restartingPreview
        binding.buttonResetUsbSettings.isEnabled = !recording && !restartingPreview
        binding.spinnerUsbVideoFormat.isEnabled = !recording && !restartingPreview
    }

    private fun requestCameraPermissions() {
        val permissions = buildList {
            if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
            if (!hasAudioPermission()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasLegacyWritePermission()) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLegacyWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun scanUsbMedia(): List<UsbCameraMediaItem> {
        val dir = usbMediaDirectory()
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        return files.mapNotNull { file ->
            val mimeType = mimeTypeFor(file) ?: return@mapNotNull null
            UsbCameraMediaItem(
                file = file,
                mimeType = mimeType,
                lastModified = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                mediaInfo = readUsbMediaInfo(file, mimeType),
            )
        }.sortedWith(compareByDescending<UsbCameraMediaItem> { it.lastModified }.thenByDescending { it.fileName })
    }

    private fun usbMediaDirectory(): File {
        val root = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        return File(root, "usb_camera").apply { mkdirs() }
    }

    private fun mimeTypeFor(file: File): String? {
        return when (file.extension.lowercase(Locale.US)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            else -> null
        }
    }

    private fun readUsbMediaInfo(file: File, mimeType: String): UsbCameraMediaInfo {
        return if (mimeType.startsWith("video/")) {
            readUsbVideoInfo(file)
        } else {
            readUsbImageInfo(file)
        }
    }

    private fun readUsbImageInfo(file: File): UsbCameraMediaInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return UsbCameraMediaInfo(
            width = options.outWidth.coerceAtLeast(0),
            height = options.outHeight.coerceAtLeast(0),
        )
    }

    private fun readUsbVideoInfo(file: File): UsbCameraMediaInfo {
        return runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                val width = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotation = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val displayWidth = if (rotation == 90 || rotation == 270) height else width
                val displayHeight = if (rotation == 90 || rotation == 270) width else height
                UsbCameraMediaInfo(
                    width = displayWidth,
                    height = displayHeight,
                    frameRate = retriever.floatMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE),
                    durationMs = retriever.longMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                )
            }
        }.getOrElse {
            UsbCameraMediaInfo(width = 0, height = 0)
        }
    }

    private fun saveMediaToSystemGallery(source: File, mimeType: String): Boolean {
        if (!source.exists() || source.length() <= 0L) {
            Log.w(TAG, "skip saveMediaToSystemGallery source missing path=${source.absolutePath}")
            return false
        }
        val isVideo = mimeType.startsWith("video/")
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        var uri: Uri? = null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.SIZE, source.length())
                put(MediaStore.MediaColumns.DATE_MODIFIED, source.lastModified() / 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/VisionRoute/USB Camera",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    val baseDir = Environment.getExternalStoragePublicDirectory(
                        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
                    )
                    val targetFile = File(File(baseDir, "VisionRoute/USB Camera").apply { mkdirs() }, source.name)
                    put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
                }
            }
            uri = contentResolver.insert(collection, values) ?: error("MediaStore insert failed")
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
            true
        }.onFailure { throwable ->
            uri?.let { contentResolver.delete(it, null, null) }
            Log.e(TAG, "saveMediaToSystemGallery failed path=${source.absolutePath}", throwable)
        }.getOrDefault(false)
    }

    private fun emptyUsbGalleryText(): TextView {
        return TextView(this).apply {
            text = getString(R.string.text_usb_album_empty)
            setTextColor(0xFF6B7280.toInt())
            textSize = 12f
            setPadding(dp(8), dp(12), dp(8), dp(12))
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(0, 3),
            )
        }
    }

    private fun emptyAnnotationJsonText(): TextView {
        return TextView(this).apply {
            text = getString(R.string.text_usb_annotation_json_empty)
            setTextColor(0xFF6B7280.toInt())
            textSize = 12f
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
    }

    private fun usbGalleryTileParams(): GridLayout.LayoutParams {
        return GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
        ).apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(dp(3), dp(3), dp(3), dp(5))
        }
    }

    private fun formatUsbTimestamp(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))
    }

    private fun RecordingAnnotationJsonItem.annotationJsonInfoLine(): String {
        val status = if (pending) "未绑定视频" else "已绑定"
        return listOf(
            formatUsbTimestamp(lastModified),
            sourceLabel,
            "动作 ${actionCount} 条",
            status,
            formatFileSize(sizeBytes),
        ).filter { it.isNotBlank() }
            .joinToString(separator = " · ")
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> "${bytes.coerceAtLeast(0L)} B"
        }
    }

    private fun UsbCameraMediaItem.mediaInfoLine(): String {
        val type = if (isVideo) "视频" else "图片"
        return listOf(
            formatUsbTimestamp(lastModified),
            type,
            mediaInfo.shortLabel(isVideo),
        ).filter { it.isNotBlank() }
            .joinToString(separator = " · ")
    }

    private fun UsbCameraMediaItem.mediaDetailLine(): String {
        return mediaInfo.detailLabel(isVideo).ifBlank {
            if (isVideo) "视频参数未知" else "图片参数未知"
        }
    }

    private fun UsbCameraMediaInfo.shortLabel(isVideo: Boolean): String {
        val resolution = resolutionLabel()
        if (!isVideo) {
            return resolution
        }
        val fps = frameRateLabel()
        return listOf(resolution, fps)
            .filter { it.isNotBlank() }
            .joinToString(separator = " / ")
    }

    private fun UsbCameraMediaInfo.detailLabel(isVideo: Boolean): String {
        val parts = mutableListOf<String>()
        resolutionLabel().takeIf { it.isNotBlank() }?.let { parts.add("分辨率 $it") }
        if (isVideo) {
            frameRateLabel().takeIf { it.isNotBlank() }?.let { parts.add("帧率 $it") }
            durationLabel().takeIf { it.isNotBlank() }?.let { parts.add("时长 $it") }
        }
        return parts.joinToString(separator = "，")
    }

    private fun UsbCameraMediaInfo.resolutionLabel(): String {
        return if (hasResolution) "${width}×${height}" else ""
    }

    private fun UsbCameraMediaInfo.frameRateLabel(): String {
        val fps = frameRate?.takeIf { it > 0f } ?: return ""
        val formatted = if (fps % 1f == 0f) {
            fps.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", fps).trimEnd('0').trimEnd('.')
        }
        return "${formatted}fps"
    }

    private fun UsbCameraMediaInfo.durationLabel(): String {
        val duration = durationMs?.takeIf { it > 0L } ?: return ""
        val totalSeconds = (duration + 500L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) {
            "${minutes}分${seconds}秒"
        } else {
            "${seconds}秒"
        }
    }

    private fun MediaMetadataRetriever.intMetadata(keyCode: Int): Int {
        return extractMetadata(keyCode)?.toIntOrNull() ?: 0
    }

    private fun MediaMetadataRetriever.longMetadata(keyCode: Int): Long? {
        return extractMetadata(keyCode)?.toLongOrNull()
    }

    private fun MediaMetadataRetriever.floatMetadata(keyCode: Int): Float? {
        return extractMetadata(keyCode)?.toFloatOrNull()
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

    private fun defaultPreviewSize(): Size {
        return Size(
            UVCCamera.UVC_VS_FRAME_MJPEG,
            DEFAULT_PREVIEW_WIDTH,
            DEFAULT_PREVIEW_HEIGHT,
            DEFAULT_PREVIEW_FPS,
            listOf(DEFAULT_PREVIEW_FPS),
        )
    }

    private fun Size.safeFpsList(): List<Int> {
        val values = (fpsList ?: emptyList()).filter { it > 0 }.ifEmpty {
            listOf(fps.takeIf { it > 0 } ?: DEFAULT_PREVIEW_FPS)
        }
        return values.distinct()
    }

    private fun Size.hasSameConfig(other: Size): Boolean {
        return type == other.type && width == other.width && height == other.height && fps == other.fps
    }

    private fun Size.isPreferredPreviewConfig(): Boolean {
        return width == DEFAULT_PREVIEW_WIDTH && height == DEFAULT_PREVIEW_HEIGHT && fps == DEFAULT_PREVIEW_FPS
    }

    private fun preferredUsbFormatOptionIndex(options: List<UsbFormatOption>): Int? {
        val preferredMjpeg = options.indexOfFirst {
            it.size.isPreferredPreviewConfig() && it.size.type == UVCCamera.UVC_VS_FRAME_MJPEG
        }
        if (preferredMjpeg >= 0) return preferredMjpeg
        return options.indexOfFirst { it.size.isPreferredPreviewConfig() }
            .takeIf { it >= 0 }
    }

    private fun Size.formatKey(): String {
        return "$type:$width:$height:$fps"
    }

    private fun IntArray.toUsbControlRange(currentValue: Int): UsbControlRange {
        val min = getOrNull(0) ?: currentValue
        val max = getOrNull(1) ?: min
        val default = getOrNull(2) ?: currentValue.coerceIn(min.coerceAtMost(max), min.coerceAtLeast(max))
        val safeMin = min.coerceAtMost(max)
        val safeMax = min.coerceAtLeast(max)
        return UsbControlRange(
            min = safeMin,
            max = safeMax,
            default = default.coerceIn(safeMin, safeMax),
        )
    }

    private fun Size.formatName(): String {
        return when (type) {
            UVCCamera.UVC_VS_FRAME_MJPEG -> "MJPEG"
            UVCCamera.UVC_VS_FRAME_UNCOMPRESSED -> "YUYV"
            else -> "格式$type"
        }
    }

    private fun Size.formatLabel(): String {
        val divisor = gcd(width, height).coerceAtLeast(1)
        val ratio = "${width / divisor}:${height / divisor}"
        return "${width}x$height（$ratio） ${formatName()} @ ${fps}fps"
    }

    private fun gcd(a: Int, b: Int): Int {
        var left = kotlin.math.abs(a)
        var right = kotlin.math.abs(b)
        while (right != 0) {
            val temp = left % right
            left = right
            right = temp
        }
        return left
    }

    private fun formatMbps(value: Float): String {
        return if (value % 1f == 0f) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    private fun outputFile(prefix: String, extension: String): File {
        val dir = usbMediaDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "${prefix}_$timestamp.$extension")
    }

    private fun summarizeUsbDevices(): String {
        val devices = readUsbDevices()
        if (devices.isEmpty()) return "OTG USB：未检测到 USB 设备"
        return buildString {
            appendLine("OTG USB：检测到 ${devices.size} 个设备 / UVC ${uvcDevices.size} 个")
            devices.take(6).forEach { device ->
                appendLine(
                    "· ${device.deviceName} vendor=${device.vendorId} product=${device.productId} class=${device.deviceClass}",
                )
                appendLine("  interfaces=${device.interfaceSummary()}")
            }
        }.trim()
    }

    private fun readUsbDevices(): List<UsbDevice> {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values.toList()
    }

    private fun UsbDevice.isLikelyUvcCamera(): Boolean {
        if (deviceClass == UsbConstants.USB_CLASS_VIDEO || deviceClass == UsbConstants.USB_CLASS_MISC) return true
        for (index in 0 until interfaceCount) {
            val usbInterface = getInterface(index)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_VIDEO || usbInterface.interfaceClass == UsbConstants.USB_CLASS_MISC) {
                return true
            }
        }
        return false
    }

    private fun UsbDevice.interfaceSummary(): String {
        if (interfaceCount == 0) return "none"
        return (0 until interfaceCount).joinToString { index ->
            val usbInterface = getInterface(index)
            "#$index class=${usbInterface.interfaceClass} subclass=${usbInterface.interfaceSubclass}"
        }
    }

    private data class UsbVideoHudAction(
        val type: String,
        val label: String,
        val elapsedMs: Long,
        val durationMs: Long,
    )

    private companion object {
        private const val TAG = "UsbCameraDebug"
        private const val DEFAULT_PREVIEW_WIDTH = 1920
        private const val DEFAULT_PREVIEW_HEIGHT = 1080
        private const val DEFAULT_PREVIEW_FPS = 30
        private const val DEFAULT_PHOTO_QUALITY = 90
        private const val DEFAULT_VIDEO_FPS = 30
        private const val DEFAULT_VIDEO_BITRATE_MBPS = 8f
        private const val DEFAULT_IFRAME_INTERVAL_SECONDS = 1
        private const val DEFAULT_RECORD_AUDIO = true
        private const val DEFAULT_AUDIO_BITRATE_KBPS = 64
        private const val DEFAULT_AUDIO_SAMPLE_RATE = 8000
        private const val DEFAULT_AUDIO_CHANNELS = 1
        private const val DEFAULT_AUDIO_MIN_BUFFER = 1024
        private const val USB_PREVIEW_REOPEN_DELAY_MS = 700L
        private const val USB_PREVIEW_SURFACE_REATTACH_DELAY_MS = 250L
        private const val USB_PREVIEW_SURFACE_SETTLE_DELAY_MS = 900L
        private const val USB_PREVIEW_FRAME_TIMEOUT_MS = 4_000L
        private const val USB_VIDEO_HUD_TICK_MS = 200L
        private const val KEY_WHITE_BALANCE_AUTO = "white_balance_auto"
        private const val KEY_WHITE_BALANCE = "white_balance"
        private const val KEY_EXPOSURE_AUTO = "exposure_auto"
        private const val KEY_EXPOSURE_TIME = "exposure_time"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_CONTRAST = "contrast"
        private const val KEY_SATURATION = "saturation"
        private const val KEY_GAIN = "gain"
        private const val KEY_SHARPNESS = "sharpness"
        private const val KEY_GAMMA = "gamma"
        private const val KEY_BACKLIGHT = "backlight"
        private const val KEY_FOCUS_AUTO = "focus_auto"
        private const val KEY_FOCUS = "focus"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_POWERLINE = "powerline"
    }
}
