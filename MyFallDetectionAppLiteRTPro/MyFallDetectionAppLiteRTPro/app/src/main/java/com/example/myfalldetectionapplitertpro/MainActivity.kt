package com.example.myfalldetectionapplitertpro

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    // UI
    private lateinit var btnStart: Button
    private lateinit var tvActivated: TextView
    private lateinit var tvStopwatch: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView
    private lateinit var tvHistory: TextView
    private lateinit var scrollViewPredictions: ScrollView

    // State for the stopwatch
    private var isRunning = false
    private var startMs: Long = 0L

    // Handler to update stopwatch every 100 ms
    private val uiHandler = Handler(Looper.getMainLooper())
    private val updateStopwatchRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val elapsed = System.currentTimeMillis() - startMs
                tvStopwatch.text = "Stopwatch: $elapsed ms"
                uiHandler.postDelayed(this, 100)
            }
        }
    }

    // Keep a small list of predictions
    private val predictionsHistory = mutableListOf<String>()

    // Listen for broadcasts from the service
    private val inferenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BackgroundFallService.ACTION_INFERENCE_RESULT -> {
                    val label = intent.getStringExtra(BackgroundFallService.EXTRA_LABEL) ?: "N/A"
                    val probability = intent.getFloatExtra(BackgroundFallService.EXTRA_PROBABILITY, -9999f)
                    Log.d(TAG, "Inference: $label ($probability)")
                    updateInferenceUI(label, probability)
                }
                BackgroundFallService.ACTION_FALL_DETECTED -> {
                    Log.d(TAG, "Fall Detected broadcast!")
                    showFallDetectedDialog()
                }
            }
        }
    }

    private fun updateInferenceUI(label: String, probability: Float) {
        tvPrediction.text = label
        tvProbability.text = "Probability: %.3f".format(probability)

        // Keep last 8
        predictionsHistory.add("$label (%.3f)".format(probability))
        if (predictionsHistory.size > 8) {
            predictionsHistory.removeAt(0)
        }
        tvHistory.text = predictionsHistory.joinToString("\n")

        scrollViewPredictions.post {
            scrollViewPredictions.fullScroll(ScrollView.FOCUS_DOWN)
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
        tvHistory = findViewById(R.id.tvPredictionsHistory)
        scrollViewPredictions = findViewById(R.id.scrollViewPredictions)

        btnStart.setOnClickListener {
            if (!isRunning) {
                startCapture()
            } else {
                stopCapture()
            }
        }

        checkStoragePermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(BackgroundFallService.ACTION_INFERENCE_RESULT)
            addAction(BackgroundFallService.ACTION_FALL_DETECTED)
        }
        registerReceiver(inferenceReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(inferenceReceiver)
    }

    private fun startCapture() {
        isRunning = true
        startMs = System.currentTimeMillis()

        tvActivated.text = "Activated!"
        tvStopwatch.text = "Stopwatch: 0 ms"
        tvPrediction.text = "Waiting..."
        tvProbability.text = "Probability: -"
        tvHistory.text = ""
        predictionsHistory.clear()

        // Start the service with action = "START_CAPTURE"
        val intent = Intent(this, BackgroundFallService::class.java).apply {
            action = "START_CAPTURE"
        }
        // For Android 8.0+ you must use startForegroundService
        ContextCompat.startForegroundService(this, intent)

        uiHandler.post(updateStopwatchRunnable)
    }

    private fun stopCapture() {
        isRunning = false
        tvActivated.text = "Not Activated"
        uiHandler.removeCallbacks(updateStopwatchRunnable)
        val elapsed = System.currentTimeMillis() - startMs
        tvStopwatch.text = "Stopwatch: $elapsed ms"

        // Tell the service to stop capturing
        val intent = Intent(this, BackgroundFallService::class.java).apply {
            action = "STOP_CAPTURE"
        }
        startService(intent)
    }

    /**
     * Called if service broadcasts ACTION_FALL_DETECTED
     */
    private fun showFallDetectedDialog() {
        if (!isFinishing) {
            AlertDialog.Builder(this)
                .setTitle("Fall Detected")
                .setMessage("A fall has been detected. Please click OK to continue.")
                .setCancelable(false)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                    // Optionally re-start capture
                    startCapture()
                }
                .show()
        }
    }

    /**
     * For API < 29, request WRITE_EXTERNAL_STORAGE if needed.
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
