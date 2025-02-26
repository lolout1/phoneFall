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
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * A Service responsible for:
 *  - Registering the accelerometer listener
 *  - Sampling sensor data into a queue
 *  - Periodically running TFLite inference
 *  - Logging results, sending "fall detected" events, etc.
 *
 * This service broadcasts updates back to MainActivity via standard broadcasts,
 * which MainActivity listens for and updates its UI.
 *
 * Requires TFLiteLiteRT.kt in the same package for actual ML inference.
 */
class InferenceService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "InferenceService"

        // Broadcast Actions and Extras
        const val ACTION_INFERENCE_RESULT =
            "com.example.myfalldetectionapplitertpro.INFERENCE_RESULT"
        const val EXTRA_LABEL = "label"
        const val EXTRA_PROBABILITY = "probability"

        const val ACTION_FALL_DETECTED =
            "com.example.myfalldetectionapplitertpro.FALL_DETECTED"

        // Foreground notification channel
        private const val CHANNEL_ID = "fall_detection_channel"
        private const val NOTIFICATION_ID = 1001
    }

    // Sensor management
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Model helper
    private var tflHelper: TFLiteLiteRT? = null
    private var isModelReady = false

    // State
    private var isRunning = false
    private var isPausedForFall = false

    // Queued sensor samples
    private val sampleQueue = ArrayDeque<RawSample>()

    // Handler and Runnables
    private val serviceHandler = Handler(Looper.getMainLooper())

    // Inference every 1 second
    private val inferenceIntervalMs = 1000L
    private val inferenceRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                doInference()
                serviceHandler.postDelayed(this, inferenceIntervalMs)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        // Initialize sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // (Optionally) start as a foreground service so it isn't killed in background
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Initializing model..."))

        // Load TFLite model in background
        Thread {
            try {
                tflHelper = TFLiteLiteRT(this, "fall_time2vec_transformer.tflite")
                isModelReady = true
                Log.d(TAG, "TFLite model loaded successfully.")
                // Update the notification once model is ready
                updateForegroundNotification("Model ready. Tap START in app.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing TFLite", e)
                updateForegroundNotification("Model init error: ${e.message}")
            }
        }.start()
    }

    /**
     * Called whenever we do startService(...) or startForegroundService(...).
     * We'll parse any "start" or "stop" command from the Intent extras.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called, intent=$intent")

        val action = intent?.action
        when (action) {
            "START_CAPTURE" -> {
                startCapture()
            }
            "STOP_CAPTURE" -> {
                stopCapture()
            }
        }
        // If the service is killed by the system, do not recreate automatically
        return START_NOT_STICKY
    }

    /**
     * startCapture() begins sensor data collection and schedules inference.
     */
    private fun startCapture() {
        if (isRunning) return
        isRunning = true
        isPausedForFall = false
        sampleQueue.clear()

        // Register sensor at ~33 Hz
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, 30_000)
        }

        // Schedule inference
        serviceHandler.postDelayed(inferenceRunnable, inferenceIntervalMs)

        updateForegroundNotification("Capturing sensor data...")
        Log.d(TAG, "startCapture")
    }

    /**
     * stopCapture() stops sensor data collection and inference.
     */
    private fun stopCapture() {
        if (!isRunning) return
        isRunning = false

        sensorManager.unregisterListener(this)
        serviceHandler.removeCallbacks(inferenceRunnable)

        updateForegroundNotification("Stopped capturing.")
        Log.d(TAG, "stopCapture")
    }

    /**
     * Called periodically to run inference with the last 128 sensor samples.
     */
    private fun doInference() {
        if (!isModelReady) return
        if (sampleQueue.size < 128) return

        // Take the most recent 128 samples
        val count = 128
        val used = sampleQueue.toList().takeLast(count)
        val finalTime = FloatArray(count)
        val finalXYZ = Array(count) { FloatArray(3) }
        val finalMask = FloatArray(count) { 0f } // 0 => valid sample

        val t0 = used.first().nanoTime.toDouble()
        for ((i, s) in used.withIndex()) {
            finalTime[i] = ((s.nanoTime - t0) / 1e9).toFloat()
            finalXYZ[i][0] = s.x
            finalXYZ[i][1] = s.y
            finalXYZ[i][2] = s.z
        }

        // Optionally log to logcat
        for (s in used) {
            Log.d("SensorData", "Timestamp=${s.nanoTime}, x=${s.x}, y=${s.y}, z=${s.z}")
        }

        // Save sensor data to CSV
        saveSensorData(used)

        // Inference in background thread
        Thread {
            try {
                val pFall = tflHelper?.runInference(finalTime, finalXYZ, finalMask) ?: -9999f
                val label = if (pFall > 0.9f) "FALL DETECTED" else "No Fall"

                // Broadcast result to MainActivity
                broadcastInferenceResult(label, pFall)

                if (label == "FALL DETECTED" && !isPausedForFall) {
                    isPausedForFall = true
                    // Vibrate phone
                    vibratePhone()

                    // Stop capturing so we don't spam detections
                    stopCapture()

                    // Notify MainActivity to show the alert
                    sendFallDetectedBroadcast()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
            }
        }.start()
    }

    /**
     * Called whenever new accelerometer data arrives.
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Add sample to the queue
            sampleQueue.addLast(
                RawSample(
                    nanoTime = event.timestamp,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2]
                )
            )
            // Bound the queue size
            if (sampleQueue.size > 2000) {
                repeat(sampleQueue.size - 2000) { sampleQueue.removeFirst() }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * Broadcast the inference result back to the Activity to update UI.
     */
    private fun broadcastInferenceResult(label: String, probability: Float) {
        val intent = Intent(ACTION_INFERENCE_RESULT).apply {
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_PROBABILITY, probability)
        }
        sendBroadcast(intent)
    }

    /**
     * Broadcast a "fall detected" event, prompting MainActivity to show an AlertDialog.
     */
    private fun sendFallDetectedBroadcast() {
        val intent = Intent(ACTION_FALL_DETECTED)
        sendBroadcast(intent)
    }

    /**
     * Vibrate the phone for 500 ms.
     */
    private fun vibratePhone() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }

    /**
     * Save sensor samples to a CSV file in the app's external files directory.
     */
    private fun saveSensorData(samples: List<RawSample>) {
        try {
            // Check for runtime storage permission if below Android 10
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "No WRITE_EXTERNAL_STORAGE permission; skipping CSV write.")
                    return
                }
            }

            val folder = getExternalFilesDir(null)
            val file = File(folder, "sensor_data.csv")
            val writer = FileWriter(file, true)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            for (sample in samples) {
                val timeStr = sdf.format(Date(System.currentTimeMillis()))
                writer.append("$timeStr,${sample.nanoTime},${sample.x},${sample.y},${sample.z}\n")
            }
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sensor data", e)
        }
    }

    /**
     * Create a notification channel for our foreground service (required on Android 8.0+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fall Detection Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for background fall detection"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Build a notification for foreground service.
     */
    private fun buildForegroundNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyFallDetectionApp")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Update the foreground notification's text. (Optional convenience method)
     */
    private fun updateForegroundNotification(contentText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = buildForegroundNotification(contentText)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        // Clean up
        stopCapture()
        tflHelper?.close()
        tflHelper = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        // We won't do binding in this sample; return null
        return null
    }
}
