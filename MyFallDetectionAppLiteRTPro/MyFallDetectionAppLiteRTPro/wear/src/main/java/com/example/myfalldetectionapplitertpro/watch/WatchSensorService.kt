package com.example.myfalldetectionapplitertpro.watch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
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

        // Message paths for communicating with phone
        private const val PATH_ACCEL_DATA = "/watch_accel_data"
        private const val PATH_WATCH_STATUS = "/watch_status"
        private const val PATH_HEARTBEAT = "/watch_heartbeat"

        // Buffer size constraints
        private const val MAX_BUFFER_SIZE = 50  // Maximum samples to buffer before sending
        private const val SEND_INTERVAL_MS = 200L  // Send data every 200ms
        private const val HEARTBEAT_INTERVAL_MS = 2000L  // Send heartbeat every 2 seconds

        // Static service status tracking
        private var running = false

        /**
         * Checks if the service is currently running
         */
        fun isRunning(): Boolean = running
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var scheduler: ScheduledExecutorService

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

        // Initialize sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
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

        // Unregister sensor listener
        sensorManager.unregisterListener(this)

        // Shutdown scheduler
        if (::scheduler.isInitialized) {
            scheduler.shutdown()
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

            // Create byte buffer to hold the data
            // Each sample is timestamp (long = 8 bytes) + x,y,z (float = 4 bytes each) = 20 bytes
            val bufferSize = sampleBuffer.size * 16 // 16 bytes per sample
            val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

            // Pack all samples into the buffer
            sampleBuffer.forEach { sample ->
                buffer.putLong(sample.timestamp)
                buffer.putFloat(sample.x)
                buffer.putFloat(sample.y)
                buffer.putFloat(sample.z)
            }

            // Log data being sent
            Log.d(TAG, "Sending sensor batch to phone: ${sampleBuffer.size} samples")

            // Send to phone
            Wearable.getMessageClient(this).sendMessage(
                phoneNodeId!!,
                PATH_ACCEL_DATA,
                buffer.array()
            )
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully sent ${sampleBuffer.size} samples to phone")
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