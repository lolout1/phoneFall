package com.example.myfalldetectionapplitertpro.watch

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Service that collects sensor data on the watch and sends it to the phone
 * for fall detection processing.
 */
class WatchSensorService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "WatchSensorService"
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "fall_detection_channel"

        // Message paths for communicating with phone
        private const val PATH_ACCEL_DATA = "/watch_accel_data"
        private const val PATH_WATCH_STATUS = "/watch_status"
        private const val PATH_HEARTBEAT = "/watch_heartbeat"

        // Buffer size constraints
        private const val MAX_BUFFER_SIZE = 50  // Maximum samples to buffer before sending
        private const val SEND_INTERVAL_MS = 200L  // Send data every 200ms
        private const val HEARTBEAT_INTERVAL_MS = 2000L  // Send heartbeat every 2 seconds

        // Static service status tracking
        @Volatile private var running = false

        /**
         * Checks if the service is currently running
         */
        fun isRunning(): Boolean = running
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var scheduler: ScheduledExecutorService
    private var wakeLock: PowerManager.WakeLock? = null

    private var accelerometer: Sensor? = null
    private var useLinearAcceleration: Boolean = false

    // Phone node for sending messages
    private var phoneNodeId: String? = null

    // Buffer for collecting sensor samples
    private val sampleBuffer = mutableListOf<SensorSample>()

    // Data class for sensor samples
    data class SensorSample(
        val timestamp: Long,
        val x: Float,
        val y: Float,
        val z: Float
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WatchSensorService onCreate")

        // Create notification channel
        createNotificationChannel()

        // Initialize sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Acquire wake lock to prevent the device from sleeping while collecting sensor data
        acquireWakeLock()
    }

    /**
     * Creates notification channel for foreground service
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fall Detection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Collects sensor data for fall detection"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates a notification for the foreground service
     */
    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fall Detection Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Acquires a wake lock to prevent the device from sleeping while collecting sensor data
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WatchSensorService::SensorWakeLock"
        ).apply {
            acquire(10*60*1000L) // 10 minutes timeout
        }

        Log.d(TAG, "Wake lock acquired: ${wakeLock?.isHeld}")
    }

    /**
     * Release wake lock if it's held
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WatchSensorService onStartCommand: ${intent?.action}")

        when (intent?.action) {
            "START_SERVICE" -> {
                val useLinear = intent.getBooleanExtra("USE_LINEAR", false)
                val phoneNode = intent.getStringExtra("PHONE_NODE_ID")

                if (phoneNode != null) {
                    startSensorCollection(useLinear, phoneNode)

                    // Broadcast to UI that we're starting
                    sendBroadcast(Intent(WatchMainActivity.ACTION_START_ON_WATCH))
                } else {
                    Log.e(TAG, "Cannot start sensor collection: No phone node ID")
                }
            }
            "STOP_SERVICE" -> {
                stopSensorCollection()

                // Broadcast to UI that we're stopping
                sendBroadcast(Intent(WatchMainActivity.ACTION_STOP_ON_WATCH))
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSensorCollection()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Starts collecting and sending sensor data
     */
    private fun startSensorCollection(useLinear: Boolean = false, phoneNode: String) {
        if (running) {
            Log.d(TAG, "Sensor collection already running")
            return
        }

        this.useLinearAcceleration = useLinear
        this.phoneNodeId = phoneNode

        Log.d(TAG, "Starting sensor collection with useLinear=$useLinear, phoneNode=$phoneNode")

        // Start as a foreground service to increase priority
        startForeground(NOTIFICATION_ID, createNotification("Collecting accelerometer data"))

        // Use linear acceleration or raw accelerometer based on config
        accelerometer = if (useLinearAcceleration) {
            Log.d(TAG, "Using LINEAR ACCELERATION sensor on watch")
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        } else {
            Log.d(TAG, "Using ACCELEROMETER sensor on watch")
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        // Register the sensor listener
        accelerometer?.let { sensor ->
            val success = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            if (success) {
                Log.d(TAG, "Successfully registered sensor listener")
            } else {
                Log.e(TAG, "Failed to register sensor listener")
                return
            }
        } ?: run {
            Log.e(TAG, "Failed to get accelerometer sensor")
            return
        }

        // Start periodic data sending
        scheduler = Executors.newScheduledThreadPool(2)

        // Schedule data sending task
        scheduler.scheduleAtFixedRate({
            sendBufferedSamples()
        }, SEND_INTERVAL_MS, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS)

        // Schedule heartbeat task
        scheduler.scheduleAtFixedRate({
            sendHeartbeat()
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)

        running = true

        // Send status to phone
        sendStatusToPhone("WATCH_STARTED:$useLinearAcceleration")

        Log.d(TAG, "Watch sensor service started")
    }

    /**
     * Stops the service
     */
    private fun stopSensorCollection() {
        if (!running) return

        Log.d(TAG, "Stopping watch sensor service")
        running = false

        // Remove foreground status
        stopForeground(true)

        // Unregister sensor listener
        sensorManager.unregisterListener(this)

        // Shutdown scheduler
        if (::scheduler.isInitialized) {
            try {
                scheduler.shutdown()
                if (!scheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    scheduler.shutdownNow()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down scheduler: ${e.message}")
                scheduler.shutdownNow()
            }
        }

        // Send any remaining samples
        sendBufferedSamples()

        // Send stop status to phone
        sendStatusToPhone("WATCH_STOPPED")

        Log.d(TAG, "Watch sensor service stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER ||
            event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {

            // Add to buffer
            synchronized(sampleBuffer) {
                sampleBuffer.add(SensorSample(
                    timestamp = event.timestamp,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2]
                ))

                // Log the first few samples to verify values
                if (sampleBuffer.size <= 5) {
                    Log.d(TAG, "Sample: t=${event.timestamp}, x=${event.values[0]}, y=${event.values[1]}, z=${event.values[2]}")
                }

                // If buffer gets too large, send immediately
                if (sampleBuffer.size >= MAX_BUFFER_SIZE) {
                    sendBufferedSamples()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    /**
     * Sends buffered sensor samples to the phone
     */
    private fun sendBufferedSamples() {
        synchronized(sampleBuffer) {
            if (sampleBuffer.isEmpty() || phoneNodeId == null) return

            // CRITICAL: Sort samples by timestamp to ensure chronological order
            // This is essential for the time series transformer model
            val sortedSamples = sampleBuffer.sortedBy { it.timestamp }

            // Create byte buffer to hold the data
            // Each sample is timestamp (long = 8 bytes) + x,y,z (float = 4 bytes each) = 20 bytes
            val bufferSize = sortedSamples.size * 16 // 16 bytes per sample
            val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

            // Pack all samples into the buffer
            for (sample in sortedSamples) {
                buffer.putLong(sample.timestamp)
                buffer.putFloat(sample.x)
                buffer.putFloat(sample.y)
                buffer.putFloat(sample.z)
            }

            // Log data being sent
            Log.d(TAG, "Sending sensor batch to phone: ${sortedSamples.size} samples, " +
                    "time range: ${sortedSamples.first().timestamp}-${sortedSamples.last().timestamp}")

            // Send to phone
            Wearable.getMessageClient(this).sendMessage(
                phoneNodeId!!,
                PATH_ACCEL_DATA,
                buffer.array()
            )
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully sent ${sortedSamples.size} samples to phone")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send samples to phone: ${e.message}")
                }

            // Clear the buffer
            sampleBuffer.clear()
        }
    }

    /**
     * Sends periodic heartbeat to phone
     */
    private fun sendHeartbeat() {
        if (!running || phoneNodeId == null) return

        Log.d(TAG, "Sending heartbeat to phone")

        Wearable.getMessageClient(this).sendMessage(
            phoneNodeId!!,
            PATH_HEARTBEAT,
            "ACTIVE".toByteArray()
        )
            .addOnSuccessListener {
                Log.d(TAG, "Successfully sent heartbeat to phone")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send heartbeat to phone: ${e.message}")
            }
    }

    /**
     * Sends status updates to the phone
     */
    private fun sendStatusToPhone(status: String) {
        if (phoneNodeId == null) return

        Log.d(TAG, "Sending status to phone: $status")

        Wearable.getMessageClient(this).sendMessage(
            phoneNodeId!!,
            PATH_WATCH_STATUS,
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