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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

// Data class representing one accelerometer sample.
data class AccelSample(val timeNs: Long, val x: Float, val y: Float, val z: Float)

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // UI elements
    private lateinit var btnStart: Button
    private lateinit var tvActivated: TextView
    private lateinit var tvStopwatch: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView
    private lateinit var tvPredictionsHistory: TextView

    // TFLite helper (initialized in a background thread)
    private var tflHelper: TFLiteLiteRT? = null
    private var isModelReady = false

    // Capture state
    private var isRunning = false
    private var startTime: Long = 0L

    // Sliding window for 128 samples
    private val targetSampleCount = 128
    private val sampleQueue = mutableListOf<AccelSample>()

    // Keep last 8 predictions
    private val predictionHistory = mutableListOf<Pair<String, Float>>()

    // For controlling inference frequency: only once per second
    private var lastInferenceTimeNs: Long = 0

    // Vibration
    private lateinit var vibrator: Vibrator

    // Stopwatch update runnable
    private val updateStopwatchRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val elapsed = System.currentTimeMillis() - startTime
                tvStopwatch.text = "Stopwatch: ${elapsed} ms"
                Handler(Looper.getMainLooper()).postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind UI
        btnStart = findViewById(R.id.btnStart)
        tvActivated = findViewById(R.id.tvActivated)
        tvStopwatch = findViewById(R.id.tvStopwatch)
        tvPrediction = findViewById(R.id.tvPrediction)
        tvProbability = findViewById(R.id.tvProbability)
        tvPredictionsHistory = findViewById(R.id.tvPredictionsHistory)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Initialize the TFLite helper off the main thread:
        Thread {
            try {
                // Make sure "fall_time2vec_transformer.tflite" is in assets/
                tflHelper = TFLiteLiteRT(this, "fall_time2vec_transformer.tflite")
                isModelReady = true
                Log.d("MainActivity", "Model initialized successfully")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error initializing model", e)
            }
        }.start()

        btnStart.setOnClickListener {
            if (!isRunning) {
                startSensorCapture()
            } else {
                stopSensorCapture()
            }
        }

        // If you truly do not need external storage access, you can remove this check:
        checkStoragePermissionIfNeeded()
    }

    private fun startSensorCapture() {
        isRunning = true
        tvActivated.text = "Activated!"
        startTime = System.currentTimeMillis()
        sampleQueue.clear()
        predictionHistory.clear()
        tvPredictionsHistory.text = ""
        tvPrediction.text = "Waiting..."
        tvProbability.text = "Probability: -"
        lastInferenceTimeNs = 0

        Handler(Looper.getMainLooper()).post(updateStopwatchRunnable)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopSensorCapture() {
        isRunning = false
        tvActivated.text = "Not Activated"
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // Add new sample
        val sample = AccelSample(event.timestamp, event.values[0], event.values[1], event.values[2])
        if (sampleQueue.size >= targetSampleCount) {
            sampleQueue.removeAt(0)
        }
        sampleQueue.add(sample)

        // Only run inference if we have 128 samples and at least 1 second since last inference
        if (sampleQueue.size == targetSampleCount) {
            val nowNs = event.timestamp
            if (lastInferenceTimeNs == 0L || (nowNs - lastInferenceTimeNs) >= 1_000_000_000L) {
                lastInferenceTimeNs = nowNs
                runInferenceOnBackgroundThread()
            }
        }
    }

    private fun runInferenceOnBackgroundThread() {
        // Convert the queue to magnitude
        val inputData = FloatArray(targetSampleCount)
        for (i in sampleQueue.indices) {
            val s = sampleQueue[i]
            val mag = sqrt(s.x * s.x + s.y * s.y + s.z * s.z)
            inputData[i] = mag
        }

        Thread {
            try {
                if (!isModelReady) {
                    // If model not ready, skip
                    updateUI("Model not ready", -9999f)
                    return@Thread
                }
                val prob = tflHelper?.runInference(inputData) ?: -9999f

                val label = if (prob > 0.5f) "FALL DETECTED" else "No Fall"

                // Vibrate if fall detected
                if (label == "FALL DETECTED") {
                    vibrateBriefly()
                }

                updateUI(label, prob)
            } catch (e: Exception) {
                Log.e("MainActivity", "Inference failed", e)
                runOnUiThread {
                    tvPrediction.text = "Inference error: ${e.message}"
                }
            }
        }.start()
    }

    private fun updateUI(label: String, probability: Float) {
        runOnUiThread {
            // Add to history
            predictionHistory.add(label to probability)
            if (predictionHistory.size > 8) {
                predictionHistory.removeAt(0)
            }
            // Display
            val historyText = predictionHistory.joinToString("\n") {
                "${it.first} - ${"%.3f".format(it.second)}"
            }
            tvPredictionsHistory.text = historyText

            tvPrediction.text = label
            tvProbability.text = "Probability: ${"%.3f".format(probability)}"
        }
    }

    private fun vibrateBriefly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(300)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // If you do not need external storage at all, you can remove this method entirely.
    private fun checkStoragePermissionIfNeeded() {
        // For Android 13+ there's a different approach, but if you truly do not need it, remove.
        val neededPermission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, neededPermission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(neededPermission), 123)
            }
        }
    }
}
