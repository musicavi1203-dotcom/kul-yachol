package com.example.kulyacholandro

import android.hardware.usb.*

data class UsbEndpoints(val epIn: UsbEndpoint, val epOut: UsbEndpoint)

object MtkBromConnection {

    var onLog: ((String) -> Unit)? = null
    private const val TIMEOUT_MS = 1000

    private val CHIP_NAMES = mapOf(
        0x0279 to "MT6575",
        0x0321 to "MT6589",
        0x0335 to "MT6572",
        0x0337 to "MT6582",
        0x0351 to "MT6580",
        0x0507 to "MT6755 (Helio P10)",
        0x0517 to "MT6757 (Helio P25)",
        0x0599 to "MT6771 (Helio P60)",
        0x0685 to "MT6765 (Helio G35)",
        0x0689 to "MT6769 (Helio G85)",
        0x0695 to "MT6833 (Dimensity 700)",
        0x0717 to "MT6761 (Helio A22)",
        0x0765 to "MT6785 (Helio G90T)",
        0x0788 to "MT6873 (Dimensity 800)",
        0x0800 to "MT6893 (Dimensity 1200)",
        0x8127 to "MT8127",
        0x8163 to "MT8163"
    )

    private val HS_TX = intArrayOf(0xA0, 0x0A, 0x50, 0x05)
    private val HS_RX = intArrayOf(0x5F, 0xF5, 0xAF, 0xFA)

    const val CMD_GET_HW_CODE    = 0xFD
    const val CMD_GET_HW_SW_VER  = 0xFC
    const val CMD_SEND_DA        = 0xD7
    const val CMD_JUMP_DA        = 0xD5
    const val CMD_WRITE32        = 0xD4
    const val CMD_SEND_PAYLOAD   = 0xE0

    private fun say(msg: String) { onLog?.invoke(msg) }

    fun findEndpoints(iface: UsbInterface): UsbEndpoints? {
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                else epOut = ep
            }
        }
        return if (epIn != null && epOut != null) UsbEndpoints(epIn, epOut) else null
    }

    private fun sendByte(conn: UsbDeviceConnection, ep: UsbEndpoint, b: Int): Boolean {
        val buf = byteArrayOf(b.toByte())
        return conn.bulkTransfer(ep, buf, 1, TIMEOUT_MS) == 1
    }

    private fun recvByte(conn: UsbDeviceConnection, ep: UsbEndpoint): Int {
        val buf = ByteArray(1)
        val r = conn.bulkTransfer(ep, buf, 1, TIMEOUT_MS)
        return if (r == 1) buf[0].toInt() and 0xFF else -1
    }

    private fun recvWord16(conn: UsbDeviceConnection, ep: UsbEndpoint): Int? {
        val buf = ByteArray(2)
        var got = 0
        while (got < 2) {
            val r = conn.bulkTransfer(ep, buf, buf.size, TIMEOUT_MS)
            if (r <= 0) return null
            got += r
        }
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    private fun sendCmdWithEcho(conn: UsbDeviceConnection, ep: UsbEndpoint, epIn: UsbEndpoint, cmd: Int): Boolean {
        if (!sendByte(conn, ep, cmd)) { say("❌ שליחת פקודה 0x${cmd.toString(16)} נכשלה"); return false }
        val echo = recvByte(conn, epIn)
        if (echo != cmd) { say("❌ echo לא תקין: קיבלתי 0x${echo.toString(16)}, ציפיתי 0x${cmd.toString(16)}"); return false }
        return true
    }

    fun doHandshake(conn: UsbDeviceConnection, ep: UsbEndpoint, epIn: UsbEndpoint): Boolean {
        say("🤝 מתחיל handshake...")
        for (i in HS_TX.indices) {
            if (!sendByte(conn, ep, HS_TX[i])) { say("❌ handshake TX[$i] נכשל"); return false }
            val rx = recvByte(conn, epIn)
            say("  TX: 0x${HS_TX[i].toString(16).uppercase()} → RX: 0x${rx.toString(16).uppercase()}")
            if (rx != HS_RX[i]) { say("❌ handshake RX[$i] שגוי: ציפיתי 0x${HS_RX[i].toString(16).uppercase()}"); return false }
        }
        say("✅ Handshake הצליח")
        return true
    }

    fun readHwCode(conn: UsbDeviceConnection, eps: UsbEndpoints): Int? {
        if (!sendCmdWithEcho(conn, eps.epOut, eps.epIn, CMD_GET_HW_CODE)) return null
        val value = recvWord16(conn, eps.epIn) ?: run { say("❌ קריאת HW code נכשלה"); return null }
        val status = recvWord16(conn, eps.epIn) ?: run { say("❌ קריאת status נכשלה"); return null }
        say("📟 HW Code: 0x${value.toString(16).uppercase()}, Status: 0x${status.toString(16).uppercase()}")
        val name = CHIP_NAMES[value] ?: "לא מוכר"
        say("🔖 שבב: $name")
        return value
    }

    fun readHwSwVer(conn: UsbDeviceConnection, eps: UsbEndpoints) {
        if (!sendCmdWithEcho(conn, eps.epOut, eps.epIn, CMD_GET_HW_SW_VER)) return
        val sub = recvWord16(conn, eps.epIn) ?: return
        val hw  = recvWord16(conn, eps.epIn) ?: return
        val sw  = recvWord16(conn, eps.epIn) ?: return
        val sts = recvWord16(conn, eps.epIn) ?: return
        say("📋 sub=0x${sub.toString(16).uppercase()}, hw=0x${hw.toString(16).uppercase()}, sw=0x${sw.toString(16).uppercase()}, status=0x${sts.toString(16).uppercase()}")
    }

    // Write 4-byte big-endian value
    fun write4BE(conn: UsbDeviceConnection, ep: UsbEndpoint, value: Long) {
        val buf = byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        conn.bulkTransfer(ep, buf, 4, TIMEOUT_MS)
    }

    // Read 4 bytes big-endian
    fun read4BE(conn: UsbDeviceConnection, ep: UsbEndpoint): Long? {
        val buf = ByteArray(4)
        var got = 0
        while (got < 4) {
            val r = conn.bulkTransfer(ep, buf, buf.size, TIMEOUT_MS)
            if (r <= 0) return null
            got += r
        }
        return ((buf[0].toLong() and 0xFF) shl 24) or
               ((buf[1].toLong() and 0xFF) shl 16) or
               ((buf[2].toLong() and 0xFF) shl 8)  or
               (buf[3].toLong() and 0xFF)
    }

    // Send raw bytes in chunks
    fun sendBytes(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray): Boolean {
        var offset = 0
        val chunk = 512
        while (offset < data.size) {
            val len = minOf(chunk, data.size - offset)
            val r = conn.bulkTransfer(ep, data, offset, len, TIMEOUT_MS * 5)
            if (r < 0) return false
            offset += r
        }
        return true
    }

    // Recv bytes with known length
    fun recvBytes(conn: UsbDeviceConnection, ep: UsbEndpoint, len: Int): ByteArray? {
        val buf = ByteArray(len)
        var got = 0
        val chunk = 512
        while (got < len) {
            val toRead = minOf(chunk, len - got)
            val r = conn.bulkTransfer(ep, buf, got, toRead, TIMEOUT_MS * 5)
            if (r < 0) return null
            got += r
        }
        return buf
    }

    fun getChipName(hwCode: Int): String = CHIP_NAMES[hwCode] ?: "0x${hwCode.toString(16).uppercase()}"
}
