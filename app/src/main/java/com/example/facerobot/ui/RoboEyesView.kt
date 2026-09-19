package com.example.facerobot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
 *  - Mood states: IDLE (normal), ALERT (HAPPY EYES - "may nakita ako!"), SEARCHING,
 *    ANGRY (sobrang lapit ng mukha - nakababang "kilay" sa loob ng mata, tulad ng RoboEyes ANGRY)
 *  - Happy eyes: tulad ng RoboEyes HAPPY mood - tinatakpan ng rounded na "lower eyelid"
 *    ang ibabang bahagi ng mata kaya nagiging arko/ngiti (^ ^)
 */
class RoboEyesView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mood { IDLE, ALERT, SEARCHING, ANGRY }

    // ---- Dito i-adjust ang itsura ----
    private val eyeWidthScale = 1.25f   // lapad ng mata (lahat ng mood). dati 1.15f
    private val eyeHeightScale = 1.10f  // taas ng mata (lahat ng mood)
    private val happySmile = 0.40f      // lalim ng ngiti ng happy eyes (dati 0.5f, mas mataas = mas malalim)
    private val angryLid = 0.55f        // baba ng "kilay" ng angry eyes (mas mataas = mas galit)

    private val backgroundPaint = Paint().apply { color = Color.BLACK }
    private val lidPath = Path()
    private val eyePaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // cyan, parang matang robot
        isAntiAlias = true
    }

    private var mood = Mood.IDLE

    // 0f = wide open, 1f = fully closed (blink)
    private var blinkAmount = 0f

    // 0f = normal na mata, 1f = fully happy (nakataas ang lower eyelid). Smooth ang transition.
    private var happyAmount = 0f

    // 0f = walang kilay, 1f = fully angry (nakababa ang inner top ng mata). Smooth din ang transition.
    private var angryAmount = 0f

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
        if (mood == newMood) return
        mood = newMood
        scheduleNextLookChange() // agad na susunod sa bagong mood (hal. SEARCHING = mas malawak lumingon)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleNextBlink()
        scheduleNextLookChange()
        if (visibility == VISIBLE) startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    // Huwag nang mag-animate kapag nakatago ang eyes (Camera mode) para hindi sayang ang CPU/baterya
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) startLoop() else stopLoop()
    }

    private fun stopLoop() {
        animator?.cancel()
        animator = null
    }

    private fun startLoop() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { tick() }
            start()
        }
    }

    private fun scheduleNextBlink() {
        nextBlinkAtMs = System.currentTimeMillis() + random.nextLong(1400, 3800)
    }

    private fun scheduleNextLookChange() {
        nextLookChangeAtMs = System.currentTimeMillis() + random.nextLong(1500, 3800)
        lookTargetX = if (mood == Mood.SEARCHING) random.nextFloat() * 2f - 1f else (random.nextFloat() * 1.2f - 0.6f)
        lookTargetY = random.nextFloat() * 0.6f - 0.3f
    }

    private var blinkPhase = 0 // 0 idle, 1 closing, 2 opening
    private fun tick() {
        val now = System.currentTimeMillis()

        // Blink state machine
        if (blinkPhase == 0 && now >= nextBlinkAtMs) {
            blinkPhase = 1
        }
        when (blinkPhase) {
            1 -> { // closing
                blinkAmount += 0.26f
                if (blinkAmount >= 1f) { blinkAmount = 1f; blinkPhase = 2 }
            }
            2 -> { // opening
                blinkAmount -= 0.26f
                if (blinkAmount <= 0f) { blinkAmount = 0f; blinkPhase = 0; scheduleNextBlink() }
            }
        }

        // Idle look-around, lerp papunta sa target
        if (now >= nextLookChangeAtMs) scheduleNextLookChange()
        lookX += (lookTargetX - lookX) * 0.07f
        lookY += (lookTargetY - lookY) * 0.07f

        // Happy eyes: smooth na pagtaas/pagbaba ng lower eyelid
        val happyTarget = if (mood == Mood.ALERT) 1f else 0f
        happyAmount += (happyTarget - happyAmount) * 0.2f
        if (kotlin.math.abs(happyTarget - happyAmount) < 0.01f) happyAmount = happyTarget

        val angryTarget = if (mood == Mood.ANGRY) 1f else 0f
        angryAmount += (angryTarget - angryAmount) * 0.25f
        if (kotlin.math.abs(angryTarget - angryAmount) < 0.01f) angryAmount = angryTarget

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val eyeHeight = height * 0.28f * eyeHeightScale
        val eyeWidth = width * 0.22f * eyeWidthScale
        val spacing = width * 0.10f

        val centerY = height / 2f + lookY * height * 0.08f
        val shiftX = lookX * width * 0.06f

        val leftCenterX = width / 2f - spacing / 2f - eyeWidth / 2f + shiftX
        val rightCenterX = width / 2f + spacing / 2f + eyeWidth / 2f + shiftX

        // Pareho ang laki ng mata sa lahat ng mood; ang ALERT ay happy eyes na (tingnan ang drawEye)
        val currentEyeHeight = eyeHeight * (1f - blinkAmount).coerceAtLeast(0.06f)
        val cornerRadius = currentEyeHeight * 0.35f

        drawEye(canvas, leftCenterX, centerY, eyeWidth, currentEyeHeight, cornerRadius, isLeft = true)
        drawEye(canvas, rightCenterX, centerY, eyeWidth, currentEyeHeight, cornerRadius, isLeft = false)
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, radius: Float, isLeft: Boolean) {
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(rect, radius, radius, eyePaint)

        // HAPPY (inspired by FluxGarage RoboEyes): rounded na itim na "lower eyelid" ang
        // itinataas mula sa ibaba ng mata (hanggang kalahati ng taas) - lumalabas na arko/ngiti
        if (happyAmount > 0f) {
            val offset = h * happySmile * happyAmount
            val pad = w * 0.03f // bahagyang lampas sa gilid para walang manipis na sliver
            val cover = RectF(cx - w / 2f - pad, cy + h / 2f - offset, cx + w / 2f + pad, cy + h / 2f - offset + h)
            canvas.drawRoundRect(cover, radius, radius, backgroundPaint)
        }

        // ANGRY (inspired by FluxGarage RoboEyes): itim na tatsulok sa itaas ng mata na
        // mas mababa sa LOOB (ilong side) - kaliwang mata: baba sa kanan, kanang mata: baba sa kaliwa
        // kaya lumalabas na nakakunot ang noo (\ /)
        if (angryAmount > 0f) {
            val lidH = h * angryLid * angryAmount
            val left = cx - w / 2f - 2f
            val right = cx + w / 2f + 2f
            val top = cy - h / 2f - 2f
            lidPath.reset()
            lidPath.moveTo(left, top)
            lidPath.lineTo(right, top)
            if (isLeft) lidPath.lineTo(right, top + lidH + 2f) else lidPath.lineTo(left, top + lidH + 2f)
            lidPath.close()
            canvas.drawPath(lidPath, backgroundPaint)
        }
    }
}
