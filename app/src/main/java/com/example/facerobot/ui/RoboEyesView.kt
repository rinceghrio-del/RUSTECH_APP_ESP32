package com.example.facerobot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * Simpleng "robot eyes" custom View, inspired by FluxGarage RoboEyes (yung library na
 * ginagamit sa mga OLED display ng ESP32/Arduino robots) pero dito iginuhit gamit ang
 * Canvas sa Android para sa "idle screen" ng app - lalabas ito habang wala pang taong
 * nakikita ng camera.
 *
 * Mga tampok:
 *  - Random na pagkurap (blink) paminsan-minsan
 *  - Idle "paglingon" - dahan-dahang gumagalaw ang mga mata papunta sa random na direksyon
 *  - Mood states: IDLE (normal), ALERT (namumulat, tulad ng "may nakita ako!")
 */
class RoboEyesView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mood { IDLE, ALERT, SEARCHING }

    private val backgroundPaint = Paint().apply { color = Color.BLACK }
    private val eyePaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // cyan, parang matang robot
        isAntiAlias = true
    }

    private var mood = Mood.IDLE

    // 0f = wide open, 1f = fully closed (blink)
    private var blinkAmount = 0f

    // Kung saan "nakatingin" ang mga mata, -1f (kaliwa/taas) hanggang 1f (kanan/baba)
    private var lookX = 0f
    private var lookY = 0f
    private var lookTargetX = 0f
    private var lookTargetY = 0f

    private var animator: ValueAnimator? = null
    private val random = Random(System.currentTimeMillis())

    private var nextBlinkAtMs = 0L
    private var nextLookChangeAtMs = 0L

    fun setMood(newMood: Mood) {
        mood = newMood
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleNextBlink()
        scheduleNextLookChange()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    private fun startLoop() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { tick() }
            start()
        }
    }

    private fun scheduleNextBlink() {
        nextBlinkAtMs = System.currentTimeMillis() + random.nextLong(2000, 5500)
    }

    private fun scheduleNextLookChange() {
        nextLookChangeAtMs = System.currentTimeMillis() + random.nextLong(2500, 6000)
        lookTargetX = if (mood == Mood.SEARCHING) random.nextFloat() * 2f - 1f else (random.nextFloat() * 1.2f - 0.6f)
        lookTargetY = random.nextFloat() * 0.6f - 0.3f
    }

    private var blinkPhase = 0 // 0 idle, 1 closing, 2 opening
    private fun tick() {
        val now = System.currentTimeMillis()

        // Blink state machine
        if (blinkPhase == 0 && now >= nextBlinkAtMs && mood != Mood.ALERT) {
            blinkPhase = 1
        }
        when (blinkPhase) {
            1 -> { // closing
                blinkAmount += 0.18f
                if (blinkAmount >= 1f) { blinkAmount = 1f; blinkPhase = 2 }
            }
            2 -> { // opening
                blinkAmount -= 0.18f
                if (blinkAmount <= 0f) { blinkAmount = 0f; blinkPhase = 0; scheduleNextBlink() }
            }
        }

        // Idle look-around, lerp papunta sa target
        if (now >= nextLookChangeAtMs) scheduleNextLookChange()
        lookX += (lookTargetX - lookX) * 0.04f
        lookY += (lookTargetY - lookY) * 0.04f

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val eyeHeight = height * 0.28f
        val eyeWidthAlert = width * 0.22f
        val eyeWidth = if (mood == Mood.ALERT) eyeWidthAlert * 1.15f else eyeWidthAlert
        val spacing = width * 0.10f

        val centerY = height / 2f + lookY * height * 0.08f
        val shiftX = lookX * width * 0.06f

        val leftCenterX = width / 2f - spacing / 2f - eyeWidth / 2f + shiftX
        val rightCenterX = width / 2f + spacing / 2f + eyeWidth / 2f + shiftX

        // Kapag namumulat (ALERT) medyo mas malaki at bilog ang mata; kapag SEARCHING
        // mabagal na gumagalaw pakaliwa't kanan (hawak na ng lookX/lookY logic sa itaas)
        val closeFactor = if (mood == Mood.ALERT) blinkAmount * 0.3f else blinkAmount
        val currentEyeHeight = eyeHeight * (1f - closeFactor).coerceAtLeast(0.06f)
        val cornerRadius = if (mood == Mood.ALERT) currentEyeHeight * 0.5f else currentEyeHeight * 0.35f

        drawEye(canvas, leftCenterX, centerY, eyeWidth, currentEyeHeight, cornerRadius)
        drawEye(canvas, rightCenterX, centerY, eyeWidth, currentEyeHeight, cornerRadius)
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, radius: Float) {
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(rect, radius, radius, eyePaint)
    }
}
