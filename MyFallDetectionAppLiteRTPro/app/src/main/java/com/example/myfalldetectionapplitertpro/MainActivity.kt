package com.example.myfalldetectionapplitertpro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.exp
import kotlin.system.measureTimeMillis

/**
 * A robust solution for real-time fall detection with TFLite LiteRT integration.
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    // Accelerometer
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val sampleQueue = mutableListOf<AccelSample>()
    private val windowDurationSec = 4.0f
    private val strideSec = 1.0f
    private val targetCount = 128
    private val sensorDelayUs = 30000 // ~30ms => ~33Hz

    // UI elements
    private lateinit var btnStart: Button
    private lateinit var tvActivated: TextView
    private lateinit var tvStopwatch: TextView
    private lateinit var tvPredCount: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView

    // TFLite
    private lateinit var tflHelper: TFLiteLiteRT

    // Logging
    private lateinit var logFile: File

    // State
    private var isRunning = false
    private var startTimeMs: Long = 0
    private var predictionCount = 0

    // We'll use a Handler to update the stopwatch
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateStopwatchTask = object : Runnable {
        override fun run() {
            if (isRunning) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                tvStopwatch.text = "Stopwatch: $elapsed ms"
                mainHandler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI
        btnStart = findViewById(R.id.btnStart)
        tvActivated = findViewById(R.id.tvActivated)
        tvStopwatch = findViewById(R.id.tvStopwatch)
        tvPredCount = findViewById(R.id.tvPredCount)
        tvPrediction = findViewById(R.id.tvPrediction)
        tvProbability = findViewById(R.id.tvProbability)

        // TFLite
        tflHelper = TFLiteLiteRT(this, "fall_time2vec_transformer.tflite")

        // Logging
        val externalFiles = getExternalFilesDir(null)
        logFile = File(externalFiles, "fall_logs.txt")

        // Sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        btnStart.setOnClickListener {
            onStartButtonPressed()
        }

        checkStoragePermissionIfNeeded()
    }

    private fun onStartButtonPressed() {
        if (!isRunning) {
            isRunning = true
            tvActivated.text = "Activated!"
            startTimeMs = System.currentTimeMillis()
            predictionCount = 0
            tvPredCount.text = "Predictions: 0"
            tvPrediction.text = "No predictions yet"
            tvProbability.text = "Last Probability: -"
            mainHandler.post(updateStopwatchTask)

            // Register sensor listener
            accelerometer?.also {
                sensorManager.registerListener(this, it, sensorDelayUs)
            }
        } else {
            // Stop
            isRunning = false
            tvActivated.text = "Not Activated"
            sensorManager.unregisterListener(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // If collecting only after Start press, do nothing
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(updateStopwatchTask)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning) return

        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val nowNs = event.timestamp
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            sampleQueue.add(AccelSample(nowNs, x, y, z))
            checkWindow()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkWindow() {
        if (sampleQueue.isEmpty()) return
        val oldestNs = sampleQueue.first().timeNs
        val newestNs = sampleQueue.last().timeNs
        val elapsedSec = (newestNs - oldestNs) / 1_000_000_000.0f

        if (elapsedSec >= windowDurationSec) {
            // We have >=4s of data
            val cutoffNs = oldestNs + (windowDurationSec * 1_000_000_000L)
            val windowSamples = sampleQueue.filter { it.timeNs <= cutoffNs }
            if (windowSamples.size < targetCount) {
                // discard
                logMsg("[DEBUG] Window has only ${windowSamples.size} < $targetCount, discarding.")
            } else {
                val finalSamples = if (windowSamples.size > targetCount) {
                    uniformDownsample(windowSamples, targetCount)
                } else {
                    windowSamples
                }
                doInference(finalSamples)
            }
            // remove everything up to oldestNs + 1s
            val removeNs = oldestNs + (strideSec * 1_000_000_000L)
            sampleQueue.removeAll { it.timeNs <= removeNs }
        }
    }

    private fun uniformDownsample(data: List<AccelSample>, target: Int): List<AccelSample> {
        val n = data.size
        val out = mutableListOf<AccelSample>()
        for (i in 0 until target) {
            val idxF = i.toFloat() * (n - 1) / (target - 1)
            out.add(data[idxF.toInt()])
        }
        return out
    }

    private fun doInference(samples: List<AccelSample>) {
        val T = samples.size
        val firstNs = samples.first().timeNs
        val accelData = Array(T) { FloatArray(3) }
        val timeData  = FloatArray(T)
        val maskData  = BooleanArray(T) { false }

        for (i in samples.indices) {
            val s = samples[i]
            accelData[i][0] = s.x
            accelData[i][1] = s.y
            accelData[i][2] = s.z
            timeData[i] = (s.timeNs - firstNs) / 1_000_000_000.0f
        }

        // Perform inference with TFLite LiteRT
        val logits = tflHelper.predict(accelData, timeData, maskData)
        val probs = softmax(logits)
        val pFall = probs[1]
        val isFall = (pFall > 0.5f)

        // UI updates
        predictionCount++
        runOnUiThread {
            tvPredCount.text = "Predictions: $predictionCount"
            tvPrediction.text = if (isFall) "FALL DETECTED!" else "No Fall"
            tvProbability.text = "Last Probability: %.3f".format(pFall)
        }

        // Log
        logWindow(samples, logits)
    }

    private fun softmax(vals: FloatArray): FloatArray {
        val maxVal = vals.maxOrNull() ?: 0f
        val exps = vals.map { exp(it - maxVal) }
        val sumExp = exps.sum()
        return exps.map { it / sumExp }.toFloatArray()
    }

    private fun logWindow(samples: List<AccelSample>, logits: FloatArray) {
        try {
            FileOutputStream(logFile, true).use { fos ->
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                val nowStr = sdf.format(System.currentTimeMillis())
                fos.write(("[Window: $nowStr]\n").toByteArray())

                val firstNs = samples.first().timeNs
                for (s in samples) {
                    val dtSec = (s.timeNs - firstNs) / 1_000_000_000.0f
                    val line = String.format(
                        Locale.US,
                        "%8.5f, %.5f, %.5f, %.5f\n",
                        dtSec, s.x, s.y, s.z
                    )
                    fos.write(line.toByteArray())
                }
                val outLine = "Model logits=%.5f, %.5f\n\n".format(logits[0], logits[1])
                fos.write(outLine.toByteArray())
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
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

    private fun logMsg(msg: String) {
        try {
            FileOutputStream(logFile, true).use { fos ->
                fos.write(("$msg\n").toByteArray())
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    data class AccelSample(
        val timeNs: Long,
        val x: Float,
        val y: Float,
        val z: Float
    )
}
