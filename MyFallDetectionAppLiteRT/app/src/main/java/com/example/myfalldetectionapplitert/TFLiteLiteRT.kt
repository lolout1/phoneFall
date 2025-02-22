package com.example.myfalldetectionapplitert

import android.content.Context
import org.tensorflow.lite.litert.JavaLiteRT
import org.tensorflow.lite.litert.TensorDataType

/**
 * A helper class that uses the new LiteRT approach to load and run the TFLite model.
 * The model is expected to have 3 inputs (accel_seq, accel_mask, accel_time)
 * each shaped (1, T, ...) or (1, T). We produce (1,2) logits as the output.
 *
 *   - accel_seq => float32[1, T, 3]
 *   - accel_mask => bool[1, T]
 *   - accel_time => float32[1, T]
 *
 */
class TFLiteLiteRT(context: Context, modelFileName: String) {

    private val model: JavaLiteRT

    init {
        // Use JavaLiteRT to load the model from assets
        val assetFd = context.assets.openFd(modelFileName)
        model = JavaLiteRT.createFromFileDescriptor(
            assetFd.fileDescriptor,
            assetFd.startOffset,
            assetFd.length
        )
        assetFd.close()
    }

    /**
     * Inference:
     *   @param accelData: shape [T,3]
     *   @param timeData:  shape [T]
     *   @param maskData:  shape [T]
     * Returns FloatArray of size 2 => [logit0, logit1]
     */
    fun predict(
        accelData: Array<FloatArray>,
        timeData: FloatArray,
        maskData: BooleanArray
    ): FloatArray {
        val T = accelData.size

        // We create input arrays with batch dimension=1
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

        // Provide each input by name or by index; in LiteRT you typically name them or use index
        // We'll assume the model has input0=accel_seq, input1=accel_mask, input2=accel_time
        model.setInput(
            /*inputIndex=*/0,
            accelSeq,  // shape (1,T,3)
            TensorDataType.FLOAT32
        )
        model.setInput(
            /*inputIndex=*/1,
            boolMask,  // shape (1,T)
            TensorDataType.BOOL
        )
        model.setInput(
            /*inputIndex=*/2,
            timeSeq,   // shape (1,T)
            TensorDataType.FLOAT32
        )

        // Prepare output for shape (1,2)
        val outArray = Array(1) { FloatArray(2) }
        model.setOutput(0, outArray, TensorDataType.FLOAT32)

        // Run
        model.runInference()

        return outArray[0]  // shape [2]
    }
}
