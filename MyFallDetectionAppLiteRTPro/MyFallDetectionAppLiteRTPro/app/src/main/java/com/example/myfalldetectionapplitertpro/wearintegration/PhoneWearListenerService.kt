package com.example.myfalldetectionapplitertpro.wearintegration

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.myfalldetectionapplitertpro.BackgroundFallService
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Service that manages bidirectional communication with the watch.
 *
 * Responsibilities:
 * 1. Receive sensor data from watch and forward to BackgroundFallService
 * 2. Send commands (start/stop/config) to watch
 * 3. Send prediction results back to watch
 * 4. Monitor watch connection status
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

        // Message paths for sending to watch
        private const val PATH_START_ON_WATCH = "/start_on_watch"
        private const val PATH_STOP_ON_WATCH = "/stop_on_watch"
        private const val PATH_CONFIG_UPDATE = "/config_update"
        private const val PATH_PREDICT_UPDATE = "/predict_update"

        // Connection management
        private var watchNodeId: String? = null
        private var lastDataReceived = 0L
        private var connectionCheckScheduled = false
        private val handler = Handler(Looper.getMainLooper())
        private const val CONNECTION_CHECK_INTERVAL = 10000L // 10 seconds

        // Connection status checker
        private val connectionChecker = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (watchNodeId != null && now - lastDataReceived > 5000) {
                    Log.w(TAG, "No data received from watch in ${now - lastDataReceived}ms")

                    // If we haven't received data for too long, broadcast a warning
                    if (now - lastDataReceived > 15000) {
                        Log.e(TAG, "Watch connection may be lost - no data for ${now - lastDataReceived}ms")
                        // Clear the node ID - we'll need to rediscover it
                        watchNodeId = null
                        // Let the UI know
                        broadcastWatchStatus("WATCH_CONNECTION_LOST")
                    }
                }

                // Schedule next check
                handler.postDelayed(this, CONNECTION_CHECK_INTERVAL)
            }
        }

        /**
         * Gets the currently connected watch node ID
         */
        fun getWatchNodeId(): String? = watchNodeId

        /**
         * Checks if we've received data from the watch recently
         * @return true if the watch is actively sending data
         */
        fun hasRecentData(): Boolean {
            return watchNodeId != null &&
                    System.currentTimeMillis() - lastDataReceived < 5000
        }

        /**
         * Finds available watch nodes
         * @return List of watch nodes
         */
        suspend fun findWatchNodes(context: Context): List<Node> {
            try {
                return Tasks.await(
                    Wearable.getNodeClient(context).connectedNodes,
                    5, TimeUnit.SECONDS
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error finding watch nodes: ${e.message}")
                return emptyList()
            }
        }

        /**
         * Sends a message to the watch using the stored node ID
         * @return true if message was sent successfully
         */
        fun sendMessageToWatch(context: Context, path: String, data: ByteArray = ByteArray(0)): Boolean {
            if (watchNodeId == null) {
                Log.e(TAG, "Cannot send message to watch: No watch node ID")

                // Try to find watch nodes
                Thread {
                    try {
                        val nodes = Tasks.await(
                            Wearable.getNodeClient(context).connectedNodes,
                            2, TimeUnit.SECONDS
                        )

                        if (nodes.isNotEmpty()) {
                            watchNodeId = nodes.first().id
                            Log.d(TAG, "Discovered watch node: $watchNodeId")
                            // Try sending again with the new node ID
                            sendMessageToWatch(context, path, data)
                        } else {
                            Log.e(TAG, "No connected watch nodes found")
                            broadcastWatchStatus(context, "WATCH_NOT_CONNECTED")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error finding watch nodes: ${e.message}")
                    }
                }.start()

                return false
            }

            try {
                Log.d(TAG, "Sending message to watch: $path")

                Wearable.getMessageClient(context)
                    .sendMessage(watchNodeId!!, path, data)
                    .addOnSuccessListener {
                        Log.d(TAG, "Successfully sent message to watch: $path")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to send message to watch: ${e.message}")
                        // Clear node ID if communication failed
                        if (e.message?.contains("Status{statusCode=NETWORK_ERROR") == true) {
                            watchNodeId = null
                            broadcastWatchStatus(context, "WATCH_CONNECTION_ERROR")
                        }
                    }
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message to watch: ${e.message}")
                return false
            }
        }

        /**
         * Broadcasts a watch status update from any context
         */
        fun broadcastWatchStatus(context: Context, status: String) {
            val intent = Intent(ACTION_WATCH_STATUS_UPDATE).apply {
                putExtra(EXTRA_WATCH_STATUS, status)
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            context.sendBroadcast(intent)
        }

        /**
         * Starts the connection checker if not already running
         */
        fun startConnectionChecker() {
            if (!connectionCheckScheduled) {
                handler.post(connectionChecker)
                connectionCheckScheduled = true
                Log.d(TAG, "Watch connection checker started")
            }
        }

        /**
         * Stops the connection checker
         */
        fun stopConnectionChecker() {
            handler.removeCallbacks(connectionChecker)
            connectionCheckScheduled = false
            Log.d(TAG, "Watch connection checker stopped")
        }

        /**
         * Sends a start command to the watch
         */
        fun startWatchSensors(context: Context, useLinear: Boolean = false) {
            val configStr = "CONFIG:Watch:${if (useLinear) "Linear" else "Raw"}:false"
            sendMessageToWatch(context, PATH_START_ON_WATCH, configStr.toByteArray())
        }

        /**
         * Sends a stop command to the watch
         */
        fun stopWatchSensors(context: Context) {
            sendMessageToWatch(context, PATH_STOP_ON_WATCH)
        }

        /**
         * Sends configuration update to the watch
         */
        fun updateWatchConfig(context: Context, deviceMode: String, dataType: String, timeEmbed: Boolean) {
            val configStr = "CONFIG:$deviceMode:$dataType:$timeEmbed"
            sendMessageToWatch(context, PATH_CONFIG_UPDATE, configStr.toByteArray())
        }

        /**
         * Sends prediction results to the watch
         */
        fun sendPredictionToWatch(context: Context, label: String, probability: Float) {
            val prediction = "$label (${String.format("%.2f", probability)})"
            sendMessageToWatch(context, PATH_PREDICT_UPDATE, prediction.toByteArray())
        }
    }

    private val messageClient: MessageClient by lazy {
        Wearable.getMessageClient(this)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PhoneWearListenerService created and ready")

        // Start connection checker
        startConnectionChecker()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopConnectionChecker()
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
        // Use both LocalBroadcastManager and regular broadcast
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        sendBroadcast(intent)
    }
}