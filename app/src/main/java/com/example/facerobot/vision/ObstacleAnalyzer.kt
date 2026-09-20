package com.example.facerobot.vision

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Camera-based obstacle avoidance gamit ang MiDaS v2.1 small (monocular depth, 256x256).
 *
 * Paano gumagana:
 *  1. Ang upright na frame ay ini-resize sa 256x256 at ipinapasok sa MiDaS -> "relative inverse depth"
 *     (mas MALAKI ang halaga = mas MALAPIT).
 *  2. Ang depth map ay ni-normalize kada frame (5th-98th percentile -> 0..1) at hinahati sa 3 zone
 *     (kaliwa / gitna / kanan) sa gitnang banda ng larawan (hindi kasama ang pinakababa na malapit na sahig
 *     at ang pinakataas na kisame).
 *  3. Score ng zone = average ng pinakamalapit na 30% ng mga cell sa loob nito (0..1).
 *  4. Kung ang gitna ay >= blockThreshold -> may harang: liko sa mas libreng gilid (LEFT/RIGHT),
 *     o BACK kapag parehong gilid ay harang din (dead end). May hysteresis: kapag pumili na ng gilid,
 *     hindi ito magpapalit hanggang sa luminaw ang gitna (score < threshold - clearMargin).
 *  5. Kung may dalawang servo pose (tilt A / tilt B), pinagsasama ang huling score ng dalawa (pinakamataas
 *     bawat zone) para makita ang parehong malapit na sahig at mas malayong tanaw.
 *
 * Ang mga score ay RELATIVE (hindi metric distance) kaya kailangan i-calibrate ang blockThreshold
 * sa totoong robot/sahig/ilaw mo (tignan ang Calibrate sa Nav Settings ng app).
 */
class ObstacleAnalyzer(modelFile: File) : AutoCloseable {

    enum class Decision { CLEAR, LEFT, RIGHT, BACK }

    data class Config(
        val blockThreshold: Float = 0.75f,
        val clearMargin: Float = 0.10f,
        val rowTop: Float = 0.30f,      // simula ng banda (fraction ng taas ng larawan)
        val rowBottom: Float = 0.75f    // dulo ng banda - ibaba ang dalawa kapag hindi makatingin pababa ang camera
    )

    data class Result(
        val left: Float,
        val center: Float,
        val right: Float,
        val decision: Decision,
        val flat: Boolean
    )

    companion object {
        const val SIZE = 256
        private const val GRID = 64
        private const val CELL = SIZE / GRID          // 4
        private const val LEFT_CUT = 0.35f            // hangganan ng kaliwang zone
        private const val RIGHT_CUT = 0.65f           // hangganan ng kanang zone
        private const val TOP_FRACTION = 0.30f        // pinakamalapit na % ng cells sa zone na iaaverage
        private const val MIN_RANGE = 100f            // mas maliit dito = flat na eksena -> fail-open (CLEAR)
        private const val EMA_ALPHA = 0.6f            // bigat ng bagong frame sa smoothing
        private const val POSE_MAX_AGE_MS = 3000L     // gaano katagal valid ang score ng isang servo pose
        private const val COMMIT_TIMEOUT_MS = 4500L   // kapag ganito katagal na umiikot pero harang pa rin -> BACK
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val interpreter: Interpreter

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val outputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())

    private val pixels = IntArray(SIZE * SIZE)
    private val depth = FloatArray(SIZE * SIZE)
    private val grid = FloatArray(GRID * GRID)
    private val sortedGrid = FloatArray(GRID * GRID)
    private val zoneTmp = FloatArray(GRID * GRID)

    // Huling score ng bawat servo pose (0 = tilt A, 1 = tilt B): [left, center, right]
    private val poseScores = Array(2) { FloatArray(3) }
    private val poseValid = BooleanArray(2)
    private val poseTime = LongArray(2)

    private var committed = Decision.CLEAR
    private var commitStart = 0L

    init {
        val options = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(modelFile, options)
    }

    @Synchronized
    fun reset() {
        poseValid[0] = false
        poseValid[1] = false
        committed = Decision.CLEAR
        commitStart = 0L
    }

    /**
     * @param upright  frame na naka-ikot na sa tamang orientation (kaliwa sa larawan = kaliwa ng robot)
     * @param pose     0 o 1 - alin sa dalawang servo tilt ang gamit ng frame na ito
     */
    @Synchronized
    fun analyze(upright: Bitmap, pose: Int, cfg: Config, nowMs: Long = System.currentTimeMillis()): Result? {
        val p = pose.coerceIn(0, 1)

        // --- 1. Preprocess: resize + ImageNet normalization ---
        val scaled = if (upright.width == SIZE && upright.height == SIZE) {
            upright
        } else {
            Bitmap.createScaledBitmap(upright, SIZE, SIZE, true)
        }
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== upright) scaled.recycle()

        inputBuffer.rewind()
        for (px in pixels) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            inputBuffer.putFloat((r - MEAN[0]) / STD[0])
            inputBuffer.putFloat((g - MEAN[1]) / STD[1])
            inputBuffer.putFloat((b - MEAN[2]) / STD[2])
        }
        inputBuffer.rewind()

        // --- 2. Inference ---
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(depth)

        // --- 3. Pool 4x4 -> 64x64 grid ---
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                var sum = 0f
                for (dy in 0 until CELL) {
                    val row = (gy * CELL + dy) * SIZE + gx * CELL
                    for (dx in 0 until CELL) sum += depth[row + dx]
                }
                grid[gy * GRID + gx] = sum / (CELL * CELL)
            }
        }

        // --- 4. Robust normalization (5th..98th percentile) ---
        System.arraycopy(grid, 0, sortedGrid, 0, grid.size)
        java.util.Arrays.sort(sortedGrid)
        val lo = sortedGrid[(0.05f * (sortedGrid.size - 1)).toInt()]
        val hi = sortedGrid[(0.98f * (sortedGrid.size - 1)).toInt()]
        val range = hi - lo
        val flat = range < MIN_RANGE

        // --- 5. Zone scores ---
        val fresh = FloatArray(3)
        if (!flat) {
            val y0 = (cfg.rowTop * GRID).toInt().coerceIn(0, GRID - 6)
            val y1 = (cfg.rowBottom * GRID).toInt().coerceIn(y0 + 6, GRID)
            val cuts = intArrayOf(0, (LEFT_CUT * GRID).toInt(), (RIGHT_CUT * GRID).toInt(), GRID)
            for (z in 0..2) {
                var n = 0
                for (y in y0 until y1) {
                    for (x in cuts[z] until cuts[z + 1]) {
                        val v = ((grid[y * GRID + x] - lo) / range).coerceIn(0f, 1f)
                        zoneTmp[n++] = v
                    }
                }
                java.util.Arrays.sort(zoneTmp, 0, n)
                val k = max(1, (n * TOP_FRACTION).toInt())
                var sum = 0f
                for (i in n - k until n) sum += zoneTmp[i]
                fresh[z] = sum / k
            }
        }
        // (flat scene -> zeros = fail-open; ultrasonic/IR pa rin ang bahala sa ESP32)

        // --- 6. Smoothing per pose ---
        val ps = poseScores[p]
        if (!poseValid[p]) {
            for (i in 0..2) ps[i] = fresh[i]
            poseValid[p] = true
        } else {
            for (i in 0..2) ps[i] = EMA_ALPHA * fresh[i] + (1f - EMA_ALPHA) * ps[i]
        }
        poseTime[p] = nowMs

        // --- 7. Pagsamahin ang mga pose na sariwa pa (pinakamataas bawat zone) ---
        var l = 0f
        var c = 0f
        var r = 0f
        for (q in 0..1) {
            if (poseValid[q] && nowMs - poseTime[q] <= POSE_MAX_AGE_MS) {
                l = max(l, poseScores[q][0])
                c = max(c, poseScores[q][1])
                r = max(r, poseScores[q][2])
            }
        }

        // --- 8. Desisyon na may hysteresis ---
        val threshold = cfg.blockThreshold
        val clearLevel = threshold - cfg.clearMargin
        var decision: Decision

        if (committed == Decision.LEFT || committed == Decision.RIGHT) {
            if (c < clearLevel) {
                committed = Decision.CLEAR
                decision = Decision.CLEAR
            } else if (nowMs - commitStart > COMMIT_TIMEOUT_MS) {
                committed = Decision.CLEAR
                decision = Decision.BACK
            } else {
                decision = committed
            }
        } else if (c >= threshold) {
            decision = when {
                l >= threshold && r >= threshold -> Decision.BACK
                l <= r -> Decision.LEFT
                else -> Decision.RIGHT
            }
            if (decision == Decision.LEFT || decision == Decision.RIGHT) {
                committed = decision
                commitStart = nowMs
            }
        } else {
            decision = Decision.CLEAR
        }

        return Result(left = l, center = c, right = r, decision = decision, flat = flat)
    }

    override fun close() {
        try {
            interpreter.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
