package com.example.kulyacholandro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.kulyacholandro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private var currentDevice: UsbDevice? = null

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.kulyacholandro.USB_PERMISSION"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let { handleDeviceAttached(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    currentDevice = null
                    showDisconnected()
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        binding.btnRequestPermission.visibility = View.GONE
                        showDeviceInfo(device)
                        startChipRead(device)
                    } else {
                        appendLog("❌ הרשאת USB נדחתה")
                        binding.btnRequestPermission.text = getString(R.string.permission_denied)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)

        binding.btnRequestPermission.setOnClickListener {
            requestPermissionForFirstMtkDevice()
        }

        binding.btnReadChip.setOnClickListener {
            currentDevice?.let { dev ->
                if (usbManager.hasPermission(dev)) {
                    startChipRead(dev)
                } else {
                    requestPermissionForFirstMtkDevice()
                }
            }
        }

        checkAlreadyConnectedDevices()

        intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
            handleDeviceAttached(it)
        }
    }

    private fun checkAlreadyConnectedDevices() {
        usbManager.deviceList.values.forEach { device ->
            if (MtkBromConnection.isMtkDevice(device)) {
                handleDeviceAttached(device)
                return
            }
        }
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        if (!MtkBromConnection.isMtkDevice(device)) {
            binding.tvStatus.text = getString(R.string.status_unknown)
            binding.cardDeviceInfo.visibility = View.GONE
            binding.btnRequestPermission.visibility = View.GONE
            binding.btnReadChip.visibility = View.GONE
            return
        }

        currentDevice = device

        if (usbManager.hasPermission(device)) {
            showDeviceInfo(device)
            startChipRead(device)
        } else {
            binding.tvStatus.text = getString(R.string.status_connected)
            binding.tvStatusIcon.text = "🔌"
            binding.btnRequestPermission.visibility = View.VISIBLE
            binding.btnRequestPermission.text = getString(R.string.btn_request_permission)
            binding.cardDeviceInfo.visibility = View.GONE
            binding.btnReadChip.visibility = View.GONE
            appendLog("מכשיר MTK חובר — דרושה הרשאת USB")
        }
    }

    private fun requestPermissionForFirstMtkDevice() {
        val device = usbManager.deviceList.values
            .firstOrNull { MtkBromConnection.isMtkDevice(it) } ?: return
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, pi)
    }

    private fun showDeviceInfo(device: UsbDevice) {
        val info = MtkBromConnection.getDeviceInfo(device)

        binding.tvStatus.text = getString(R.string.status_connected)
        binding.tvStatusIcon.text = "✅"
        binding.btnRequestPermission.visibility = View.GONE
        binding.cardDeviceInfo.visibility = View.VISIBLE
        binding.btnReadChip.visibility = View.VISIBLE

        binding.tvVid.text = "VID: ${MtkBromConnection.formatVid(info.vid)}"
        binding.tvPid.text = "PID: ${MtkBromConnection.formatPid(info.pid)}"
        binding.tvManufacturer.text = "יצרן: ${info.manufacturer ?: "—"}"
        binding.tvProduct.text = "מוצר: ${info.product ?: "—"}"

        val modeStr = when (info.mode) {
            MtkMode.BROM       -> getString(R.string.mode_brom)
            MtkMode.PRELOADER  -> getString(R.string.mode_preloader)
            MtkMode.UNKNOWN    -> getString(R.string.mode_unknown)
        }
        binding.tvMode.text = "מצב: $modeStr"

        binding.cardChipInfo.visibility = View.GONE
    }

    private fun startChipRead(device: UsbDevice) {
        val eps = MtkBromConnection.findBulkEndpoints(device)
        if (eps == null) {
            appendLog("❌ לא נמצאו Bulk endpoints במכשיר")
            return
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            appendLog("❌ לא ניתן לפתוח חיבור USB")
            return
        }

        val mode = MtkBromConnection.getDeviceMode(device)
        clearLog()
        appendLog("מתחבר למכשיר במצב ${mode.name}...")

        binding.progressChip.visibility = View.VISIBLE
        binding.btnReadChip.isEnabled = false

        ChipReadWorker(
            conn  = conn,
            eps   = eps,
            mode  = mode,
            onResult = { chipInfo ->
                runOnUiThread {
                    binding.progressChip.visibility = View.GONE
                    binding.btnReadChip.isEnabled = true
                    conn.close()
                    if (chipInfo != null) {
                        showChipInfo(chipInfo)
                    }
                }
            },
            onLog = { msg ->
                runOnUiThread { appendLog(msg) }
            }
        ).run()
    }

    private fun showChipInfo(chipInfo: MtkChipInfo) {
        binding.cardChipInfo.visibility = View.VISIBLE
        binding.tvChipName.text = chipInfo.chipName
        binding.tvChipId.text = "Chip ID: ${MtkBromConnection.formatHex(chipInfo.chipId)}"
        if (chipInfo.hwVer != 0 || chipInfo.swVer != 0) {
            binding.tvChipVer.visibility = View.VISIBLE
            binding.tvChipVer.text =
                "HW: ${MtkBromConnection.formatHex(chipInfo.hwVer)}  " +
                "SW: ${MtkBromConnection.formatHex(chipInfo.swVer)}"
        } else {
            binding.tvChipVer.visibility = View.GONE
        }
    }

    private fun appendLog(msg: String) {
        val current = binding.tvLog.text.toString()
        val newText = if (current.isEmpty()) msg else "$current\n$msg"
        binding.tvLog.text = newText
        binding.cardLog.visibility = View.VISIBLE
        // גלול למטה
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun clearLog() {
        binding.tvLog.text = ""
    }

    private fun showDisconnected() {
        binding.tvStatus.text = getString(R.string.status_disconnected)
        binding.tvStatusIcon.text = "🔌"
        binding.cardDeviceInfo.visibility = View.GONE
        binding.cardChipInfo.visibility = View.GONE
        binding.btnRequestPermission.visibility = View.GONE
        binding.btnReadChip.visibility = View.GONE
        binding.progressChip.visibility = View.GONE
        appendLog("המכשיר נותק")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
