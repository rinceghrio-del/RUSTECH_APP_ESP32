package com.example.facerobot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Simpleng wrapper para sa isang YOLOv8-style TFLite model (anchor-free, output shape
 * [1, 4 + numClasses, numBoxes]). Dating "person-only" detector lang ito, ngayon ay
 * general-purpose na - kaya nang mag-detect ng kahit anong COCO class (tignan ang
 * PERSON_CLASS_INDEX, CAT_CLASS_INDEX, DOG_CLASS_INDEX sa baba, dagdagan lang kung
 * may gusto pang ibang class).
 *
 * PAALALA IDOL: Hindi kasama dito ang aktwal na model file. Kailangan mong maglagay ng
 * ".tflite" file sa: app/src/main/assets/yolo_person.tflite
 *
 * Kung custom-trained ang model mo (person-only, 1 class), i-adjust ang mga class index
 * at NUM_CLASSES sa baba.
 */
class YoloPersonDetector(context: Context, modelAssetName: String = "yolo_person.tflite") {

    companion object {
        private const val TAG = "YoloPersonDetector"
        const val INPUT_SIZE = 320          // dapat tugma sa imgsz na ginamit sa export
        const val NUM_CLASSES = 80          // COCO default; baguhin kung custom-trained
        const val PERSON_CLASS_INDEX = 0    // "person" ang class 0 sa COCO
        const val CAT_CLASS_INDEX = 15      // "cat" ang class 15 sa COCO
        const val DOG_CLASS_INDEX = 16      // "dog" ang class 16 sa COCO
        const val CONF_THRESHOLD = 0.5f
        const val IOU_THRESHOLD = 0.45f

        // Mga class na ginagamit sa app para sa "may hayop" na detection - dagdagan
        // lang dito kung gusto ng ibang klase (tignan ang COCO 80-class list).
        val PET_CLASSES = setOf(CAT_CLASS_INDEX, DOG_CLASS_INDEX)
    }

    // label = Filipino na pangalan ng class, direktang magagamit sa TTS/UI
    data class Detection(val box: RectF, val confidence: Float, val classId: Int, val label: String)

    private var interpreter: Interpreter? = null
    val isReady: Boolean get() = interpreter != null

    // Ilang bagong export (hal. litert_torch/Colab) ay NCHW ([1,3,H,W] - "channel muna"),
    // habang ang mga onnx2tf-based export noon ay NHWC ([1,H,W,3] - "pixel muna, tapos
    // channel"). Kailangan itong tugma sa PAGKAKA-AYOS ng bytes na ipinapasok natin,
    // kaya sinusuri natin ito minsan lang, base mismo sa shape ng model - hindi hula.
    private var isNchw = false

    // Para sa on-device debugging (walang adb/logcat kailangan) - nakikita mismo sa
    // status text ng app kung gaano "kalapit" ang YOLO sa pag-detect.
    var lastMaxPersonScore: Float = 0f
        private set
    var lastError: String? = null
        private set

    init {
        try {
            val model = loadModelFile(context, modelAssetName)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            val interp = Interpreter(model, options)
            interpreter = interp

            val inputShape = interp.getInputTensor(0).shape() // [1,3,H,W] o [1,H,W,3]
            isNchw = inputShape.size == 4 && inputShape[1] == 3
            Log.i(TAG, "YOLO model loaded: $modelAssetName (input shape=${inputShape.toList()}, isNchw=$isNchw)")
        } catch (e: Exception) {
            // Sadyang hindi natin ipapa-crash ang app kung wala pa/mali ang model file -
            // gagana pa rin ang RoboEyes, wala lang auto detection.
            Log.e(TAG, "Hindi na-load ang YOLO model ($modelAssetName). Ilagay ito sa assets/.", e)
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

    private fun labelFor(classId: Int): String = when (classId) {
        PERSON_CLASS_INDEX -> "tao"
        CAT_CLASS_INDEX -> "pusa"
        DOG_CLASS_INDEX -> "aso"
        else -> "class_$classId"
    }

    /** Dating behavior - "person" detections lang. Hindi ginalaw para di masira ang existing calls. */
    fun detectPersons(bitmap: Bitmap): List<Detection> {
        return detect(bitmap, setOf(PERSON_CLASS_INDEX))
    }

    /** Bagong function - "aso"/"pusa" detections lang. */
    fun detectPets(bitmap: Bitmap): List<Detection> {
        return detect(bitmap, PET_CLASSES)
    }

    /**
     * General-purpose na detection - iisang inference call lang bawat tawag, tapos
     * hinahanap lang natin yung mga class na nasa loob ng `classesOfInterest`. Kung
     * kailangan mo ng person AT pets sa parehong frame, mas mabuting isang tawag na
     * lang dito na may parehong class indices, imbes na tawagin nang hiwalay ang
     * detectPersons() at detectPets() (doble ang gagastusing inference kung ganon).
     */
    fun detect(bitmap: Bitmap, classesOfInterest: Set<Int>): List<Detection> {
        val interp = interpreter ?: return emptyList()

        val resized = ImageUtils.resize(bitmap, INPUT_SIZE)
        val inputBuffer = bitmapToInputBuffer(resized)

        val outputShape = interp.getOutputTensor(0).shape() // e.g. [1, 84, 2100]
        val output = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }

        try {
            interp.run(inputBuffer, output)
            lastError = null
        } catch (e: Exception) {
            Log.e(TAG, "YOLO inference failed", e)
            lastError = e.message ?: e.javaClass.simpleName
            return emptyList()
        }

        return decodeOutput(output[0], outputShape, bitmap.width, bitmap.height, classesOfInterest)
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        if (isNchw) {
            // NCHW: buong R plane muna, tapos buong G plane, tapos buong B plane.
            for (pixel in pixels) buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            for (pixel in pixels) buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            for (pixel in pixels) buffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        } else {
            // NHWC: interleaved per-pixel R,G,B,R,G,B,...
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
                buffer.putFloat((pixel and 0xFF) / 255.0f)          // B
            }
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Output layout na inaasahan: [numAttributes][numBoxes] kung saan numAttributes = 4 + NUM_CLASSES
     * (cx, cy, w, h, tapos yung class scores). Para sa bawat box, hinahanap ang pinakamataas
     * na class score SA LOOB LANG ng classesOfInterest (hal. {person} o {cat, dog}) - hindi
     * lahat ng 80 classes, para mabilis pa rin at hindi ma-confuse ng ibang class na
     * hindi naman natin pinapansin.
     */
    private fun decodeOutput(
        output: Array<FloatArray>,
        shape: IntArray,
        origWidth: Int,
        origHeight: Int,
        classesOfInterest: Set<Int>
    ): List<Detection> {
        val numAttrs = shape[1]
        val numBoxes = shape[2]
        val maxClassIndex = classesOfInterest.maxOrNull() ?: return emptyList()
        if (numAttrs < 4 + maxClassIndex + 1) return emptyList()

        val candidates = mutableListOf<Detection>()
        var maxScore = 0f

        for (i in 0 until numBoxes) {
            var bestClassId = -1
            var bestScore = 0f
            for (classId in classesOfInterest) {
                val score = output[4 + classId][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClassId = classId
                }
            }
            if (bestScore > maxScore) maxScore = bestScore
            if (bestClassId == -1 || bestScore < CONF_THRESHOLD) continue

            val cx = output[0][i] / INPUT_SIZE * origWidth
            val cy = output[1][i] / INPUT_SIZE * origHeight
            val w = output[2][i] / INPUT_SIZE * origWidth
            val h = output[3][i] / INPUT_SIZE * origHeight

            val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            candidates.add(Detection(rect, bestScore, bestClassId, labelFor(bestClassId)))
        }

        lastMaxPersonScore = maxScore
        return nonMaxSuppression(candidates)
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best.box, it.box) > IOU_THRESHOLD }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
