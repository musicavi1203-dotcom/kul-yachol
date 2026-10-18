package com.example.kulyacholandro

import android.hardware.usb.UsbDevice

data class MtkDeviceInfo(
    val vid: Int,
    val pid: Int,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val mode: MtkMode
)

enum class MtkMode {
    BROM,       // Boot ROM — מכשיר כבוי / לחלוטין לא טעון
    PRELOADER,  // Preloader — שלב ראשון של אתחול
    UNKNOWN
}

object MtkBromConnection {

    // VID של MediaTek
    const val MTK_VID = 0x0E8D

    // PIDs ידועים
    private val BROM_PIDS = setOf(0x0003, 0x0001)
    private val PRELOADER_PIDS = setOf(0x07D0, 0x2000, 0x0050)

    fun isMtkDevice(device: UsbDevice): Boolean =
        device.vendorId == MTK_VID

    fun getDeviceInfo(device: UsbDevice): MtkDeviceInfo {
        val mode = when (device.productId) {
            in BROM_PIDS -> MtkMode.BROM
            in PRELOADER_PIDS -> MtkMode.PRELOADER
            else -> MtkMode.UNKNOWN
        }
        return MtkDeviceInfo(
            vid = device.vendorId,
            pid = device.productId,
            manufacturer = device.manufacturerName,
            product = device.productName,
            serial = device.serialNumber,
            mode = mode
        )
    }

    fun formatVid(vid: Int) = "0x${vid.toString(16).uppercase().padStart(4, '0')}"
    fun formatPid(pid: Int) = "0x${pid.toString(16).uppercase().padStart(4, '0')}"
}
