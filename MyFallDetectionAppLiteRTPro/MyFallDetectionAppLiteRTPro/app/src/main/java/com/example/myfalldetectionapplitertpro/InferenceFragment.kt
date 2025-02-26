package com.example.myfalldetectionapplitertpro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class InferenceFragment : Fragment() {

    private lateinit var btnStart: Button
    private lateinit var tvActivated: TextView
    private lateinit var tvStopwatch: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvProbability: TextView

    private var isRunning = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_inference, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnStart = view.findViewById(R.id.btnStart)
        tvActivated = view.findViewById(R.id.tvActivated)
        tvStopwatch = view.findViewById(R.id.tvStopwatch)
        tvPrediction = view.findViewById(R.id.tvPrediction)
        tvProbability = view.findViewById(R.id.tvProbability)

        btnStart.setOnClickListener {
            toggleCapture()
        }
    }

    private fun toggleCapture() {
        val serviceIntent = Intent(requireContext(), BackgroundFallService::class.java)
        if (isRunning) {
            serviceIntent.action = "STOP_CAPTURE"
            requireContext().startService(serviceIntent)
            isRunning = false
            btnStart.text = getString(R.string.btn_start)
            tvActivated.text = getString(R.string.activated_status_not)
        } else {
            serviceIntent.action = "START_CAPTURE"
            // For Android 8.0+ start foreground service
            ContextCompat.startForegroundService(requireContext(), serviceIntent)
            isRunning = true
            btnStart.text = getString(R.string.btn_stop)
            tvActivated.text = "Activated!"  // You might want to use a string resource here as well.
        }
    }
}
