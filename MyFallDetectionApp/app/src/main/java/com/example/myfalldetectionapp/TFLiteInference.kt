package com.example.myfalldetectionapp

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil

class TFLiteInference(context: Context) {

    private val tfliteInterpreter: Interpreter

    init {
        val tfliteModel = FileUtil.loadMappedFile(context, "fall_time2vec_transformer.tflite")
        tfliteInterpreter = Interpreter(tfliteModel)
    }

    /**
     * Predict using the TFLite model. The model expects:
     *   - accel_seq:  (1, T, 3) float32
     *   - accel_mask: (1, T) bool
     *   - accel_time: (1, T) float32
     *
     * We'll assume T=128 at inference time.
     * Return shape is (1, 2) => [class0_logit, class1_logit].
     */
    fun predict(
        accelData: Array<FloatArray>,   // shape [T, 3]
        timeData: FloatArray,           // shape [T]
        maskData: BooleanArray          // shape [T]
    ): FloatArray {
        val T = accelData.size
        require(timeData.size == T) { "timeData size mismatch" }
        require(maskData.size == T) { "maskData size mismatch" }

        // Prepare input
        val accelInput = Array(1) { Array(T) { FloatArray(3) } }
        val timeInput = Array(1) { FloatArray(T) }
        val maskInput = Array(1) { BooleanArray(T) }

        for (i in 0 until T) {
            accelInput[0][i] = accelData[i]
            timeInput[0][i] = timeData[i]
            maskInput[0][i] = maskData[i]
        }

        val outputScores = Array(1) { FloatArray(2) }

        // The order of inputs depends on how your TFLite model is set up.
        // Adjust if needed based on getInputDetails().
        val inputs = arrayOf<Any>(accelInput, maskInput, timeInput)

        // We map output index 0 => outputScores
        val outputs = mutableMapOf<Int, Any>()
        outputs[0] = outputScores

        tfliteInterpreter.runForMultipleInputsOutputs(inputs, outputs)

        return outputScores[0]
    }
}
