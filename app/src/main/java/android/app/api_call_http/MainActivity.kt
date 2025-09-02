package android.app.api_call_http

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler // <-- Tambahkan ini
import android.os.Looper // <-- Tambahkan ini
import android.os.SystemClock
import android.provider.Settings // <-- Tambahkan ini
import android.util.Log
import android.webkit.URLUtil
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher // <-- Tambahkan ini
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider // <-- Tambahkan ini
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var ipAddressTextView: TextView
    private lateinit var deviceNameTextView: TextView
    private lateinit var rebootDeviceButton: Button

    private lateinit var downloadApkButton: Button
    private lateinit var apkDownloadStatusTextView: TextView

    private lateinit var sdf: SimpleDateFormat
    private var apkDownloadID: Long = -1L

    private val APK_DOWNLOAD_URL = ""
    private val APK_FILE_NAME = "" // Nama file yang diinginkan jika DownloadManager tidak bisa menebak

    private lateinit var requestInstallPackagesLauncher: ActivityResultLauncher<Intent>
    private var pendingInstallApkUri: Uri? = null


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                if (!it.value) {
                    allGranted = false
                }
            }
            if (allGranted) {
                startApkDownload()
            } else {
                Toast.makeText(this, "Izin diperlukan untuk mengunduh file.", Toast.LENGTH_LONG).show()
                apkDownloadStatusTextView.text = "Status Unduh APK: Izin ditolak."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        ipAddressTextView = findViewById(R.id.ipAddressTextView)
        deviceNameTextView = findViewById(R.id.deviceNameTextView)
        rebootDeviceButton = findViewById(R.id.rebootDeviceButton)

        downloadApkButton = findViewById(R.id.downloadApkButton)
        apkDownloadStatusTextView = findViewById(R.id.apkDownloadStatusTextView)

        sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val ipAddress = getIpAddress()
        ipAddressTextView.text = "IP: $ipAddress"

        val deviceName = getDeviceName()
        deviceNameTextView.text = "Perangkat: $deviceName"

        sendDataToServer(deviceName, ipAddress)

        rebootDeviceButton.setOnClickListener {
            val uptimeMillis = SystemClock.elapsedRealtime()
            val formattedUptime = formatUptime(uptimeMillis)
            val currentTimeMillis = System.currentTimeMillis()
            val lastRebootTimeMillis = currentTimeMillis - uptimeMillis
            val formattedLastRebootTime = sdf.format(Date(lastRebootTimeMillis))
            val suggestedRestartIntervalMillis = 8 * 60 * 60 * 1000L
            val suggestedIntervalHours = suggestedRestartIntervalMillis / (60 * 60 * 1000)
            val nextSuggestedRebootTimeMillis = lastRebootTimeMillis + suggestedRestartIntervalMillis
            val formattedNextSuggestedRebootTime = sdf.format(Date(nextSuggestedRebootTimeMillis))

            val message = StringBuilder()
            message.append("Lama Perangkat Menyala:\n$formattedUptime\n\n")
            message.append("Perkiraan Terakhir Direstart:\n$formattedLastRebootTime\n\n")
            message.append("Interval Restart Disarankan:\n$suggestedIntervalHours jam\n\n")
            message.append("Saran Restart Berikutnya:\n$formattedNextSuggestedRebootTime")

            AlertDialog.Builder(this)
                .setTitle("Info Uptime & Restart")
                .setMessage(message.toString())
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        downloadApkButton.setOnClickListener {
            checkPermissionsAndStartApkDownload()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadCompleteApk, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        } else {
            registerReceiver(onDownloadCompleteApk, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }

        // Inisialisasi launcher untuk intent Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
        requestInstallPackagesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            // Setelah kembali dari layar pengaturan, cek lagi izinnya
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (packageManager.canRequestPackageInstalls()) {
                    pendingInstallApkUri?.let {
                        installApk(it)
                        pendingInstallApkUri = null // Reset
                    }
                } else {
                    Toast.makeText(this, "Izin menginstal aplikasi tidak diberikan.", Toast.LENGTH_LONG).show()
                }
            }
        }

        startDeviceCheckService()
    }

    private fun checkPermissionsAndStartApkDownload() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isEmpty()) {
            startApkDownload()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startApkDownload() {
        if (!URLUtil.isValidUrl(APK_DOWNLOAD_URL)) {
            apkDownloadStatusTextView.text = "Status Unduh APK: URL tidak valid."
            Toast.makeText(this, "URL APK tidak valid.", Toast.LENGTH_LONG).show()
            logApkDownloadToServer(APK_FILE_NAME, "FAILED_INVALID_URL", sdf.format(Date()), APK_DOWNLOAD_URL)
            return
        }

        // Hapus file lama jika ada (untuk memastikan kita mendapatkan versi terbaru jika nama sama)
        val destination = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
        if (destination.exists()) {
            destination.delete()
        }


        apkDownloadStatusTextView.text = "Status Unduh APK: Memulai unduhan $APK_FILE_NAME..."
        val request = DownloadManager.Request(Uri.parse(APK_DOWNLOAD_URL))
            .setTitle(APK_FILE_NAME)
            .setDescription("Mengunduh APK Kasir...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            apkDownloadID = downloadManager.enqueue(request)
            Toast.makeText(this, "Unduhan APK dimulai...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error memulai unduhan APK", e)
            Toast.makeText(this, "Gagal memulai unduhan APK: ${e.message}", Toast.LENGTH_LONG).show()
            apkDownloadStatusTextView.text = "Status Unduh APK: Gagal memulai."
            logApkDownloadToServer(APK_FILE_NAME, "FAILED_TO_START", sdf.format(Date()), e.message ?: "Enqueue error")
        }
    }

    private val onDownloadCompleteApk: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (apkDownloadID == id) {
                val query = DownloadManager.Query().setFilterById(id)
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val cursor: Cursor? = downloadManager.query(query)

                if (cursor != null && cursor.moveToFirst()) {
                    val statusColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val reasonColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    // val localUriColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI); // Cara lama

                    val status = if(statusColumnIndex != -1) cursor.getInt(statusColumnIndex) else -1
                    val reason = if(reasonColumnIndex != -1) cursor.getInt(reasonColumnIndex) else 0

                    var downloadDetails = "Reason Code: $reason"
                    var logStatus = "UNKNOWN"
                    var isSuccess = false
                    var apkUriForInstall: Uri? = null // Variabel baru untuk menyimpan URI yang akan diinstal

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            // CARA BARU dan LEBIH BAIK untuk mendapatkan URI yang bisa di-share:
                            apkUriForInstall = downloadManager.getUriForDownloadedFile(id)

                            if (apkUriForInstall != null) {
                                apkDownloadStatusTextView.text = "Status Unduh APK: '$APK_FILE_NAME' berhasil!"
                                Toast.makeText(context, "'$APK_FILE_NAME' berhasil diunduh!", Toast.LENGTH_SHORT).show()
                                downloadDetails = "Disimpan, URI: $apkUriForInstall"
                                isSuccess = true
                                promptInstallApk(apkUriForInstall) // Memicu instalasi dengan URI yang didapat
                            } else {
                                apkDownloadStatusTextView.text = "Status Unduh APK: Berhasil tapi URI tidak valid."
                                Toast.makeText(context, "Unduhan berhasil, tapi URI file tidak bisa didapatkan.", Toast.LENGTH_LONG).show()
                                downloadDetails = "Berhasil tetapi getUriForDownloadedFile null."
                            }
                            logStatus = "SUCCESS"
                        }
                        DownloadManager.STATUS_FAILED -> {
                            apkDownloadStatusTextView.text = "Status Unduh APK: Gagal. Alasan: $reason"
                            Toast.makeText(context, "Unduhan '$APK_FILE_NAME' gagal. Alasan: $reason", Toast.LENGTH_LONG).show()
                            logStatus = "FAILED"
                        }
                        // ... (status lain jika perlu penanganan khusus)
                        else -> {
                            apkDownloadStatusTextView.text = "Status Unduh APK: Tidak diketahui ($status), Alasan: $reason"
                            logStatus = "UNKNOWN_STATUS_$status"
                        }
                    }
                    logApkDownloadToServer(APK_FILE_NAME, logStatus, sdf.format(Date()), downloadDetails)

                    // Hilangkan pesan status setelah beberapa detik
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (apkDownloadStatusTextView.text.toString().startsWith("Status Unduh APK: '$APK_FILE_NAME' berhasil!")) {
                            // Biarkan pesan sukses, atau reset jika mau
                            // apkDownloadStatusTextView.text = "Status Unduh APK: -"
                        } else if (!isSuccess && apkDownloadStatusTextView.text.toString().contains(APK_FILE_NAME)){
                            // Reset jika gagal atau status lain setelah beberapa waktu
                            apkDownloadStatusTextView.text = "Status Unduh APK: -"
                        }
                    }, 10000) // Hilang setelah 10 detik

                }
                cursor?.close()
            }
        }
    }

    private fun promptInstallApk(apkUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                apkDownloadStatusTextView.text = "Status Unduh APK: Perlu izin instalasi."
                Toast.makeText(this, "Mohon izinkan aplikasi ini untuk menginstal dari sumber tidak dikenal.", Toast.LENGTH_LONG).show()
                pendingInstallApkUri = apkUri // Simpan URI untuk nanti
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                requestInstallPackagesLauncher.launch(intent)
                return
            }
        }
        installApk(apkUri)
    }

    private fun installApk(apkUri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            Log.d("InstallAPK", "Attempting to install APK from URI: $apkUri")
            var actualApkUri = apkUri

            // Untuk Android N ke atas, kita mungkin perlu menggunakan FileProvider jika URI dari DownloadManager
            // tidak secara langsung dapat di-grant. Namun, biasanya URI DownloadManager sudah content://
            // yang bisa di-share. Jika file disimpan di direktori internal aplikasi, FileProvider WAJIB.
            // Karena kita menyimpan di DIRECTORY_DOWNLOADS, mari coba dulu dengan URI langsung.
            // Jika gagal, FileProvider menjadi langkah berikutnya.
            //
            // Jika URI adalah file:// (jarang dari DownloadManager modern), maka perlu FileProvider untuk N+
            if ("file".equals(apkUri.scheme, ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val apkFile = File(apkUri.path!!) // Pastikan path tidak null
                actualApkUri = FileProvider.getUriForFile(this@MainActivity, "${applicationContext.packageName}.provider", apkFile)
                Log.d("InstallAPK", "Using FileProvider URI: $actualApkUri")
            }

            setDataAndType(actualApkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Diperlukan jika dipanggil dari konteks non-Activity
        }
        try {
            apkDownloadStatusTextView.text = "Status Unduh APK: Memulai instalasi..."
            startActivity(installIntent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting APK install intent", e)
            Toast.makeText(this, "Gagal memulai instalasi APK: ${e.message}", Toast.LENGTH_LONG).show()
            apkDownloadStatusTextView.text = "Status Unduh APK: Gagal memulai instalasi."
            logApkDownloadToServer(APK_FILE_NAME, "INSTALL_FAILED_TO_START", sdf.format(Date()), e.message ?: "ActivityNotFound")
        }
    }


    private fun logApkDownloadToServer(fileName: String, status: String, timestamp: String, details: String) {
        val queue = Volley.newRequestQueue(this)
        val url = "http://10.234.202.219:81/log_http_download.php"
        Log.d("LogAPKDownload", "Logging to server: File=$fileName, Status=$status, Time=$timestamp, Details=$details")

        val stringRequest = object : StringRequest(Request.Method.POST, url,
            { response ->
                Log.d("LogAPKDownload", "Server log response: $response")
            },
            { error ->
                Log.e("LogAPKDownload", "Error sending download log: ${error.toString()}")
            }) {
            override fun getParams(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["file_name"] = fileName
                params["download_status"] = status
                params["timestamp"] = timestamp
                params["details"] = details
                params["device_name"] = getDeviceName()
                params["ip_address"] = getIpAddress()
                return params
            }
        }
        queue.add(stringRequest)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(onDownloadCompleteApk)
    }

    private fun startDeviceCheckService() {
        val serviceIntent = Intent(this, DeviceCheckService::class.java)
        serviceIntent.action = DeviceCheckService.ACTION_START_SERVICE
        ContextCompat.startForegroundService(this, serviceIntent)
        Log.d("MainActivity", "Requested to start DeviceCheckService.")
    }

    @Suppress("DEPRECATION")
    private fun getIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return String.format(
            Locale.getDefault(),
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }

    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }
    private fun formatUptime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000) % 60
        val minutes = (milliseconds / (1000 * 60)) % 60
        val hours = (milliseconds / (1000 * 60 * 60)) % 24
        val days = milliseconds / (1000 * 60 * 60 * 24)

        val uptimeString = StringBuilder()
        if (days > 0) uptimeString.append("$days hari, ")
        if (hours > 0 || days > 0) uptimeString.append("$hours jam, ")
        if (minutes > 0 || hours > 0 || days > 0) uptimeString.append("$minutes menit, ")
        uptimeString.append("$seconds detik")
        return uptimeString.toString()
    }
    private fun sendDataToServer(deviceName: String, ipAddress: String) {
        Log.d("MainActivity", "Attempting to send data to server: Device=$deviceName, IP=$ipAddress")
        val queue = Volley.newRequestQueue(this)
        val url = "http://10.234.202.219:81/save_device.php"

        val stringRequest = object : StringRequest(Request.Method.POST, url,
            { response ->
                Log.d("MainActivity", "Response from server: $response")
                val updateIntent = Intent(this, DeviceCheckService::class.java)
                updateIntent.action = DeviceCheckService.ACTION_UPDATE_TIMESTAMP
                updateIntent.putExtra(DeviceCheckService.EXTRA_TIMESTAMP, System.currentTimeMillis())
                startService(updateIntent)
            },
            { error ->
                Log.e("MainActivity", "Error sending data: ${error.toString()}")
            }) {
            override fun getParams(): MutableMap<String, String> {
                val uptimeMillis = SystemClock.elapsedRealtime()
                val currentTimeMillis = System.currentTimeMillis()
                val lastRebootTimeMillis = currentTimeMillis - uptimeMillis
                val formattedLastRebootTime = sdf.format(Date(lastRebootTimeMillis))
                val params = HashMap<String, String>()
                params["device_name"] = deviceName
                params["ip_address"] = ipAddress
                params["last_reboot"] = formattedLastRebootTime
                return params
            }
        }
        queue.add(stringRequest)
    }
}
