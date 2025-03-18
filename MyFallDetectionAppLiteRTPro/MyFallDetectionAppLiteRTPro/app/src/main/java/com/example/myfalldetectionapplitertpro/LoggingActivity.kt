package com.example.myfalldetectionapplitertpro

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class LoggingActivity : AppCompatActivity() {

    private val TAG = "LoggingActivity"
    private lateinit var etLogs: EditText
    private lateinit var btnComputeStats: Button
    private lateinit var btnDeleteLogs: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logging)

        etLogs = findViewById(R.id.etLogs)
        btnComputeStats = findViewById(R.id.btnComputeStats)
        btnDeleteLogs = findViewById(R.id.btnDeleteLogs)

        loadLogs()

        btnComputeStats.setOnClickListener {
            etLogs.append("\n[Compute Stats Stub]")
        }
        btnDeleteLogs.setOnClickListener {
            deleteLogs()
            etLogs.setText("")
        }
    }

    private fun loadLogs() {
        try {
            val file = File(getExternalFilesDir(null), "run_log.txt")
            etLogs.setText(if (file.exists()) file.readText() else "No logs found.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading logs", e)
            etLogs.setText("Error loading logs.")
        }
    }

    private fun deleteLogs() {
        try {
            val file = File(getExternalFilesDir(null), "run_log.txt")
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting logs", e)
        }
    }
}
