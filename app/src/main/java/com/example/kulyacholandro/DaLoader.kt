package com.example.kulyacholandro

import android.hardware.usb.*

/**
 * DA Loader — loads DA1 and DA2 into device RAM and jumps to DA1
 * Based on mtkclient/Library/mtk_preloader.py + DA/xflash/xflash_lib.py by bkerler
 *
 * Flow:
 * 1. SEND_DA (0xD7): upload DA1 to 0x200000
 * 2. JUMP_DA (0xD5): jump to 0x200000
 * 3. Wait for sync byte 0xC0
 * 4. XFLASH init: sync, setup_env, setup_hw_init
 * 5. boot_to: load DA2 to 0x40000000
 */
object DaLoader {

    var onLog: ((String) -> Unit)? = null
    private fun say(msg: String) { onLog?.invoke(msg) }

    private const val TIMEOUT_MS = 3000
    private const val DA1_LOAD_ADDR = 0x200000L
    private const val DA2_LOAD_ADDR = 0x40000000L
    private const val DA1_SIG_LEN   = 0x100
    private const val DA2_SIG_LEN   = 0x100

    // XFLASH commands
    private const val XFLASH_SYNC          = 0x50
    private const val XFLASH_SETUP_ENV     = 0x51
    private const val XFLASH_SETUP_HW_INIT = 0x52
    private const val XFLASH_BOOT_TO       = 0x54

    /**
     * Full DA loading sequence.
     * da1: bytes of DA1 region
     * da2: bytes of DA2 region
     */
    fun loadDA(conn: UsbDeviceConnection, eps: UsbEndpoints, da1: ByteArray, da2: ByteArray): Boolean {
        say("📦 טוען DA1 (${da1.size} bytes) לכתובת 0x${DA1_LOAD_ADDR.toString(16)}...")
        if (!sendDA(conn, eps, da1, DA1_LOAD_ADDR, DA1_SIG_LEN)) {
            say("❌ SEND_DA נכשל")
            return false
        }
        say("✅ SEND_DA הצליח")

        say("🚀 קופץ ל-DA1 (JUMP_DA)...")
        if (!jumpDA(conn, eps, DA1_LOAD_ADDR)) {
            say("❌ JUMP_DA נכשל")
            return false
        }
        say("✅ JUMP_DA הצליח")

        say("⏳ ממתין לסנכרון DA1 (0xC0)...")
        if (!waitSync(conn, eps)) {
            say("❌ sync byte לא התקבל")
            return false
        }
        say("✅ sync 0xC0 התקבל")

        say("🔧 אתחול XFLASH...")
        if (!xflashInit(conn, eps)) {
            say("❌ XFLASH init נכשל")
            return false
        }
        say("✅ XFLASH מאותחל")

        say("📦 טוען DA2 (${da2.size} bytes)...")
        if (!bootToDA2(conn, eps, da2)) {
            say("❌ boot_to DA2 נכשל")
            return false
        }
        say("✅ DA2 נטען — מוכן לפעולות flash!")
        return true
    }

    /**
     * SEND_DA (0xD7):
     * cmd + echo, 4B addr, 4B size, 4B sig_len, status, data+checksum, checksum_echo
     */
    private fun sendDA(conn: UsbDeviceConnection, eps: UsbEndpoints,
                       data: ByteArray, addr: Long, sigLen: Int): Boolean {
        // Send command with echo
        val cmdBuf = byteArrayOf(MtkBromConnection.CMD_SEND_DA.toByte())
        if (conn.bulkTransfer(eps.epOut, cmdBuf, 1, TIMEOUT_MS) < 0) return false
        val echo = ByteArray(1)
        conn.bulkTransfer(eps.epIn, echo, 1, TIMEOUT_MS)
        if (echo[0].toInt() and 0xFF != MtkBromConnection.CMD_SEND_DA) {
            say("❌ SEND_DA echo שגוי: 0x${(echo[0].toInt() and 0xFF).toString(16)}")
            return false
        }

        // Send addr (4 bytes BE)
        MtkBromConnection.write4BE(conn, eps.epOut, addr)
        // Send size (4 bytes BE)
        MtkBromConnection.write4BE(conn, eps.epOut, data.size.toLong())
        // Send sig_len (4 bytes BE)
        MtkBromConnection.write4BE(conn, eps.epOut, sigLen.toLong())

        // Read status (should be 0x0000 as 2 bytes)
        val statusBuf = ByteArray(2)
        conn.bulkTransfer(eps.epIn, statusBuf, 2, TIMEOUT_MS)
        val status = ((statusBuf[0].toInt() and 0xFF) shl 8) or (statusBuf[1].toInt() and 0xFF)
        if (status != 0) {
            say("❌ SEND_DA status: 0x${status.toString(16)}")
            return false
        }
        say("   status OK, שולח ${data.size} bytes...")

        // Calculate XOR checksum
        var checksum = 0
        for (b in data) checksum = checksum xor (b.toInt() and 0xFF)

        // Send data
        if (!MtkBromConnection.sendBytes(conn, eps.epOut, data)) {
            say("❌ שליחת data DA נכשלה")
            return false
        }

        // Send checksum (2 bytes BE)
        val csBuf = byteArrayOf(
            ((checksum shr 8) and 0xFF).toByte(),
            (checksum and 0xFF).toByte()
        )
        conn.bulkTransfer(eps.epOut, csBuf, 2, TIMEOUT_MS)

        // Read checksum echo
        val csEcho = ByteArray(2)
        conn.bulkTransfer(eps.epIn, csEcho, 2, TIMEOUT_MS)
        val csEchoVal = ((csEcho[0].toInt() and 0xFF) shl 8) or (csEcho[1].toInt() and 0xFF)
        if (csEchoVal != checksum) {
            say("⚠️ checksum: שלחתי 0x${checksum.toString(16)}, קיבלתי 0x${csEchoVal.toString(16)}")
            // Continue anyway — some devices don't echo correctly
        }
        say("   checksum: 0x${checksum.toString(16)}")
        return true
    }

    /**
     * JUMP_DA (0xD5):
     * cmd + echo, 4B addr, read addr echo, read status
     */
    private fun jumpDA(conn: UsbDeviceConnection, eps: UsbEndpoints, addr: Long): Boolean {
        val cmdBuf = byteArrayOf(MtkBromConnection.CMD_JUMP_DA.toByte())
        if (conn.bulkTransfer(eps.epOut, cmdBuf, 1, TIMEOUT_MS) < 0) return false
        val echo = ByteArray(1)
        conn.bulkTransfer(eps.epIn, echo, 1, TIMEOUT_MS)
        if (echo[0].toInt() and 0xFF != MtkBromConnection.CMD_JUMP_DA) {
            say("❌ JUMP_DA echo שגוי: 0x${(echo[0].toInt() and 0xFF).toString(16)}")
            return false
        }

        // Send address
        MtkBromConnection.write4BE(conn, eps.epOut, addr)

        // Read back address echo
        val addrEcho = MtkBromConnection.read4BE(conn, eps.epIn)
        say("   addr echo: 0x${addrEcho?.toString(16) ?: "null"}")

        // Read status
        val status = ByteArray(2)
        conn.bulkTransfer(eps.epIn, status, 2, TIMEOUT_MS)
        val statusVal = ((status[0].toInt() and 0xFF) shl 8) or (status[1].toInt() and 0xFF)
        if (statusVal != 0) {
            say("❌ JUMP_DA status: 0x${statusVal.toString(16)}")
            return false
        }
        return true
    }

    /**
     * Wait for sync byte 0xC0 from DA1
     */
    private fun waitSync(conn: UsbDeviceConnection, eps: UsbEndpoints): Boolean {
        val buf = ByteArray(1)
        for (attempt in 0..50) {
            val r = conn.bulkTransfer(eps.epIn, buf, 1, TIMEOUT_MS)
            if (r == 1 && buf[0].toInt() and 0xFF == 0xC0) return true
            Thread.sleep(100)
        }
        return false
    }

    /**
     * XFLASH init: send SYNC, setup_env, setup_hw_init
     */
    private fun xflashInit(conn: UsbDeviceConnection, eps: UsbEndpoints): Boolean {
        // Send SYNC (0x50)
        if (!sendXflashCmd(conn, eps, XFLASH_SYNC)) {
            say("❌ XFLASH SYNC נכשל")
            return false
        }
        say("   SYNC ✓")

        // setup_env (0x51)
        if (!sendXflashCmd(conn, eps, XFLASH_SETUP_ENV)) {
            say("❌ XFLASH SETUP_ENV נכשל")
            return false
        }
        say("   SETUP_ENV ✓")

        // setup_hw_init (0x52)
        if (!sendXflashCmd(conn, eps, XFLASH_SETUP_HW_INIT)) {
            say("❌ XFLASH SETUP_HW_INIT נכשל")
            return false
        }
        say("   SETUP_HW_INIT ✓")
        return true
    }

    /**
     * Send XFLASH command and read status byte
     */
    private fun sendXflashCmd(conn: UsbDeviceConnection, eps: UsbEndpoints, cmd: Int): Boolean {
        val buf = byteArrayOf(cmd.toByte())
        conn.bulkTransfer(eps.epOut, buf, 1, TIMEOUT_MS)
        val resp = ByteArray(1)
        val r = conn.bulkTransfer(eps.epIn, resp, 1, TIMEOUT_MS)
        return r == 1 && resp[0].toInt() and 0xFF == 0  // status 0 = OK
    }

    /**
     * boot_to (0x54): upload DA2 to 0x40000000
     */
    private fun bootToDA2(conn: UsbDeviceConnection, eps: UsbEndpoints, da2: ByteArray): Boolean {
        val buf = byteArrayOf(XFLASH_BOOT_TO.toByte())
        conn.bulkTransfer(eps.epOut, buf, 1, TIMEOUT_MS)

        // Send DA2 address (8 bytes BE for 64-bit) then size
        val addrBuf = byteArrayOf(0, 0, 0, 0, 0x40, 0, 0, 0) // 0x40000000 as 8 bytes
        conn.bulkTransfer(eps.epOut, addrBuf, 8, TIMEOUT_MS)
        MtkBromConnection.write4BE(conn, eps.epOut, da2.size.toLong())

        // Read status
        val status = ByteArray(1)
        conn.bulkTransfer(eps.epIn, status, 1, TIMEOUT_MS)
        if (status[0].toInt() and 0xFF != 0) {
            say("⚠️ boot_to status: 0x${(status[0].toInt() and 0xFF).toString(16)}")
        }

        // Send DA2
        return MtkBromConnection.sendBytes(conn, eps.epOut, da2)
    }
}
