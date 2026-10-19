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
                onLog("פותח ממשק USB...")
                val claimed = conn.claimInterface(eps.usbInterface, true)
                if (!claimed) {
                    onLog("❌ לא ניתן לתפוס ממשק USB")
                    onResult(null)
                    return@Thread
                }

                val chipInfo = when (mode) {
                    MtkMode.BROM -> {
                        onLog("מצב BROM — מבצע handshake...")
                        MtkBromConnection.readChipInfoBrom(conn, eps)
                    }
                    MtkMode.PRELOADER -> {
                        onLog("מצב Preloader — שולח פקודת GET_CHIP_ID...")
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
                }

                onResult(chipInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in ChipReadWorker", e)
                onLog("❌ שגיאה: ${e.message}")
                onResult(null)
            }
        }.start()
    }
}
