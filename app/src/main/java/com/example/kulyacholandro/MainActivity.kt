package com.example.kulyacholandro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.kulyacholandro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private var currentDevice: UsbDevice? = null

    private var scatterPartitions: List<ScatterPartition> = emptyList()
    private var scatterPlatform: String? = null
    private var firmwareTree: Uri? = null
    private var backupTree: Uri? = null

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.kulyacholandro.USB_PERMISSION"
    }

    private val prefs by lazy { getSharedPreferences("kul_yachol", Context.MODE_PRIVATE) }

    private val pickFirmware = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            firmwareTree = uri
            prefs.edit().putString("firmware_tree", uri.toString()).apply()
            refreshFlashInfo()
        }
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            backupTree = uri
            prefs.edit().putString("backup_tree", uri.toString()).apply()
            refreshFlashInfo()
        }
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
                        appendLog("ההרשאה אושרה. לחץ על 'קרא מידע שבב' כדי להתחיל")
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

        binding.btnLoadDA.setOnClickListener {
            currentDevice?.let { dev ->
                if (usbManager.hasPermission(dev)) {
                    startDaLoad(dev)
                } else {
                    requestPermissionForFirstMtkDevice()
                }
            }
        }

        binding.btnPickFirmware.setOnClickListener { pickFirmware.launch(null) }
        binding.btnPickBackup.setOnClickListener { pickBackup.launch(null) }

        // scatter ו-DA מוטמעים באפליקציה, ותיקיות נשמרות מהבחירה הקודמת
        loadBundledScatter()
        prefs.getString("firmware_tree", null)?.let { firmwareTree = Uri.parse(it) }
        prefs.getString("backup_tree", null)?.let { backupTree = Uri.parse(it) }
        refreshFlashInfo()
        checkAlreadyConnectedDevices()

        intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
            handleDeviceAttached(it)
        }
    }

    // ---------- scatter / קושחה / גיבוי (קריאה בלבד) ----------

    private fun loadBundledScatter() {
        val text = try {
            assets.open("scatter/MT6761_Android_scatter.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        if (text.isBlank()) {
            binding.tvFlashInfo.text = "❌ קובץ ה-scatter המוטמע לא נמצא"
            return
        }
        scatterPlatform = ScatterParser.platform(text)
        scatterPartitions = ScatterParser.parse(text)
    }

    private fun baseName(name: String): String =
        name.lowercase().replace("-verified", "").substringBeforeLast('.')

    private fun findFirmwareFile(scatterFile: String, available: Set<String>): String? {
        if (scatterFile in available) return scatterFile
        val want = baseName(scatterFile)
        return available.firstOrNull { baseName(it) == want }
    }

    private fun listFirmwareFiles(treeUri: Uri): Map<String, Uri> {
        val out = mutableMapOf<String, Uri>()
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1)
                    out[name] = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        } catch (e: Exception) {
            // תיקייה לא נגישה — מחזירים ריק
        }
        return out
    }

    private fun formatSize(b: Long): String = when {
        b >= 1L shl 30 -> "%.1fGB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.0fMB".format(b / (1L shl 20).toDouble())
        else -> "${b / 1024}KB"
    }

    private fun refreshFlashInfo() {
        val sb = StringBuilder()
        if (scatterPartitions.isEmpty()) {
            sb.append("scatter: לא נבחר\n")
        } else {
            sb.append("scatter: ${scatterPlatform ?: "?"}, ${scatterPartitions.size} מחיצות\n")
        }
        sb.append("קושחה: ${if (firmwareTree == null) "לא נבחרה" else "נבחרה"}\n")
        sb.append("גיבוי: ${if (backupTree == null) "לא נבחר" else "נבחר"}\n")

        if (scatterPartitions.isNotEmpty()) {
            val files = firmwareTree?.let { listFirmwareFiles(it) }?.keys ?: emptySet()
            sb.append("\nמחיצות להורדה (גודל מחיצה / קובץ):\n")
            for (p in scatterPartitions.filter { it.isDownload }) {
                val status = when {
                    p.fileName == "NONE" -> "—"
                    firmwareTree == null -> "?"
                    else -> findFirmwareFile(p.fileName, files)?.let { "✅ $it" } ?: "❌ חסר (${p.fileName})"
                }
                sb.append("${p.name} (${formatSize(p.sizeBytes)}) $status\n")
            }
        }
        binding.tvFlashInfo.text = sb.toString()
    }

    // ---------- USB ----------

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
            appendLog("הטלפון התחבר. לחץ על 'קרא מידע שבב' כדי להתחיל")
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
        binding.btnLoadDA.visibility = View.VISIBLE

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

    private fun startDaLoad(device: UsbDevice) {
        val da1 = try { assets.open("da/da1_mt6761.bin").readBytes() } catch (e: Exception) {
            appendLog("❌ da1_mt6761.bin לא נמצא"); return
        }
        val da2 = try { assets.open("da/da2_mt6761.bin").readBytes() } catch (e: Exception) {
            appendLog("❌ da2_mt6761.bin לא נמצא"); return
        }
        val payload = try { assets.open("da/mt6761_payload.bin").readBytes() } catch (e: Exception) {
            appendLog("❌ mt6761_payload.bin לא נמצא"); return
        }

        appendLog("🔓 מתחיל Kamakiri + DA loading...")
        binding.btnLoadDA.isEnabled = false

        MtkBromConnection.onLog = { msg -> runOnUiThread { appendLog(msg) } }

        DaWorker(
            usbManager = usbManager,
            device     = device,
            da1        = da1,
            da2        = da2,
            payload    = payload,
            onDone     = { success, msg ->
                runOnUiThread {
                    appendLog(msg)
                    binding.btnLoadDA.isEnabled = true
                }
            }
        ).start()
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
