package com.example.myfalldetectionapplitertpro.wearintegration

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myfalldetectionapplitertpro.BackgroundFallService
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Service that listens for messages from the watch, processes them, and forwards
 * the data to the BackgroundFallService for fall detection processing.
 */
class PhoneWearListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneWearListener"

        // Action for broadcasting watch status updates to the UI
        const val ACTION_WATCH_STATUS_UPDATE = "com.example.myfalldetectionapplitertpro.WATCH_STATUS_UPDATE"
        const val EXTRA_WATCH_STATUS = "watch_status"

        // Message paths for watch communication
        private const val PATH_ACCEL_DATA = "/watch_accel_data"
        private const val PATH_WATCH_STATUS = "/watch_status"
        private const val PATH_HEARTBEAT = "/watch_heartbeat"
        private const val PATH_START_ON_WATCH = "/start_on_watch"
        private const val PATH_STOP_ON_WATCH = "/stop_on_watch"
        private const val PATH_CONFIG_UPDATE = "/config_update"
        private const val PATH_PREDICT_UPDATE = "/predict_update"

        // Track connected watch node
        private var watchNodeId: String? = null
        private var lastDataReceived = 0L

        /**
         * Gets the currently connected watch node ID
         */
        fun getWatchNodeId(): String? = watchNodeId
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PhoneWearListenerService created and ready")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        // Store the watch node ID when we receive any message
        watchNodeId = messageEvent.sourceNodeId
        lastDataReceived = System.currentTimeMillis()

        when (messageEvent.path) {
            PATH_ACCEL_DATA -> {
                try {
                    // Process accelerometer data from watch
                    val samples = parseAccelerometerData(messageEvent.data)

                    // Enhanced logging for data reception
                    Log.d(TAG, "Received data from watch: ${samples.size} samples")

                    // Forward to background service for processing
                    BackgroundFallService.addWatchSamples(samples)

                    // Send heartbeat acknowledgment to the UI
                    broadcastWatchStatus("WATCH_DATA_RECEIVED")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing watch data: ${e.message}", e)
                    broadcastWatchStatus("ERROR:Failed to process watch data: ${e.message}")
                }
            }

            PATH_WATCH_STATUS -> {
                val status = String(messageEvent.data)
                Log.d(TAG, "Received watch status: $status")
                broadcastWatchStatus(status)

                // Update BackgroundFallService about watch connection
                val intent = Intent(this, BackgroundFallService::class.java)

                if (status.startsWith("WATCH_STARTED")) {
                    intent.action = "WATCH_STARTED"
                    intent.putExtra("LINEAR_ACCELERATION", status.contains("true"))
                    startService(intent)
                } else if (status == "WATCH_STOPPED") {
                    intent.action = "WATCH_STOPPED"
                    startService(intent)
                }
            }

            PATH_HEARTBEAT -> {
                Log.d(TAG, "Received heartbeat from watch")
                val intent = Intent(this, BackgroundFallService::class.java).apply {
                    action = "WATCH_ACTIVE"
                }
                startService(intent)

                // Also broadcast to UI
                broadcastWatchStatus("WATCH_ACTIVE")
            }
        }
    }

    /**
     * Parses raw accelerometer data bytes into a list of RawSample objects
     */
    private fun parseAccelerometerData(data: ByteArray): List<BackgroundFallService.RawSample> {
        val samples = mutableListOf<BackgroundFallService.RawSample>()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Each sample has 16 bytes: timestamp (long) + x,y,z (float each)
        val sampleCount = data.size / 16
        Log.d(TAG, "Parsing $sampleCount samples from ${data.size} bytes")

        try {
            for (i in 0 until sampleCount) {
                val timestamp = buffer.getLong()
                val x = buffer.getFloat()
                val y = buffer.getFloat()
                val z = buffer.getFloat()

                samples.add(BackgroundFallService.RawSample(
                    nanoTime = timestamp,
                    x = x,
                    y = y,
                    z = z,
                    isWatch = true
                ))
            }

            return samples
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing accelerometer data: ${e.message}", e)
            throw e
        }
    }

    /**
     * Broadcasts watch status updates to the UI
     */
    private fun broadcastWatchStatus(status: String) {
        val intent = Intent(ACTION_WATCH_STATUS_UPDATE).apply {
            putExtra(EXTRA_WATCH_STATUS, status)
        }
        sendBroadcast(intent)
    }

    /**
     * Sends a message to the watch
     */
    fun sendMessageToWatch(path: String, data: ByteArray = ByteArray(0)) {
        if (watchNodeId == null) {
            Log.e(TAG, "Cannot send message to watch: No watch node ID")
            return
        }

        Wearable.getMessageClient(this)
            .sendMessage(watchNodeId!!, path, data)
            .addOnSuccessListener {
                Log.d(TAG, "Successfully sent message to watch: $path")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send message to watch: ${e.message}")
            }
    }

    /**
     * Helper method to start watch sensors
     */
    fun startWatch(useLinear: Boolean = false) {
        val configStr = "CONFIG:Watch:${if (useLinear) "Linear" else "Raw"}:false"
        sendMessageToWatch(PATH_START_ON_WATCH, configStr.toByteArray())
    }

    /**
     * Helper method to stop watch sensors
     */
    fun stopWatch() {
        sendMessageToWatch(PATH_STOP_ON_WATCH)
    }

    /**
     * Helper method to update watch configuration
     */
    fun updateConfig(deviceMode: String, dataType: String, timeEmbed: Boolean) {
        val configStr = "CONFIG:$deviceMode:$dataType:$timeEmbed"
        sendMessageToWatch(PATH_CONFIG_UPDATE, configStr.toByteArray())
    }

    /**
     * Helper method to send predictions to watch
     */
    fun sendPrediction(label: String, probability: Float) {
        val prediction = "$label (${String.format("%.2f", probability)})"
        sendMessageToWatch(PATH_PREDICT_UPDATE, prediction.toByteArray())
    }
}