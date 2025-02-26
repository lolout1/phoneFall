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
 * Model input shapes:
 *   - Input 0: [1, 128] (time)
 *   - Input 1: [1, 128, 3] (accelerometer)
 *   - Input 2: [1, 128] (mask)
 *
 * The output is [1, 2] representing logits for "no_fall" and "fall".
 * Softmax is applied to compute the fall probability.
 */
class TFLiteLiteRT(context: Context, modelFileName: String) {
    private var interpreter: InterpreterApi? = null
    private var modelLoaded = false

    init {
        try {
            Tasks.await(TfLite.initialize(context))
            Log.d("TFLiteLiteRT", "TfLite.initialize succeeded")
            val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelFileName)
            val opts = Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
            interpreter = InterpreterApi.create(modelBuffer, opts)
            Log.d("TFLiteLiteRT", "Interpreter created successfully")
            modelLoaded = true
            logInputShapes()
        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "Initialization failed", e)
        }
    }

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

    fun runInference(
        timeArr: FloatArray,
        xyzArr: Array<FloatArray>,
        maskArr: FloatArray
    ): Float {
        if (!modelLoaded || interpreter == null) {
            Log.e("TFLiteLiteRT", "Interpreter not loaded. Cannot run inference.")
            return -9999f
        }
        Log.d("TFLiteLiteRT", "runInference called with: timeArr.size=${timeArr.size}, xyzArr.size=${xyzArr.size}, maskArr.size=${maskArr.size}")
        if (timeArr.size != 128 || maskArr.size != 128 || xyzArr.size != 128) {
            Log.e("TFLiteLiteRT", "Input arrays must have length 128.")
            return -9999f
        }
        val inputTime = arrayOf(timeArr)
        val inputXYZ = arrayOf(xyzArr)
        val inputMask = arrayOf(maskArr)
        val outputArr = Array(1) { FloatArray(2) }
        val outputs = mutableMapOf<Int, Any>(0 to outputArr)
        val inputs = arrayOf<Any>(inputTime, inputXYZ, inputMask)

        return try {
            interpreter!!.runForMultipleInputsOutputs(inputs, outputs)
            val logits = outputArr[0]
            val maxVal = logits.maxOrNull() ?: 0f
            val exp0 = exp(logits[0] - maxVal)
            val exp1 = exp(logits[1] - maxVal)
            exp1 / (exp0 + exp1)
        } catch (e: Exception) {
            Log.e("TFLiteLiteRT", "Inference error", e)
            -9999f
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        modelLoaded = false
    }
}
