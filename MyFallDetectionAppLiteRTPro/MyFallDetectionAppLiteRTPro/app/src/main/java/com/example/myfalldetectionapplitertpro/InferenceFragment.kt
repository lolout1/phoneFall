package com.example.myfalldetectionapplitertpro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.myfalldetectionapplitertpro.wearintegration.PhoneWearListenerService
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Fragment for controlling fall detection and displaying results.
 * Handles communication with the watch and displays status information.
 */
class InferenceFragment : Fragment() {

    companion object {
        private const val TAG = "InferenceFragment"

        // Constants for broadcasts from BackgroundFallService
        const val ACTION_INFERENCE_RESULT = BackgroundFallService.ACTION_INFERENCE_RESULT
        const val ACTION_FALL_DETECTED = BackgroundFallService.ACTION_FALL_DETECTED
        const val ACTION_SERVICE_STATUS = BackgroundFallService.ACTION_SERVICE_STATUS

        // Message paths for watch communication
        private const val PATH_START_ON_WATCH = "/start_on_watch"
        private const val PATH_STOP_ON_WATCH = "/stop_on_watch"
        private const val PATH_CONFIG_UPDATE = "/config_update"
        private const val PATH_STATUS_REQUEST = "/status_request"
    }

    // UI Components
    private lateinit var btnStart: Button
    private lateinit var tvActivated: TextView
    private lateinit var tvStopwatch: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView
    private lateinit var tvSmvInfo: TextView
    private lateinit var tvWatchStatus: TextView
    private lateinit var tvHistory: TextView
    private lateinit var scrollView: ScrollView

    // State tracking
    private var isRunning = false
    private var startMs = 0L
    private val predictionsHistory = mutableListOf<String>()
    private var lastWatchCheck = 0L
    private var watchConnectionState = "Unknown"

    // Handler for UI updates
    private val uiHandler = Handler(Looper.getMainLooper())

    // Stopwatch update runnable
    private val updateStopwatchRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val elapsed = System.currentTimeMillis() - startMs
                tvStopwatch.text = "Stopwatch: ${elapsed} ms"
                uiHandler.postDelayed(this, 100)

                // Check watch connection status periodically
                if (System.currentTimeMillis() - lastWatchCheck > 5000) {
                    checkWatchConnectivity()
                    lastWatchCheck = System.currentTimeMillis()
                }
            }
        }
    }

    // Receiver for service inference results and status
    private val inferenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                ACTION_INFERENCE_RESULT -> {
                    val label = intent.getStringExtra(BackgroundFallService.EXTRA_LABEL) ?: "N/A"
                    val prob = intent.getFloatExtra(BackgroundFallService.EXTRA_PROBABILITY, -999f)
                    val minSmv = intent.getFloatExtra(BackgroundFallService.EXTRA_MIN_SMV, 0f)
                    val maxSmv = intent.getFloatExtra(BackgroundFallService.EXTRA_MAX_SMV, 0f)
                    val avgSmv = intent.getFloatExtra(BackgroundFallService.EXTRA_AVG_SMV, 0f)
                    val dataSource = intent.getStringExtra(BackgroundFallService.EXTRA_DATA_SOURCE) ?: "Unknown"

                    Log.d(TAG, "Got inference: $label prob=$prob SMV=($minSmv..$maxSmv) avg=$avgSmv source=$dataSource")

                    // Update UI
                    tvPrediction.text = label
                    tvProbability.text = "Probability: %.3f".format(prob)
                    tvSmvInfo.text = "SMV stats: (Min=%.2f, Max=%.2f, Avg=%.2f) Source=$dataSource".format(minSmv, maxSmv, avgSmv)

                    // Add to history
                    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    predictionsHistory.add("[$timestamp] $label (%.3f) - $dataSource".format(prob))
                    if (predictionsHistory.size > 8) predictionsHistory.removeFirst()
                    tvHistory.text = predictionsHistory.joinToString("\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }

                ACTION_FALL_DETECTED -> {
                    Log.d(TAG, "Fall Detected broadcast!")
                    tvPrediction.text = "FALL DETECTED!"
                    tvPrediction.setTextColor(Color.RED)

                    // Show toast message
                    Toast.makeText(context, "⚠️ FALL DETECTED! ⚠️", Toast.LENGTH_LONG).show()

                    // Vibrate phone
                    val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (vibrator?.hasVibrator() == true) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(1000)
                        }
                    }
                }

                ACTION_SERVICE_STATUS -> {
                    val isServiceRunning = intent.getBooleanExtra("isRunning", false)
                    val isWatchReady = intent.getBooleanExtra("isWatchReady", false)
                    val watchQueueSize = intent.getIntExtra("watchQueueSize", 0)
                    val phoneQueueSize = intent.getIntExtra("phoneQueueSize", 0)
                    val expectingWatchData = intent.getBooleanExtra("expectingWatchData", false)

                    // Update UI with service status
                    if (isServiceRunning) {
                        tvActivated.text = "Activated!"
                        tvActivated.setTextColor(Color.GREEN)
                    } else {
                        tvActivated.text = "Not Activated"
                        tvActivated.setTextColor(Color.WHITE)
                    }

                    // Update watch status
                    updateWatchStatusUI(isWatchReady, watchQueueSize, expectingData = expectingWatchData)
                }

                "com.example.myfalldetectionapplitertpro.SERVICE_UPDATE" -> {
                    val message = intent.getStringExtra("message") ?: return
                    Log.d(TAG, "Service update: $message")

                    // Add message to history
                    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    predictionsHistory.add("[$timestamp] $message")
                    if (predictionsHistory.size > 8) predictionsHistory.removeFirst()
                    tvHistory.text = predictionsHistory.joinToString("\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }

                    // Special handling for warnings
                    if (message.startsWith("WARNING:") || message.startsWith("ERROR:")) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Receiver for watch status updates
    private val watchStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PhoneWearListenerService.ACTION_WATCH_STATUS_UPDATE) {
                val status = intent.getStringExtra(PhoneWearListenerService.EXTRA_WATCH_STATUS)
                Log.d(TAG, "Received watch status update: $status")

                // Track connection state
                watchConnectionState = status ?: "Unknown"

                // Update UI to show watch status
                when {
                    status?.startsWith("WATCH_STARTED") == true -> {
                        tvWatchStatus.text = "Watch: Connected & Active"
                        tvWatchStatus.setTextColor(Color.GREEN)
                    }
                    status == "WATCH_STOPPED" -> {
                        tvWatchStatus.text = "Watch: Connected (Inactive)"
                        tvWatchStatus.setTextColor(Color.YELLOW)
                    }
                    status?.startsWith("CONFIG_UPDATED") == true -> {
                        tvWatchStatus.text = "Watch: Configuration Updated"
                        tvWatchStatus.setTextColor(Color.GREEN)
                    }
                    status?.startsWith("ERROR:") == true -> {
                        val errorMsg = status.substringAfter("ERROR:")
                        tvWatchStatus.text = "Watch Error: $errorMsg"
                        tvWatchStatus.setTextColor(Color.RED)

                        // Show error toast
                        Toast.makeText(context, "Watch Error: $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Intent filters for receivers
    private val serviceFilter = IntentFilter().apply {
        addAction(ACTION_INFERENCE_RESULT)
        addAction(ACTION_FALL_DETECTED)
        addAction(ACTION_SERVICE_STATUS)
        addAction("com.example.myfalldetectionapplitertpro.SERVICE_UPDATE")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_inference, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Initialize UI components
        btnStart = view.findViewById(R.id.btnStart)
        tvActivated = view.findViewById(R.id.tvActivated)
        tvStopwatch = view.findViewById(R.id.tvStopwatch)
        tvPrediction = view.findViewById(R.id.tvPrediction)
        tvProbability = view.findViewById(R.id.tvProbability)
        tvSmvInfo = view.findViewById(R.id.tvSmvInfo)
        tvWatchStatus = view.findViewById(R.id.tvWatchStatus)
        tvHistory = view.findViewById(R.id.tvHistory)
        scrollView = view.findViewById(R.id.scrollViewInference)

        // Set button click listener
        btnStart.setOnClickListener { toggleCapture() }

        // Initialize watch status
        tvWatchStatus.text = "Watch: Checking status..."
        tvWatchStatus.setTextColor(Color.YELLOW)

        // Register watch status receiver
        val watchStatusFilter = IntentFilter(PhoneWearListenerService.ACTION_WATCH_STATUS_UPDATE)
        registerReceiverCompat(watchStatusReceiver, watchStatusFilter)
    }

    override fun onResume() {
        super.onResume()

        // Register service status receiver
        registerReceiverCompat(inferenceReceiver, serviceFilter)

        // Check current service status
        val intent = Intent(requireContext(), BackgroundFallService::class.java).apply {
            action = "CHECK_STATUS"
        }
        requireContext().startService(intent)

        // Check watch connectivity
        checkWatchConnectivity()

        // Check device mode and auto-start watch if needed
        autoConfigureWatchIfNeeded()
    }

    override fun onPause() {
        super.onPause()

        // Unregister service receiver
        try {
            requireContext().unregisterReceiver(inferenceReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering inferenceReceiver: ${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Unregister watch status receiver
        try {
            requireContext().unregisterReceiver(watchStatusReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering watchStatusReceiver: ${e.message}")
        }
    }

    /**
     * Registers a broadcast receiver with compatibility for all Android versions.
     * This method handles the different API signatures to avoid type mismatches.
     *
     * FIXED: Added proper handling for Android 13+ (API 33+) with explicit RECEIVER_NOT_EXPORTED flag.
     */
    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        try {
            // Use the explicit API level check to ensure compatibility
            if (Build.VERSION.SDK_INT >= 33) { // Android 13 (API 33) or higher - requires export flag
                Log.d(TAG, "Registering receiver with RECEIVER_NOT_EXPORTED flag (Android 13+)")
                requireContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // For Android 12 and below - no export flag needed
                Log.d(TAG, "Registering receiver without export flag (pre-Android 13)")
                requireContext().registerReceiver(receiver, filter)
            }
            Log.d(TAG, "Successfully registered receiver for ${filter.actionsIterator().asSequence().joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receiver: ${e.message}", e)
            // Show error toast so the user knows something went wrong
            Toast.makeText(context, "App initialization error. Please restart.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Toggles capture state (start/stop)
     */
    private fun toggleCapture() {
        if (!isRunning) {
            startCapture()
        } else {
            stopCapture()
        }
    }

    /**
     * Starts sensor data capture
     */
    private fun startCapture() {
        isRunning = true
        startMs = System.currentTimeMillis()

        // Update UI
        tvActivated.text = "Activated!"
        tvActivated.setTextColor(Color.GREEN)
        tvStopwatch.text = "Stopwatch: 0 ms"
        tvPrediction.text = "Waiting..."
        tvPrediction.setTextColor(Color.WHITE)
        tvProbability.text = "Probability: -"
        tvSmvInfo.text = "SMV stats: (Min=0, Max=0, Avg=0)"
        predictionsHistory.clear()
        tvHistory.text = ""

        btnStart.text = "STOP DETECTION"
        uiHandler.post(updateStopwatchRunnable)

        // Start phone's sensor service
        val intent = Intent(requireContext(), BackgroundFallService::class.java).apply {
            action = "START_CAPTURE"
        }
        ContextCompat.startForegroundService(requireContext(), intent)

        // If config says watch or both => send watch start with config
        val deviceMode = PrefsHelper.getDeviceMode(requireContext())
        val dataType = PrefsHelper.getSharedPrefs(requireContext()).getString("pref_data_type", "Raw") ?: "Raw"
        val timeEmbed = PrefsHelper.isTimeEmbeddingEnabled(requireContext())

        if (deviceMode.equals("Watch", ignoreCase=true) || deviceMode.equals("Both", ignoreCase=true)) {
            // Send config with command
            val configStr = "CONFIG:$deviceMode:$dataType:$timeEmbed"
            Log.d(TAG, "Starting watch with config: $configStr")

            // Update watch status in UI
            tvWatchStatus.text = "Watch: Sending start command..."
            tvWatchStatus.setTextColor(Color.YELLOW)

            sendMessageToWatch(PATH_START_ON_WATCH, configStr.toByteArray())
        }
    }

    /**
     * Stops sensor data capture
     */
    private fun stopCapture() {
        isRunning = false
        uiHandler.removeCallbacks(updateStopwatchRunnable)

        // Update UI
        val elapsed = System.currentTimeMillis() - startMs
        tvActivated.text = "Not Activated"
        tvActivated.setTextColor(Color.WHITE)
        tvStopwatch.text = "Stopwatch: ${elapsed} ms (stopped)"
        btnStart.text = "START DETECTION"

        // Stop phone's sensor service
        val intent = Intent(requireContext(), BackgroundFallService::class.java).apply {
            action = "STOP_CAPTURE"
        }
        requireContext().startService(intent)

        // If watch or both => send watch stop
        val deviceMode = PrefsHelper.getDeviceMode(requireContext())
        if (deviceMode.equals("Watch", ignoreCase=true) || deviceMode.equals("Both", ignoreCase=true)) {
            tvWatchStatus.text = "Watch: Sending stop command..."
            tvWatchStatus.setTextColor(Color.YELLOW)

            sendMessageToWatch(PATH_STOP_ON_WATCH, ByteArray(0))
            Log.d(TAG, "Sent stop command to watch")
        }
    }

    /**
     * Checks for connected watches and their status
     */
    private fun checkWatchConnectivity() {
        // First check if any watches are connected
        Wearable.getNodeClient(requireContext()).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.d(TAG, "No watches connected")
                    tvWatchStatus.text = "Watch: Not Connected"
                    tvWatchStatus.setTextColor(Color.RED)
                    return@addOnSuccessListener
                }

                Log.d(TAG, "Found ${nodes.size} connected watches: ${nodes.joinToString { it.displayName }}")

                // If we've never received a status update, request one
                if (watchConnectionState == "Unknown") {
                    tvWatchStatus.text = "Watch: Connected (Requesting Status...)"
                    tvWatchStatus.setTextColor(Color.YELLOW)

                    // For each connected node, send a status request
                    for (node in nodes) {
                        Wearable.getMessageClient(requireContext())
                            .sendMessage(node.id, PATH_STATUS_REQUEST, ByteArray(0))
                            .addOnSuccessListener {
                                Log.d(TAG, "Sent status request to ${node.displayName}")
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to send status request: ${e.message}")
                            }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking connected watches: ${e.message}")
                tvWatchStatus.text = "Watch: Connection Error"
                tvWatchStatus.setTextColor(Color.RED)
            }
    }

    /**
     * Automatically configures the watch if it should be active based on current settings
     */
    private fun autoConfigureWatchIfNeeded() {
        val deviceMode = PrefsHelper.getDeviceMode(requireContext())

        // Only auto-configure if device mode is Watch or Both
        if (deviceMode.equals("Watch", ignoreCase=true) || deviceMode.equals("Both", ignoreCase=true)) {
            Log.d(TAG, "Device mode is '$deviceMode', sending config to watch")

            // Prepare config string
            val dataType = PrefsHelper.getSharedPrefs(requireContext())
                .getString("pref_data_type", "Raw") ?: "Raw"
            val timeEmbed = PrefsHelper.isTimeEmbeddingEnabled(requireContext())
            val configStr = "CONFIG:$deviceMode:$dataType:$timeEmbed"

            // Send config update
            tvWatchStatus.text = "Watch: Sending configuration..."
            tvWatchStatus.setTextColor(Color.YELLOW)

            sendMessageToWatch(PATH_CONFIG_UPDATE, configStr.toByteArray())

            // If in Watch mode, check if service is running and auto-start if needed
            if (deviceMode.equals("Watch", ignoreCase=true) && isRunning) {
                // Maybe we need to start the watch if running in watch mode
                Log.d(TAG, "Running in Watch mode, sending start command")
                sendMessageToWatch(PATH_START_ON_WATCH, configStr.toByteArray())
            }
        }
    }

    /**
     * Updates the watch status UI
     */
    private fun updateWatchStatusUI(isWatchReady: Boolean, watchQueueSize: Int, expectingData: Boolean) {
        if (isWatchReady) {
            tvWatchStatus.text = "Watch: Connected & Active (${watchQueueSize} samples)"
            tvWatchStatus.setTextColor(Color.GREEN)
        } else if (expectingData) {
            tvWatchStatus.text = "Watch: Expected but not sending data"
            tvWatchStatus.setTextColor(Color.RED)
        } else {
            tvWatchStatus.text = "Watch: Not Active"
            tvWatchStatus.setTextColor(Color.YELLOW)
        }
    }

    /**
     * Sends a message to the watch
     */
    private fun sendMessageToWatch(path: String, payload: ByteArray) {
        // Improved watch messaging with error handling and logging
        Wearable.getNodeClient(requireContext()).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.e(TAG, "No connected watch found!")
                    tvWatchStatus.text = "Watch: Not Connected"
                    tvWatchStatus.setTextColor(Color.RED)
                    return@addOnSuccessListener
                }

                for (node in nodes) {
                    Log.d(TAG, "Sending $path to watch node ${node.id} (${node.displayName})")

                    try {
                        // First attempt with Tasks.await for more responsive feedback
                        try {
                            Tasks.await(
                                Wearable.getMessageClient(requireContext())
                                    .sendMessage(node.id, path, payload),
                                2, TimeUnit.SECONDS
                            )
                            Log.d(TAG, "Successfully sent $path to watch synchronously")

                            // Update UI status
                            when (path) {
                                PATH_START_ON_WATCH -> {
                                    tvWatchStatus.text = "Watch: Start Command Sent"
                                    tvWatchStatus.setTextColor(Color.GREEN)
                                }
                                PATH_STOP_ON_WATCH -> {
                                    tvWatchStatus.text = "Watch: Stop Command Sent"
                                    tvWatchStatus.setTextColor(Color.YELLOW)
                                }
                                PATH_CONFIG_UPDATE -> {
                                    tvWatchStatus.text = "Watch: Config Sent"
                                    tvWatchStatus.setTextColor(Color.GREEN)
                                }
                            }
                        } catch (e: Exception) {
                            // Fall back to asynchronous approach
                            Log.d(TAG, "Falling back to async send")

                            Wearable.getMessageClient(requireContext())
                                .sendMessage(node.id, path, payload)
                                .addOnSuccessListener {
                                    Log.d(TAG, "Successfully sent $path to watch ${node.displayName}")

                                    // Update UI status
                                    when (path) {
                                        PATH_START_ON_WATCH -> {
                                            tvWatchStatus.text = "Watch: Start Command Sent"
                                            tvWatchStatus.setTextColor(Color.GREEN)
                                        }
                                        PATH_STOP_ON_WATCH -> {
                                            tvWatchStatus.text = "Watch: Stop Command Sent"
                                            tvWatchStatus.setTextColor(Color.YELLOW)
                                        }
                                        PATH_CONFIG_UPDATE -> {
                                            tvWatchStatus.text = "Watch: Config Sent"
                                            tvWatchStatus.setTextColor(Color.GREEN)
                                        }
                                    }
                                }
                                .addOnFailureListener { e2 ->
                                    Log.e(TAG, "Failed to send $path to watch ${node.displayName}: ${e2.message}")
                                    tvWatchStatus.text = "Watch: Communication Failed"
                                    tvWatchStatus.setTextColor(Color.RED)
                                }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending message: ${e.message}")
                        tvWatchStatus.text = "Watch: Communication Error"
                        tvWatchStatus.setTextColor(Color.RED)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get connected nodes: ${e.message}")
                tvWatchStatus.text = "Watch: Connection Error"
                tvWatchStatus.setTextColor(Color.RED)
            }
    }
}