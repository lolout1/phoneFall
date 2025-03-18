package com.example.myfalldetectionapplitertpro

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.gms.wearable.Wearable

class ConfigFragment : Fragment() {

    companion object {
        private const val TAG = "ConfigFragment"
    }

    private lateinit var spinnerModelFile: Spinner
    private lateinit var spinnerDeviceMode: Spinner
    private lateinit var spinnerSensorType: Spinner
    private lateinit var spinnerTimeEmbedding: Spinner
    private lateinit var spinnerDataType: Spinner
    private lateinit var btnSaveConfig: Button

    // Example spinner data
    private val modelFiles = listOf("fall_time2vec_transformer.tflite", "alternative_model.tflite")
    private val deviceModes = listOf("Phone", "Watch", "Both")
    private val sensorTypes = listOf("Accelerometer", "Gyroscope")
    private val timeEmbeddingOptions = listOf("Enabled", "Disabled")
    private val dataTypeOptions = listOf("Raw", "Linear")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate your layout with 5 spinners + Save button
        return inflater.inflate(R.layout.fragment_configuration, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Find spinners & button by ID
        spinnerModelFile = view.findViewById(R.id.spinnerModelFile)
        spinnerDeviceMode = view.findViewById(R.id.spinnerDeviceMode)
        spinnerSensorType = view.findViewById(R.id.spinnerSensorType)
        spinnerTimeEmbedding = view.findViewById(R.id.spinnerTimeEmbedding)
        spinnerDataType = view.findViewById(R.id.spinnerDataType)
        btnSaveConfig = view.findViewById(R.id.btnSaveConfig)

        // Setup adapters
        setupSpinner(spinnerModelFile, modelFiles)
        setupSpinner(spinnerDeviceMode, deviceModes)
        setupSpinner(spinnerSensorType, sensorTypes)
        setupSpinner(spinnerTimeEmbedding, timeEmbeddingOptions)
        setupSpinner(spinnerDataType, dataTypeOptions)

        // Load existing prefs
        loadPrefsIntoUI()

        // Save config
        btnSaveConfig.setOnClickListener {
            saveConfig()
        }
    }

    private fun setupSpinner(spinner: Spinner, items: List<String>) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun loadPrefsIntoUI() {
        val ctx = requireContext()
        val currentModel = PrefsHelper.getModelFile(ctx)
        val currentDevice = PrefsHelper.getDeviceMode(ctx)
        val currentSensor = PrefsHelper.getSensorType(ctx)
        val currentTimeEmbed = if (PrefsHelper.isTimeEmbeddingEnabled(ctx)) "Enabled" else "Disabled"
        val currentDataType = PrefsHelper.getSharedPrefs(ctx).getString("pref_data_type", "Raw")

        setSpinnerSelection(spinnerModelFile, modelFiles, currentModel)
        setSpinnerSelection(spinnerDeviceMode, deviceModes, currentDevice)
        setSpinnerSelection(spinnerSensorType, sensorTypes, currentSensor)
        setSpinnerSelection(spinnerTimeEmbedding, timeEmbeddingOptions, currentTimeEmbed)
        setSpinnerSelection(spinnerDataType, dataTypeOptions, currentDataType ?: "Raw")
    }

    private fun setSpinnerSelection(spinner: Spinner, items: List<String>, value: String) {
        val idx = items.indexOf(value)
        spinner.setSelection(if (idx >= 0) idx else 0)
    }

    private fun saveConfig() {
        val ctx = requireContext()
        val selectedModel = spinnerModelFile.selectedItem.toString()
        val selectedDevice = spinnerDeviceMode.selectedItem.toString()
        val selectedSensor = spinnerSensorType.selectedItem.toString()
        val selectedTimeEmbed = (spinnerTimeEmbedding.selectedItem.toString() == "Enabled")
        val selectedDataType = spinnerDataType.selectedItem.toString()

        // Standard fields
        PrefsHelper.saveConfig(ctx, selectedModel, selectedDevice, selectedSensor, selectedTimeEmbed)

        // Extra "dataType" field
        PrefsHelper.getSharedPrefs(ctx).edit()
            .putString("pref_data_type", selectedDataType)
            .apply()

        Log.d(TAG, "Saved config: model=$selectedModel, device=$selectedDevice, sensor=$selectedSensor, timeEmbed=$selectedTimeEmbed, dataType=$selectedDataType")

        // If a watch is connected, send the updated config
        val configStr = "CONFIG:$selectedDevice:$selectedDataType:$selectedTimeEmbed"
        sendConfigToWatch(configStr)

        Toast.makeText(ctx, "Saved: $selectedDevice, $selectedSensor, $selectedDataType", Toast.LENGTH_SHORT).show()
    }

    private fun sendConfigToWatch(configStr: String) {
        Log.d(TAG, "Config sent to watch: $configStr")

        Wearable.getNodeClient(requireContext()).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.d(TAG, "No connected watches found")
                    return@addOnSuccessListener
                }

                val nodeNames = nodes.joinToString { it.displayName }
                Log.d(TAG, "Connected watches: $nodeNames")

                for (node in nodes) {
                    Wearable.getMessageClient(requireContext())
                        .sendMessage(node.id, "/config_update", configStr.toByteArray())
                        .addOnSuccessListener {
                            Log.d(TAG, "Config successfully sent to ${node.displayName}")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send config to watch: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get connected nodes: ${e.message}")
            }
    }
}