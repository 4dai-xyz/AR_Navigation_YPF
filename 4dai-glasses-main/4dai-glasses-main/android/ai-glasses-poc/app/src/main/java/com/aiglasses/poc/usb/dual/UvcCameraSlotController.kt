package com.aiglasses.poc.usb.dual

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.VideoCapture
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

data class UvcCameraSlotState(
    val role: String,
    val opened: Boolean = false,
    val recording: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val targetFps: Int = 30,
    val observedFps: Double = 0.0,
    val frameCount: Long = 0L,
    val status: String = "未打开",
    val deviceLabel: String = "",
    val fingerprint: String = "",
) {
    fun summary(title: String): String {
        return buildString {
            appendLine("$title：${if (opened) "已打开" else "未打开"} · ${if (recording) "录像中" else "未录像"}")
            appendLine("画面：${if (width > 0 && height > 0) "${width}x${height}" else "-"} · 目标 ${targetFps}fps · 实际 ${"%.1f".format(Locale.US, observedFps)}fps")
            appendLine("帧数：$frameCount")
            if (deviceLabel.isNotBlank()) appendLine("设备：$deviceLabel")
            append(status)
        }
    }
}

class UvcCameraSlotController(
    private val context: Context,
    private val role: String,
    private val textureView: TextureView,
    private val onState: (UvcCameraSlotState) -> Unit,
    private val onFrame: (String, CameraFrameRecord) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private var helper: ICameraHelper? = null
    private var selectedDevice: UsbDevice? = null
    private var selectedFingerprint: String = ""
    private var selectedLabel: String = ""
    private var previewTexture: SurfaceTexture? = null
    private var previewTextureAdded = false
    private var session: DualSensorSessionController.ActiveSession? = null
    private var frameIndex = 0L
    private val frameWindowNs = ArrayDeque<Long>()
    private var latestState = UvcCameraSlotState(role = role)
    private val targetSize = Size(
        UVCCamera.UVC_VS_FRAME_MJPEG,
        DEFAULT_WIDTH,
        DEFAULT_HEIGHT,
        DEFAULT_FPS,
        listOf(DEFAULT_FPS),
    )

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            previewTexture = surface
            addSurfaceIfNeeded()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            removeSurfaceIfNeeded(surface)
            previewTexture = null
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            frameIndex += 1
            frameWindowNs += nowNs
            while (frameWindowNs.isNotEmpty() && nowNs - frameWindowNs.first() > 1_000_000_000L) {
                frameWindowNs.removeFirst()
            }
            val fps = observedFps(nowNs)
            val size = helper?.previewSize
            updateState(
                frameCount = frameIndex,
                observedFps = fps,
                width = size?.width ?: latestState.width,
                height = size?.height ?: latestState.height,
            )
            val active = session ?: return
            onFrame(
                role,
                CameraFrameRecord(
                    frameIndex = frameIndex,
                    frameElapsedNs = nowNs,
                    arrivalElapsedNs = nowNs,
                    presentationTimeUs = (nowNs - active.t0Ns) / 1000L,
                    width = size?.width ?: DEFAULT_WIDTH,
                    height = size?.height ?: DEFAULT_HEIGHT,
                    format = size?.formatName() ?: "MJPEG",
                    observedFpsWindow = fps,
                    estimated = false,
                ),
            )
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) = Unit

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            val size = preferredSize()
            runCatching { helper?.openCamera(size) }
                .onFailure { throwable -> updateState(status = "打开相机失败：${throwable.message ?: throwable.javaClass.simpleName}") }
        }

        override fun onCameraOpen(device: UsbDevice) {
            applyVideoConfig()
            addSurfaceIfNeeded()
            val size = helper?.previewSize ?: preferredSize()
            updateState(
                opened = true,
                recording = helper?.isRecording == true,
                width = size.width,
                height = size.height,
                targetFps = DEFAULT_FPS,
                status = "预览中",
                deviceLabel = selectedLabel,
                fingerprint = selectedFingerprint,
            )
            onLog("$role camera_open ${size.width}x${size.height}")
        }

        override fun onCameraClose(device: UsbDevice) {
            updateState(opened = false, recording = false, status = "相机已关闭")
        }

        override fun onDeviceClose(device: UsbDevice) {
            updateState(opened = false, recording = false, status = "USB 设备已关闭")
        }

        override fun onDetach(device: UsbDevice) {
            if (device.deviceName == selectedDevice?.deviceName) {
                updateState(opened = false, recording = false, status = "设备已拔出")
            }
        }

        override fun onCancel(device: UsbDevice) {
            updateState(status = "USB 授权已取消")
        }

        override fun onError(device: UsbDevice?, e: CameraException?) {
            updateState(opened = false, recording = false, status = "相机错误：${e?.message ?: "unknown"}")
        }
    }

    fun initialize() {
        if (helper != null) return
        helper = CameraHelper().apply { setStateCallback(stateCallback) }
        textureView.surfaceTextureListener = textureListener
        applyVideoConfig()
    }

    fun open(device: UsbDevice, label: String, fingerprint: String) {
        initialize()
        selectedDevice = device
        selectedLabel = label
        selectedFingerprint = fingerprint
        frameIndex = 0L
        frameWindowNs.clear()
        updateState(status = "正在请求 USB 授权并打开预览", deviceLabel = label, fingerprint = fingerprint)
        helper?.selectDevice(device)
    }

    fun startRecording(activeSession: DualSensorSessionController.ActiveSession, outputFile: File) {
        val cameraHelper = helper
        if (cameraHelper?.isCameraOpened != true) {
            activeSession.markVideoDone(role, ok = false, message = "camera_not_open")
            updateState(status = "未打开，无法录像")
            return
        }
        if (cameraHelper.isRecording) return
        session = activeSession
        frameIndex = 0L
        frameWindowNs.clear()
        applyVideoConfig()
        val options = VideoCapture.OutputFileOptions.Builder(outputFile).build()
        cameraHelper.startRecording(
            options,
            object : VideoCapture.OnVideoCaptureCallback {
                override fun onStart() {
                    activeSession.markVideoStarted(role)
                    updateState(recording = true, status = "录像中：${outputFile.name}")
                }

                override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                    session = null
                    activeSession.markVideoDone(role, ok = true, message = outputFile.absolutePath)
                    updateState(recording = false, status = "录像已保存：${outputFile.name}")
                }

                override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                    session = null
                    activeSession.markVideoDone(role, ok = false, message = message)
                    updateState(recording = false, status = "录像失败：$message")
                    Log.e(TAG, "$role recording error $videoCaptureError $message", cause)
                }
            },
        )
    }

    fun stopRecording() {
        val cameraHelper = helper ?: return
        if (cameraHelper.isRecording) {
            updateState(status = "正在停止录像…")
            runCatching { cameraHelper.stopRecording() }
                .onFailure { throwable -> updateState(recording = false, status = "停止录像失败：${throwable.message ?: throwable.javaClass.simpleName}") }
        }
    }

    fun close() {
        session = null
        removeSurfaceIfNeeded()
        runCatching { helper?.closeCamera() }
        runCatching { helper?.release() }
        helper = null
        updateState(opened = false, recording = false, status = "已释放")
    }

    fun state(): UvcCameraSlotState = latestState

    private fun preferredSize(): Size {
        val supported = runCatching { helper?.getSupportedSizeList().orEmpty() }.getOrDefault(emptyList())
        return supported.firstOrNull {
            it.width == DEFAULT_WIDTH && it.height == DEFAULT_HEIGHT && it.fps >= DEFAULT_FPS && it.type == UVCCamera.UVC_VS_FRAME_MJPEG
        }?.clone()
            ?: supported.firstOrNull { it.width == DEFAULT_WIDTH && it.height == DEFAULT_HEIGHT }?.clone()
            ?: targetSize.clone()
    }

    private fun addSurfaceIfNeeded() {
        val cameraHelper = helper ?: return
        val texture = previewTexture ?: textureView.surfaceTexture ?: return
        if (!cameraHelper.isCameraOpened || previewTextureAdded) return
        val size = cameraHelper.previewSize ?: preferredSize()
        texture.setDefaultBufferSize(size.width, size.height)
        cameraHelper.addSurface(texture, false)
        previewTextureAdded = true
    }

    private fun removeSurfaceIfNeeded(surface: SurfaceTexture? = previewTexture) {
        if (!previewTextureAdded || surface == null) return
        runCatching { helper?.removeSurface(surface) }
        previewTextureAdded = false
    }

    private fun applyVideoConfig() {
        val cameraHelper = helper ?: return
        runCatching {
            cameraHelper.setVideoCaptureConfig(
                cameraHelper.getVideoCaptureConfig()
                    .setVideoFrameRate(DEFAULT_FPS)
                    .setBitRate((DEFAULT_BITRATE_MBPS * 1024 * 1024).toInt())
                    .setIFrameInterval(DEFAULT_IFRAME_INTERVAL_SECONDS)
                    .setAudioCaptureEnable(false),
            )
        }.onFailure { throwable -> onLog("$role apply_video_config_failed ${throwable.message ?: throwable.javaClass.simpleName}") }
    }

    private fun observedFps(nowNs: Long): Double {
        if (frameWindowNs.size < 2) return 0.0
        val spanNs = nowNs - frameWindowNs.first()
        return if (spanNs > 0) (frameWindowNs.size - 1) * 1_000_000_000.0 / spanNs else 0.0
    }

    private fun updateState(
        opened: Boolean = latestState.opened,
        recording: Boolean = latestState.recording,
        width: Int = latestState.width,
        height: Int = latestState.height,
        targetFps: Int = latestState.targetFps,
        observedFps: Double = latestState.observedFps,
        frameCount: Long = latestState.frameCount,
        status: String = latestState.status,
        deviceLabel: String = latestState.deviceLabel,
        fingerprint: String = latestState.fingerprint,
    ) {
        latestState = UvcCameraSlotState(
            role = role,
            opened = opened,
            recording = recording,
            width = width,
            height = height,
            targetFps = targetFps,
            observedFps = observedFps,
            frameCount = frameCount,
            status = status,
            deviceLabel = deviceLabel,
            fingerprint = fingerprint,
        )
        onState(latestState)
    }

    private fun Size.formatName(): String {
        return when (type) {
            UVCCamera.UVC_VS_FRAME_MJPEG -> "MJPEG"
            UVCCamera.UVC_VS_FRAME_UNCOMPRESSED -> "YUYV"
            else -> "format_$type"
        }
    }

    companion object {
        private const val TAG = "UvcCameraSlot"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_FPS = 30
        private const val DEFAULT_BITRATE_MBPS = 8f
        private const val DEFAULT_IFRAME_INTERVAL_SECONDS = 1
    }
}
