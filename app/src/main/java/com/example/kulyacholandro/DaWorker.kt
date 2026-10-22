package com.example.kulyacholandro

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class DaWorker(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val da1: ByteArray,
    private val da2: ByteArray,
    private val payload: ByteArray,
    private val onDone: (Boolean, String) -> Unit
) : Thread() {

    private val TAG = "DaWorker"

    override fun run() {
        try {
            val eps = MtkBromConnection.findBulkEndpoints(device)
            if (eps == null) {
                onDone(false, "❌ לא נמצאו endpoints")
                return
            }

            val conn = usbManager.openDevice(device)
            if (conn == null) {
                onDone(false, "❌ לא ניתן לפתוח חיבור USB")
                return
            }

            if (!conn.claimInterface(eps.usbInterface, true)) {
                conn.close()
                onDone(false, "❌ לא ניתן לתפוס interface")
                return
            }

            // Handshake
            if (!MtkBromConnection.performBromHandshake(conn, eps)) {
                conn.releaseInterface(eps.usbInterface)
                conn.close()
                onDone(false, "❌ Handshake נכשל")
                return
            }

            // בדוק chip
            val hwCode = MtkBromConnection.readHwCode(conn, eps)
            if (hwCode != 0x0717) {
                conn.releaseInterface(eps.usbInterface)
                conn.close()
                onDone(false, "❌ Chip לא MT6761 (קיבלנו: 0x${hwCode?.toString(16) ?: "null"})")
                return
            }

            MtkBromConnection.onLog("✅ MT6761 מאומת")

            // הפעל Kamakiri
            val result = KamakiriExploit.run(conn, eps, payload)

            conn.releaseInterface(eps.usbInterface)
            conn.close()

            if (!result) {
                onDone(false, "❌ Kamakiri נכשל")
                return
            }

            MtkBromConnection.onLog("⏳ ממתין לחיבור מחדש (5 שניות)...")
            sleep(5000)
            onDone(true, "✅ Kamakiri הצליח — חבר מחדש את המכשיר לטעינת DA")

        } catch (e: Exception) {
            Log.e(TAG, "שגיאה", e)
            onDone(false, "❌ שגיאה: ${e.message}")
        }
    }
}
