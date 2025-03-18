package com.example.myfalldetectionapplitertpro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable

/**
 * HomeActivity that manages the primary fall detection UI and coordinates
 * communication with the watch device for accelerometer data transmission.
 */
class HomeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HomeActivity"

        // Watch communication paths
        private const val PATH_START_ON_WATCH = "/start_on_watch"
        private const val PATH_STOP_ON_WATCH = "/stop_on_watch"
    }

    // UI Elements
    private lateinit var btnStart: Button
    private lateinit var tvActivated: TextView
    private lateinit var tvStopwatch: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView
    private lateinit var tvPredictionsHistory: TextView
    private lateinit var scrollViewPredictions: ScrollView

    private var isRunning = false
    private var startMs: Long = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val predictionsHistory = mutableListOf<String>()

    // Stopwatch update runnable
    private val updateStopwatchRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val elapsed = System.currentTimeMillis() - startMs
                tvStopwatch.text = "Stopwatch: ${elapsed} ms"
                uiHandler.postDelayed(this, 100)
            }
        }
    }

    // BroadcastReceiver to receive inference results and fall detection events
    private val inferenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                BackgroundFallService.ACTION_INFERENCE_RESULT -> {
                    val label = intent.getStringExtra(BackgroundFallService.EXTRA_LABEL) ?: "N/A"
                    val probability = intent.getFloatExtra(BackgroundFallService.EXTRA_PROBABILITY, -9999f)
                    val minSmv = intent.getFloatExtra(BackgroundFallService.EXTRA_MIN_SMV, 0f)
                    val maxSmv = intent.getFloatExtra(BackgroundFallService.EXTRA_MAX_SMV, 0f)
                    val avgSmv = intent.getFloatExtra(BackgroundFallService.EXTRA_AVG_SMV, 0f)

                    Log.d(TAG, "Received inference: $label (prob=$probability, SMV min=$minSmv, max=$maxSmv, avg=$avgSmv)")
                    updateInferenceUI(label, probability, minSmv, maxSmv, avgSmv)
                }
                BackgroundFallService.ACTION_FALL_DETECTED -> {
                    Log.d(TAG, "Fall detected broadcast received!")
                    showFallDetectedDialog()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection)
        Log.d(TAG, "onCreate")

        // Bind UI views
        bindViews()

        // Set button listeners
        btnStart.setOnClickListener {
            if (!isRunning) startCapture() else stopCapture()
        }

        updateConfigDisplay()
    }

    override fun onResume() {
        super.onResume()
        // Register broadcast receiver for inference results and fall detection
        val filter = IntentFilter().apply {
            addAction(BackgroundFallService.ACTION_INFERENCE_RESULT)
            addAction(BackgroundFallService.ACTION_FALL_DETECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(inferenceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(inferenceReceiver, filter)
        }

        updateConfigDisplay()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(inferenceReceiver)
    }

    /**
     * Bind all UI elements from the layout
     */
    private fun bindViews() {
        try {
            btnStart = findViewById(R.id.btnStart)
            tvActivated = findViewById(R.id.tvActivated)
            tvStopwatch = findViewById(R.id.tvStopwatch)
            tvPrediction = findViewById(R.id.tvPrediction)
            tvProbability = findViewById(R.id.tvProbability)
            tvPredictionsHistory = findViewById(R.id.tvPredictionsHistory)
            scrollViewPredictions = findViewById(R.id.scrollViewPredictions)
        } catch (e: Exception) {
            Log.e(TAG, "Error binding views: ${e.message}")
            Toast.makeText(this, "UI Error: Some elements might not be available", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Reads and displays the saved configuration, and configures UI accordingly
     */
    private fun updateConfigDisplay() {
        val modelFile = PrefsHelper.getModelFile(this)
        val deviceMode = PrefsHelper.getDeviceMode(this)
        val sensorType = PrefsHelper.getSensorType(this)
        val timeEmbed = PrefsHelper.isTimeEmbeddingEnabled(this)

        Log.d(TAG, "Current config: Model=$modelFile, Mode=$deviceMode, Sensor=$sensorType, TimeEmbed=$timeEmbed")

        // When device mode is "Watch", disable the start button on the phone
        // (since watch is providing the data, not the phone)
        if (deviceMode.equals("Watch", ignoreCase = true)) {
            btnStart.isEnabled = true // We still need the button enabled to control the watch
            // Check if a watch is actually connected
            checkWatchConnectivity()
        } else {
            btnStart.isEnabled = true
            btnStart.alpha = 1.0f
        }
    }

    /**
     * Check if a watch is connected and alert user if not
     */
    private fun checkWatchConnectivity() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                val connectedNodes = nodes.filter { it.isNearby }
                if (connectedNodes.isEmpty()) {
                    btnStart.isEnabled = false
                    btnStart.alpha = 0.5f
                    Toast.makeText(
                        this,
                        "Warning: No watch connected but Watch mode is active",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.w(TAG, "No connected watch nodes found, but Watch mode is active")
                } else {
                    btnStart.isEnabled = true
                    btnStart.alpha = 1.0f
                    Log.d(TAG, "Found ${connectedNodes.size} connected watch nodes: ${connectedNodes.map { it.displayName }}")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to check for connected watch nodes", e)
                Toast.makeText(this, "Error checking watch connection", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Start fall detection capture
     */
    private fun startCapture() {
        isRunning = true
        startMs = System.currentTimeMillis()

        // Update UI
        tvActivated.text = "Activated!"
        tvStopwatch.text = "Stopwatch: 0 ms"
        tvPrediction.text = "Waiting..."
        tvProbability.text = "Probability: -"
        tvPredictionsHistory.text = ""
        predictionsHistory.clear()

        // Get current device mode
        val deviceMode = PrefsHelper.getDeviceMode(this)

        // Start the BackgroundFallService on the phone if needed
        if (deviceMode.equals("Phone", ignoreCase = true) || deviceMode.equals("Both", ignoreCase = true)) {
            Log.d(TAG, "Starting phone BackgroundFallService for $deviceMode mode")
            val intent = Intent(this, BackgroundFallService::class.java).apply {
                action = "START_CAPTURE"
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            Log.d(TAG, "Phone sensors not used in $deviceMode mode")
        }

        // Start capture on watch if in Watch or Both mode
        if (deviceMode.equals("Watch", ignoreCase = true) || deviceMode.equals("Both", ignoreCase = true)) {
            Log.d(TAG, "Sending start command to watch for $deviceMode mode")
            sendMessageToWatch(PATH_START_ON_WATCH, ByteArray(0))
        }

        // Start stopwatch updates
        uiHandler.post(updateStopwatchRunnable)
    }

    /**
     * Stop fall detection capture
     */
    private fun stopCapture() {
        isRunning = false
        uiHandler.removeCallbacks(updateStopwatchRunnable)

        // Update UI
        tvActivated.text = "Not Activated"
        val elapsed = System.currentTimeMillis() - startMs
        tvStopwatch.text = "Stopwatch: ${elapsed} ms (stopped)"

        // Get current device mode
        val deviceMode = PrefsHelper.getDeviceMode(this)

        // Stop the BackgroundFallService on the phone if needed
        if (deviceMode.equals("Phone", ignoreCase = true) || deviceMode.equals("Both", ignoreCase = true)) {
            Log.d(TAG, "Stopping phone BackgroundFallService for $deviceMode mode")
            val intent = Intent(this, BackgroundFallService::class.java).apply {
                action = "STOP_CAPTURE"
            }
            startService(intent)
        }

        // Stop capture on watch if in Watch or Both mode
        if (deviceMode.equals("Watch", ignoreCase = true) || deviceMode.equals("Both", ignoreCase = true)) {
            Log.d(TAG, "Sending stop command to watch for $deviceMode mode")
            sendMessageToWatch(PATH_STOP_ON_WATCH, ByteArray(0))
        }
    }

    /**
     * Update the UI with inference results
     */
    private fun updateInferenceUI(label: String, probability: Float, minSmv: Float, maxSmv: Float, avgSmv: Float) {
        tvPrediction.text = label
        tvProbability.text = "Probability: %.3f".format(probability)

        // Add to prediction history
        val historyEntry = "$label (%.3f) - SMV: ${avgSmv.format(2)}".format(probability)
        predictionsHistory.add(historyEntry)
        if (predictionsHistory.size > 8) {
            predictionsHistory.removeAt(0)
        }

        // Update the history text view
        tvPredictionsHistory.text = predictionsHistory.joinToString("\n")
        scrollViewPredictions.post { scrollViewPredictions.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * Show a dialog when a fall is detected
     */
    private fun showFallDetectedDialog() {
        if (!isFinishing) {
            AlertDialog.Builder(this)
                .setTitle("Fall Detected")
                .setMessage("A fall has been detected. Please click OK to continue.")
                .setCancelable(false)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                    // Restart capturing after acknowledging the fall
                    startCapture()
                }
                .show()
        }
    }

    /**
     * Send a message to connected watch devices
     */
    private fun sendMessageToWatch(path: String, data: ByteArray) {
        Log.d(TAG, "Preparing to send message to watch: path=$path")

        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                val connectedNodes = nodes.filter { it.isNearby }
                if (connectedNodes.isEmpty()) {
                    Log.e(TAG, "No connected watch nodes found for sending $path")
                    Toast.makeText(this, "No watch connected to receive command", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                for (node in connectedNodes) {
                    Log.d(TAG, "Sending $path to watch node: ${node.displayName} (${node.id})")
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, path, data)
                        .addOnSuccessListener {
                            Log.d(TAG, "Successfully sent $path to watch ${node.displayName}")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send $path to watch ${node.displayName}: ${e.message}", e)
                            Toast.makeText(this, "Failed to send command to watch", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get connected nodes for message $path: ${e.message}", e)
                Toast.makeText(this, "Failed to communicate with watch", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Helper extension function for formatting floats
     */
    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}