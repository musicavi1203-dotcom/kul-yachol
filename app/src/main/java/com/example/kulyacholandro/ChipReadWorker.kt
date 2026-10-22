package com.example.kulyacholandro

import android.content.Context
import android.hardware.usb.*

class ChipReadWorker(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val onLog: (String) -> Unit,
    private val onDone: (hwCode: Int?) -> Unit
) {
    fun start() {
        Thread {
            MtkBromConnection.onLog = onLog
            run()
        }.start()
    }

    private fun run() {
        val iface = findMtkInterface(device)
        if (iface == null) {
            onLog("❌ לא נמצא interface MTK USB")
            onDone(null)
            return
        }
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            onLog("❌ לא ניתן לפתוח את המכשיר")
            onDone(null)
            return
        }
        if (!conn.claimInterface(iface, true)) {
            onLog("❌ claim interface נכשל")
            conn.close()
            onDone(null)
            return
        }
        try {
            val eps = MtkBromConnection.findEndpoints(iface)
            if (eps == null) {
                onLog("❌ לא נמצאו endpoints")
                onDone(null)
                return
            }
            onLog("🔌 Interface נתפס, endpoints: IN=${eps.epIn.endpointNumber} OUT=${eps.epOut.endpointNumber}")

            if (!MtkBromConnection.doHandshake(conn, eps.epOut, eps.epIn)) {
                onLog("❌ Handshake נכשל")
                onLog("💡 לניסיון נוסף: נתק את הטלפון, כבה אותו לחלוטין, וחבר שוב")
                onDone(null)
                return
            }

            val hwCode = MtkBromConnection.readHwCode(conn, eps)
            if (hwCode == null) {
                onLog("❌ קריאת HW code נכשלה")
                onDone(null)
                return
            }

            MtkBromConnection.readHwSwVer(conn, eps)

            onLog("✅ קריאת שבב הושלמה!")
            onLog("💡 ניתן לטעון DA עכשיו")
            onDone(hwCode)
        } finally {
            conn.releaseInterface(iface)
            conn.close()
        }
    }

    private fun findMtkInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 0xFF) return iface
        }
        // Fallback: first interface
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }
}
