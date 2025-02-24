package com.example.myfalldetectionapplitertpro

import android.content.Context
import org.tensorflow.lite.litert.JavaLiteRT
import org.tensorflow.lite.litert.TensorDataType

/**
 * Helper that loads TFLite model via LiteRT and does a prediction.
 * Expects 3 inputs:
 *   #0 => float32[1, T, 3]   (accel)
 *   #1 => bool[1, T]        (mask)
 *   #2 => float32[1, T]     (time)
 * Produces output => float32[1, 2]
 */
class TFLiteLiteRT(context: Context, modelFileName: String) {

    private val litert: JavaLiteRT

    init {
        // Load model from assets
        val assetFd = context.assets.openFd(modelFileName)
        litert = JavaLiteRT.createFromFileDescriptor(
            assetFd.fileDescriptor,
            assetFd.startOffset,
            assetFd.length
        )
        assetFd.close()
    }

    fun predict(
        accelData: Array<FloatArray>,   // shape (T,3)
        timeData: FloatArray,           // shape (T)
        maskData: BooleanArray          // shape (T)
    ): FloatArray {
        val T = accelData.size
        // Expand dims to [1, T, ...]
        val accelSeq = Array(1) { Array(T) { FloatArray(3) } }
        val timeSeq  = Array(1) { FloatArray(T) }
        val boolMask = Array(1) { BooleanArray(T) }

        for (i in 0 until T) {
            accelSeq[0][i][0] = accelData[i][0]
            accelSeq[0][i][1] = accelData[i][1]
            accelSeq[0][i][2] = accelData[i][2]
            timeSeq[0][i] = timeData[i]
            boolMask[0][i] = maskData[i]
        }

        // Set inputs
        litert.setInput(0, accelSeq, TensorDataType.FLOAT32)
        litert.setInput(1, boolMask, TensorDataType.BOOL)
        litert.setInput(2, timeSeq, TensorDataType.FLOAT32)

        // Prepare output => shape (1,2)
        val outData = Array(1) { FloatArray(2) }
        litert.setOutput(0, outData, TensorDataType.FLOAT32)

        // Run inference
        litert.runInference()

        // Return logits
        return outData[0]
    }
}
