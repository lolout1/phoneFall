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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.TextView
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
    private lateinit var tvPredCount: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView

    // TFLite helper; initially null until loaded.
    @Volatile
    private var tflHelper: TFLiteLiteRT? = null

    // Sensor capture and inference state
    private var isRunning = false
    private var startTime: Long = 0L
    private var predictionCount = 0
    private val handler = Handler(Looper.getMainLooper())

    // Sensor sample buffer and parameters
    private val sampleQueue = mutableListOf<AccelSample>()
    private val windowDurationNs = 4_000_000_000L // 4 seconds (in nanoseconds)
    private val targetSampleCount = 128

    // Runnable to update the stopwatch every 100ms.
    private val updateStopwatchRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val elapsed = System.currentTimeMillis() - startTime
                tvStopwatch.text = "Stopwatch: ${elapsed} ms"
                handler.postDelayed(this, 100)
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
        tvPredCount = findViewById(R.id.tvPredCount)
        tvPrediction = findViewById(R.id.tvPrediction)
        tvProbability = findViewById(R.id.tvProbability)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Disable start button until model is loaded.
        btnStart.isEnabled = false

        // Initialize TFLite helper on a background thread.
        Thread {
            val helper = TFLiteLiteRT(applicationContext, "fall_time2vec_transformer.tflite")
            runOnUiThread {
                tflHelper = helper
                btnStart.isEnabled = true
                Log.d("MainActivity", "Model loaded and interpreter ready")
            }
        }.start()

        btnStart.setOnClickListener {
            if (!isRunning) {
                startSensorCapture()
            } else {
                stopSensorCapture()
            }
        }

        checkStoragePermissionIfNeeded()
    }

    private fun startSensorCapture() {
        // Ensure the model is loaded.
        if (tflHelper == null) {
            Log.e("MainActivity", "Model not loaded yet!")
            return
        }
        isRunning = true
        tvActivated.text = "Activated!"
        startTime = System.currentTimeMillis()
        predictionCount = 0
        tvPredCount.text = "Predictions: 0"
        tvPrediction.text = "No predictions yet"
        tvProbability.text = "Last Probability: -"
        sampleQueue.clear()
        handler.post(updateStopwatchRunnable)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopSensorCapture() {
        isRunning = false
        tvActivated.text = "Not Activated"
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(updateStopwatchRunnable)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val sample = AccelSample(event.timestamp, event.values[0], event.values[1], event.values[2])
        sampleQueue.add(sample)

        // When the window duration (4 seconds) has elapsed…
        if (sampleQueue.isNotEmpty() &&
            (sampleQueue.last().timeNs - sampleQueue.first().timeNs >= windowDurationNs)) {

            val finalSamples = if (sampleQueue.size > targetSampleCount) {
                uniformDownsample(sampleQueue, targetSampleCount)
            } else {
                sampleQueue.toList()
            }

            // Example preprocessing: compute the magnitude of each sample.
            val inputData = FloatArray(finalSamples.size)
            finalSamples.forEachIndexed { index, s ->
                inputData[index] = sqrt(s.x * s.x + s.y * s.y + s.z * s.z)
            }

            // Run inference in a background thread.
            Thread {
                try {
                    // Use the model helper; if it is null (should not be), default to -9999.
                    val probability = tflHelper?.runInference(inputData) ?: -9999f
                    predictionCount++
                    runOnUiThread {
                        tvPredCount.text = "Predictions: $predictionCount"
                        tvProbability.text = "Last Probability: ${"%.3f".format(probability)}"
                        tvPrediction.text = if (probability > 0.5f) "FALL DETECTED!" else "No Fall"
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Inference failed", e)
                    runOnUiThread {
                        tvPrediction.text = "Inference error: ${e.message}"
                    }
                }
            }.start()

            sampleQueue.clear()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Uniformly downsample the list of samples to the target count.
     */
    private fun uniformDownsample(data: List<AccelSample>, target: Int): List<AccelSample> {
        val n = data.size
        return List(target) { i ->
            val idx = (i.toFloat() * (n - 1) / (target - 1)).toInt()
            data[idx]
        }
    }

    private fun checkStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 29) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 123)
            }
        }
    }
}
