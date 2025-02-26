package com.example.myfalldetectionapplitertpro

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class HomeActivity : AppCompatActivity() {

    private val TAG = "HomeActivity"

    // UI elements
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnViewLogs: Button
    private lateinit var tvActivated: TextView
    private lateinit var tvStopwatch: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView
    private lateinit var tvPredictionsHistory: TextView

    // Dropdowns and switch
    private lateinit var spinnerModel: Spinner
    private lateinit var spinnerDeviceMode: Spinner
    private lateinit var spinnerSensorType: Spinner
    private lateinit var switchTimeEmbedding: Switch

    private var isRunning = false
    private var startMs: Long = 0L
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

    private val predictionsHistory = mutableListOf<String>()

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
                    Log.d(TAG, "Fall Detected broadcast received")
                    showFallDetectedDialog()
                }
            }
        }
    }

    private fun updateInferenceUI(label: String, probability: Float) {
        tvPrediction.text = label
        tvProbability.text = "Probability: %.3f".format(probability)
        predictionsHistory.add("$label (%.3f)".format(probability))
        if (predictionsHistory.size > 8) predictionsHistory.removeAt(0)
        tvPredictionsHistory.text = predictionsHistory.joinToString("\n")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate")

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnViewLogs = findViewById(R.id.btnViewLogs)
        tvActivated = findViewById(R.id.tvActivated)
        tvStopwatch = findViewById(R.id.tvStopwatch)
        tvPrediction = findViewById(R.id.tvPrediction)
        tvProbability = findViewById(R.id.tvProbability)
        tvPredictionsHistory = findViewById(R.id.tvPredictionsHistory)
        spinnerModel = findViewById(R.id.spinnerModel)
        spinnerDeviceMode = findViewById(R.id.spinnerDeviceMode)
        spinnerSensorType = findViewById(R.id.spinnerSensorType)
        switchTimeEmbedding = findViewById(R.id.switchTimeEmbedding)

        // Populate spinners with array adapters
        spinnerModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.tflite_option_1), getString(R.string.tflite_option_2)))
        spinnerDeviceMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.device_mode_watch_phone), getString(R.string.device_mode_phone), getString(R.string.device_mode_watch)))
        spinnerSensorType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.sensor_accelerometer), getString(R.string.sensor_gyroscope), getString(R.string.sensor_both)))
        // Default state for switch can be set as needed.
        switchTimeEmbedding.isChecked = false

        btnStart.setOnClickListener {
            if (!isRunning) startCapture()
        }
        btnStop.setOnClickListener {
            if (isRunning) stopCapture()
        }
        btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LoggingActivity::class.java))
        }

        // Request POST_NOTIFICATIONS and BODY_SENSORS runtime permissions if needed
        checkNotificationPermissionIfNeeded()
        checkSensorPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(BackgroundFallService.ACTION_INFERENCE_RESULT)
            addAction(BackgroundFallService.ACTION_FALL_DETECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(inferenceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(inferenceReceiver, filter, RECEIVER_NOT_EXPORTED)
        }
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
        tvPredictionsHistory.text = ""
        predictionsHistory.clear()

        // Pass the current spinner selections to the service via extras
        val intent = Intent(this, BackgroundFallService::class.java).apply {
            action = "START_CAPTURE"
            putExtra("MODEL_FILE", spinnerModel.selectedItem as String)
            putExtra("DEVICE_MODE", spinnerDeviceMode.selectedItem as String)
            putExtra("SENSOR_TYPE", spinnerSensorType.selectedItem as String)
            putExtra("TIME_EMBEDDING", switchTimeEmbedding.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
        uiHandler.post(updateStopwatchRunnable)
        btnStart.visibility = View.GONE
        btnStop.visibility = View.VISIBLE
    }

    private fun stopCapture() {
        isRunning = false
        tvActivated.text = "Not Activated"
        uiHandler.removeCallbacks(updateStopwatchRunnable)
        val elapsed = System.currentTimeMillis() - startMs
        tvStopwatch.text = "Stopwatch: $elapsed ms"
        val intent = Intent(this, BackgroundFallService::class.java).apply {
            action = "STOP_CAPTURE"
        }
        startService(intent)
        btnStop.visibility = View.GONE
        btnStart.visibility = View.VISIBLE
    }

    private fun showFallDetectedDialog() {
        if (!isFinishing) {
            AlertDialog.Builder(this)
                .setTitle("Fall Detected")
                .setMessage("A fall was detected. Tap OK to continue.")
                .setCancelable(false)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                    startCapture() // optionally auto-restart
                }
                .show()
        }
    }

    private fun checkNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 456)
            }
        }
    }

    private fun checkSensorPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BODY_SENSORS), 789)
            }
        }
    }
}
