package com.example.myfalldetectionapplitertpro

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.Serializable

class InferenceService : Service() {

    private lateinit var modelHandler: TFLiteLiteRT

    override fun onCreate() {
        super.onCreate()
        // Initialize the model handler on the service's background thread.
        modelHandler = TFLiteLiteRT(applicationContext, MODEL_FILE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Retrieve sensor data from the intent.
        val sensorData: FloatArray? = intent?.getFloatArrayExtra("sensorData")
        sensorData?.let {
            try {
                val result = modelHandler.runInference(it)
                val resultIntent = Intent(ACTION_FALL_DETECTED)
                resultIntent.putExtra(EXTRA_INFERENCE_RESULT, result as Serializable)
                sendBroadcast(resultIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_FALL_DETECTED = "com.example.myfalldetectionapplitertpro.ACTION_FALL_DETECTED"
        const val EXTRA_INFERENCE_RESULT = "InferenceResult"
        private const val MODEL_FILE = "fall_time2vec_transformer.tflite"
    }
}
