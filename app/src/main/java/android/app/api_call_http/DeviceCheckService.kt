package android.app.api_call_http

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceCheckService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sdf: SimpleDateFormat
    private var lastSuccessfulSendTimestamp = 0L
    private var serviceStartTime = 0L

    // For testing: 1 minute. For production: 5 * 60 * 1000L (5 minutes)
    private val checkIntervalMillis = 1 * 60 * 1000L
    private val conditionDurationMillis = 5 * 3600 * 1000L

    companion object {
        // Foreground Service Notification
        const val NOTIFICATION_CHANNEL_ID = "DeviceCheckChannel"
        const val NOTIFICATION_ID = 1

        // Stale Data Alert Notification
        const val STALE_DATA_NOTIFICATION_CHANNEL_ID = "StaleDeviceCheckChannel"
        const val STALE_DATA_NOTIFICATION_ID = 2 // Must be different from NOTIFICATION_ID

        // Service Actions
        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_UPDATE_TIMESTAMP = "ACTION_UPDATE_TIMESTAMP"
        const val EXTRA_TIMESTAMP = "EXTRA_TIMESTAMP"

        // Broadcast Actions & Extras for MainActivity communication
        const val ACTION_STATUS_UPDATE = "android.app.api_call_http.ACTION_STATUS_UPDATE"
        const val EXTRA_IS_STALE = "EXTRA_IS_STALE"
        const val EXTRA_LAST_SYNC_TIME = "EXTRA_LAST_SYNC_TIME"
        const val EXTRA_STATUS_MESSAGE = "EXTRA_STATUS_MESSAGE" // e.g., "Initial sync pending", "Data stale", "OK"

        // SharedPreferences
        private const val PREFS_NAME = "DeviceCheckPrefs"
        private const val KEY_LAST_SUCCESSFUL_SEND = "lastSuccessfulSendTimestamp"
        private const val TAG = "DeviceCheckService"
    }

    override fun onCreate() {
        super.onCreate()
        sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        serviceStartTime = System.currentTimeMillis()
        loadLastSuccessfulSendTimestamp()
        createNotificationChannels() // Updated to create both channels
        Log.d(TAG, "Service Created. Last successful send: ${formatTimestamp(lastSuccessfulSendTimestamp)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_START_SERVICE -> {
                    Log.d(TAG, "Starting service and foreground notification.")
                    startForeground(NOTIFICATION_ID, createForegroundServiceNotification("Monitoring device check-in..."))
                    handler.removeCallbacks(periodicCheckRunnable) // Ensure only one instance
                    handler.post(periodicCheckRunnable)
                }
                ACTION_STOP_SERVICE -> {
                    Log.d(TAG, "Stopping service.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_UPDATE_TIMESTAMP -> {
                    val newTimestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                    Log.d(TAG, "Updating lastSuccessfulSendTimestamp to: ${formatTimestamp(newTimestamp)}")
                    lastSuccessfulSendTimestamp = newTimestamp
                    saveLastSuccessfulSendTimestamp()
                    // Reset check and potentially clear stale notification if app is now responsive
                    handler.removeCallbacks(periodicCheckRunnable)
                    handler.post(periodicCheckRunnable) // This will run the check immediately and then reschedule
                }
            }
        }
        return START_STICKY
    }
    private fun formatUptime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000) % 60
        val minutes = (milliseconds / (1000 * 60)) % 60
        val hours = (milliseconds / (1000 * 60 * 60)) % 24
        val days = milliseconds / (1000 * 60 * 60 * 24)

        val uptimeString = StringBuilder()
        if (days > 0) uptimeString.append("$days hari, ")
        if (hours > 0 || days > 0) uptimeString.append("$hours jam, ") // Tampilkan jam jika ada hari
        if (minutes > 0 || hours > 0 || days > 0) uptimeString.append("$minutes menit, ") // Tampilkan menit jika ada jam/hari
        uptimeString.append("$seconds detik")

        return uptimeString.toString()
    }
    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            val currentTime = System.currentTimeMillis()
            var isStale = false
            var statusMessage: String

            Log.d(TAG, "Periodic check. Current: ${formatTimestamp(currentTime)}, Last Send: ${formatTimestamp(lastSuccessfulSendTimestamp)}")

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (lastSuccessfulSendTimestamp == 0L && (currentTime - serviceStartTime) > conditionDurationMillis) {
                isStale = true
                statusMessage = "Initial device sync has not occurred after ${conditionDurationMillis / (60*1000)} min."
                Log.w(TAG, statusMessage)
                updateForegroundServiceNotification("Initial sync overdue. Please check.")
                showStaleDataAlertNotification("Pemberitahuan Restart Perangkat", statusMessage)
            } else if (lastSuccessfulSendTimestamp != 0L && (currentTime - lastSuccessfulSendTimestamp) > conditionDurationMillis) {
                isStale = true
                val uptimeMillis = SystemClock.elapsedRealtime()
                val currentTimeMillis = System.currentTimeMillis()
                val lastRebootTimeMillis = currentTimeMillis - uptimeMillis
                val formattedLastRebootTime = sdf.format(java.util.Date(lastRebootTimeMillis))
                statusMessage = "Perangkat Sudah Aktif Dari: ${formattedLastRebootTime}."
                Log.w(TAG, statusMessage)
                updateForegroundServiceNotification("Device check-in. Last: ${formattedLastRebootTime}")
                showStaleDataAlertNotification("Peringatan Untuk Restart POS", statusMessage)
            } else {
                isStale = false
                val uptimeMillis = SystemClock.elapsedRealtime()
                val currentTimeMillis = System.currentTimeMillis()
                val lastRebootTimeMillis = currentTimeMillis - uptimeMillis
                val formattedLastRebootTime = sdf.format(java.util.Date(lastRebootTimeMillis))
                if (lastSuccessfulSendTimestamp == 0L) {
                    statusMessage = "Checking for device check-in... (Initial sync pending)"
                } else {
                    statusMessage = "Last sync: ${formattedLastRebootTime}"
                }
                Log.i(TAG, "Device check-in is OK. Last sync: ${formattedLastRebootTime}")
                updateForegroundServiceNotification(statusMessage)
                notificationManager.cancel(STALE_DATA_NOTIFICATION_ID) // Clear stale alert if it was shown
            }

            // Send broadcast to MainActivity if it's listening
            val statusIntent = Intent(ACTION_STATUS_UPDATE).apply {
                putExtra(EXTRA_IS_STALE, isStale)
                putExtra(EXTRA_LAST_SYNC_TIME, lastSuccessfulSendTimestamp)
                putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
            }
            LocalBroadcastManager.getInstance(this@DeviceCheckService).sendBroadcast(statusIntent)

            handler.postDelayed(this, checkIntervalMillis)
        }
    }

    private fun createForegroundServiceNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Monitoring Penggunaan POS")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // For ongoing foreground service
            .build()
    }

    private fun updateForegroundServiceNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createForegroundServiceNotification(contentText))
    }

    private fun showStaleDataAlertNotification(title: String, message: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 1, notificationIntent, pendingIntentFlags) // Request code 1 to be different

        val notificationBuilder = NotificationCompat.Builder(this, STALE_DATA_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round) // Consider a different icon for alerts
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Notification disappears when tapped

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(STALE_DATA_NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel for the ongoing foreground service notification
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Device Check Service Channel",
                NotificationManager.IMPORTANCE_LOW // Low importance for ongoing
            ).apply {
                description = "Channel for the persistent device monitoring service notification."
            }

            // Channel for the stale data alerts (higher importance)
            val alertChannel = NotificationChannel(
                STALE_DATA_NOTIFICATION_CHANNEL_ID,
                "Device Stale Alerts",
                NotificationManager.IMPORTANCE_HIGH // High importance for alerts
            ).apply {
                description = "Channel for alerts when device check-in is overdue."
                enableLights(true)
                enableVibration(true)
                // You can set light color, vibration pattern etc. here
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun saveLastSuccessfulSendTimestamp() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_SUCCESSFUL_SEND, lastSuccessfulSendTimestamp).apply()
        Log.d(TAG, "Saved lastSuccessfulSendTimestamp: ${formatTimestamp(lastSuccessfulSendTimestamp)}")
    }

    private fun loadLastSuccessfulSendTimestamp() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lastSuccessfulSendTimestamp = prefs.getLong(KEY_LAST_SUCCESSFUL_SEND, 0L)
        // serviceStartTime will be set in onCreate, so an initial 0L here is fine.
    }

    private fun formatTimestamp(timestamp: Long): String {
        return if (timestamp == 0L) "Never" else sdf.format(Date(timestamp))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicCheckRunnable)
        Log.d(TAG, "Service Destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
