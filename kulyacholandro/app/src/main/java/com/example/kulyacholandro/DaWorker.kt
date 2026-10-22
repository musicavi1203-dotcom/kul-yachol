package com.example.kulyacholandro

import android.content.Context
import android.hardware.usb.*

/**
 * Background worker: runs Kamakiri exploit + loads DA1/DA2
 */
class DaWorker(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val onLog: (String) -> Unit,
    private val onDone: (success: Boolean) -> Unit
) {
    fun start() {
        Thread {
            MtkBromConnection.onLog = onLog
            KamakiriExploit.onLog = onLog
            DaLoader.onLog = onLog
            run()
        }.start()
    }

    private fun run() {
        // Load binary assets
        val da1: ByteArray
        val da2: ByteArray
        val payload: ByteArray
        try {
            da1 = context.assets.open("da/da1_mt6761.bin").readBytes()
            da2 = context.assets.open("da/da2_mt6761.bin").readBytes()
            payload = context.assets.open("da/mt6761_payload.bin").readBytes()
            onLog("📁 DA1: ${da1.size} bytes, DA2: ${da2.size} bytes, payload: ${payload.size} bytes")
        } catch (e: Exception) {
            onLog("❌ לא ניתן לטעון קבצי DA: ${e.message}")
            onDone(false)
            return
        }

        val iface = findMtkInterface(device)
        if (iface == null) {
            onLog("❌ לא נמצא interface MTK USB")
            onDone(false)
            return
        }
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            onLog("❌ לא ניתן לפתוח מכשיר")
            onDone(false)
            return
        }
        if (!conn.claimInterface(iface, true)) {
            onLog("❌ claim interface נכשל")
            conn.close()
            onDone(false)
            return
        }
        try {
            val eps = MtkBromConnection.findEndpoints(iface)
            if (eps == null) {
                onLog("❌ לא נמצאו endpoints")
                onDone(false)
                return
            }

            // Handshake first
            if (!MtkBromConnection.doHandshake(conn, eps.epOut, eps.epIn)) {
                onLog("❌ Handshake נכשל לפני exploit")
                onDone(false)
                return
            }

            // Run Kamakiri
            val exploitOk = KamakiriExploit.run(conn, device, iface, eps, payload)
            if (!exploitOk) {
                onLog("❌ Exploit נכשל")
                onDone(false)
                return
            }

            // Device will disconnect — DA loading continues after reconnect
            // The user will need to reconnect and press "טען DA" again
            // OR we can try to wait for reconnect here
            onLog("⏳ ממתין להתחברות מחדש (5 שניות)...")
            Thread.sleep(5000)

            onLog("💡 אם המכשיר התחבר מחדש, טען DA כעת")
            onDone(true)
        } finally {
            try { conn.releaseInterface(iface) } catch (_: Exception) {}
            conn.close()
        }
    }

    private fun findMtkInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 0xFF) return iface
        }
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }
}
