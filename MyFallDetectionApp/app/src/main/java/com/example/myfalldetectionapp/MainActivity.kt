package com.example.myfalldetectionapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import kotlin.math.exp

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // We'll store live accelerometer samples in a queue, along with timestamps.
    // We want to continuously build 4-second segments, sample rate ~30ms => ~133 samples
    // If <128 => discard window, if >128 => uniform resample
    private data class AccelSample(
        val timeNs: Long,
        val x: Float,
        val y: Float,
        val z: Float
    )

    private val sampleQueue = mutableListOf<AccelSample>()
    private val windowSizeSec = 4.0f
    private val strideSec = 1.0f
    private val desiredCount = 128

    private lateinit var tfliteHelper: TFLiteInference
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.textStatus)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        tfliteHelper = TFLiteInference(this)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also {
            // ~30ms => ~33Hz => 30ms * 133= 3990ms => ~4s
            // SensorManager.SENSOR_DELAY_GAME is ~20ms... let's manually set it
            sensorManager.registerListener(this, it, 30000) // microseconds
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val nowNs = event.timestamp
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Insert into queue
            sampleQueue.add(AccelSample(nowNs, x, y, z))

            // Try to see if we can form a window of 4.0s
            processWindows()
        }
    }

    private fun processWindows() {
        if (sampleQueue.isEmpty()) return

        val oldestNs = sampleQueue.first().timeNs
        val newestNs = sampleQueue.last().timeNs
        val elapsedSec = (newestNs - oldestNs) / 1_000_000_000.0f

        if (elapsedSec >= windowSizeSec) {
            // We have at least 4s of data
            // => Extract the chunk from oldestNs to oldestNs+4s
            val cutoffNs = oldestNs + (windowSizeSec * 1_000_000_000L)
            val windowSamples = sampleQueue.filter { it.timeNs <= cutoffNs }

            // Check how many samples in that 4s window
            if (windowSamples.size < desiredCount) {
                // Not enough => discard
                logMsg("[DEBUG] Window has only ${windowSamples.size} samples < 128 => discarding")
            } else {
                // Possibly more than 128 => uniform resample
                val finalSamples = if (windowSamples.size > desiredCount) {
                    uniformDownsample(windowSamples, desiredCount)
                } else windowSamples

                // Convert finalSamples to TFLite input
                runInference(finalSamples)
            }

            // Now drop from the queue everything up to oldestNs + strideSec
            val strideNs = oldestNs + (strideSec * 1_000_000_000L)
            sampleQueue.removeAll { it.timeNs <= strideNs }
        }
    }

    /**
     * If we have e.g. 133 samples in 4s but only want 128,
     * we pick 128 indices spaced out across the 133.
     */
    private fun uniformDownsample(data: List<AccelSample>, targetCount: Int): List<AccelSample> {
        val n = data.size
        val indices = FloatArray(targetCount) { i ->
            i.toFloat() * (n - 1) / (targetCount - 1).toFloat()
        }
        val sampled = indices.map { idx ->
            data[idx.toInt()]
        }
        logMsg("[DEBUG] uniformDownsample: original=$n, target=$targetCount => done.")
        return sampled
    }

    private fun runInference(samples: List<AccelSample>) {
        // Convert to shape [T, 3], [T], [T]
        val T = samples.size
        val firstNs = samples.first().timeNs
        val accelData = Array(T) { FloatArray(3) }
        val timeData = FloatArray(T)
        val maskData = BooleanArray(T) { false }

        for (i in samples.indices) {
            val s = samples[i]
            accelData[i][0] = s.x
            accelData[i][1] = s.y
            accelData[i][2] = s.z
            // relative time in seconds
            timeData[i] = (s.timeNs - firstNs) / 1_000_000_000.0f
            // no padding => mask=false
        }

        val logits = tfliteHelper.predict(accelData, timeData, maskData)
        // Argmax or softmax
        val probabilities = softmax(logits)
        val pFall = probabilities[1]
        val isFall = (pFall > 0.5f)
        runOnUiThread {
            if (isFall) {
                statusTextView.text = "FALL DETECTED (p=%.2f)".format(pFall)
            } else {
                statusTextView.text = "No Fall (pFall=%.2f)".format(pFall)
            }
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = logits.map { exp(it - maxLogit) }
        val sumExp = exps.sum()
        return exps.map { it / sumExp }.toFloatArray()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    private fun logMsg(msg: String) {
        println(msg) // simple debug logging
    }
}
