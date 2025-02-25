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

// Single raw sample: timestamp (ns) + x,y,z
data class RawSample(
    val nanoTime: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // UI
    private lateinit var btnStart: Button
    private lateinit var tvActivated: TextView
    private lateinit var tvStopwatch: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView
    private lateinit var tvPredictionsHistory: TextView

    // TFLite helper
    private var tflHelper: TFLiteLiteRT? = null
    private var isModelReady = false

    // Capture / state
    private var isRunning = false
    private var startMs = 0L

    // We store raw samples up to 128
    private val sampleQueue = ArrayDeque<RawSample>()  // use ArrayDeque for efficient removal from front

    private val uiHandler = Handler(Looper.getMainLooper())

    // We'll run inference every 1 second while capturing
    private val inferenceIntervalMs = 1000L
    private val inferenceRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                doInference() // if at least 128 samples are available
                uiHandler.postDelayed(this, inferenceIntervalMs)
            }
        }
    }

    // Periodic UI stopwatch update
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

        // UI references
        btnStart = findViewById(R.id.btnStart)
        tvActivated = findViewById(R.id.tvActivated)
        tvStopwatch = findViewById(R.id.tvStopwatch)
        tvPrediction = findViewById(R.id.tvPrediction)
        tvProbability = findViewById(R.id.tvProbability)
        tvPredictionsHistory = findViewById(R.id.tvPredictionsHistory)

        // Sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Load TFLite model off the main thread
        Thread {
            try {
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

    private fun startCapture() {
        isRunning = true
        sampleQueue.clear()

        tvActivated.text = "Activated!"
        tvStopwatch.text = "Stopwatch: 0 ms"
        tvPrediction.text = "Waiting..."
        tvProbability.text = "Probability: -"
        tvPredictionsHistory.text = ""

        startMs = System.currentTimeMillis()

        // ~33 Hz => 30_000 µs
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, 30_000)
        }

        // Start UI stopwatch
        uiHandler.post(updateStopwatchRunnable)

        // Start repeating inference every 1 second
        uiHandler.postDelayed(inferenceRunnable, inferenceIntervalMs)
    }

    private fun stopCapture() {
        if (!isRunning) return
        isRunning = false

        sensorManager.unregisterListener(this)
        tvActivated.text = "Not Activated"

        // Stop UI updates
        uiHandler.removeCallbacks(updateStopwatchRunnable)
        uiHandler.removeCallbacks(inferenceRunnable)

        val elapsed = System.currentTimeMillis() - startMs
        tvStopwatch.text = "Stopwatch: $elapsed ms"
    }

    /**
     * doInference():
     * If we have >= 128 samples, run inference on the latest 128.
     * Optionally, remove older samples from the front to achieve a stride (overlap or no overlap).
     */
    private fun doInference() {
        if (!isModelReady) return

        val N = sampleQueue.size
        if (N < 128) {
            // Not enough data yet
            return
        }

        // We'll take the last 128
        // If we want a pure sliding window with stride of 1 sample, we'd remove 1 each time, etc.
        // For demonstration, we'll do a FULL overlap => always the last 128 samples
        val finalCount = 128
        val used = sampleQueue.takeLast(finalCount)

        // Convert to arrays: finalTime [128], finalXYZ [128,3], finalMask [128]
        val finalTime = FloatArray(finalCount)
        val finalXYZ = Array(finalCount) { FloatArray(3) }
        val finalMask = FloatArray(finalCount) { 0f }

        // Base time
        val t0 = used.first().nanoTime.toDouble()
        for (i in used.indices) {
            val s = used.elementAt(i)
            finalTime[i] = ((s.nanoTime - t0) / 1e9).toFloat()
            finalXYZ[i][0] = s.x
            finalXYZ[i][1] = s.y
            finalXYZ[i][2] = s.z
        }

        // (Optionally) remove older samples so the queue doesn't grow infinitely
        // If we want a full overlap, do not remove any. (We'll keep them for the next inference.)
        // If we want partial overlap, remove e.g. 33 samples for ~1s stride at ~33Hz sampling:
        //   for (x in 0 until 33) { sampleQueue.removeFirst() }
        // For now, let's do full overlap => do not remove.

        Thread {
            try {
                val pFall = tflHelper?.runInference(finalTime, finalXYZ, finalMask) ?: -9999f
                val label = if (pFall > 0.5f) "FALL DETECTED" else "No Fall"
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
            // (Optional) If we want to cap queue size to avoid indefinite memory growth:
            if (sampleQueue.size > 2000) {
                // just in case, limit queue
                repeat(sampleQueue.size - 2000) {
                    sampleQueue.removeFirst()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    private fun updateUI(label: String, probability: Float) {
        runOnUiThread {
            tvPrediction.text = label
            tvProbability.text = "Probability: %.3f".format(probability)
            val historyLine = "$label - %.3f".format(probability)
            tvPredictionsHistory.append("\n$historyLine")
        }
    }

    /**
     * Check or request external storage permission on older devices (below API 29),
     * if you actually need read/write. You can remove if not needed.
     */
    private fun checkStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, perm)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(perm), 123)
            }
        }
    }
}
