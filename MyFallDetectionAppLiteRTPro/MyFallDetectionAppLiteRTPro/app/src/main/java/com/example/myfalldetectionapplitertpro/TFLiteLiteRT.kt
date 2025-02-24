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
    private val context: Context,
    private val modelFileName: String
) {
    private var interpreter: InterpreterApi? = null

    init {
        // Ensure that this constructor is not called on the main thread.
        try {
            // Initialize the TF Lite runtime.
            Tasks.await(TfLite.initialize(context))
            Log.d("TFLiteLiteRT", "TfLite.initialize succeeded")
        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "TfLite.initialize failed", e)
            // If initialization fails, interpreter remains null.
            // Cannot 'return' from an init block, so we simply continue.
        }

        try {
            // Load the model from assets as a memory-mapped file.
            val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelFileName)
            // Create interpreter options forcing the system runtime.
            val opts = Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
            interpreter = InterpreterApi.create(modelBuffer, opts)
            Log.d("TFLiteLiteRT", "Interpreter created successfully")
        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "Failed to load model or create interpreter: ${e.message}")
        }
    }

    /**
     * Runs inference on the given 1D float array.
     *
     * The model is assumed to expect an input tensor of shape [1, N] and produce an
     * output tensor of shape [1, 2]. We compute the softmax probability for class 1.
     */
    fun runInference(data: FloatArray): Float {
        // If the interpreter wasn’t created, return -9999f.
        val currentInterp = interpreter ?: return -9999f

        // Wrap input in an array to match shape [1, N]
        val inputData = arrayOf(data)
        // Create output container matching shape [1, 2]
        val outputArray = Array(1) { FloatArray(2) }
        val outputs: MutableMap<Int, Any> = mutableMapOf(0 to outputArray)

        try {
            currentInterp.runForMultipleInputsOutputs(arrayOf(inputData), outputs)
        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "Error during inference", e)
            throw e
        }

        // Compute softmax over the two output values.
        val out = outputArray[0]
        val exp0 = exp(out[0].toDouble()).toFloat()
        val exp1 = exp(out[1].toDouble()).toFloat()
        val sum = exp0 + exp1
        return exp1 / sum
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
