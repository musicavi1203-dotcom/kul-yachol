package com.example.kulyacholandro

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private var currentDevice: UsbDevice? = null
    private var lastHwCode: Int? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnReadChip: Button
    private lateinit var btnLoadDA: Button
    private lateinit var tvScatter: TextView
    private lateinit var tvFirmwareDir: TextView

    private val ACTION_USB_PERMISSION = "com.example.kulyacholandro.USB_PERMISSION"
    private val PREFS_KEY_FIRMWARE = "firmware_uri"
    private val PREFS_KEY_BACKUP = "backup_uri"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { handleDeviceAttached(it) }
                    } else {
                        appendLog("❌ הרשאה USB נדחתה")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { requestOrConnect(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("🔌 הטלפון נותק")
                    currentDevice = null
                    tvStatus.text = getString(R.string.status_disconnected)
                    btnReadChip.isEnabled = false
                    btnLoadDA.isEnabled = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnReadChip = findViewById(R.id.btnReadChip)
        btnLoadDA = findViewById(R.id.btnLoadDA)
        tvScatter = findViewById(R.id.tvScatter)
        tvFirmwareDir = findViewById(R.id.tvFirmwareDir)

        btnReadChip.isEnabled = false
        btnLoadDA.isEnabled = false

        btnReadChip.setOnClickListener {
            currentDevice?.let { startChipRead(it) }
        }

        btnLoadDA.setOnClickListener {
            currentDevice?.let { startDaLoad(it) }
        }

        findViewById<Button>(R.id.btnSelectFirmware).setOnClickListener {
            openDirPicker(REQ_FIRMWARE_DIR)
        }

        // Register USB events
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)

        // Check already-connected device
        usbManager.deviceList.values.forEach { requestOrConnect(it) }

        // Load scatter info
        loadScatterInfo()

        // Restore firmware dir
        val prefs = getPreferences(MODE_PRIVATE)
        prefs.getString(PREFS_KEY_FIRMWARE, null)?.let { uri ->
            tvFirmwareDir.text = Uri.parse(uri).lastPathSegment ?: uri
        }

        appendLog("👋 כל יכול אנדרואיד מוכן")
        appendLog("📱 חבר טלפון MTK במצב BROM (כבוי + כבל USB)")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun requestOrConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            handleDeviceAttached(device)
        } else {
            val intent = Intent(ACTION_USB_PERMISSION)
            val pi = PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        currentDevice = device
        val vid = device.vendorId
        val pid = device.productId
        tvStatus.text = getString(R.string.status_connected)
        appendLog("✅ מכשיר USB: VID=0x${vid.toString(16).uppercase()} PID=0x${pid.toString(16).uppercase()}")
        appendLog("   ${device.manufacturerName ?: "?"} / ${device.productName ?: "?"}")
        appendLog("💡 לחץ על 'קרא מידע שבב' כדי להתחיל")
        btnReadChip.isEnabled = true
        btnLoadDA.isEnabled = false
    }

    private fun startChipRead(device: UsbDevice) {
        btnReadChip.isEnabled = false
        btnLoadDA.isEnabled = false
        appendLog("─────────────────")
        ChipReadWorker(this, usbManager, device,
            onLog = { msg -> runOnUiThread { appendLog(msg) } },
            onDone = { hwCode ->
                runOnUiThread {
                    lastHwCode = hwCode
                    btnReadChip.isEnabled = true
                    if (hwCode != null) {
                        btnLoadDA.isEnabled = true
                    }
                }
            }
        ).start()
    }

    private fun startDaLoad(device: UsbDevice) {
        btnReadChip.isEnabled = false
        btnLoadDA.isEnabled = false
        appendLog("─────────────────")
        appendLog("🔓 מתחיל טעינת DA...")
        DaWorker(this, usbManager, device,
            onLog = { msg -> runOnUiThread { appendLog(msg) } },
            onDone = { success ->
                runOnUiThread {
                    btnReadChip.isEnabled = true
                    if (success) {
                        appendLog("✅ DA נטען — מוכן לפעולות!")
                        btnLoadDA.isEnabled = false // wait for reconnect
                    } else {
                        btnLoadDA.isEnabled = true
                    }
                }
            }
        ).start()
    }

    private fun loadScatterInfo() {
        try {
            val text = assets.open("scatter/MT6761_Android_scatter.txt").bufferedReader().readText()
            val platform = ScatterParser.platform(text)
            val partitions = ScatterParser.parse(text)
            val downloadable = partitions.count { it.isDownload }
            tvScatter.text = "Scatter: $platform | ${partitions.size} partitions | $downloadable להורדה"
        } catch (e: Exception) {
            tvScatter.text = "Scatter: שגיאה: ${e.message}"
        }
    }

    private val REQ_FIRMWARE_DIR = 1001

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        when (requestCode) {
            REQ_FIRMWARE_DIR -> {
                getPreferences(MODE_PRIVATE).edit().putString(PREFS_KEY_FIRMWARE, uri.toString()).apply()
                tvFirmwareDir.text = uri.lastPathSegment ?: uri.toString()
                appendLog("📁 תיקיית firmware: ${uri.lastPathSegment}")
            }
        }
    }

    private fun openDirPicker(reqCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, reqCode)
    }

    fun appendLog(msg: String) {
        tvLog.append("$msg\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
