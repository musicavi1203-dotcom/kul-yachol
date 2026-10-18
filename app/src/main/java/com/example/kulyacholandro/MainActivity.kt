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
                    showDisconnected()
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        binding.btnRequestPermission.text = getString(R.string.permission_granted)
                        binding.btnRequestPermission.isEnabled = false
                        showDeviceInfo(device)
                    } else {
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

        // בדוק אם מכשיר כבר מחובר
        checkAlreadyConnectedDevices()

        // אם האפליקציה נפתחה מחיבור USB
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
            return
        }

        if (usbManager.hasPermission(device)) {
            showDeviceInfo(device)
        } else {
            binding.tvStatus.text = getString(R.string.status_connected)
            binding.btnRequestPermission.visibility = View.VISIBLE
            binding.cardDeviceInfo.visibility = View.GONE
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
        binding.btnRequestPermission.visibility = View.GONE
        binding.cardDeviceInfo.visibility = View.VISIBLE

        binding.tvVid.text = "${getString(R.string.label_vendor_id)}: ${MtkBromConnection.formatVid(info.vid)}"
        binding.tvPid.text = "${getString(R.string.label_product_id)}: ${MtkBromConnection.formatPid(info.pid)}"
        binding.tvManufacturer.text = "${getString(R.string.label_manufacturer)}: ${info.manufacturer ?: "—"}"
        binding.tvProduct.text = "${getString(R.string.label_product)}: ${info.product ?: "—"}"

        val modeStr = when (info.mode) {
            MtkMode.BROM -> getString(R.string.mode_brom)
            MtkMode.PRELOADER -> getString(R.string.mode_preloader)
            MtkMode.UNKNOWN -> getString(R.string.mode_unknown)
        }
        binding.tvMode.text = "${getString(R.string.label_mode)}: $modeStr"
    }

    private fun showDisconnected() {
        binding.tvStatus.text = getString(R.string.status_disconnected)
        binding.cardDeviceInfo.visibility = View.GONE
        binding.btnRequestPermission.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
