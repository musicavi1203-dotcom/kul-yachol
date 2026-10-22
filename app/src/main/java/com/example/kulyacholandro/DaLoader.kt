package com.example.kulyacholandro

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log

object DaLoader {
    private const val TAG = "DaLoader"
    private const val TIMEOUT_MS = 5000

    private const val CMD_SEND_DA: Int  = 0xD7
    private const val CMD_JUMP_DA: Int  = 0xD5

    private const val DA1_LOAD_ADDR: Long = 0x200000L
    private const val DA2_LOAD_ADDR: Long = 0x40000000L
    private const val SIG_LEN: Long       = 0x100L

    private fun say(msg: String) {
        Log.d(TAG, msg)
        MtkBromConnection.onLog(msg)
    }

    private fun sendByte(conn: UsbDeviceConnection, ep: UsbEndpoint, v: Int): Boolean {
        val buf = byteArrayOf(v.toByte())
        return conn.bulkTransfer(ep, buf, 1, TIMEOUT_MS) == 1
    }

    private fun recvByte(conn: UsbDeviceConnection, ep: UsbEndpoint): Int? {
        val buf = ByteArray(1)
        val ret = conn.bulkTransfer(ep, buf, 1, TIMEOUT_MS)
        if (ret < 1) return null
        return buf[0].toInt() and 0xFF
    }

    private fun recvWord16(conn: UsbDeviceConnection, ep: UsbEndpoint): Int? {
        val buf = ByteArray(2)
        val ret = conn.bulkTransfer(ep, buf, 2, TIMEOUT_MS)
        if (ret != 2) return null
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    private fun write4BE(conn: UsbDeviceConnection, ep: UsbEndpoint, value: Long) {
        val buf = byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr  8) and 0xFF).toByte(),
            ( value         and 0xFF).toByte()
        )
        conn.bulkTransfer(ep, buf, 4, TIMEOUT_MS)
    }

    private fun read4BE(conn: UsbDeviceConnection, ep: UsbEndpoint): Long? {
        val buf = ByteArray(4)
        val ret = conn.bulkTransfer(ep, buf, 4, TIMEOUT_MS)
        if (ret != 4) return null
        return ((buf[0].toLong() and 0xFF) shl 24) or
               ((buf[1].toLong() and 0xFF) shl 16) or
               ((buf[2].toLong() and 0xFF) shl  8) or
                (buf[3].toLong() and 0xFF)
    }

    private fun sendBytes(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(512, data.size - offset)
            val ret = conn.bulkTransfer(ep, data, offset, chunk, TIMEOUT_MS)
            if (ret <= 0) return false
            offset += ret
        }
        return true
    }

    /**
     * SEND_DA (0xD7): שולח DA לזיכרון המכשיר
     */
    private fun sendDA(conn: UsbDeviceConnection, eps: MtkBromConnection.BulkEndpoints,
                       data: ByteArray, loadAddr: Long, sigLen: Long): Boolean {
        say("SEND_DA: addr=0x${loadAddr.toString(16)} size=${data.size}")

        if (!sendByte(conn, eps.epOut, CMD_SEND_DA)) { say("❌ שליחת CMD_SEND_DA נכשלה"); return false }
        val echo = recvByte(conn, eps.epIn)
        if (echo != CMD_SEND_DA) { say("❌ echo שגוי"); return false }

        write4BE(conn, eps.epOut, loadAddr)
        write4BE(conn, eps.epOut, data.size.toLong())
        write4BE(conn, eps.epOut, sigLen)

        val status = recvWord16(conn, eps.epIn)
        if (status != 0x0000) { say("❌ status שגוי: 0x${status?.toString(16)}"); return false }

        say("שולח ${data.size} bytes...")
        if (!sendBytes(conn, eps.epOut, data)) { say("❌ שליחת bytes נכשלה"); return false }

        // חשב XOR checksum
        var checksum: Int = 0
        for (b in data) checksum = checksum xor (b.toInt() and 0xFF)
        val csBuf = byteArrayOf(0, checksum.toByte())
        conn.bulkTransfer(eps.epOut, csBuf, 2, TIMEOUT_MS)

        val csEcho = recvWord16(conn, eps.epIn)
        say("Checksum: שלחנו 0x${checksum.toString(16)}, קיבלנו 0x${csEcho?.toString(16)}")

        say("✅ SEND_DA הצליח")
        return true
    }

    /**
     * JUMP_DA (0xD5): קופץ לכתובת ה-DA
     */
    private fun jumpDA(conn: UsbDeviceConnection, eps: MtkBromConnection.BulkEndpoints, addr: Long): Boolean {
        say("JUMP_DA: addr=0x${addr.toString(16)}")

        if (!sendByte(conn, eps.epOut, CMD_JUMP_DA)) { say("❌ שליחת CMD_JUMP_DA נכשלה"); return false }
        val echo = recvByte(conn, eps.epIn)
        if (echo != CMD_JUMP_DA) { say("❌ echo שגוי"); return false }

        write4BE(conn, eps.epOut, addr)

        val addrEcho = read4BE(conn, eps.epIn)
        say("addr echo = 0x${addrEcho?.toString(16)}")

        val status = recvWord16(conn, eps.epIn)
        if (status != 0x0000) { say("❌ status שגוי: 0x${status?.toString(16)}"); return false }

        say("✅ JUMP_DA הצליח")
        return true
    }

    /**
     * ממתין לסנכרון XFLASH (0xC0) אחרי JUMP_DA
     */
    private fun waitSync(conn: UsbDeviceConnection, eps: MtkBromConnection.BulkEndpoints): Boolean {
        say("ממתין ל-XFLASH sync (0xC0)...")
        for (i in 0..50) {
            val b = recvByte(conn, eps.epIn)
            if (b == 0xC0) { say("✅ קיבלנו sync 0xC0"); return true }
            Thread.sleep(100)
        }
        say("❌ timeout: לא קיבלנו 0xC0")
        return false
    }

    /**
     * שולח פקודת XFLASH בסיסית ומצפה לstatus=0
     */
    private fun xflashCmd(conn: UsbDeviceConnection, eps: MtkBromConnection.BulkEndpoints, cmd: Int, name: String): Boolean {
        val buf = byteArrayOf(cmd.toByte())
        conn.bulkTransfer(eps.epOut, buf, 1, TIMEOUT_MS)
        val status = recvByte(conn, eps.epIn)
        say("$name: status=0x${status?.toString(16)}")
        return status == 0x00
    }

    /**
     * BOOT_TO (0x54): מעביר ל-DA2
     */
    private fun bootToDA2(conn: UsbDeviceConnection, eps: MtkBromConnection.BulkEndpoints, da2: ByteArray): Boolean {
        say("BOOT_TO DA2: addr=0x${DA2_LOAD_ADDR.toString(16)} size=${da2.size}")

        val buf = byteArrayOf(0x54)
        conn.bulkTransfer(eps.epOut, buf, 1, TIMEOUT_MS)

        // שלח כתובת DA2 (8 bytes BE)
        val addrBuf = ByteArray(8)
        for (i in 0..7) addrBuf[i] = ((DA2_LOAD_ADDR shr ((7 - i) * 8)) and 0xFF).toByte()
        conn.bulkTransfer(eps.epOut, addrBuf, 8, TIMEOUT_MS)

        // שלח גודל (4 bytes BE)
        write4BE(conn, eps.epOut, da2.size.toLong())

        val status = recvByte(conn, eps.epIn)
        if (status != 0x00) { say("❌ BOOT_TO status שגוי: 0x${status?.toString(16)}"); return false }

        say("שולח DA2 (${da2.size} bytes)...")
        if (!sendBytes(conn, eps.epOut, da2)) { say("❌ שליחת DA2 נכשלה"); return false }

        say("✅ BOOT_TO DA2 הצליח")
        return true
    }

    /**
     * הפונקציה הראשית: טוענת DA1 + DA2
     */
    fun loadDA(conn: UsbDeviceConnection, eps: MtkBromConnection.BulkEndpoints,
               da1: ByteArray, da2: ByteArray): Boolean {
        say("=== DA Loading ===")

        if (!sendDA(conn, eps, da1, DA1_LOAD_ADDR, SIG_LEN)) return false
        if (!jumpDA(conn, eps, DA1_LOAD_ADDR)) return false
        if (!waitSync(conn, eps)) return false

        say("XFLASH init...")
        if (!xflashCmd(conn, eps, 0x50, "SYNC")) return false
        if (!xflashCmd(conn, eps, 0x51, "SETUP_ENV")) return false
        if (!xflashCmd(conn, eps, 0x52, "SETUP_HW_INIT")) return false

        if (!bootToDA2(conn, eps, da2)) return false

        say("✅ DA נטען בהצלחה!")
        return true
    }
}
