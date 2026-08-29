package com.aiglasses.poc.usb.dual

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

data class UsbDeviceDescriptor(
    val device: UsbDevice,
    val label: String,
    val fingerprint: String,
    val isUvcCamera: Boolean,
    val isWt901ImuCandidate: Boolean,
    val hasPermission: Boolean,
    val interfaceSummary: String,
)

data class UsbDeviceSnapshot(
    val cameras: List<UsbDeviceDescriptor>,
    val imuCandidates: List<UsbDeviceDescriptor>,
    val allDevices: List<UsbDeviceDescriptor>,
) {
    val ready: Boolean
        get() = cameras.size >= 2 && imuCandidates.isNotEmpty()
}

class UsbDeviceRegistry(
    private val context: Context,
) {
    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun snapshot(): UsbDeviceSnapshot {
        val descriptors = usbManager.deviceList.values
            .sortedWith(compareBy<UsbDevice> { it.deviceName }.thenBy { it.vendorId }.thenBy { it.productId })
            .mapIndexed { index, device -> descriptor(index, device) }
        return UsbDeviceSnapshot(
            cameras = descriptors.filter { it.isUvcCamera },
            imuCandidates = descriptors.filter { it.isWt901ImuCandidate },
            allDevices = descriptors,
        )
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    private fun descriptor(index: Int, device: UsbDevice): UsbDeviceDescriptor {
        val interfaceSummary = device.interfaceSummary()
        val uvc = device.isLikelyUvcCamera()
        val imu = device.isWt901CdcCandidate()
        val kind = when {
            uvc && imu -> "UVC/CDC"
            uvc -> "UVC"
            imu -> "WT901"
            else -> "USB"
        }
        return UsbDeviceDescriptor(
            device = device,
            label = "$kind ${index + 1} · vid=${device.vendorId} pid=${device.productId}",
            fingerprint = device.fingerprint(interfaceSummary),
            isUvcCamera = uvc,
            isWt901ImuCandidate = imu,
            hasPermission = usbManager.hasPermission(device),
            interfaceSummary = interfaceSummary,
        )
    }
}

fun UsbDevice.isLikelyUvcCamera(): Boolean {
    if (deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
    for (index in 0 until interfaceCount) {
        val usbInterface = getInterface(index)
        if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
    }
    return false
}

fun UsbDevice.isWt901CdcCandidate(): Boolean {
    var hasCdcControl = false
    var hasCdcData = false
    for (index in 0 until interfaceCount) {
        val usbInterface = getInterface(index)
        if (
            usbInterface.interfaceClass == UsbConstants.USB_CLASS_COMM &&
            usbInterface.interfaceSubclass == 2
        ) {
            hasCdcControl = true
        }
        if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
            hasCdcData = true
        }
    }
    return hasCdcControl && hasCdcData
}

fun UsbDevice.interfaceSummary(): String {
    return buildString {
        for (index in 0 until interfaceCount) {
            if (index > 0) append(";")
            val usbInterface = getInterface(index)
            append(index)
            append(":")
            append(usbInterface.interfaceClass)
            append("/")
            append(usbInterface.interfaceSubclass)
            append("/")
            append(usbInterface.interfaceProtocol)
        }
    }
}

fun UsbDevice.fingerprint(interfaceSummary: String = interfaceSummary()): String {
    return listOf(
        deviceName,
        vendorId.toString(),
        productId.toString(),
        deviceClass.toString(),
        deviceSubclass.toString(),
        deviceProtocol.toString(),
        interfaceSummary,
    ).joinToString("|")
}
