package com.rokid.cxrswithcxrl.activities.main

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Size
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RokidRecordEvent(
    val event: String,
    val ok: Boolean,
    val message: String,
    val fileName: String = "",
    val filePath: String = "",
    val sizeBytes: Long = 0L,
    val startTimeMs: Long = 0L,
    val stopTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
)

class RokidVideoRecordController(
    private val context: Context,
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var activeFile: File? = null
    private var activeSize: Size = Size(1280, 720)
    private var activeStartTimeMs: Long = 0L
    private var recording = false

    @SuppressLint("MissingPermission")
    fun start(onEvent: (RokidRecordEvent) -> Unit) {
        if (recording) {
            onEvent(error("已经在录像中"))
            return
        }
        val cameraId = chooseCameraId()
        if (cameraId == null) {
            onEvent(error("未找到可用摄像头"))
            return
        }
        activeSize = chooseRecordSize(cameraId)
        val outputFile = createOutputFile()
        activeFile = outputFile
        val recorder = runCatching { createMediaRecorder(outputFile, activeSize) }
            .getOrElse { throwable ->
                onEvent(error("初始化 MediaRecorder 失败：${throwable.message ?: throwable.javaClass.simpleName}"))
                return
            }
        mediaRecorder = recorder
        runCatching {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createRecordSession(camera, recorder, onEvent)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cleanup()
                        onEvent(error("摄像头已断开"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cleanup()
                        onEvent(error("摄像头打开失败：$error"))
                    }
                },
                handler,
            )
        }.onFailure { throwable ->
            cleanup()
            onEvent(error("打开摄像头失败：${throwable.message ?: throwable.javaClass.simpleName}"))
        }
    }

    fun stop(onEvent: (RokidRecordEvent) -> Unit) {
        if (!recording) {
            onEvent(error("当前没有正在进行的录像"))
            return
        }
        val output = activeFile
        val startMs = activeStartTimeMs
        val stopMs = System.currentTimeMillis()
        val stopResult = runCatching {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            mediaRecorder?.stop()
        }
        cleanup()
        if (stopResult.isFailure || output == null) {
            output?.delete()
            onEvent(error("停止录像失败：${stopResult.exceptionOrNull()?.message ?: "未生成文件"}"))
            return
        }
        onEvent(
            RokidRecordEvent(
                event = "RECORD_STOPPED",
                ok = true,
                message = "录像已保存",
                fileName = output.name,
                filePath = output.absolutePath,
                sizeBytes = output.length(),
                startTimeMs = startMs,
                stopTimeMs = stopMs,
                durationMs = stopMs - startMs,
                width = activeSize.width,
                height = activeSize.height,
            ),
        )
    }

    fun release() {
        cleanup()
    }

    fun isRecording(): Boolean = recording

    private fun createRecordSession(
        camera: CameraDevice,
        recorder: MediaRecorder,
        onEvent: (RokidRecordEvent) -> Unit,
    ) {
        val recorderSurface = recorder.surface
        runCatching {
            camera.createCaptureSession(
                listOf(recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        runCatching {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(recorderSurface)
                            }.build()
                            session.setRepeatingRequest(request, null, handler)
                            recorder.start()
                            activeStartTimeMs = System.currentTimeMillis()
                            recording = true
                            val output = activeFile
                            onEvent(
                                RokidRecordEvent(
                                    event = "RECORD_STARTED",
                                    ok = true,
                                    message = "录像已开始",
                                    fileName = output?.name.orEmpty(),
                                    filePath = output?.absolutePath.orEmpty(),
                                    startTimeMs = activeStartTimeMs,
                                    width = activeSize.width,
                                    height = activeSize.height,
                                ),
                            )
                        }.onFailure { throwable ->
                            cleanup()
                            onEvent(error("启动录像失败：${throwable.message ?: throwable.javaClass.simpleName}"))
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cleanup()
                        onEvent(error("创建录像会话失败"))
                    }
                },
                handler,
            )
        }.onFailure { throwable ->
            cleanup()
            onEvent(error("配置录像会话失败：${throwable.message ?: throwable.javaClass.simpleName}"))
        }
    }

    private fun chooseCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
    }

    private fun chooseRecordSize(cameraId: String): Size {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaRecorder::class.java).orEmpty()
        return sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
    }

    private fun createMediaRecorder(output: File, size: Size): MediaRecorder {
        return MediaRecorder(context).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(output.absolutePath)
            setVideoEncodingBitRate(8_000_000)
            setVideoFrameRate(30)
            setVideoSize(size.width, size.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            prepare()
        }
    }

    private fun createOutputFile(): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(baseDir, "VisionRouteRokid")
            .apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "rokid_record_$stamp.mp4")
    }

    private fun cleanup() {
        recording = false
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
    }

    private fun error(message: String): RokidRecordEvent {
        return RokidRecordEvent(
            event = "RECORD_ERROR",
            ok = false,
            message = message,
        )
    }
}
