package com.example.myfalldetectionapplitertpro

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myfalldetectionapplitertpro.wearintegration.PhoneWearListenerService
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.java.TfLite
import com.google.android.gms.wearable.Wearable
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Background service responsible for collecting sensor data from the phone
 * and/or watch, processing it through the TensorFlow model, and detecting falls.
 */
class BackgroundFallService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "BackgroundFallService"

        // Broadcast actions
        const val ACTION_INFERENCE_RESULT = "com.example.myfalldetectionapplitertpro.INFERENCE_RESULT"
        const val ACTION_FALL_DETECTED = "com.example.myfalldetectionapplitertpro.FALL_DETECTED"
        const val ACTION_SERVICE_STATUS = "com.example.myfalldetectionapplitertpro.SERVICE_STATUS"

        const val EXTRA_LABEL = "label"
        const val EXTRA_PROBABILITY = "probability"
        const val EXTRA_MIN_SMV = "min_smv"
        const val EXTRA_MAX_SMV = "max_smv"
        const val EXTRA_AVG_SMV = "avg_smv"
        const val EXTRA_DATA_SOURCE = "data_source"

        private const val CHANNEL_ID = "fall_detection_channel"
        private const val NOTIFICATION_ID = 999

        // Shared watch queue with synchronization
        private val watchQueue = ArrayDeque<RawSample>()
        private var lastWatchDataTime = 0L
        private var isWatchConnected = false
        private var watchDataReceived = false

        /**
         * Adds sensor samples from the watch to the processing queue
         */
        fun addWatchSamples(samples: List<RawSample>) {
            synchronized(watchQueue) {
                val count = samples.size
                samples.forEach { watchQueue.addLast(it) }

                // Maintain reasonable queue size
                if (watchQueue.size > 3000) {
                    repeat(watchQueue.size - 3000) { watchQueue.removeFirst() }
                }

                lastWatchDataTime = System.currentTimeMillis()
                isWatchConnected = true
                watchDataReceived = true

                Log.d(TAG, "Added $count samples to watchQueue, now has ${watchQueue.size} samples")
            }
        }

        /**
         * Checks if watch is actively sending data
         */
        fun isWatchActive(): Boolean {
            // Consider watch active if we received data in the last 5 seconds
            synchronized(watchQueue) {
                val active = isWatchConnected &&
                        (System.currentTimeMillis() - lastWatchDataTime < 5000)
                return active
            }
        }

        /**
         * Clears the watch data queue
         */
        fun clearWatchQueue() {
            synchronized(watchQueue) {
                watchQueue.clear()
                Log.d(TAG, "Watch queue cleared")
            }
        }

        /**
         * Gets the number of watch samples available
         */
        fun getWatchQueueSize(): Int {
            synchronized(watchQueue) {
                return watchQueue.size
            }
        }

        /**
         * Checks if any watch data has been received during this session
         */
        fun hasReceivedWatchData(): Boolean {
            synchronized(watchQueue) {
                return watchDataReceived
            }
        }
    }

    /**
     * Data structure for raw sensor samples
     */
    data class RawSample(
        val nanoTime: Long,
        val x: Float,
        val y: Float,
        val z: Float,
        val isWatch: Boolean = false
    )

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val phoneQueue = ArrayDeque<RawSample>()

    private var interpreter: InterpreterApi? = null
    private var isModelReady = false

    private var isRunning = false
    private var isPausedForFall = false
    private val serviceHandler = Handler(Looper.getMainLooper())

    // Track watch integration status
    private var expectingWatchData = false
    private var watchStartAttempted = false
    private var lastWatchPing = 0L
    private var watchHeartbeatMissed = 0

    // Track device mode for inference
    private var currentDeviceMode = "Phone"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // Check permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing BODY_SENSORS permission. Stopping service.")
                stopSelf()
                return
            }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createNotificationChannel()

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Initializing model..."))
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException: ${se.message}")
            stopSelf()
            return
        }

        // Load TFLite model
        loadTFLiteModel()
    }

    /**
     * Loads the TensorFlow Lite model for inference
     */
    /**
     * Parses raw accelerometer data bytes into a list of RawSample objects
     */
    private fun parseAccelerometerData(data: ByteArray): List<RawSample> {
        val samples = mutableListOf<RawSample>()
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

                samples.add(RawSample(
                    nanoTime = timestamp,
                    x = x,
                    y = y,
                    z = z,
                    isWatch = true
                ))
            }

            // Critical: Sort samples by timestamp to ensure chronological order
            val sortedSamples = samples.sortedBy { it.nanoTime }

            Log.d(TAG, "Successfully parsed ${sortedSamples.size} watch samples, time range: ${
                if (sortedSamples.isNotEmpty())
                    "${sortedSamples.first().nanoTime} to ${sortedSamples.last().nanoTime}"
                else "empty"
            }")

            return sortedSamples

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing accelerometer data: ${e.message}", e)
            throw e
        }
    }
    private fun loadTFLiteModel() {
        val modelFile = PrefsHelper.getModelFile(this)
        Thread {
            try {
                Tasks.await(TfLite.initialize(this))
                Log.d(TAG, "TfLite.initialize succeeded")

                val modelBytes = assets.open(modelFile).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
                    put(modelBytes)
                    flip()
                }

                val opts = Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
                interpreter = InterpreterApi.create(buffer, opts)
                Log.d(TAG, "Interpreter created successfully (GMS)")

                isModelReady = true
                updateForegroundNotification("Model ready; waiting to start detection")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading TFLite in GMS", e)
                updateForegroundNotification("Model init error: ${e.message}")
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action")

        when (action) {
            "START_CAPTURE" -> startCapture()
            "STOP_CAPTURE" -> stopCapture()
            "CHECK_STATUS" -> broadcastStatus()
            "WATCH_STARTED" -> {
                val useLinear = intent.getBooleanExtra("LINEAR_ACCELERATION", false)
                Log.d(TAG, "Watch started with LINEAR_ACCELERATION=$useLinear")
                expectingWatchData = true
                watchStartAttempted = true
                updateForegroundNotification("Watch connected and sending data...")
            }
            "WATCH_STOPPED" -> {
                Log.d(TAG, "Watch stopped")
                expectingWatchData = false
                clearWatchQueue()
                updateForegroundNotification("Watch disconnected or stopped")
            }
            "WATCH_DATA_RECEIVED" -> {
                Log.d(TAG, "Watch data received notification")
                lastWatchPing = System.currentTimeMillis()
                watchHeartbeatMissed = 0 // Reset missed heartbeat counter
            }
            "WATCH_CONNECTED" -> {
                Log.d(TAG, "Watch connected")
                isWatchConnected = true
                broadcastServiceUpdate("Watch connected and ready for use")
            }
            "WATCH_DISCONNECTED" -> {
                Log.d(TAG, "Watch disconnected")
                isWatchConnected = false
                broadcastServiceUpdate("Watch disconnected")

                // If we're in Watch-only mode, we need to pause
                if (currentDeviceMode.equals("Watch", ignoreCase=true)) {
                    broadcastServiceUpdate("WARNING: Watch disconnected while in Watch-only mode")
                }
            }
            "WATCH_ACTIVE" -> {
                Log.d(TAG, "Watch active signal received")
                lastWatchPing = System.currentTimeMillis()
                watchHeartbeatMissed = 0
            }
            "WATCH_INACTIVE" -> {
                Log.d(TAG, "Watch inactive signal received")
                // The watch service is running but not collecting data
                if (currentDeviceMode.equals("Watch", ignoreCase=true)) {
                    broadcastServiceUpdate("WARNING: Watch is not collecting data")
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Starts capturing sensor data and processing
     */
    private fun startCapture() {
        if (isRunning) return

        Log.d(TAG, "Starting capture")
        isRunning = true
        isPausedForFall = false

        // Clear data queues
        phoneQueue.clear()
        synchronized(watchQueue) { watchQueue.clear() }
        watchDataReceived = false

        // Get device mode for this session
        currentDeviceMode = PrefsHelper.getDeviceMode(this)

        // Set up the correct sensor type based on preferences
        val dataType = PrefsHelper.getSharedPrefs(this).getString("pref_data_type", "Raw")
        accelerometer = if (dataType.equals("Linear", ignoreCase=true)) {
            Log.d(TAG, "Using LINEAR ACCELERATION sensor")
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        } else {
            Log.d(TAG, "Using ACCELEROMETER sensor")
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        // Only register phone sensors if needed
        if (currentDeviceMode.equals("Phone", ignoreCase=true) ||
            currentDeviceMode.equals("Both", ignoreCase=true)) {
            accelerometer?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        // Start proactively checking watch connection if in Watch mode
        if (currentDeviceMode.equals("Watch", ignoreCase = true) ||
            currentDeviceMode.equals("Both", ignoreCase = true)) {

            // Check if watch is already known to be connected
            if (!isWatchConnected) {
                Log.d(TAG, "Starting in Watch mode but no watch connected yet - checking connection")
                updateForegroundNotification("Connecting to watch...")
                sendWatchCommand("CONNECT")
            } else {
                updateForegroundNotification("Watch connected - streaming data...")
            }

            // Start watch heartbeat monitor
            serviceHandler.postDelayed(watchHeartbeatChecker, 2000L)
        }

        // Start inference loop
        serviceHandler.postDelayed(inferenceRunnable, 1000L)

        // Update UI and status
        updateForegroundNotification("Capturing sensor data (mode: $currentDeviceMode)")
        broadcastServiceUpdate("Started capturing sensor data in $currentDeviceMode mode")
    }

    /**
     * Stops capturing sensor data
     */
    private fun stopCapture() {
        if (!isRunning) return

        Log.d(TAG, "Stopping capture")
        isRunning = false

        // Unregister sensor listener
        sensorManager.unregisterListener(this)

        // Stop inference loop and heartbeat checker
        serviceHandler.removeCallbacks(inferenceRunnable)
        serviceHandler.removeCallbacks(watchHeartbeatChecker)

        // Update UI and status
        updateForegroundNotification("Stopped capturing")
        broadcastServiceUpdate("Stopped capturing")
    }

    /**
     * Runnable that checks for watch heartbeats
     */
    private val watchHeartbeatChecker = object : Runnable {
        override fun run() {
            if (!isRunning) return

            // Check if we're expecting watch data
            if (currentDeviceMode.equals("Watch", ignoreCase = true) ||
                currentDeviceMode.equals("Both", ignoreCase = true)) {

                // Check if it's been too long since the last watch ping
                val timeSinceLastPing = System.currentTimeMillis() - lastWatchPing

                if (timeSinceLastPing > 5000) { // 5 seconds
                    watchHeartbeatMissed++
                    Log.w(TAG, "No watch heartbeat for ${timeSinceLastPing}ms (missed: $watchHeartbeatMissed)")

                    if (watchHeartbeatMissed >= 3) {
                        // Try to reconnect with the watch
                        sendWatchCommand("PING")

                        broadcastServiceUpdate("WARNING: Watch connection unstable")

                        // If we're in Watch-only mode and data is critical, consider pausing
                        if (currentDeviceMode.equals("Watch", ignoreCase = true) && watchHeartbeatMissed >= 5) {
                            broadcastServiceUpdate("ERROR: Lost connection to watch in Watch-only mode")
                        }
                    }
                } else {
                    watchHeartbeatMissed = 0
                }

                // Schedule next check
                serviceHandler.postDelayed(this, 2000L)
            }
        }
    }

    /**
     * Runnable that performs periodic inference
     */
    private val inferenceRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                doInference()
                serviceHandler.postDelayed(this, 1000L)
            }
        }
    }

    /**
     * Sends a command to the connected watch
     */
    private fun sendWatchCommand(command: String) {
        // Get the watch node ID from the listener service
        val watchNodeId = PhoneWearListenerService.getWatchNodeId()

        if (watchNodeId != null) {
            // If we know the watch node ID, send directly
            Thread {
                try {
                    Wearable.getMessageClient(this)
                        .sendMessage(watchNodeId, "/phone_command", command.toByteArray())
                        .addOnSuccessListener {
                            Log.d(TAG, "Sent command to watch: $command")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send command to watch: ${e.message}")
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending command to watch: ${e.message}")
                }
            }.start()
        } else {
            // Otherwise try to discover nodes
            Thread {
                try {
                    val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                    for (node in nodes) {
                        Wearable.getMessageClient(this)
                            .sendMessage(node.id, "/phone_command", command.toByteArray())
                            .addOnSuccessListener {
                                Log.d(TAG, "Sent command to watch node ${node.displayName}: $command")
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to send command to watch: ${e.message}")
                            }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error finding watch nodes: ${e.message}")
                }
            }.start()
        }
    }

    /**
     * Performs inference using the loaded model
     */
    private fun doInference() {
        if (!isModelReady || interpreter == null) return

        val deviceMode = currentDeviceMode
        val timeEmbedding = PrefsHelper.isTimeEmbeddingEnabled(this)

        // Check if we're expecting watch data but not receiving it
        if ((deviceMode.equals("Watch", ignoreCase = true) ||
                    deviceMode.equals("Both", ignoreCase = true)) &&
            expectingWatchData &&
            System.currentTimeMillis() - lastWatchPing > 5000 &&
            watchStartAttempted) {

            // It's been too long since we heard from the watch
            Log.w(TAG, "Watch data expected but not received for 5+ seconds")

            // If in Watch-only mode and no data, broadcast a warning
            if (deviceMode.equals("Watch", ignoreCase = true) && !hasReceivedWatchData()) {
                broadcastServiceUpdate("WARNING: Watch not sending data")

                // Try to ping the watch
                sendWatchCommand("PING")
            }
        }

        // Get watch data status
        val watchQueueSize = synchronized(watchQueue) { watchQueue.size }
        val watchActive = isWatchActive()

        Log.d(TAG, "Inference with mode=$deviceMode, phoneQueue=${phoneQueue.size}, " +
                "watchQueue=$watchQueueSize, watchActive=$watchActive")

        when {
            deviceMode.equals("Phone", ignoreCase = true) -> {
                // Phone-only mode
                if (phoneQueue.size < 128) {
                    Log.d(TAG, "Phone samples not enough: ${phoneQueue.size}/128")
                    return
                }
                val samples = phoneQueue.takeLast(128).toList()
                runInference(samples, timeEmbedding, 128, "Phone")
            }

            deviceMode.equals("Watch", ignoreCase = true) -> {
                // Watch-only mode
                synchronized(watchQueue) {
                    if (watchQueue.size < 128) {
                        Log.d(TAG, "Watch samples not enough: ${watchQueue.size}/128")
                        return
                    }
                    Log.d(TAG, "Using ${watchQueue.size} watch samples for inference")
                    val samples = watchQueue.takeLast(128).toList()

                    // IMPORTANT: Sort samples by timestamp to ensure chronological order
                    val sortedSamples = samples.sortedBy { it.nanoTime }
                    runInference(sortedSamples, timeEmbedding, sortedSamples.size, "Watch")
                }
            }

            else -> { // "Both" mode
                // For "Both" mode, we need at least some data from the phone
                if (phoneQueue.size < 64) {
                    Log.d(TAG, "Phone samples not enough for 'Both' mode: ${phoneQueue.size}/64")
                    return
                }

                val watchSamples = synchronized(watchQueue) {
                    if (watchQueue.size < 64) {
                        // Not enough watch samples, but we'll use what we have
                        Log.d(TAG, "Limited watch samples in 'Both' mode: ${watchQueue.size}/64")
                        watchQueue.toList()
                    } else {
                        watchQueue.takeLast(64).toList()
                    }
                }

                // Get phone samples
                val phoneSamples = phoneQueue.takeLast(64).toList()

                if (watchSamples.isEmpty()) {
                    // No watch samples at all, fallback to phone-only
                    Log.d(TAG, "No watch samples available, using phone-only in 'Both' mode")
                    val phoneFallback = phoneQueue.takeLast(128).toList()
                    runInference(phoneFallback, timeEmbedding, phoneFallback.size, "Phone")
                } else {
                    // Use combined data from both sources
                    // IMPORTANT: Merge and sort by timestamp to ensure chronological order
                    val combined = (phoneSamples + watchSamples).sortedBy { it.nanoTime }

                    // Trim to 128 samples if we have more
                    val finalSamples = if (combined.size > 128) {
                        combined.takeLast(128)
                    } else {
                        combined
                    }

                    Log.d(TAG, "Using combined data: ${phoneSamples.size} phone + " +
                            "${watchSamples.size} watch = ${finalSamples.size} samples")

                    runInference(finalSamples, timeEmbedding, finalSamples.size, "Both")
                }
            }
        }
    }

    /**
     * Runs the actual inference through the TensorFlow model
     */
    private fun runInference(samples: List<RawSample>, timeEmbed: Boolean, count: Int, sourceType: String) {
        // Check for empty samples list
        if (samples.isEmpty()) {
            Log.e(TAG, "Cannot run inference on empty samples list")
            return
        }

        // Initialize arrays with proper size
        val finalTime = FloatArray(count)
        val finalXYZ = Array(count) { FloatArray(3) }
        val finalMask = FloatArray(count) { 0f }

        // Debugging stats on sample sources
        val watchSamples = samples.count { it.isWatch }
        val phoneSamples = samples.count { !it.isWatch }
        Log.d(TAG, "Running inference with $phoneSamples phone samples and $watchSamples watch samples")

        // Check sample timestamps and spacing
        if (samples.size > 1) {
            val firstTime = samples.first().nanoTime
            val lastTime = samples.last().nanoTime
            val timeSpanSeconds = (lastTime - firstTime) / 1e9

            Log.d(TAG, "Sample timespan: $timeSpanSeconds seconds")

            // Validate time order
            val isSorted = samples.zipWithNext { a, b -> a.nanoTime <= b.nanoTime }.all { it }
            if (!isSorted) {
                Log.w(TAG, "Samples are not in chronological order! Sorting now...")
                // This shouldn't happen as we now sort before calling this method
            }
        }

        // Log first and last timestamps to verify chronological order
        if (samples.size > 1) {
            Log.d(TAG, "First sample time: ${samples.first().nanoTime}, Last sample time: ${samples.last().nanoTime}")
        }

        // Set up model inputs
        val t0 = samples.first().nanoTime.toDouble()
        for ((i, s) in samples.withIndex()) {
            // Calculate time delta in seconds
            val deltaSec = ((s.nanoTime - t0) / 1e9).toFloat()
            // Only use time embedding if enabled in config
            finalTime[i] = if (timeEmbed) deltaSec else 0f
            finalXYZ[i][0] = s.x
            finalXYZ[i][1] = s.y
            finalXYZ[i][2] = s.z
        }

        // Calculate SMV (Signal Magnitude Vector) stats for diagnostics
        val smvList = samples.map { sqrt(it.x*it.x + it.y*it.y + it.z*it.z) }
        val minSmv = smvList.minOrNull() ?: 0.0
        val maxSmv = smvList.maxOrNull() ?: 0.0
        val avgSmv = smvList.average().toFloat()

        // Run inference in a thread to avoid blocking the main thread
        Thread {
            try {
                val batch = 1
                val inputTime = arrayOf(finalTime)
                val inputXYZ = arrayOf(finalXYZ)
                val inputMask = arrayOf(finalMask)

                val outputs = mutableMapOf<Int, Any>(0 to Array(batch) { FloatArray(2) })
                val inputObjects = arrayOf<Any>(inputTime, inputXYZ, inputMask)

                // Log input shapes for debugging
                Log.d(TAG, "Input shapes - time: ${inputTime[0].size}, XYZ: ${inputXYZ[0].size}x3, mask: ${inputMask[0].size}")

                // Run model inference
                interpreter?.runForMultipleInputsOutputs(inputObjects, outputs)
                val logits = (outputs[0] as Array<FloatArray>)[0]

                // Log raw logits for debugging
                Log.d(TAG, "Raw logits: [${logits[0]}, ${logits[1]}]")

                // Post-process results (softmax calculation)
                val maxVal = logits.maxOrNull() ?: 0f
                val exp0 = kotlin.math.exp(logits[0] - maxVal)
                val exp1 = kotlin.math.exp(logits[1] - maxVal)
                val fallProb = exp1 / (exp0 + exp1)
                val label = if (fallProb > 0.9f) "FALL DETECTED" else "No Fall"

                Log.d(TAG, "Inference result: $label (prob=$fallProb)")

                // Broadcast inference result
                val brIntent = Intent(ACTION_INFERENCE_RESULT).apply {
                    `package` = packageName
                    putExtra(EXTRA_LABEL, label)
                    putExtra(EXTRA_PROBABILITY, fallProb)
                    putExtra(EXTRA_MIN_SMV, minSmv.toFloat())
                    putExtra(EXTRA_MAX_SMV, maxSmv.toFloat())
                    putExtra(EXTRA_AVG_SMV, avgSmv)
                    putExtra(EXTRA_DATA_SOURCE, sourceType)
                }
                sendBroadcast(brIntent)

                // Handle fall detection
                if (label == "FALL DETECTED" && !isPausedForFall) {
                    handleFallDetection(fallProb)
                }

                // Send prediction to watch if we're using it
                if ((currentDeviceMode.equals("Watch", ignoreCase = true) ||
                            currentDeviceMode.equals("Both", ignoreCase = true)) &&
                    watchStartAttempted) {

                    sendPredictionToWatch(label, fallProb)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
                broadcastServiceUpdate("ERROR: Inference failed - ${e.message}")
            }
        }.start()
    }

    /**
     * Handles detected falls
     */
    private fun handleFallDetection(probability: Float) {
        isPausedForFall = true
        stopCapture()
        vibratePhone()

        Log.d(TAG, "FALL DETECTED with probability $probability")
        sendFallDetectedBroadcast()

        // Update notification with fall alert
        updateForegroundNotification("FALL DETECTED! Check device.")
        broadcastServiceUpdate("FALL DETECTED with probability $probability")
    }

    /**
     * Sends prediction results to the watch
     */
    private fun sendPredictionToWatch(label: String, probability: Float) {
        val message = "$label (${String.format("%.2f", probability)})"
        Thread {
            try {
                // First try to use the known watch node ID if available
                val watchNodeId = PhoneWearListenerService.getWatchNodeId()
                if (watchNodeId != null) {
                    Wearable.getMessageClient(this)
                        .sendMessage(watchNodeId, "/predict_update", message.toByteArray())
                        .addOnSuccessListener {
                            Log.d(TAG, "Sent prediction to watch: $message")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send prediction to watch: ${e.message}")
                        }
                    return@Thread
                }

                // Fall back to discovering nodes
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, "/predict_update", message.toByteArray())
                        .addOnSuccessListener {
                            Log.d(TAG, "Sent prediction to watch: $message")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send prediction to watch: ${e.message}")
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending prediction to watch: ${e.message}")
            }
        }.start()
    }

    /**
     * Processes sensor data from the phone
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER ||
            event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {

            phoneQueue.addLast(
                RawSample(event.timestamp, event.values[0], event.values[1], event.values[2], false)
            )

            // Maintain reasonable queue size
            if (phoneQueue.size > 3000) {
                repeat(phoneQueue.size - 3000) { phoneQueue.removeFirst() }
            }
        }
    }

    /**
     * Broadcasts the current service status to UI components
     */
    private fun broadcastStatus() {
        val isWatchReady = isWatchActive()
        val watchQueueSize = synchronized(watchQueue) { watchQueue.size }

        val statusIntent = Intent(ACTION_SERVICE_STATUS).apply {
            putExtra("isRunning", isRunning)
            putExtra("isWatchReady", isWatchReady)
            putExtra("watchQueueSize", watchQueueSize)
            putExtra("phoneQueueSize", phoneQueue.size)
            putExtra("expectingWatchData", expectingWatchData)
            putExtra("watchStartAttempted", watchStartAttempted)
            putExtra("deviceMode", currentDeviceMode)
        }
        sendBroadcast(statusIntent)

        Log.d(TAG, "Broadcast status: running=$isRunning, watchReady=$isWatchReady, " +
                "watchQueue=$watchQueueSize, phoneQueue=${phoneQueue.size}")
    }

    /**
     * Broadcasts service updates/messages to UI components
     */
    private fun broadcastServiceUpdate(message: String) {
        val intent = Intent("com.example.myfalldetectionapplitertpro.SERVICE_UPDATE").apply {
            putExtra("message", message)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Service update: $message")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        interpreter?.close()
        interpreter = null
    }

    /**
     * Vibrates the phone to alert the user
     */
    private fun vibratePhone() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(500)
        }
    }

    /**
     * Broadcasts fall detection to UI components
     */
    private fun sendFallDetectedBroadcast() {
        val intent = Intent(ACTION_FALL_DETECTED).apply { `package` = packageName }
        sendBroadcast(intent)
    }

    /**
     * Creates the notification channel for foreground service
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fall Detection Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs background fall detection with GMS TFLite"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /**
     * Builds a notification for the foreground service
     */
    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fall Detection (GMS TFLite)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Updates the service's notification
     */
    private fun updateForegroundNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
    }
}