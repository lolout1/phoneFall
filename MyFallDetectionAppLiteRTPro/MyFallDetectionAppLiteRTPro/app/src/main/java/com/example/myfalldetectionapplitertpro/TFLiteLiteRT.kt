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
 * A robust TFLite helper class using the Google Play Services LiteRT runtime.
 *
 * Model input shapes from logs:
 *   - Input 0 => [1, 128] (time)
 *   - Input 1 => [1, 128, 3] (accelerometer)
 *   - Input 2 => [1, 128] (mask)
 *
 * The output is [1, 2] => logits for "no_fall" and "fall".
 * We apply a softmax to retrieve the fall probability.
 */
class TFLiteLiteRT(context: Context, modelFileName: String) {

    private var interpreter: InterpreterApi? = null
    private var modelLoaded = false

    init {
        try {
            // Initialize TF Lite (off the main thread).
            Tasks.await(TfLite.initialize(context))
            Log.d("TFLiteLiteRT", "TfLite.initialize succeeded")

            // Load the TFLite model from assets as a memory-mapped file.
            val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelFileName)
            // Force use of the system (Google Play Services) runtime.
            val opts = Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
            interpreter = InterpreterApi.create(modelBuffer, opts)
            Log.d("TFLiteLiteRT", "Interpreter created successfully")
            modelLoaded = true

            // Optional: log the shapes of the model's input tensors.
            logInputShapes()

        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "Initialization failed", e)
        }
    }

    /**
     * Logs the shapes of the model's input tensors for debugging.
     */
    fun logInputShapes() {
        if (!modelLoaded || interpreter == null) {
            Log.w("TFLiteLiteRT", "Interpreter not loaded; cannot log input shapes.")
            return
        }
        try {
            val inputCount = interpreter!!.getInputTensorCount()
            for (i in 0 until inputCount) {
                val tensor = interpreter!!.getInputTensor(i)
                val shape = tensor.shape()
                Log.d("TFLiteLiteRT", "Input $i shape: [${shape.joinToString()}]")
            }
        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "Error retrieving input shapes", e)
        }
    }

    /**
     * Runs inference on the given data arrays.
     *
     * Expecting the model signature:
     *   Input 0 => shape [1,128]       (timeArr)
     *   Input 1 => shape [1,128,3]     (xyzArr)
     *   Input 2 => shape [1,128]       (maskArr)
     *
     * @param timeArr FloatArray of length 128 => reshaped to [1,128].
     * @param xyzArr  Array<FloatArray> of shape (128, 3) => reshaped to [1,128,3].
     * @param maskArr FloatArray of length 128 => reshaped to [1,128].
     *
     * @return A float in [0, 1] representing the probability of "fall",
     *         or -9999f if an error occurs.
     */
    fun runInference(
        timeArr: FloatArray,
        xyzArr: Array<FloatArray>,
        maskArr: FloatArray
    ): Float {
        if (!modelLoaded || interpreter == null) {
            Log.e("TFLiteLiteRT", "Interpreter not loaded. Cannot run inference.")
            return -9999f
        }

        // Log the input lengths for debugging.
        Log.d("TFLiteLiteRT", "runInference called with: " +
                "timeArr.size=${timeArr.size}, xyzArr.size=${xyzArr.size}, maskArr.size=${maskArr.size}")

        // Check that each array has the expected length (128).
        if (timeArr.size != 128) {
            Log.e("TFLiteLiteRT", "timeArr must have length 128. Actual: ${timeArr.size}")
            return -9999f
        }
        if (maskArr.size != 128) {
            Log.e("TFLiteLiteRT", "maskArr must have length 128. Actual: ${maskArr.size}")
            return -9999f
        }
        if (xyzArr.size != 128) {
            Log.e("TFLiteLiteRT", "xyzArr must have length 128. Actual: ${xyzArr.size}")
            return -9999f
        }

        // Convert them to the shapes the model expects:
        // Input 0 => [1,128]
        val inputTime = arrayOf(timeArr)

        // Input 1 => [1,128,3]
        val inputXYZ = arrayOf(xyzArr)

        // Input 2 => [1,128]
        val inputMask = arrayOf(maskArr)

        // Prepare the output => shape [1,2].
        val outputArr = Array(1) { FloatArray(2) }
        val outputs = mutableMapOf<Int, Any>(0 to outputArr)

        // The model signature is (time, xyz, mask) or (time, mask, xyz) depending on your logs.
        // Based on your logs:
        //   Input 0 shape: [1, 128]
        //   Input 1 shape: [1, 128, 3]
        //   Input 2 shape: [1, 128]
        //
        // So the array order is [inputTime, inputXYZ, inputMask].
        val inputs = arrayOf<Any>(inputTime, inputXYZ, inputMask)

        return try {
            interpreter!!.runForMultipleInputsOutputs(inputs, outputs)

            val logits = outputArr[0] // shape(2): [noFallLogit, fallLogit]
            // Apply softmax => compute fall probability (index 1).
            val maxVal = logits.maxOrNull() ?: 0f
            val exp0 = exp(logits[0] - maxVal)
            val exp1 = exp(logits[1] - maxVal)
            val sum = exp0 + exp1
            val fallProb = exp1 / sum

            fallProb
        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "Inference error", e)
            -9999f
        }
    }

    /**
     * Closes the interpreter and frees resources.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        modelLoaded = false
    }
}
