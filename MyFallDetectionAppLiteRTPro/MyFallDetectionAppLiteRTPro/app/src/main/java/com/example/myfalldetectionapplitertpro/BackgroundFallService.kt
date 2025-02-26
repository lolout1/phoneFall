package com.example.myfalldetectionapplitertpro

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class RawSample(
    val nanoTime: Long,
    val x: Float,
    val y: Float,
    val z: Float
)
class BackgroundFallService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "BackgroundFallService"
        const val ACTION_INFERENCE_RESULT = "com.example.myfalldetectionapplitertpro.INFERENCE_RESULT"
        const val ACTION_FALL_DETECTED = "com.example.myfalldetectionapplitertpro.FALL_DETECTED"
        const val EXTRA_LABEL = "label"
        const val EXTRA_PROBABILITY = "probability"
        private const val CHANNEL_ID = "fall_detection_channel"
        private const val NOTIFICATION_ID = 999
    }

    private var tflHelper: TFLiteLiteRT? = null
    private var isModelReady = false

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    // For simplicity, if gyroscope or both are selected, additional sensor logic would be added.
    // Here we assume accelerometer only.
    private var isRunning = false
    private var isPausedForFall = false
    private val sampleQueue = ArrayDeque<RawSample>()
    private val serviceHandler = Handler(Looper.getMainLooper())
    private val inferenceIntervalMs = 1000L
    private var runCounter = 0

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
        Log.d(TAG, "onCreate")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing model..."))

        Thread {
            try {
                // You can also get extras from the start intent if needed.
                tflHelper = TFLiteLiteRT(this, "fall_time2vec_transformer.tflite")
                isModelReady = true
                Log.d(TAG, "Model loaded successfully.")
                updateForegroundNotification("Model ready; start detection from Home.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing TFLite", e)
                updateForegroundNotification("Model init error: ${e.message}")
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_CAPTURE" -> startCapture(intent)
            "STOP_CAPTURE" -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (isRunning) return
        isRunning = true
        isPausedForFall = false
        sampleQueue.clear()

        // (Optionally process extras for model file, sensor type, etc.)
        accelerometer?.let { sensor ->
            sensorManager.registerListener(this, sensor, 30000)
        }
        serviceHandler.postDelayed(inferenceRunnable, inferenceIntervalMs)
        updateForegroundNotification("Capturing sensor data...")
    }

    private fun stopCapture() {
        if (!isRunning) return
        isRunning = false
        sensorManager.unregisterListener(this)
        serviceHandler.removeCallbacks(inferenceRunnable)
        updateForegroundNotification("Stopped capturing.")
    }

    private fun doInference() {
        if (!isModelReady) return
        if (sampleQueue.size < 128) return

        val count = 128
        val usedSamples = sampleQueue.toList().takeLast(count)
        val finalTime = FloatArray(count)
        val finalXYZ = Array(count) { FloatArray(3) }
        val finalMask = FloatArray(count) { 0f }
        val t0 = usedSamples.first().nanoTime.toDouble()
        for ((i, s) in usedSamples.withIndex()) {
            finalTime[i] = ((s.nanoTime - t0) / 1e9).toFloat()
            finalXYZ[i][0] = s.x
            finalXYZ[i][1] = s.y
            finalXYZ[i][2] = s.z
        }

        saveSensorData(usedSamples)

        Thread {
            try {
                val probability = tflHelper?.runInference(finalTime, finalXYZ, finalMask) ?: -9999f
                val label = if (probability > 0.9f) "FALL DETECTED" else "No Fall"
                broadcastInferenceResult(label, probability)
                logRunSummary(usedSamples, label)
                if (label == "FALL DETECTED" && !isPausedForFall) {
                    isPausedForFall = true
                    stopCapture()
                    vibratePhone()
                    sendFallDetectedBroadcast()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
            }
        }.start()
    }

    // Log a summary of the run (average x,y,z and timestamp, run counter, fall result)
    private fun logRunSummary(samples: List<RawSample>, result: String) {
        try {
            runCounter++
            val avgX = samples.map { it.x }.average().toFloat()
            val avgY = samples.map { it.y }.average().toFloat()
            val avgZ = samples.map { it.z }.average().toFloat()
            val sdf = SimpleDateFormat("EEE, MMM d, yyyy HH:mm:ss", Locale.getDefault())
            val timestamp = sdf.format(Date())
            val summary = "Run #$runCounter | $timestamp | $result | avgX=$avgX, avgY=$avgY, avgZ=$avgZ\n"
            val file = File(getExternalFilesDir(null), "run_log.txt")
            val writer = FileWriter(file, true)
            writer.append(summary)
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error logging run summary", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            sampleQueue.addLast(RawSample(event.timestamp, event.values[0], event.values[1], event.values[2]))
            if (sampleQueue.size > 2000) repeat(sampleQueue.size - 2000) { sampleQueue.removeFirst() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun broadcastInferenceResult(label: String, probability: Float) {
        val intent = Intent(ACTION_INFERENCE_RESULT).apply {
            `package` = packageName
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_PROBABILITY, probability)
        }
        sendBroadcast(intent)
    }

    private fun sendFallDetectedBroadcast() {
        val intent = Intent(ACTION_FALL_DETECTED).apply { `package` = packageName }
        sendBroadcast(intent)
    }

    private fun vibratePhone() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }

    private fun saveSensorData(samples: List<RawSample>) {
        try {
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

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopCapture()
        tflHelper?.close()
        tflHelper = null
    }

    // ---------- Foreground Notification Helpers ---------- //

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fall Detection Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Runs background fall detection" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fall Detection")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission missing; cannot update notification.")
            }
        } else {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
