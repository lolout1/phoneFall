package com.example.myfalldetectionapplitertpro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.ArrayDeque

// Data class representing a single raw sensor sample.
data class RawSample(
    val nanoTime: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

class MainActivity : AppCompatActivity(), SensorEventListener {

    // Sensor management.
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // UI components.
    private lateinit var btnStart: Button
    private lateinit var tvActivated: TextView
    private lateinit var tvStopwatch: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView
    private lateinit var tvPredictionsHistory: TextView
    private lateinit var scrollViewPredictions: ScrollView

    // TFLite helper instance.
    private var tflHelper: TFLiteLiteRT? = null
    private var isModelReady = false

    // State variables.
    private var isRunning = false
    private var startMs: Long = 0L

    // Sensor sample queue (using a deque for efficient sliding window).
    private val sampleQueue = ArrayDeque<RawSample>()

    // Prediction history (store last 8 predictions).
    private val predictionsHistoryList = mutableListOf<String>()

    // Flag to pause capture if a fall is detected.
    private var isPausedForFall = false

    // Handler for UI and inference scheduling.
    private val uiHandler = Handler(Looper.getMainLooper())

    // Inference is scheduled every 1 second.
    private val inferenceIntervalMs = 1000L
    private val inferenceRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                doInference()
                uiHandler.postDelayed(this, inferenceIntervalMs)
            }
        }
    }

    // Update the stopwatch every 100 ms.
    private val updateStopwatchRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val elapsed = System.currentTimeMillis() - startMs
                tvStopwatch.text = "Stopwatch: $elapsed ms"
                uiHandler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind UI elements.
        btnStart = findViewById(R.id.btnStart)
        tvActivated = findViewById(R.id.tvActivated)
        tvStopwatch = findViewById(R.id.tvStopwatch)
        tvPrediction = findViewById(R.id.tvPrediction)
        tvProbability = findViewById(R.id.tvProbability)
        tvPredictionsHistory = findViewById(R.id.tvPredictionsHistory)
        scrollViewPredictions = findViewById(R.id.scrollViewPredictions)

        // Increase text sizes slightly.
        tvStopwatch.textSize = 20f
        tvPrediction.textSize = 20f
        tvProbability.textSize = 20f
        tvPredictionsHistory.textSize = 20f

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Load the TFLite model off the main thread.
        Thread {
            try {
                // Ensure "fall_time2vec_transformer.tflite" is in the assets folder.
                tflHelper = TFLiteLiteRT(this, "fall_time2vec_transformer.tflite")
                isModelReady = true
                Log.d("MainActivity", "TFLite model loaded successfully.")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error initializing TFLite", e)
            }
        }.start()

        btnStart.setOnClickListener {
            if (!isRunning) {
                startCapture()
            } else {
                stopCapture()
            }
        }

        checkStoragePermissionIfNeeded()
    }

    /**
     * startCapture() begins sensor data collection, resets UI, and schedules inference.
     */
    private fun startCapture() {
        isRunning = true
        sampleQueue.clear()
        predictionsHistoryList.clear()
        tvActivated.text = "Activated!"
        tvStopwatch.text = "Stopwatch: 0 ms"
        tvPrediction.text = "Waiting..."
        tvProbability.text = "Probability: -"
        tvPredictionsHistory.text = ""
        startMs = System.currentTimeMillis()
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, 30_000) // ~33 Hz
        }
        uiHandler.post(updateStopwatchRunnable)
        uiHandler.postDelayed(inferenceRunnable, inferenceIntervalMs)
    }

    /**
     * stopCapture() stops sensor data collection and removes scheduled callbacks.
     */
    private fun stopCapture() {
        if (!isRunning) return
        isRunning = false
        sensorManager.unregisterListener(this)
        tvActivated.text = "Not Activated"
        uiHandler.removeCallbacks(updateStopwatchRunnable)
        uiHandler.removeCallbacks(inferenceRunnable)
        val elapsed = System.currentTimeMillis() - startMs
        tvStopwatch.text = "Stopwatch: $elapsed ms"
    }

    /**
     * doInference() takes the last 128 sensor samples (if available), prepares input arrays,
     * saves sensor data to a CSV file, and runs inference on a background thread.
     */
    private fun doInference() {
        if (!isModelReady) return
        if (sampleQueue.size < 128) return

        val count = 128
        // Get the most recent 128 samples.
        val used = sampleQueue.toList().takeLast(count)
        val finalTime = FloatArray(count)
        val finalXYZ = Array(count) { FloatArray(3) }
        val finalMask = FloatArray(count) { 0f } // 0 indicates a valid sample.

        val t0 = used.first().nanoTime.toDouble()
        for ((i, s) in used.withIndex()) {
            finalTime[i] = ((s.nanoTime - t0) / 1e9).toFloat()
            finalXYZ[i][0] = s.x
            finalXYZ[i][1] = s.y
            finalXYZ[i][2] = s.z
        }

        // Save sensor data to a CSV file.
        saveSensorData(used)

        // Run inference on a background thread.
        Thread {
            try {
                // Adjust the parameter order as needed by your TFLite model.
                val pFall = tflHelper?.runInference(finalTime, finalXYZ, finalMask) ?: -9999f
                val label = if (pFall > 0.9f) "FALL DETECTED" else "No Fall"
                updateUI(label, pFall)
            } catch (e: Exception) {
                Log.e("MainActivity", "Inference error", e)
                runOnUiThread {
                    tvPrediction.text = "Inference error: ${e.message}"
                }
            }
        }.start()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            sampleQueue.addLast(
                RawSample(
                    nanoTime = event.timestamp,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2]
                )
            )
            // Optionally keep the queue size bounded.
            if (sampleQueue.size > 2000) {
                repeat(sampleQueue.size - 2000) { sampleQueue.removeFirst() }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used.
    }

    /**
     * updateUI() updates the prediction, probability, and history TextViews.
     * It auto-scrolls the prediction history ScrollView.
     * If a fall is detected, it vibrates the phone and shows an alert dialog.
     */
    private fun updateUI(label: String, probability: Float) {
        runOnUiThread {
            tvPrediction.text = label
            tvProbability.text = "Probability: %.3f".format(probability)

            // Update predictions history (keep last 8).
            predictionsHistoryList.add("$label - %.3f".format(probability))
            if (predictionsHistoryList.size > 8) {
                predictionsHistoryList.removeAt(0)
            }
            tvPredictionsHistory.text = predictionsHistoryList.joinToString("\n")

            // Auto-scroll the predictions history.
            scrollViewPredictions.post {
                scrollViewPredictions.fullScroll(ScrollView.FOCUS_DOWN)
            }

            // If a fall is detected (and not already paused), vibrate and show alert.
            if (label == "FALL DETECTED" && !isPausedForFall) {
                isPausedForFall = true
                stopCapture()
                vibratePhone()
                showFallDetectedDialog()
            }
        }
    }

    /**
     * vibratePhone() vibrates the device for 500 ms.
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
     * showFallDetectedDialog() displays an AlertDialog that blocks further interaction
     * until the user clicks "OK". After dismissing, capture restarts.
     */
    private fun showFallDetectedDialog() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Fall Detected")
                .setMessage("A fall has been detected. Please click OK to continue.")
                .setCancelable(false)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                    isPausedForFall = false
                    startCapture()
                }
                .show()
        }
    }

    /**
     * saveSensorData() saves the provided list of sensor samples to a CSV file in the app's
     * external files directory (which you can later copy to your PC).
     */
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
            Log.e("MainActivity", "Error saving sensor data", e)
        }
    }

    /**
     * For API levels below 29, request WRITE_EXTERNAL_STORAGE permission if needed.
     */
    private fun checkStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(perm), 123)
            }
        }
    }
}
