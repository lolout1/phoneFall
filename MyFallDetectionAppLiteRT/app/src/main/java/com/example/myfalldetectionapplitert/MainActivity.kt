package com.example.myfalldetectionapplitert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.exp

/**
 * This is a simple Activity that:
 *  - collects accelerometer data ~ every 30ms
 *  - builds 4s windows => ~133 samples
 *  - if <128 => discard, if >128 => uniform downsample to 128
 *  - uses stride=1s => remove older samples from queue after inference
 *  - logs (time offset, x, y, z, logits) to a text file
 *  - uses LiteRT for TFLite inference
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val sampleQueue = mutableListOf<AccelSample>()
    private val windowSizeSec = 4.0f
    private val strideSec = 1.0f
    private val targetCount = 128
    private val sensorDelayUs = 30000 // ~30ms => ~33Hz

    private lateinit var statusText: TextView
    private lateinit var tflHelper: TFLiteLiteRT

    private lateinit var logFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.textStatus)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        tflHelper = TFLiteLiteRT(this, "fall_time2vec_transformer.tflite")

        // We'll write logs to /storage/emulated/0/Android/data/<pkg>/files
        val externalFilesDir = getExternalFilesDir(null)
        logFile = File(externalFilesDir, "fall_logs.txt")

        checkStoragePermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also {
            sensorManager.registerListener(
                this,
                it,
                sensorDelayUs
            )
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

            sampleQueue.add(AccelSample(nowNs, x, y, z))
            checkWindows()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    private fun checkWindows() {
        if (sampleQueue.isEmpty()) return
        val oldestNs = sampleQueue.first().timeNs
        val newestNs = sampleQueue.last().timeNs
        val elapsedSec = (newestNs - oldestNs)/1_000_000_000.0f

        if (elapsedSec >= windowSizeSec) {
            // We have >=4s of data
            val cutoffNs = oldestNs + (windowSizeSec * 1_000_000_000L)
            val windowSamples = sampleQueue.filter { it.timeNs <= cutoffNs }
            if (windowSamples.size < targetCount) {
                // Discard
                println("[DEBUG] Window has only ${windowSamples.size}<${targetCount}, discarding")
            } else {
                val finalSamples =
                    if (windowSamples.size > targetCount) uniformDownsample(windowSamples, targetCount)
                    else windowSamples
                doInference(finalSamples)
            }
            // Remove everything up to oldestNs+1s
            val removeNs = oldestNs + (strideSec*1_000_000_000L)
            sampleQueue.removeAll { it.timeNs <= removeNs }
        }
    }

    private fun uniformDownsample(data: List<AccelSample>, target: Int): List<AccelSample> {
        val n = data.size
        val out = mutableListOf<AccelSample>()
        for (i in 0 until target) {
            val idxF = i.toFloat()*(n-1)/(target-1)
            out.add(data[idxF.toInt()])
        }
        return out
    }

    private fun doInference(samples: List<AccelSample>) {
        val T = samples.size
        val firstNs = samples.first().timeNs

        // prepare
        val accelData = Array(T){ FloatArray(3) }
        val timeData  = FloatArray(T)
        val maskData  = BooleanArray(T){ false }

        for (i in samples.indices) {
            val s = samples[i]
            accelData[i][0] = s.x
            accelData[i][1] = s.y
            accelData[i][2] = s.z
            timeData[i] = (s.timeNs - firstNs)/1_000_000_000.0f
        }

        val logits = tflHelper.predict(accelData, timeData, maskData)
        val probs = softmax(logits)
        val pFall = probs[1]
        val isFall = (pFall > 0.5f)

        runOnUiThread {
            if (isFall) {
                statusText.text = "FALL DETECTED (p=%.3f)".format(pFall)
            } else {
                statusText.text = "No Fall (p=%.3f)".format(pFall)
            }
        }

        // Log
        logSamplesAndOutput(samples, logits)
    }

    private fun softmax(vals: FloatArray): FloatArray {
        val maxVal = vals.maxOrNull() ?: 0f
        val exps = vals.map { exp(it - maxVal) }
        val sumExp = exps.sum()
        return exps.map { it/sumExp }.toFloatArray()
    }

    private fun logSamplesAndOutput(samples: List<AccelSample>, logits: FloatArray) {
        try {
            FileOutputStream(logFile, true).use { fos ->
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                val nowStr = sdf.format(System.currentTimeMillis())
                fos.write(("[Window: $nowStr ]\n").toByteArray())

                val firstNs = samples.first().timeNs
                for (s in samples) {
                    val dt = (s.timeNs - firstNs)/1_000_000_000.0f
                    val line = String.format(Locale.US, "%8.5f, %.5f, %.5f, %.5f\n", dt, s.x, s.y, s.z)
                    fos.write(line.toByteArray())
                }
                val outLine = "Model logits=%.5f, %.5f\n\n".format(logits[0], logits[1])
                fos.write(outLine.toByteArray())
            }
        } catch (ex: Exception){
            ex.printStackTrace()
        }
    }

    private fun checkStoragePermissionIfNeeded() {
        // For older versions, we might need to request
        if (Build.VERSION.SDK_INT < 29) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 123)
            }
        }
    }

    data class AccelSample(
        val timeNs: Long,
        val x: Float,
        val y: Float,
        val z: Float
    )
}
