package com.example.myfalldetectionapplitertpro.watch

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.Charset

/**
 * Service that listens for messages from the phone and takes appropriate actions.
 */
class WatchWearListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchWearListener"

        // Message paths for phone communication
        private const val PATH_START_ON_WATCH = "/start_on_watch"
        private const val PATH_STOP_ON_WATCH = "/stop_on_watch"
        private const val PATH_CONFIG_UPDATE = "/config_update"
        private const val PATH_STATUS_REQUEST = "/status_request"
        private const val PATH_PREDICT_UPDATE = "/predict_update"
        private const val PATH_PHONE_COMMAND = "/phone_command"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WatchWearListenerService created and ready to receive messages")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data
        val phoneNodeId = messageEvent.sourceNodeId

        Log.d(TAG, "Received message from phone: $path")

        when (path) {
            PATH_START_ON_WATCH -> {
                Log.d(TAG, "Received START command from phone")

                // Get configuration from message payload if available
                val configStr = String(data, Charset.forName("UTF-8"))
                var useLinearAcceleration = false

                if (configStr.startsWith("CONFIG:")) {
                    // Parse configuration
                    val parts = configStr.split(":")
                    if (parts.size >= 3) {
                        val dataType = parts[2]
                        useLinearAcceleration = dataType.equals("Linear", ignoreCase = true)
                    }

                    Log.d(TAG, "Using config from phone: useLinear=$useLinearAcceleration")
                }

                // Start the sensor service
                val serviceIntent = Intent(this, WatchSensorService::class.java).apply {
                    action = "START_SERVICE"
                    putExtra("USE_LINEAR", useLinearAcceleration)
                    putExtra("PHONE_NODE_ID", phoneNodeId)
                }
                startService(serviceIntent)

                // Also broadcast to UI
                sendBroadcast(Intent(WatchMainActivity.ACTION_START_ON_WATCH))
            }

            PATH_STOP_ON_WATCH -> {
                Log.d(TAG, "Received STOP command from phone")

                // Stop the sensor service
                val serviceIntent = Intent(this, WatchSensorService::class.java).apply {
                    action = "STOP_SERVICE"
                }
                startService(serviceIntent)

                // Also broadcast to UI
                sendBroadcast(Intent(WatchMainActivity.ACTION_STOP_ON_WATCH))
            }

            PATH_CONFIG_UPDATE -> {
                Log.d(TAG, "Received CONFIG_UPDATE command from phone")
                val configStr = String(data, Charset.forName("UTF-8"))
                Log.d(TAG, "Config received: $configStr")

                // Send confirmation back to phone
                sendStatusToPhone(phoneNodeId, "CONFIG_UPDATED:$configStr")

                // If service is running, update its configuration
                if (WatchSensorService.isRunning()) {
                    var useLinearAcceleration = false
                    if (configStr.startsWith("CONFIG:")) {
                        val parts = configStr.split(":")
                        if (parts.size >= 3) {
                            val dataType = parts[2]
                            useLinearAcceleration = dataType.equals("Linear", ignoreCase = true)

                            // Restart service with new config
                            val serviceIntent = Intent(this, WatchSensorService::class.java).apply {
                                action = "STOP_SERVICE"
                            }
                            startService(serviceIntent)

                            val newServiceIntent = Intent(this, WatchSensorService::class.java).apply {
                                action = "START_SERVICE"
                                putExtra("USE_LINEAR", useLinearAcceleration)
                                putExtra("PHONE_NODE_ID", phoneNodeId)
                            }
                            startService(newServiceIntent)
                        }
                    }
                }
            }

            PATH_STATUS_REQUEST -> {
                Log.d(TAG, "Received STATUS_REQUEST command from phone")
                // Send current status back to phone
                val status = if (WatchSensorService.isRunning()) "WATCH_STARTED" else "WATCH_STOPPED"
                sendStatusToPhone(phoneNodeId, status)
            }

            PATH_PREDICT_UPDATE -> {
                Log.d(TAG, "Received prediction update from phone")
                val prediction = String(data, Charset.forName("UTF-8"))

                // Broadcast to UI
                val intent = Intent(WatchMainActivity.ACTION_PREDICT_UPDATE).apply {
                    putExtra("prediction", prediction)
                }
                sendBroadcast(intent)
            }

            PATH_PHONE_COMMAND -> {
                Log.d(TAG, "Received PHONE_COMMAND: ${String(data)}")
                // Handle any special commands from phone
                when (String(data)) {
                    "PING" -> {
                        // Send immediate heartbeat response
                        sendStatusToPhone(phoneNodeId, "WATCH_PING_RESPONSE")
                    }
                    "CONNECT" -> {
                        // Send connection confirmation
                        sendStatusToPhone(phoneNodeId, "WATCH_CONNECTED")
                    }
                }
            }
        }
    }

    /**
     * Sends status updates to the phone
     */
    private fun sendStatusToPhone(nodeId: String, status: String) {
        Log.d(TAG, "Sending status to phone: $status")

        Wearable.getMessageClient(this).sendMessage(
            nodeId,
            "/watch_status",
            status.toByteArray()
        )
            .addOnSuccessListener {
                Log.d(TAG, "Successfully sent status to phone: $status")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send status to phone: ${e.message}")
            }
    }
}