package com.example.facerobot.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Kumukuha ng "face embedding" (isang listahan ng numbers na parang fingerprint ng mukha)
 * gamit ang isang MobileFaceNet-style TFLite model. Ito yung gagamitin para malaman kung
 * SINO ang taong nakita, sa pamamagitan ng pag-compare ng embeddings (cosine similarity)
 * laban sa mga naka-enroll na mukha (tignan ang FaceStore.kt).
 *
 * PAALALA IDOL: Kailangan mong maglagay ng model file sa:
 *   app/src/main/assets/face_embedder.tflite
 *
 * Saan kukuha:
 *  - Maghanap ng "MobileFaceNet.tflite" o "mobile_face_net.tflite" (112x112 input,
 *    192-dim output). Maraming open-source repo na may kasamang ready .tflite file,
 *    hal. mga Android face-recognition sample projects sa GitHub.
 *  - Siguraduhin lang na 112x112 ang input size at ~192 (o kung ano man) ang output
 *    dimension - i-adjust ang OUTPUT_SIZE sa baba kung iba.
 */
class FaceEmbedder(context: Context, modelAssetName: String = "face_embedder.tflite") {

    companion object {
        private const val TAG = "FaceEmbedder"
        const val INPUT_SIZE = 112
        const val OUTPUT_SIZE = 192
    }

    private var interpreter: Interpreter? = null
    val isReady: Boolean get() = interpreter != null

    init {
        try {
            val model = loadModelFile(context, modelAssetName)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(model, options)
            Log.i(TAG, "Face embedder model loaded: $modelAssetName")
        } catch (e: Exception) {
            Log.e(TAG, "Hindi na-load ang face embedder model ($modelAssetName). Ilagay ito sa assets/.", e)
            interpreter = null
        }
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    /** Ibinabalik ang L2-normalized embedding ng mukha, o null kung walang model. */
    fun getEmbedding(faceBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null

        val resized = ImageUtils.resize(faceBitmap, INPUT_SIZE)
        val inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        inputBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            // Normalize sa [-1, 1] - karaniwang preprocessing ng MobileFaceNet
            inputBuffer.putFloat((((pixel shr 16) and 0xFF) - 127.5f) / 128.0f) // R
            inputBuffer.putFloat((((pixel shr 8) and 0xFF) - 127.5f) / 128.0f)  // G
            inputBuffer.putFloat(((pixel and 0xFF) - 127.5f) / 128.0f)          // B
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(OUTPUT_SIZE) }
        try {
            interp.run(inputBuffer, output)
        } catch (e: Exception) {
            Log.e(TAG, "Face embedding inference failed", e)
            return null
        }

        return l2Normalize(output[0])
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (v in vector) sumSquares += v * v
        val norm = sqrt(sumSquares).coerceAtLeast(1e-6f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
