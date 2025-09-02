package android.app.api_call_http

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed event received. Starting DeviceCheckService.")
            val serviceIntent = Intent(context, DeviceCheckService::class.java)
            serviceIntent.action = DeviceCheckService.ACTION_START_SERVICE // Make sure this action is correct

            // Start the service. For Android O and above, startForegroundService must be used
            // if the service calls startForeground() within 5 seconds.
            // DeviceCheckService is a foreground service, so this is appropriate.
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.i(TAG, "DeviceCheckService successfully requested to start.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DeviceCheckService on boot", e)
                // Consider logging this error to a more persistent store if needed for debugging
            }
        }
    }
}