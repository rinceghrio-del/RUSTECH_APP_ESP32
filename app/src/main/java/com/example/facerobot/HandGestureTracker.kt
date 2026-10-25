package com.example.facerobot

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot

/**
 * Kamay na nakita ng camera.
 *  x, y     = posisyon ng "pointer" (0..1 sa frame; naka-mirror na kung front camera) - gitna ng thumb tip at index tip
 *  pinching = nakapinch ba (thumb + index magkadikit) = "hawak" ang candy
 *  pinchRatio = layo ng thumb-index kumpara sa laki ng palad (para sa debugging/tuning)
 */
data class HandState(val x: Float, val y: Float, val pinching: Boolean, val pinchRatio: Float)

/**
 * MediaPipe Hand Landmarker (LIVE_STREAM). Kailangan:
 *   1) build.gradle (app):  implementation 'com.google.mediapipe:tasks-vision:latest.release'
 *   2) app/src/main/assets/hand_landmarker.task   (model file)
 *
 * Gamit: start() (sa background thread) -> detect(bitmapNaPatayo, timestampMs) bawat frame -> onHand(...) ang resulta
 * (tinatawag sa thread ng MediaPipe - ilipat sa UI thread sa tumatawag). close() kapag tapos na.
 */
class HandGestureTracker(
    private val context: Context,
    private val mirrorX: Boolean,
    private val onHand: (HandState?) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        // I-TUNE: mas mataas = mas madaling ma-detect ang pinch. Hysteresis: iba ang sa simula at sa pagbitaw.
        private const val PINCH_START_RATIO = 0.35f
        private const val PINCH_RELEASE_RATIO = 0.55f
        private const val SMOOTHING = 0.55f   // 0..1: mas mataas = mas mabilis sumunod (pero mas jitter)
    }

    @Volatile
    var ready = false
        private set

    private var landmarker: HandLandmarker? = null
    private var pinching = false
    private var smoothX = -1f
    private var smoothY = -1f
    private var lastTimestamp = 0L

    /** Ilo-load ang model - tawagin sa background thread. */
    fun start() {
        try {
            val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result: HandLandmarkerResult, _: MPImage -> handleResult(result) }
                .setErrorListener { e: RuntimeException -> onError(e.message ?: "hand tracker error") }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, options)
            ready = true
        } catch (t: Throwable) {
            ready = false
            onError(
                if (t.message?.contains(MODEL_ASSET, ignoreCase = true) == true || t is java.io.FileNotFoundException)
                    "Kulang ang assets/$MODEL_ASSET"
                else "Hand tracker error: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    /** Ipasa ang bawat frame (PATAYO na ang bitmap - nai-rotate na ayon sa rotationDegrees ng camera). */
    fun detect(bitmap: Bitmap, timestampMs: Long) {
        val lm = landmarker ?: return
        // Kailangang tuloy-tuloy na tumataas ang timestamp sa LIVE_STREAM
        val ts = if (timestampMs > lastTimestamp) timestampMs else lastTimestamp + 1
        lastTimestamp = ts
        try {
            lm.detectAsync(BitmapImageBuilder(bitmap).build(), ts)
        } catch (t: Throwable) {
            onError("Hand detect error: ${t.javaClass.simpleName}")
        }
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            pinching = false
            smoothX = -1f
            onHand(null)
            return
        }
        val lm = hands[0]
        if (lm.size < 21) {
            onHand(null)
            return
        }
        val wrist = lm[0]
        val thumbTip = lm[4]
        val indexTip = lm[8]
        val middleMcp = lm[9]

        // Sukat ng palad (wrist -> gitnang knuckle) bilang batayan ng "gaano kalapit ang pinch" - hindi nagbabago
        // kahit lumapit/lumayo ang kamay sa camera.
        val palm = hypot(wrist.x() - middleMcp.x(), wrist.y() - middleMcp.y()).coerceAtLeast(0.01f)
        val ratio = hypot(thumbTip.x() - indexTip.x(), thumbTip.y() - indexTip.y()) / palm
        pinching = if (pinching) ratio < PINCH_RELEASE_RATIO else ratio < PINCH_START_RATIO

        // Pointer = gitna ng thumb at index tip: halos hindi gumagalaw habang pinipinch, kaya hindi lumilipat ang napili
        var px = (thumbTip.x() + indexTip.x()) / 2f
        val py = (thumbTip.y() + indexTip.y()) / 2f
        if (mirrorX) px = 1f - px

        if (smoothX < 0f) {
            smoothX = px
            smoothY = py
        } else {
            smoothX += (px - smoothX) * SMOOTHING
            smoothY += (py - smoothY) * SMOOTHING
        }
        onHand(HandState(smoothX, smoothY, pinching, ratio))
    }

    fun close() {
        ready = false
        try { landmarker?.close() } catch (_: Throwable) { }
        landmarker = null
    }
}
