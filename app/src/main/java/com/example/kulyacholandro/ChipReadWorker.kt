package com.example.kulyacholandro

import android.hardware.usb.UsbDeviceConnection
import android.util.Log

/**
 * Worker שמבצע קריאת chip info בthread נפרד (לא UI thread)
 */
class ChipReadWorker(
    private val conn: UsbDeviceConnection,
    private val eps: MtkBromConnection.BulkEndpoints,
    private val mode: MtkMode,
    private val onResult: (MtkChipInfo?) -> Unit,
    private val onLog: (String) -> Unit
) {

    private val TAG = "ChipReadWorker"

    fun run() {
        Thread {
            try {
                MtkBromConnection.onLog = onLog

                onLog("ממשק: ${eps.usbInterface.id}, class=${eps.usbInterface.interfaceClass}")
                onLog("IN=0x${"%02X".format(eps.epIn.address)}  OUT=0x${"%02X".format(eps.epOut.address)}")

                val claimed = conn.claimInterface(eps.usbInterface, true)
                if (!claimed) {
                    onLog("❌ לא ניתן לתפוס ממשק USB")
                    onResult(null)
                    return@Thread
                }
                onLog("✅ ממשק נתפס")

                val chipInfo = when (mode) {
                    MtkMode.BROM -> {
                        onLog("מצב BROM — מבצע handshake...")
                        MtkBromConnection.readChipInfoBrom(conn, eps)
                    }
                    MtkMode.PRELOADER -> {
                        onLog("מצב Preloader — שולח GET_CHIP_ID...")
                        MtkBromConnection.readChipInfoPreloader(conn, eps)
                    }
                    MtkMode.UNKNOWN -> {
                        onLog("מצב לא מוכר — מנסה BROM handshake...")
                        MtkBromConnection.readChipInfoBrom(conn, eps)
                            ?: MtkBromConnection.readChipInfoPreloader(conn, eps)
                    }
                }

                if (chipInfo != null) {
                    onLog("✅ שבב זוהה: ${chipInfo.chipName}")
                } else {
                    onLog("❌ לא ניתן לקרוא מידע שבב")
                    onLog("לניסיון נוסף: נתק את הטלפון וחבר אותו מחדש (כשהוא כבוי)")
                }

                onResult(chipInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in ChipReadWorker", e)
                onLog("❌ שגיאה: ${e.javaClass.simpleName}: ${e.message}")
                onResult(null)
            }
        }.start()
    }
}
