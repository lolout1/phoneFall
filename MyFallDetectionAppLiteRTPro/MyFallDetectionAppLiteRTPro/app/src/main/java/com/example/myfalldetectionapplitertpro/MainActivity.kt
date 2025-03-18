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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Main phone activity that:
 * 1) Displays TabLayout+ViewPager with fragments (Inference, Config, Logs).
 * 2) Has a "Start" button to start/stop phone sensors & watch sensors (if configured).
 * 3) Receives local broadcasts for ACTION_FALL_DETECTED from the phone's BackgroundFallService.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // Broadcast from BackgroundFallService
        const val ACTION_FALL_DETECTED = "com.example.myfalldetectionapplitertpro.FALL_DETECTED"

        // Watch message paths
        private const val PATH_START_ON_WATCH = "/start_on_watch"
        private const val PATH_STOP_ON_WATCH  = "/stop_on_watch"
    }

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var pagerAdapter: MainPagerAdapter

    // Optional stopwatch for phone
    private var isRunning = false
    private var startMs: Long = 0L
    private val uiHandler = Handler(Looper.getMainLooper())

    private val updateStopwatchRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val elapsed = System.currentTimeMillis() - startMs
                // If we put btnStart in activity_home_viewpager.xml, we can find tvStopwatch there
                val tvStopwatch = findViewById<TextView>(R.id.tvStopwatch)
                tvStopwatch?.text = "Stopwatch: ${elapsed} ms"
                uiHandler.postDelayed(this, 100)
            }
        }
    }

    // Receive local broadcast: FALL_DETECTED
    private val fallDetectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FALL_DETECTED) {
                Log.d(TAG, "Fall detected from phone's BackgroundFallService!")
                Toast.makeText(
                    this@MainActivity,
                    "Fall Detected! Check watch or logs.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // IntentFilter for local broadcast
    private val localFilter = IntentFilter().apply {
        addAction(ACTION_FALL_DETECTED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_viewpager)

        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)

        setupViewPagerWithTabs()

        // If you put a Start button in activity_home_viewpager.xml:
        val btnStart = findViewById<Button>(R.id.btnStart)
        btnStart?.setOnClickListener {
            if (!isRunning) phoneStartCapture() else phoneStopCapture()
        }
    }

    private fun setupViewPagerWithTabs() {
        val fragments = listOf(
            InferenceFragment(),
            ConfigFragment(),
            LogsFragment()
        )
        val titles = listOf("Inference", "Config", "Logs")

        pagerAdapter = MainPagerAdapter(this, fragments)
        viewPager.adapter = pagerAdapter
        viewPager.offscreenPageLimit = fragments.size

        // Optionally start on inference tab (index=0)
        viewPager.setCurrentItem(0, false)

        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = titles[pos]
        }.attach()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fallDetectedReceiver, localFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(fallDetectedReceiver, localFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(fallDetectedReceiver)
    }

    private fun phoneStartCapture() {
        isRunning = true
        startMs   = System.currentTimeMillis()

        // If you have a tvStopwatch in the layout:
        findViewById<TextView>(R.id.tvStopwatch)?.text = "Stopwatch: 0 ms"
        uiHandler.post(updateStopwatchRunnable)

        // Start the phone's background service
        val intent = Intent(this, BackgroundFallService::class.java).apply {
            action = "START_CAPTURE"
        }
        ContextCompat.startForegroundService(this, intent)

        // If config says "Watch" or "Both," send message to watch
        val deviceMode = PrefsHelper.getDeviceMode(this)
        if (deviceMode.equals("Watch", ignoreCase=true) || deviceMode.equals("Both", ignoreCase=true)) {
            sendMessageToWatch(PATH_START_ON_WATCH, ByteArray(0))
        }
    }

    private fun phoneStopCapture() {
        isRunning = false
        uiHandler.removeCallbacks(updateStopwatchRunnable)

        val elapsed = System.currentTimeMillis() - startMs
        findViewById<TextView>(R.id.tvStopwatch)?.text = "Stopwatch: ${elapsed} ms (stopped)"

        // Stop phone's service
        val intent = Intent(this, BackgroundFallService::class.java).apply {
            action = "STOP_CAPTURE"
        }
        startService(intent)

        // If watch or both, send stop command
        val deviceMode = PrefsHelper.getDeviceMode(this)
        if (deviceMode.equals("Watch", ignoreCase=true) || deviceMode.equals("Both", ignoreCase=true)) {
            sendMessageToWatch(PATH_STOP_ON_WATCH, ByteArray(0))
        }
    }

    /**
     * Example Wearable messaging, adapt to your code.
     */
    private fun sendMessageToWatch(path: String, payload: ByteArray) {
        // e.g.
        /*
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                for (node in nodes) {
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, path, payload)
                        .addOnSuccessListener {
                            Log.d(TAG, "Sent $path to watch node=${node.id}")
                        }
                        .addOnFailureListener {
                            Log.e(TAG, "Failed to send $path to watch node=${node.id}", it)
                        }
                }
            }
         */
    }
}