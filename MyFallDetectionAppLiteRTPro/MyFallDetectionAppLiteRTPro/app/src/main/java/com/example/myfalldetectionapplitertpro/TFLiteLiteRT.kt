package com.example.myfalldetectionapplitertpro

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import org.tensorflow.lite.support.common.FileUtil
import java.nio.MappedByteBuffer
import kotlin.math.exp

/**
 * A helper class that loads a TensorFlow Lite model from assets and creates an
 * InterpreterApi using Google Play Services’ TFLite runtime.
 *
 * IMPORTANT: TfLite.initialize(context) must be called off the main thread.
 */
class TFLiteLiteRT(
    context: Context,
    modelFileName: String
) {
    private var interpreter: InterpreterApi? = null
    private var modelLoaded = false

    init {
        try {
            // Initialize the TF Lite runtime off main thread in your code.
            // Here it's called in the constructor, so do it in a background thread externally.
            Tasks.await(TfLite.initialize(context))
            Log.d("TFLiteLiteRT", "TfLite.initialize succeeded")

            // Load the model from assets as a memory-mapped file.
            val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelFileName)
            // Create interpreter options forcing the system runtime.
            val opts = Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
            interpreter = InterpreterApi.create(modelBuffer, opts)
            Log.d("TFLiteLiteRT", "Interpreter created successfully")
            modelLoaded = true

        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "Initialization failed", e)
        }
    }

    /**
     * Runs inference on the given 1D float array of length 128 (the magnitude).
     * The model is assumed to expect shape [1, 128] => output [1, 2].
     * Returns softmax probability for class 1.
     */
    fun runInference(data: FloatArray): Float {
        // If the model wasn't loaded or interpreter is null, return something that indicates no inference.
        val currentInterp = interpreter ?: return -9999f
        if (!modelLoaded) return -9999f

        return try {
            // Input shape: [1, N]
            val inputData = arrayOf(data)
            // Output shape: [1, 2]
            val outputArray = Array(1) { FloatArray(2) }
            val outputs = mutableMapOf<Int, Any>(0 to outputArray)

            currentInterp.runForMultipleInputsOutputs(arrayOf<Any>(inputData), outputs)

            // Softmax
            val out = outputArray[0]
            val maxVal = out.maxOrNull() ?: 0f
            val exp0 = exp((out[0] - maxVal).toDouble()).toFloat()
            val exp1 = exp((out[1] - maxVal).toDouble()).toFloat()
            val sum = exp0 + exp1
            exp1 / sum

        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "Error during inference", e)
            -9999f
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        modelLoaded = false
    }
}
