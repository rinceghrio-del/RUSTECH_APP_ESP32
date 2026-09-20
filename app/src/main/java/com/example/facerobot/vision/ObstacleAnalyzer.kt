package com.example.facerobot.vision

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Camera-based obstacle sensor gamit ang MiDaS small (v2.1) depth model.
 *
 * Kinukuha ang isang upright na frame, pinapatakbo ang depth model (relative inverse depth: mas mataas = mas malapit),
 * hinahati ang ibabang bahagi ng frame sa KALIWA / GITNA / KANAN, at nagbibigay ng score (0..1) sa bawat isa.
 * Kapag ang GITNA ay lampas sa blockThreshold -> may harang: liko sa mas bakanteng side, o ATRAS kung sarado pareho.
 *
 * Paalala: relative lang ang depth ng MiDaS (walang totoong sentimetro), kaya may "Calibrate" sa app -
 * ilalagay ang robot ~30cm sa harap ng harang at doon kinukuha ang threshold.
 *
 * Gamit sa MainActivity:
 *   ObstacleAnalyzer(modelFile)                      -> i-load ang model
 *   analyze(bitmap, pose, Config(blockThreshold), now) -> Result?
 *   reset()                                          -> linisin ang smoothing (ligtas tawagin kahit saang thread)
 *   close()                                          -> ilabas ang interpreter
 */
class ObstacleAnalyzer(modelFile: File) {

    enum class Decision { CLEAR, LEFT, RIGHT, BACK }

    data class Config(
        val blockThreshold: Float = 0.75f, // center score na katumbas o lampas dito = may harang
        val roiTop: Float = 0.30f,         // simula ng sinusuring bahagi (fraction ng taas ng frame)
        val roiBottom: Float = 0.90f,      // dulo ng sinusuring bahagi
        val flatRatio: Float = 0.15f,      // kapag mas maliit dito ang contrast ng depth map -> "flat"
        val confirmFrames: Int = 2         // ilang magkasunod na frame bago magpalit ng desisyon
    )

    data class Result(
        val decision: Decision,
        val left: Float,
        val center: Float,
        val right: Float,
        val flat: Boolean
    )

    private companion object {
        // ImageNet normalization na ginagamit ng MiDaS v2.1
        const val MEAN_R = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_B = 0.406f
        const val STD_R = 0.229f
        const val STD_G = 0.224f
        const val STD_B = 0.225f

        const val EMA_ALPHA = 0.35f     // bilis ng pag-adjust ng depth range sa pagitan ng frames
        const val PEAK_DECAY = 0.02f    // bagal ng pagbaba ng "pinakamalapit na nakita"
        const val STALE_MS = 1500L      // kung ganito na katagal ang pagitan ng frames, simula ulit
        const val FLAT_TRUST_FRAMES = 5 // ilang frame muna bago paniwalaan ang flat -> ATRAS
        const val FLAT_BACK_RATIO = 0.80f
    }

    private val interpreter: Interpreter
    private val inW: Int
    private val inH: Int
    private val outW: Int
    private val outH: Int

    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val pixels: IntArray
    private val depth: FloatArray
    private val hist = IntArray(256)

    // Per-pose (tilt A / tilt B) na state, kasi magkaiba ang tanaw ng camera sa dalawa
    private val emaLo = FloatArray(2)
    private val emaHi = FloatArray(2)
    private val peak = FloatArray(2)
    private val frames = IntArray(2)

    private var pendingDecision = Decision.CLEAR
    private var pendingCount = 0
    private var committedDecision = Decision.CLEAR
    private var lastFrameMs = 0L

    @Volatile private var resetRequested = true
    @Volatile private var closed = false

    init {
        val options = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(modelFile, options)

        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape() // inaasahan: [1, H, W, 3]
        check(inShape.size == 4 && inShape[3] == 3) {
            "Hindi suportadong input shape ng depth model: ${inShape.joinToString(prefix = "[", postfix = "]")}"
        }
        check(inTensor.dataType() == DataType.FLOAT32) { "Float32 input lang ang suportado (nakuha: ${inTensor.dataType()})" }
        inH = inShape[1]
        inW = inShape[2]

        val outTensor = interpreter.getOutputTensor(0)
        check(outTensor.dataType() == DataType.FLOAT32) { "Float32 output lang ang suportado (nakuha: ${outTensor.dataType()})" }
        val outShape = outTensor.shape()
        // Alisin ang mga dimension na 1 (batch/channel) - ang matitira ay [H, W]
        val dims = outShape.filter { it != 1 }
        if (dims.size >= 2) {
            outH = dims[dims.size - 2]
            outW = dims[dims.size - 1]
        } else {
            val total = outShape.fold(1) { acc, v -> acc * v }
            val side = sqrt(total.toDouble()).toInt().coerceAtLeast(1)
            outH = side
            outW = side
        }

        inputBuffer = ByteBuffer.allocateDirect(4 * inW * inH * 3).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(4 * outW * outH).order(ByteOrder.nativeOrder())
        pixels = IntArray(inW * inH)
        depth = FloatArray(outW * outH)
    }

    /** Ligtas tawagin kahit saang thread - ang aktwal na paglilinis ay gagawin sa susunod na analyze(). */
    fun reset() {
        resetRequested = true
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        interpreter.close()
    }

    /**
     * @param bitmap upright na frame mula sa camera
     * @param pose 0 = tilt A, 1 = tilt B (servo scan)
     * @param nowMs kasalukuyang oras (ms)
     * @return Result, o null kung sarado na ang analyzer
     */
    @Synchronized
    fun analyze(bitmap: Bitmap, pose: Int, cfg: Config, nowMs: Long): Result? {
        if (closed) return null

        if (resetRequested || nowMs - lastFrameMs > STALE_MS) {
            clearState()
            resetRequested = false
        }
        lastFrameMs = nowMs

        val p = pose.coerceIn(0, 1)

        runModel(bitmap)

        // 1) Depth range ng frame na ito (percentile, para hindi maapektuhan ng ilang pixel na sobrang taas/baba)
        val range = percentileRange(depth)
        val fLo = range[0]
        val fHi = range[1]

        // 2) Smoothing ng range sa pagitan ng frames + "pinakamalapit na nakita"
        if (frames[p] == 0) {
            emaLo[p] = fLo
            emaHi[p] = fHi
            peak[p] = fHi
        } else {
            emaLo[p] += (fLo - emaLo[p]) * EMA_ALPHA
            emaHi[p] += (fHi - emaHi[p]) * EMA_ALPHA
            peak[p] = if (fHi > peak[p]) fHi else peak[p] + (fHi - peak[p]) * PEAK_DECAY
        }
        frames[p]++

        val flat = (fHi - fLo) < cfg.flatRatio * max(abs(fHi), 1e-6f)

        // 3) Score ng kaliwa / gitna / kanan sa sinusuring bahagi ng frame
        val y0 = (cfg.roiTop * outH).toInt().coerceIn(0, outH - 1)
        val y1 = (cfg.roiBottom * outH).toInt().coerceIn(y0 + 1, outH)
        val third1 = outW / 3
        val third2 = (2 * outW) / 3
        val lo = emaLo[p]
        val span = max(emaHi[p] - lo, 1e-6f)

        val left = regionScore(0, third1, y0, y1, lo, span)
        val center = regionScore(third1, third2, y0, y1, lo, span)
        val right = regionScore(third2, outW, y0, y1, lo, span)

        // 4) Desisyon
        val t = cfg.blockThreshold
        val raw = when {
            flat -> {
                // Halos pare-pareho ang lalim (hal. pader na sobrang lapit, o blangko ang tanaw).
                // Pinaniniwalaang harang lang kung malapit sa pinakamalapit na nakita na.
                if (frames[p] >= FLAT_TRUST_FRAMES && fHi >= FLAT_BACK_RATIO * peak[p]) Decision.BACK else Decision.CLEAR
            }
            center < t -> Decision.CLEAR
            left >= t && right >= t -> Decision.BACK
            left <= right -> Decision.LEFT
            else -> Decision.RIGHT
        }

        // 5) Kailangang magkasunod ang parehong desisyon bago magpalit (iwas-kibot)
        if (raw == pendingDecision) {
            pendingCount++
        } else {
            pendingDecision = raw
            pendingCount = 1
        }
        if (pendingCount >= cfg.confirmFrames.coerceAtLeast(1)) {
            committedDecision = pendingDecision
        }

        return Result(committedDecision, left, center, right, flat)
    }

    private fun clearState() {
        emaLo.fill(0f)
        emaHi.fill(0f)
        peak.fill(0f)
        frames.fill(0)
        pendingDecision = Decision.CLEAR
        pendingCount = 0
        committedDecision = Decision.CLEAR
    }

    private fun runModel(bitmap: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(bitmap, inW, inH, true)
        scaled.getPixels(pixels, 0, inW, 0, 0, inW, inH)
        if (scaled !== bitmap) scaled.recycle()

        inputBuffer.rewind()
        for (px in pixels) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            inputBuffer.putFloat((r - MEAN_R) / STD_R)
            inputBuffer.putFloat((g - MEAN_G) / STD_G)
            inputBuffer.putFloat((b - MEAN_B) / STD_B)
        }
        inputBuffer.rewind()
        outputBuffer.rewind()

        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(depth)
    }

    /** Ibinabalik ang [~3rd percentile, ~97th percentile] ng depth map gamit ang histogram. */
    private fun percentileRange(values: FloatArray): FloatArray {
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in values) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        if (mx - mn < 1e-6f) return floatArrayOf(mn, mx)

        hist.fill(0)
        val scale = 255f / (mx - mn)
        for (v in values) {
            hist[((v - mn) * scale).toInt().coerceIn(0, 255)]++
        }

        val total = values.size
        val loCount = (total * 0.03f).toInt()
        val hiCount = (total * 0.97f).toInt()
        var acc = 0
        var loIdx = 0
        var hiIdx = 255
        var loFound = false
        for (i in 0 until 256) {
            acc += hist[i]
            if (!loFound && acc > loCount) {
                loIdx = i
                loFound = true
            }
            if (acc >= hiCount) {
                hiIdx = i
                break
            }
        }
        return floatArrayOf(mn + loIdx / scale, mn + hiIdx / scale)
    }

    private fun regionScore(x0: Int, x1: Int, y0: Int, y1: Int, lo: Float, span: Float): Float {
        var sum = 0f
        var count = 0
        for (y in y0 until y1) {
            val row = y * outW
            for (x in x0 until x1) {
                val n = ((depth[row + x] - lo) / span).coerceIn(0f, 1f)
                sum += n
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }
}
