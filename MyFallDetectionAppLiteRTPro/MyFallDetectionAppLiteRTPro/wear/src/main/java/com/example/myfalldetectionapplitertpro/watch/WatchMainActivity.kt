// Replace WatchMainActivity.kt with this version
package com.example.myfalldetectionapplitertpro.watch

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.*
import android.util.Log
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main activity for the watch app.
 * This displays status information and connection state.
 */
class WatchMainActivity : Activity() {  // CRITICAL: Must be Activity, not AppCompatActivity

    companion object {
        private const val TAG = "WatchMainActivity"

        // Broadcast action constants
        const val ACTION_START_ON_WATCH = "com.example.myfalldetectionapplitertpro.START_ON_WATCH"
        const val ACTION_STOP_ON_WATCH = "com.example.myfalldetectionapplitertpro.STOP_ON_WATCH"
        const val ACTION_PREDICT_UPDATE = "com.example.myfalldetectionapplitertpro.WATCH_PREDICT_UPDATE"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvLastUpdated: TextView

    private var isRunning = false
    private var startTime: Long = 0
    private var handler = Handler(Looper.getMainLooper())
    private var phoneConnectionChecker: Runnable? = null

    // Broadcast receiver for status updates
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_ON_WATCH -> {
                    Log.d(TAG, "Received START broadcast")
                    isRunning = true
                    startTime = System.currentTimeMillis()
                    updateUI(true)
                }
                ACTION_STOP_ON_WATCH -> {
                    Log.d(TAG, "Received STOP broadcast")
                    isRunning = false
                    updateUI(false)
                }
                ACTION_PREDICT_UPDATE -> {
                    val prediction = intent.getStringExtra("prediction") ?: "N/A"
                    Log.d(TAG, "Received prediction update: $prediction")
                    updatePrediction(prediction)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvPrediction = findViewById(R.id.tvPrediction)
        tvLastUpdated = findViewById(R.id.tvLastUpdated)

        // Initial UI state
        tvStatus.text = "Waiting for phone..."
        tvPrediction.text = "No predictions yet"
        updateTimestamp()

        // Check if service is already running
        if (WatchSensorService.isRunning()) {
            isRunning = true
            updateUI(true)
        }

        // Start a connection checker
        startPhoneConnectionChecker()
    }

    /**
     * Starts a simple background checker for phone connections
     */
    private fun startPhoneConnectionChecker() {
        phoneConnectionChecker = object : Runnable {
            override fun run() {
                // Check service status and update UI if needed
                val serviceRunning = WatchSensorService.isRunning()
                if (serviceRunning != isRunning) {
                    isRunning = serviceRunning
                    updateUI(isRunning)
                }

                // Check last updated time
                val lastUpdated = tvLastUpdated.text.toString()
                if (lastUpdated.contains("--:--:--") || lastUpdated == "Last updated: Never") {
                    // We've never received an update, check connection
                    checkPhoneConnection()
                }

                // Schedule next check
                handler.postDelayed(this, 5000) // Check every 5 seconds
            }
        }

        // Start the checks
        handler.post(phoneConnectionChecker!!)
    }

    /**
     * Stops the connection checker
     */
    private fun stopPhoneConnectionChecker() {
        phoneConnectionChecker?.let {
            handler.removeCallbacks(it)
        }
        phoneConnectionChecker = null
    }

    /**
     * Checks if we have a connection to the phone
     */
    private fun checkPhoneConnection() {
        // If we've been waiting for "too long", show a hint
        val now = System.currentTimeMillis()
        if (now - startTime > 10000) { // 10 seconds
            tvStatus.text = "Waiting for phone... Make sure app is open on phone."
            tvStatus.setTextColor(Color.YELLOW)
        }
    }

    override fun onResume() {
        super.onResume()

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_START_ON_WATCH)
            addAction(ACTION_STOP_ON_WATCH)
            addAction(ACTION_PREDICT_UPDATE)
        }

        // IMPORTANT: For API 30 (Android 11), don't use RECEIVER_NOT_EXPORTED flag
        registerReceiver(statusReceiver, filter)

        // Check service status and update UI
        checkServiceStatus()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPhoneConnectionChecker()
    }

    /**
     * Updates the UI based on running state
     */
    private fun updateUI(running: Boolean) {
        runOnUiThread {
            if (running) {
                tvStatus.text = "Active - Sending data to phone"
                tvStatus.setTextColor(Color.GREEN)
                startTime = System.currentTimeMillis()
            } else {
                tvStatus.text = "Inactive - Waiting for phone command"
                tvStatus.setTextColor(Color.YELLOW)
            }
            updateTimestamp()
        }
    }

    /**
     * Updates the prediction display
     */
    private fun updatePrediction(prediction: String) {
        runOnUiThread {
            tvPrediction.text = prediction

            // Highlight fall detection
            if (prediction.contains("FALL", ignoreCase = true)) {
                tvPrediction.setTextColor(Color.RED)
                // Vibrate to alert user
                vibrateDevice()
            } else {
                tvPrediction.setTextColor(Color.WHITE)
            }

            updateTimestamp()
        }
    }

    /**
     * Updates the last updated timestamp
     */
    private fun updateTimestamp() {
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeString = dateFormat.format(Date())
        tvLastUpdated.text = "Last updated: $timeString"
    }

    /**
     * Vibrates the watch to alert the user
     */
    private fun vibrateDevice() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating device: ${e.message}")
        }
    }

    /**
     * Checks if the sensor service is running and updates UI
     */
    private fun checkServiceStatus() {
        val isServiceRunning = WatchSensorService.isRunning()
        if (isServiceRunning != isRunning) {
            isRunning = isServiceRunning
            updateUI(isRunning)
        }
    }
}