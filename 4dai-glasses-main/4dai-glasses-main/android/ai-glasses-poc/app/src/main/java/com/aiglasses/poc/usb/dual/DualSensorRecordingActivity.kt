package com.aiglasses.poc.usb.dual

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aiglasses.poc.databinding.ActivityDualSensorRecordingBinding
import java.io.File
import java.util.Locale

class DualSensorRecordingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDualSensorRecordingBinding
    private lateinit var registry: UsbDeviceRegistry
    private lateinit var imuController: UsbCdcImuController
    private lateinit var leftCamera: UvcCameraSlotController
    private lateinit var rightCamera: UvcCameraSlotController
    private lateinit var sessionController: DualSensorSessionController
    private val mainHandler = Handler(Looper.getMainLooper())
    private var snapshot = UsbDeviceSnapshot(emptyList(), emptyList(), emptyList())
    private var logs: List<String> = listOf("双 UVC + USB IMU 采集页已就绪")
    private var leftState = UvcCameraSlotState(role = "left")
    private var rightState = UvcCameraSlotState(role = "right")
    private var imuState = ImuRuntimeState()
    private var receiverRegistered = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        renderAll()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    addLog("usb_permission device=${device?.deviceName.orEmpty()} granted=$granted")
                    refreshDevices(keepSelection = true)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    addLog("usb_event ${intent.action}")
                    refreshDevices(keepSelection = false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDualSensorRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        registry = UsbDeviceRegistry(this)
        sessionController = DualSensorSessionController(this)
        imuController = UsbCdcImuController(
            context = this,
            onState = { state -> runOnUiThread { imuState = state; renderAll() } },
            onLog = ::addLog,
        )
        leftCamera = UvcCameraSlotController(
            context = this,
            role = "left",
            textureView = binding.textureLeftCamera,
            onState = { state -> runOnUiThread { leftState = state; renderAll(); maybeFinalizeSession() } },
            onFrame = ::recordCameraFrame,
            onLog = ::addLog,
        )
        rightCamera = UvcCameraSlotController(
            context = this,
            role = "right",
            textureView = binding.textureRightCamera,
            onState = { state -> runOnUiThread { rightState = state; renderAll(); maybeFinalizeSession() } },
            onFrame = ::recordCameraFrame,
            onLog = ::addLog,
        )
        bindActions()
        registerUsbReceiver()
        refreshDevices(keepSelection = false)
        startStatusTicker()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (sessionController.isRecording) {
            stopRecording(forceIncomplete = true)
        }
        leftCamera.close()
        rightCamera.close()
        imuController.close()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun bindActions() {
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonRefreshDevices.setOnClickListener { refreshDevices(keepSelection = true) }
        binding.buttonRequestUsbPermission.setOnClickListener { requestSelectedUsbPermissions() }
        binding.buttonSwapCameras.setOnClickListener { swapCameraSelection() }
        binding.buttonOpenPreview.setOnClickListener { openSelectedDevices() }
        binding.buttonSetImu200Hz.setOnClickListener { imuController.setTemporary200Hz() }
        binding.buttonReadImuRate.setOnClickListener { imuController.readRateRegister() }
        binding.buttonStartRecording.setOnClickListener { startRecording() }
        binding.buttonStopRecording.setOnClickListener { stopRecording(forceIncomplete = false) }
        binding.buttonExportZip.setOnClickListener { exportZip() }
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

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun refreshDevices(keepSelection: Boolean) {
        val oldLeft = if (keepSelection) selectedCameraDescriptor(binding.spinnerLeftCamera.selectedItemPosition)?.fingerprint else null
        val oldRight = if (keepSelection) selectedCameraDescriptor(binding.spinnerRightCamera.selectedItemPosition)?.fingerprint else null
        val oldImu = if (keepSelection) selectedImuDescriptor()?.fingerprint else null
        snapshot = registry.snapshot()
        val cameraLabels = snapshot.cameras.mapIndexed { index, descriptor ->
            "相机 ${index + 1} · ${descriptor.label} · ${if (descriptor.hasPermission) "已授权" else "未授权"}"
        }.ifEmpty { listOf("未发现 UVC 摄像头") }
        val imuLabels = snapshot.imuCandidates.mapIndexed { index, descriptor ->
            "IMU ${index + 1} · ${descriptor.label} · ${if (descriptor.hasPermission) "已授权" else "未授权"}"
        }.ifEmpty { listOf("未发现 WT901 USB IMU") }
        binding.spinnerLeftCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraLabels)
        binding.spinnerRightCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraLabels)
        binding.spinnerImu.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, imuLabels)
        selectSpinnerByFingerprint(binding.spinnerLeftCamera, snapshot.cameras, oldLeft, fallback = 0)
        selectSpinnerByFingerprint(binding.spinnerRightCamera, snapshot.cameras, oldRight, fallback = if (snapshot.cameras.size > 1) 1 else 0)
        selectSpinnerByFingerprint(binding.spinnerImu, snapshot.imuCandidates, oldImu, fallback = 0)
        renderAll()
    }

    private fun requestSelectedUsbPermissions() {
        selectedDevices().filterNot { registry.hasPermission(it) }.forEach { device ->
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(this, device.deviceId, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
            (getSystemService(Context.USB_SERVICE) as UsbManager).requestPermission(device, intent)
        }
        if (selectedDevices().all { registry.hasPermission(it) }) {
            Toast.makeText(this, "所选 USB 设备已授权", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSelectedDevices() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        requestSelectedUsbPermissions()
        val left = selectedCameraDescriptor(binding.spinnerLeftCamera.selectedItemPosition)
        val right = selectedCameraDescriptor(binding.spinnerRightCamera.selectedItemPosition)
        val imu = selectedImuDescriptor()
        if (left == null || right == null || left.device.deviceName == right.device.deviceName) {
            Toast.makeText(this, "请选择两路不同 UVC 摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        if (left.hasPermission) {
            leftCamera.open(left.device, left.label, left.fingerprint)
        }
        if (right.hasPermission) {
            rightCamera.open(right.device, right.label, right.fingerprint)
        }
        if (imu != null && imu.hasPermission) {
            imuController.connect(imu.device)
        }
        renderAll()
    }

    private fun startRecording() {
        if (sessionController.isRecording) return
        val left = selectedCameraDescriptor(binding.spinnerLeftCamera.selectedItemPosition)
        val right = selectedCameraDescriptor(binding.spinnerRightCamera.selectedItemPosition)
        val imu = selectedImuDescriptor()
        if (left == null || right == null || left.device.deviceName == right.device.deviceName) {
            Toast.makeText(this, "需要两路不同 UVC 摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        if (!leftState.opened || !rightState.opened) {
            Toast.makeText(this, "请先打开双路预览", Toast.LENGTH_SHORT).show()
            return
        }
        if (!imuState.connected) {
            Toast.makeText(this, "请先连接 USB IMU", Toast.LENGTH_SHORT).show()
            return
        }
        val config = DualRecordingConfig()
        val session = sessionController.start(
            config = config,
            left = CameraSessionDeviceInfo("left", left.fingerprint, left.label, leftState.width.takeIf { it > 0 } ?: config.width, leftState.height.takeIf { it > 0 } ?: config.height, config.cameraTargetFps),
            right = CameraSessionDeviceInfo("right", right.fingerprint, right.label, rightState.width.takeIf { it > 0 } ?: config.width, rightState.height.takeIf { it > 0 } ?: config.height, config.cameraTargetFps),
            imu = imu?.let { ImuSessionDeviceInfo(it.fingerprint, it.label, config.imuTargetHz) },
        )
        imuController.startRecording(session)
        leftCamera.startRecording(session, session.leftVideoFile)
        rightCamera.startRecording(session, session.rightVideoFile)
        addLog("recording_started session=${session.sessionId}")
        renderAll()
    }

    private fun stopRecording(forceIncomplete: Boolean) {
        val session = sessionController.stop(if (forceIncomplete) "incomplete" else "complete") ?: return
        if (forceIncomplete) session.markIncomplete("activity_destroyed")
        imuController.stopRecording()
        leftCamera.stopRecording()
        rightCamera.stopRecording()
        mainHandler.postDelayed({ maybeFinalizeSession(force = forceIncomplete) }, if (forceIncomplete) 500L else 4_000L)
        renderAll()
    }

    private fun maybeFinalizeSession(force: Boolean = false) {
        val dir = sessionController.finalizeIfReady(force) ?: return
        addLog("session_finalized path=${dir.absolutePath}")
        Toast.makeText(this, "Session 已保存：${dir.name}", Toast.LENGTH_SHORT).show()
        renderAll()
    }

    private fun exportZip() {
        val zip = runCatching { sessionController.exportLastSessionZip() }.getOrElse { throwable ->
            Toast.makeText(this, "导出失败：${throwable.message ?: throwable.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            null
        }
        if (zip == null) {
            Toast.makeText(this, "暂无可导出的 Session", Toast.LENGTH_SHORT).show()
        } else {
            addLog("zip_exported ${zip.absolutePath}")
            Toast.makeText(this, "已导出：${zip.absolutePath}", Toast.LENGTH_LONG).show()
        }
        renderAll()
    }

    private fun recordCameraFrame(role: String, record: CameraFrameRecord) {
        sessionController.current()?.recordCameraFrame(role, record)
    }

    private fun swapCameraSelection() {
        val left = binding.spinnerLeftCamera.selectedItemPosition
        val right = binding.spinnerRightCamera.selectedItemPosition
        binding.spinnerLeftCamera.setSelection(right.coerceAtLeast(0))
        binding.spinnerRightCamera.setSelection(left.coerceAtLeast(0))
    }

    private fun renderAll() {
        binding.textPermission.text = "权限：相机 ${if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) "已授权" else "未授权"}"
        binding.textDeviceSummary.text = buildString {
            appendLine("检测：USB ${snapshot.allDevices.size} 个 · UVC ${snapshot.cameras.size} 个 · IMU ${snapshot.imuCandidates.size} 个")
            snapshot.allDevices.take(6).forEach { descriptor ->
                appendLine("${descriptor.label} · ${if (descriptor.hasPermission) "已授权" else "未授权"} · ${descriptor.interfaceSummary}")
            }
        }.trim()
        binding.textLeftCamera.text = leftState.summary("左相机")
        binding.textRightCamera.text = rightState.summary("右相机")
        binding.textImu.text = imuState.summary()
        binding.textRecordingStatus.text = recordingSummary()
        binding.textLog.text = logs.joinToString("\n")
        val recording = sessionController.isRecording
        binding.buttonStartRecording.isEnabled = !recording
        binding.buttonStopRecording.isEnabled = recording
        binding.buttonExportZip.isEnabled = !recording && sessionController.lastCompletedSessionDir != null
        binding.buttonOpenPreview.isEnabled = !recording
        binding.buttonSwapCameras.isEnabled = !recording
    }

    private fun recordingSummary(): String {
        val session = sessionController.current()
        val last = sessionController.lastCompletedSessionDir
        return buildString {
            if (session != null) {
                appendLine("状态：录制中 · ${session.sessionId}")
                appendLine("时长：${formatDurationMs(session.durationNs / 1_000_000L)}")
                appendLine("剩余空间：${formatBytes(session.storageFreeBytes())}")
                appendLine("Session：${session.sessionDir.absolutePath}")
            } else {
                appendLine("状态：未录制")
                appendLine("最近 Session：${last?.absolutePath ?: "暂无"}")
                appendLine("目标：双 720p30 + IMU 200Hz，软件时间戳对齐")
            }
        }.trim()
    }

    private fun startStatusTicker() {
        mainHandler.postDelayed(
            object : Runnable {
                override fun run() {
                    renderAll()
                    if (sessionController.isRecording) maybeFinalizeSession()
                    mainHandler.postDelayed(this, 500L)
                }
            },
            500L,
        )
    }

    private fun selectedCameraDescriptor(position: Int): UsbDeviceDescriptor? = snapshot.cameras.getOrNull(position)

    private fun selectedImuDescriptor(): UsbDeviceDescriptor? = snapshot.imuCandidates.getOrNull(binding.spinnerImu.selectedItemPosition)

    private fun selectedDevices(): List<UsbDevice> {
        return listOfNotNull(
            selectedCameraDescriptor(binding.spinnerLeftCamera.selectedItemPosition)?.device,
            selectedCameraDescriptor(binding.spinnerRightCamera.selectedItemPosition)?.device,
            selectedImuDescriptor()?.device,
        ).distinctBy { it.deviceName }
    }

    private fun selectSpinnerByFingerprint(
        spinner: android.widget.Spinner,
        descriptors: List<UsbDeviceDescriptor>,
        fingerprint: String?,
        fallback: Int,
    ) {
        if (descriptors.isEmpty()) return
        val index = fingerprint?.let { saved -> descriptors.indexOfFirst { it.fingerprint == saved } }?.takeIf { it >= 0 }
            ?: fallback.coerceIn(descriptors.indices)
        spinner.setSelection(index)
    }

    private fun addLog(message: String) {
        runOnUiThread {
            logs = (listOf(message) + logs).take(80)
            binding.textLog.text = logs.joinToString("\n")
        }
    }

    private fun formatDurationMs(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = durationMs % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return "%.2f GB".format(Locale.US, gb)
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.aiglasses.poc.usb.dual.USB_PERMISSION"
    }
}
