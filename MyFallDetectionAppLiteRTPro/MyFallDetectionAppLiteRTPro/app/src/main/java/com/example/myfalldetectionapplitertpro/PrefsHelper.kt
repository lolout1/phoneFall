package com.example.myfalldetectionapplitertpro

import android.content.Context
import android.content.SharedPreferences

object PrefsHelper {
    private const val PREFS_NAME = "fall_detection_prefs"

    const val KEY_MODEL_FILE = "pref_model_file"
    const val KEY_DEVICE_MODE = "pref_device_mode"
    const val KEY_SENSOR_TYPE = "pref_sensor_type"
    const val KEY_TIME_EMBED = "pref_time_embed"
    const val KEY_ACTIVE_UI = "pref_active_ui"

    const val DEFAULT_MODEL_FILE = "fall_time2vec_transformer.tflite"
    const val DEFAULT_DEVICE_MODE = "Phone"
    const val DEFAULT_SENSOR_TYPE = "Accelerometer"
    const val DEFAULT_TIME_EMBED = true
    const val DEFAULT_ACTIVE_UI = "Phone" // Default UI control location

    fun getSharedPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getModelFile(context: Context): String =
        getSharedPrefs(context).getString(KEY_MODEL_FILE, DEFAULT_MODEL_FILE) ?: DEFAULT_MODEL_FILE

    fun getDeviceMode(context: Context): String =
        getSharedPrefs(context).getString(KEY_DEVICE_MODE, DEFAULT_DEVICE_MODE) ?: DEFAULT_DEVICE_MODE

    fun getSensorType(context: Context): String =
        getSharedPrefs(context).getString(KEY_SENSOR_TYPE, DEFAULT_SENSOR_TYPE) ?: DEFAULT_SENSOR_TYPE

    fun isTimeEmbeddingEnabled(context: Context): Boolean =
        getSharedPrefs(context).getBoolean(KEY_TIME_EMBED, DEFAULT_TIME_EMBED)

    // Get the current active UI location (where start/stop controls should be shown)
    fun getActiveUI(context: Context): String =
        getSharedPrefs(context).getString(KEY_ACTIVE_UI, DEFAULT_ACTIVE_UI) ?: DEFAULT_ACTIVE_UI

    // Set the active UI location (automatically called when device mode changes)
    fun setActiveUI(context: Context, location: String) {
        getSharedPrefs(context).edit()
            .putString(KEY_ACTIVE_UI, location)
            .apply()
    }

    fun saveConfig(context: Context, modelFile: String, deviceMode: String, sensorType: String, timeEmbed: Boolean) {
        getSharedPrefs(context).edit()
            .putString(KEY_MODEL_FILE, modelFile)
            .putString(KEY_DEVICE_MODE, deviceMode)
            .putString(KEY_SENSOR_TYPE, sensorType)
            .putBoolean(KEY_TIME_EMBED, timeEmbed)
            .apply()

        // When device mode changes, automatically set the active UI location
        when (deviceMode) {
            "Phone" -> setActiveUI(context, "Phone")
            "Watch" -> setActiveUI(context, "Watch")
            "Both" -> setActiveUI(context, "Both") // In "Both" mode, allow control from either
        }
    }

    // Check if the controls should be shown on phone
    fun shouldShowPhoneControls(context: Context): Boolean {
        val activeUI = getActiveUI(context)
        return activeUI == "Phone" || activeUI == "Both"
    }

    // Check if the controls should be shown on watch
    fun shouldShowWatchControls(context: Context): Boolean {
        val activeUI = getActiveUI(context)
        return activeUI == "Watch" || activeUI == "Both"
    }
}