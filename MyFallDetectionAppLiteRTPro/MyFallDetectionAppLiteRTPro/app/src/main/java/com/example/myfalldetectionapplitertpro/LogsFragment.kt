package com.example.myfalldetectionapplitertpro

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File

class LogsFragment : Fragment() {

    companion object {
        private const val TAG = "LogsFragment"
    }

    private lateinit var etLogs: EditText
    private lateinit var btnComputeStats: Button
    private lateinit var btnDeleteLogs: Button
    private lateinit var tvStats: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        etLogs = view.findViewById(R.id.tvLogs)
        btnComputeStats = view.findViewById(R.id.btnComputeStats)
        btnDeleteLogs = view.findViewById(R.id.btnDeleteLogs)
        tvStats = view.findViewById(R.id.tvStats)

        loadLogs()

        btnComputeStats.setOnClickListener {
            // For instance, read sensor_data.csv or run_log.txt, compute min/avg SMV, etc.
            computeStats()
        }
        btnDeleteLogs.setOnClickListener {
            deleteLogs()
            etLogs.setText("")
            tvStats.text = "Stats cleared."
        }
    }

    private fun loadLogs() {
        try {
            // This loads from run_log.txt
            val file = File(requireContext().getExternalFilesDir(null), "run_log.txt")
            if (file.exists()) {
                etLogs.setText(file.readText())
            } else {
                etLogs.setText("No run_log.txt found.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading logs", e)
            etLogs.setText("Error loading logs: ${e.message}")
        }
    }

    private fun deleteLogs() {
        try {
            val file = File(requireContext().getExternalFilesDir(null), "run_log.txt")
            if (file.exists()) file.delete()
            // Also consider deleting sensor_data.csv if desired
            val csvFile = File(requireContext().getExternalFilesDir(null), "sensor_data.csv")
            if (csvFile.exists()) csvFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting logs", e)
        }
    }

    private fun computeStats() {
        // Example: read sensor_data.csv to compute SMV min, max, avg
        val csvFile = File(requireContext().getExternalFilesDir(null), "sensor_data.csv")
        if (!csvFile.exists()) {
            tvStats.text = "No sensor_data.csv available."
            return
        }
        var lineCount = 0
        var sumSMV = 0.0
        var minSMV = Double.MAX_VALUE
        var maxSMV = Double.MIN_VALUE

        csvFile.forEachLine { line ->
            if (lineCount == 0) {
                // Possibly skip header if you have one, or if there's no header just parse
            }
            lineCount++
            val parts = line.split(",")
            // Assuming: timeStr,nanoTime,x,y,z,isWatch,sampleRateStr
            if (parts.size >= 7) {
                val x = parts[2].toFloatOrNull() ?: 0f
                val y = parts[3].toFloatOrNull() ?: 0f
                val z = parts[4].toFloatOrNull() ?: 0f
                val smv = Math.sqrt((x*x + y*y + z*z).toDouble())
                if (smv < minSMV) minSMV = smv
                if (smv > maxSMV) maxSMV = smv
                sumSMV += smv
            }
        }
        val count = (lineCount - 1).coerceAtLeast(1)
        val avgSMV = sumSMV / count
        tvStats.text = "Processed $count lines.\nMin SMV=%.2f, Max SMV=%.2f, Avg SMV=%.2f".format(minSMV, maxSMV, avgSMV)
    }
}
