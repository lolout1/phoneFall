package com.example.myfalldetectionapplitertpro

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Example service that does an inference using TFLiteLiteRT.
 */
class InferenceService : Service() {

    private lateinit var modelHandler: TFLiteLiteRT

    override fun onCreate() {
        super.onCreate()
        // Initialize the model handler with context and model file name in assets
        // Ensure that "fall_time2vec_transformer.tflite" is actually present in app/src/main/assets/
        modelHandler = TFLiteLiteRT(applicationContext, "fall_time2vec_transformer.tflite")
        Log.d(TAG, "InferenceService onCreate: TFLiteLiteRT initialized.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Example: just run a quick inference if we get some sensor data
        val sensorData: FloatArray? = intent?.getFloatArrayExtra(KEY_SENSOR_DATA)
        sensorData?.let {
            // For demonstration, the runInference() returns a single float
            val result = modelHandler.runInference(it)
            Log.d(TAG, "InferenceService result=$result")

            // Possibly broadcast it
            val broadcastIntent = Intent(ACTION_INFERENCE_RESULT)
            broadcastIntent.putExtra(EXTRA_RESULT, result)
            sendBroadcast(broadcastIntent)
        }

        // Stop after one shot
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "InferenceService"
        const val KEY_SENSOR_DATA = "sensorData"
        const val ACTION_INFERENCE_RESULT = "com.example.myfalldetectionapplitertpro.INFERENCE_RESULT"
        const val EXTRA_RESULT = "InferenceResult"
    }
}
