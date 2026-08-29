package com.aiglasses.poc.usb.dual

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class ImuSample(
    val index: Long,
    val sampleElapsedNs: Long,
    val readEndElapsedNs: Long,
    val batchSize: Int,
    val accXG: Double,
    val accYG: Double,
    val accZG: Double,
    val gyroXDps: Double,
    val gyroYDps: Double,
    val gyroZDps: Double,
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
    val rawHex: String,
) {
    fun csvLine(t0Ns: Long): String {
        val relativeMs = (sampleElapsedNs - t0Ns) / 1_000_000.0
        return listOf(
            index,
            sampleElapsedNs,
            "%.3f".format(Locale.US, relativeMs),
            readEndElapsedNs,
            batchSize,
            "%.6f".format(Locale.US, accXG),
            "%.6f".format(Locale.US, accYG),
            "%.6f".format(Locale.US, accZG),
            "%.6f".format(Locale.US, gyroXDps),
            "%.6f".format(Locale.US, gyroYDps),
            "%.6f".format(Locale.US, gyroZDps),
            "%.6f".format(Locale.US, rollDeg),
            "%.6f".format(Locale.US, pitchDeg),
            "%.6f".format(Locale.US, yawDeg),
            rawHex,
        ).joinToString(",") + "\n"
    }
}

data class ImuRuntimeState(
    val connected: Boolean = false,
    val reading: Boolean = false,
    val targetHz: Int = 200,
    val observedHz: Double = 0.0,
    val sampleCount: Long = 0,
    val lastSampleElapsedNs: Long = 0L,
    val lastSampleAgeMs: Double = -1.0,
    val status: String = "IMU 未连接",
    val deviceLabel: String = "",
) {
    fun summary(): String {
        return buildString {
            appendLine("连接：${if (connected) "已连接" else "未连接"} · ${if (reading) "读取中" else "未读取"}")
            appendLine("频率：目标 ${targetHz}Hz · 实际 ${"%.1f".format(Locale.US, observedHz)}Hz")
            appendLine("样本：$sampleCount · 最近 ${if (lastSampleAgeMs >= 0) "%.1fms".format(Locale.US, lastSampleAgeMs) else "-"}")
            if (deviceLabel.isNotBlank()) appendLine("设备：$deviceLabel")
            append(status)
        }
    }
}

class UsbCdcImuController(
    private val context: Context,
    private val onState: (ImuRuntimeState) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val running = AtomicBoolean(false)
    private val ioLock = Object()
    private var connection: UsbDeviceConnection? = null
    private var controlInterface: UsbInterface? = null
    private var dataInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var readThread: Thread? = null
    private var targetHz = 200
    private var recordingSession: DualSensorSessionController.ActiveSession? = null
    private var sampleIndex = 0L
    private var firstSampleNs = 0L
    private var lastSampleNs = 0L
    private var latestState = ImuRuntimeState()

    fun connect(device: UsbDevice): Boolean {
        close()
        if (!usbManager.hasPermission(device)) {
            updateState(connected = false, reading = false, status = "IMU USB 权限未授权", deviceLabel = device.label())
            return false
        }
        val endpoints = findCdcEndpoints(device)
        if (endpoints == null) {
            updateState(connected = false, reading = false, status = "未找到 CDC data bulk IN/OUT endpoint", deviceLabel = device.label())
            return false
        }
        val opened = usbManager.openDevice(device)
        if (opened == null) {
            updateState(connected = false, reading = false, status = "打开 IMU USB 设备失败", deviceLabel = device.label())
            return false
        }
        connection = opened
        controlInterface = endpoints.controlInterface
        dataInterface = endpoints.dataInterface
        bulkIn = endpoints.bulkIn
        bulkOut = endpoints.bulkOut
        runCatching { endpoints.controlInterface?.let { opened.claimInterface(it, true) } }
        if (!opened.claimInterface(endpoints.dataInterface, true)) {
            close()
            updateState(connected = false, reading = false, status = "claim CDC data interface 失败", deviceLabel = device.label())
            return false
        }
        running.set(true)
        sampleIndex = 0L
        firstSampleNs = 0L
        lastSampleNs = 0L
        updateState(connected = true, reading = true, status = "IMU 已连接，正在读取 WT901 帧", deviceLabel = device.label())
        readThread = Thread({ readLoop(device.label()) }, "vr-wt901-usb-reader").also { it.start() }
        return true
    }

    fun close() {
        running.set(false)
        readThread?.interrupt()
        readThread = null
        synchronized(ioLock) {
            runCatching { dataInterface?.let { connection?.releaseInterface(it) } }
            runCatching { controlInterface?.let { connection?.releaseInterface(it) } }
            runCatching { connection?.close() }
            connection = null
            controlInterface = null
            dataInterface = null
            bulkIn = null
            bulkOut = null
        }
        recordingSession = null
        updateState(connected = false, reading = false, status = "IMU 已关闭")
    }

    fun setTemporary200Hz(): Boolean {
        targetHz = 200
        val ok = sendCommand(UNLOCK_COMMAND) && sendCommand(SET_200HZ_COMMAND)
        sendCommand(READ_RATE_COMMAND)
        updateState(targetHz = 200, status = if (ok) "已发送 200Hz 临时设置（未写入永久保存）" else "200Hz 设置发送失败")
        return ok
    }

    fun readRateRegister(): Boolean {
        val ok = sendCommand(READ_RATE_COMMAND)
        updateState(status = if (ok) "已发送读取频率寄存器命令" else "读取频率寄存器命令发送失败")
        return ok
    }

    fun startRecording(session: DualSensorSessionController.ActiveSession) {
        recordingSession = session
        session.event("imu_recording_started", mapOf("target_hz" to targetHz.toString()))
    }

    fun stopRecording() {
        recordingSession?.event("imu_recording_stopped", mapOf("sample_count" to sampleIndex.toString()))
        recordingSession = null
    }

    fun state(): ImuRuntimeState = latestState

    private fun readLoop(deviceLabel: String) {
        val buffer = ByteArray(4096)
        var pending = ByteArray(0)
        var lastUiUpdateNs = 0L
        while (running.get()) {
            var readEndNs = 0L
            var read = -1
            var shouldStop = false
            synchronized(ioLock) {
                val conn = connection
                val endpoint = bulkIn
                if (conn == null || endpoint == null) {
                    shouldStop = true
                } else {
                    read = conn.bulkTransfer(endpoint, buffer, buffer.size, BULK_READ_TIMEOUT_MS)
                    readEndNs = SystemClock.elapsedRealtimeNanos()
                }
            }
            if (shouldStop) break
            if (read <= 0) {
                val ageMs = if (lastSampleNs > 0) (SystemClock.elapsedRealtimeNanos() - lastSampleNs) / 1_000_000.0 else -1.0
                if (ageMs > 500 && SystemClock.elapsedRealtimeNanos() - lastUiUpdateNs > 500_000_000L) {
                    updateState(status = if (ageMs > 2_000) "IMU 无样本超过 2s" else "IMU 样本暂时中断", lastSampleAgeMs = ageMs, deviceLabel = deviceLabel)
                    lastUiUpdateNs = SystemClock.elapsedRealtimeNanos()
                }
                continue
            }
            val merged = pending + buffer.copyOf(read)
            val frames = mutableListOf<ByteArray>()
            var offset = 0
            while (offset + WT901_FRAME_LENGTH <= merged.size) {
                if (merged[offset] == 0x55.toByte() && merged[offset + 1] == 0x61.toByte()) {
                    frames += merged.copyOfRange(offset, offset + WT901_FRAME_LENGTH)
                    offset += WT901_FRAME_LENGTH
                } else {
                    offset += 1
                }
            }
            pending = merged.copyOfRange(offset, merged.size)
            if (frames.isEmpty()) continue
            val periodNs = 1_000_000_000L / targetHz.coerceAtLeast(1)
            frames.forEachIndexed { index, frame ->
                val sampleNs = readEndNs - (frames.lastIndex - index) * periodNs
                val sample = parseFrame(frame, sampleNs, readEndNs, frames.size)
                sampleIndex = sample.index
                if (firstSampleNs == 0L) firstSampleNs = sample.sampleElapsedNs
                lastSampleNs = sample.sampleElapsedNs
                recordingSession?.imuCsvAppend(sample.csvLine(recordingSession?.t0Ns ?: firstSampleNs))
            }
            val nowNs = SystemClock.elapsedRealtimeNanos()
            if (nowNs - lastUiUpdateNs > 250_000_000L) {
                updateState(
                    connected = true,
                    reading = true,
                    targetHz = targetHz,
                    observedHz = observedHz(),
                    sampleCount = sampleIndex,
                    lastSampleElapsedNs = lastSampleNs,
                    lastSampleAgeMs = max(0.0, (nowNs - lastSampleNs) / 1_000_000.0),
                    status = "IMU 正常读取",
                    deviceLabel = deviceLabel,
                )
                lastUiUpdateNs = nowNs
            }
        }
    }

    private fun parseFrame(frame: ByteArray, sampleNs: Long, readEndNs: Long, batchSize: Int): ImuSample {
        val index = sampleIndex + 1
        return ImuSample(
            index = index,
            sampleElapsedNs = sampleNs,
            readEndElapsedNs = readEndNs,
            batchSize = batchSize,
            accXG = signed16(frame, 2) / 32768.0 * 16.0,
            accYG = signed16(frame, 4) / 32768.0 * 16.0,
            accZG = signed16(frame, 6) / 32768.0 * 16.0,
            gyroXDps = signed16(frame, 8) / 32768.0 * 2000.0,
            gyroYDps = signed16(frame, 10) / 32768.0 * 2000.0,
            gyroZDps = signed16(frame, 12) / 32768.0 * 2000.0,
            rollDeg = signed16(frame, 14) / 32768.0 * 180.0,
            pitchDeg = signed16(frame, 16) / 32768.0 * 180.0,
            yawDeg = signed16(frame, 18) / 32768.0 * 180.0,
            rawHex = frame.joinToString("") { "%02X".format(it.toInt() and 0xFF) },
        )
    }

    private fun sendCommand(command: ByteArray): Boolean {
        synchronized(ioLock) {
            val conn = connection ?: return false
            val endpoint = bulkOut ?: return false
            val sent = conn.bulkTransfer(endpoint, command, command.size, BULK_WRITE_TIMEOUT_MS)
            onLog("wt901_send ${command.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }} sent=$sent")
            return sent == command.size
        }
    }

    private fun observedHz(): Double {
        val first = firstSampleNs
        val last = lastSampleNs
        if (first <= 0L || last <= first || sampleIndex < 2) return 0.0
        return (sampleIndex - 1) * 1_000_000_000.0 / (last - first)
    }

    private fun updateState(
        connected: Boolean = latestState.connected,
        reading: Boolean = latestState.reading,
        targetHz: Int = this.targetHz,
        observedHz: Double = latestState.observedHz,
        sampleCount: Long = latestState.sampleCount,
        lastSampleElapsedNs: Long = latestState.lastSampleElapsedNs,
        lastSampleAgeMs: Double = latestState.lastSampleAgeMs,
        status: String = latestState.status,
        deviceLabel: String = latestState.deviceLabel,
    ) {
        latestState = ImuRuntimeState(
            connected = connected,
            reading = reading,
            targetHz = targetHz,
            observedHz = observedHz,
            sampleCount = sampleCount,
            lastSampleElapsedNs = lastSampleElapsedNs,
            lastSampleAgeMs = lastSampleAgeMs,
            status = status,
            deviceLabel = deviceLabel,
        )
        onState(latestState)
    }

    private fun findCdcEndpoints(device: UsbDevice): CdcEndpoints? {
        var control: UsbInterface? = null
        var data: UsbInterface? = null
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                control = usbInterface
            }
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                data = usbInterface
            }
        }
        val dataInterface = data ?: return null
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (index in 0 until dataInterface.endpointCount) {
            val endpoint = dataInterface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) output = endpoint
        }
        return CdcEndpoints(control, dataInterface, input ?: return null, output ?: return null)
    }

    private data class CdcEndpoints(
        val controlInterface: UsbInterface?,
        val dataInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    private fun UsbDevice.label(): String = "vid=$vendorId pid=$productId ${deviceName}"

    companion object {
        private const val WT901_FRAME_LENGTH = 20
        private const val BULK_READ_TIMEOUT_MS = 20
        private const val BULK_WRITE_TIMEOUT_MS = 200
        private val UNLOCK_COMMAND = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x69, 0x88.toByte(), 0xB5.toByte())
        private val SET_200HZ_COMMAND = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x03, 0x0B, 0x00)
        private val READ_RATE_COMMAND = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, 0x03, 0x00)
    }
}

private fun signed16(bytes: ByteArray, offset: Int): Short {
    val low = bytes[offset].toInt() and 0xFF
    val high = bytes[offset + 1].toInt()
    return ((high shl 8) or low).toShort()
}
