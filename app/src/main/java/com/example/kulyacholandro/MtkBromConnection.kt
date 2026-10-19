package com.example.kulyacholandro

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

data class MtkDeviceInfo(
    val vid: Int,
    val pid: Int,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val mode: MtkMode
)

data class MtkChipInfo(
    val chipId: Int,
    val hwCode: Int,
    val hwSubCode: Int,
    val hwVer: Int,
    val swVer: Int
) {
    val chipName: String get() = when (chipId) {
        0x6580 -> "MT6580"
        0x6735 -> "MT6735"
        0x6737 -> "MT6737"
        0x6739 -> "MT6739"
        0x6750 -> "MT6750"
        0x6755 -> "MT6755 (Helio P10)"
        0x6757 -> "MT6757 (Helio P20)"
        0x6758 -> "MT6758 (Helio P30)"
        0x6761 -> "MT6761 (Helio A22)"
        0x6762 -> "MT6762 (Helio P22)"
        0x6763 -> "MT6763 (Helio P23)"
        0x6765 -> "MT6765 (Helio G35)"
        0x6768 -> "MT6768 (Helio G85)"
        0x6771 -> "MT6771 (Helio P60)"
        0x6779 -> "MT6779 (Helio G90)"
        0x6785 -> "MT6785 (Helio G90T)"
        0x6833 -> "MT6833 (Dimensity 700)"
        0x6853 -> "MT6853 (Dimensity 720)"
        0x6873 -> "MT6873 (Dimensity 800)"
        0x6875 -> "MT6875 (Dimensity 800U)"
        0x6877 -> "MT6877 (Dimensity 900)"
        0x6893 -> "MT6893 (Dimensity 1200)"
        0x8127 -> "MT8127"
        0x8163 -> "MT8163"
        0x8173 -> "MT8173"
        0x8183 -> "MT8183 (Helio P60T)"
        else   -> "MT${chipId.toString(16).uppercase()}"
    }
}

enum class MtkMode {
    BROM,
    PRELOADER,
    UNKNOWN
}

object MtkBromConnection {
    private const val TAG = "MtkBromConnection"

    const val MTK_VID = 0x0E8D
    private val BROM_PIDS      = setOf(0x0003, 0x0001)
    private val PRELOADER_PIDS = setOf(0x07D0, 0x2000, 0x0050)

    // timeout קצר כדי שלא נתקע על spinner
    private const val TIMEOUT_MS = 1000

    // לוג שמופיע על המסך (מוגדר ע"י ChipReadWorker)
    @Volatile
    var onLog: (String) -> Unit = {}

    private const val CMD_GET_HW_CODE: Int    = 0xFD
    private const val CMD_GET_HW_SW_VER: Int  = 0xFC
    private const val CMD_GET_CHIP_ID: Int    = 0xEF

    // Handshake: שולחים בייט, מצפים לתשובה מתאימה
    private val HS_TX = intArrayOf(0xA0, 0x0A, 0x50, 0x05)
    private val HS_RX = intArrayOf(0x5F, 0xF5, 0xAF, 0xFA)

    private fun say(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }

    private fun hex(v: Int): String = "%02X".format(v and 0xFF)

    fun isMtkDevice(device: UsbDevice): Boolean = device.vendorId == MTK_VID

    fun getDeviceMode(device: UsbDevice): MtkMode = when (device.productId) {
        in BROM_PIDS      -> MtkMode.BROM
        in PRELOADER_PIDS -> MtkMode.PRELOADER
        else              -> MtkMode.UNKNOWN
    }

    fun getDeviceInfo(device: UsbDevice): MtkDeviceInfo = MtkDeviceInfo(
        vid          = device.vendorId,
        pid          = device.productId,
        manufacturer = device.manufacturerName,
        product      = device.productName,
        serial       = device.serialNumber,
        mode         = getDeviceMode(device)
    )

    data class BulkEndpoints(
        val usbInterface: UsbInterface,
        val epIn: UsbEndpoint,
        val epOut: UsbEndpoint
    )

    fun findBulkEndpoints(device: UsbDevice): BulkEndpoints? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN)  epIn  = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                }
            }
            if (epIn != null && epOut != null) {
                return BulkEndpoints(iface, epIn, epOut)
            }
        }
        return null
    }

    private fun sendByte(conn: UsbDeviceConnection, ep: UsbEndpoint, v: Int): Boolean {
        val buf = byteArrayOf(v.toByte())
        val ret = conn.bulkTransfer(ep, buf, 1, TIMEOUT_MS)
        say("  TX ${hex(v)} → ret=$ret")
        return ret == 1
    }

    private fun recvByte(conn: UsbDeviceConnection, ep: UsbEndpoint): Int? {
        val buf = ByteArray(1)
        val ret = conn.bulkTransfer(ep, buf, 1, TIMEOUT_MS)
        if (ret < 1) {
            say("  RX → אין תשובה (ret=$ret)")
            return null
        }
        val v = buf[0].toInt() and 0xFF
        say("  RX ${hex(v)}")
        return v
    }

    private fun recvWord16(conn: UsbDeviceConnection, ep: UsbEndpoint): Int? {
        val buf = ByteArray(2)
        val ret = conn.bulkTransfer(ep, buf, 2, TIMEOUT_MS)
        if (ret != 2) {
            say("  RX16 → ret=$ret")
            return null
        }
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    /**
     * BROM handshake: שולחים A0 0A 50 05, מצפים ל-5F F5 AF FA
     */
    fun performBromHandshake(conn: UsbDeviceConnection, eps: BulkEndpoints): Boolean {
        say("Handshake: שולח A0 0A 50 05")
        for (i in HS_TX.indices) {
            if (!sendByte(conn, eps.epOut, HS_TX[i])) {
                say("❌ שליחה נכשלה בבייט $i")
                return false
            }
            val rx = recvByte(conn, eps.epIn)
            if (rx == null) {
                say("❌ אין תשובה בבייט $i (צפוי ${hex(HS_RX[i])})")
                return false
            }
            if (rx != HS_RX[i]) {
                say("❌ תשובה שגויה בבייט $i: קיבלתי ${hex(rx)}, צפוי ${hex(HS_RX[i])}")
                return false
            }
        }
        say("✅ Handshake הצליח")
        return true
    }

    /**
     * שולח פקודה וקורא את ה-echo שלה — הטלפון מחזיר את אותו בייט לפני התשובה
     */
    private fun sendCmdWithEcho(conn: UsbDeviceConnection, ep: UsbEndpoint, epIn: UsbEndpoint, cmd: Int): Boolean {
        if (!sendByte(conn, ep, cmd)) {
            say("❌ שליחת ${hex(cmd)} נכשלה")
            return false
        }
        val echo = recvByte(conn, epIn)
        if (echo != cmd) {
            val got = if (echo == null) "כלום" else hex(echo)
            say("❌ echo לא תקין: קיבלתי $got, צפוי ${hex(cmd)}")
            return false
        }
        return true
    }

    fun readHwCode(conn: UsbDeviceConnection, eps: BulkEndpoints): Int? {
        say("שולח GET_HW_CODE (FD)")
        if (!sendCmdWithEcho(conn, eps.epOut, eps.epIn, CMD_GET_HW_CODE)) return null
        val value = recvWord16(conn, eps.epIn) ?: return null
        val status = recvWord16(conn, eps.epIn)
        say("HW code = ${formatHex(value)}, status = ${status?.let { formatHex(it) } ?: "?"}")
        return value
    }

    fun readHwSwVer(conn: UsbDeviceConnection, eps: BulkEndpoints): Triple<Int, Int, Int>? {
        say("שולח GET_HW_SW_VER (FC)")
        if (!sendCmdWithEcho(conn, eps.epOut, eps.epIn, CMD_GET_HW_SW_VER)) return null
        val sub = recvWord16(conn, eps.epIn) ?: return null
        val hw  = recvWord16(conn, eps.epIn) ?: return null
        val sw  = recvWord16(conn, eps.epIn) ?: return null
        val status = recvWord16(conn, eps.epIn)
        say("HW sub=${formatHex(sub)} hw=${formatHex(hw)} sw=${formatHex(sw)} status=${status?.let { formatHex(it) } ?: "?"}")
        return Triple(sub, hw, sw)
    }

    fun readChipInfoBrom(conn: UsbDeviceConnection, eps: BulkEndpoints): MtkChipInfo? {
        if (!performBromHandshake(conn, eps)) return null
        val hwCode = readHwCode(conn, eps) ?: run {
            say("❌ GET_HW_CODE לא החזיר תשובה")
            return null
        }
        val verTuple = readHwSwVer(conn, eps)
        return MtkChipInfo(
            chipId    = hwCode,
            hwCode    = hwCode,
            hwSubCode = verTuple?.first  ?: 0,
            hwVer     = verTuple?.second ?: 0,
            swVer     = verTuple?.third  ?: 0
        )
    }

    fun readChipInfoPreloader(conn: UsbDeviceConnection, eps: BulkEndpoints): MtkChipInfo? {
        say("שולח GET_CHIP_ID (EF) למצב Preloader")
        if (!sendByte(conn, eps.epOut, CMD_GET_CHIP_ID)) return null
        val chipId = recvWord16(conn, eps.epIn) ?: run {
            say("❌ אין תשובה ל-GET_CHIP_ID")
            return null
        }
        return MtkChipInfo(
            chipId    = chipId,
            hwCode    = chipId,
            hwSubCode = 0,
            hwVer     = 0,
            swVer     = 0
        )
    }

    fun formatVid(vid: Int) = "0x${vid.toString(16).uppercase().padStart(4, '0')}"
    fun formatPid(pid: Int) = "0x${pid.toString(16).uppercase().padStart(4, '0')}"
    fun formatHex(v: Int)   = "0x${v.toString(16).uppercase().padStart(4, '0')}"
}
