package com.example.facerobot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
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
 *  - Mga expression (Mood): IDLE (normal), ALERT (masaya), SEARCHING, ANGRY (galit, pula),
 *    SLEEPY (inaantok, may "z"), SURPRISED (gulat, may "!"), LOVE (pusong mata),
 *    SAD (malungkot, may luha), THINKING (nag-iisip, may tatlong tuldok),
 *    LISTENING (nakikinig, may gumagalaw na bars)
 *  - flashMood(): pansamantalang expression na kusang bumabalik sa dating mood
 *    (ginagamit kapag may natanggap na command)
 */
class RoboEyesView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * key = ang naka-save sa Mga Utos (CommandStore.expression). label = pangalan sa app.
     * Ang SEARCHING ay panloob na mood lang (hindi pinipili sa command).
     */
    enum class Mood(val key: String, val label: String) {
        IDLE("NORMAL", "Normal"),
        ALERT("HAPPY", "Masaya"),
        SEARCHING("SEARCHING", "Naghahanap"),
        ANGRY("ANGRY", "Galit"),
        SLEEPY("SLEEPY", "Inaantok"),
        SURPRISED("SURPRISED", "Gulat"),
        LOVE("LOVE", "In love"),
        SAD("SAD", "Malungkot"),
        THINKING("THINKING", "Nag-iisip"),
        LISTENING("LISTENING", "Nakikinig");

        companion object {
            fun fromKey(key: String): Mood? =
                values().firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
        }
    }

    // ---- Dito i-adjust ang itsura ----
    private val eyeWidthScale = 1.25f   // lapad ng mata (lahat ng mood). dati 1.15f
    private val eyeHeightScale = 1.10f  // taas ng mata (lahat ng mood)
    private val happySmile = 0.40f      // lalim ng ngiti ng happy eyes (mas mataas = mas malalim)
    private val angryLid = 0.55f        // baba ng "kilay" ng angry eyes (mas mataas = mas galit)
    private val sadLid = 0.50f          // baba ng "kilay" ng malungkot na mata (sa labas)

    private val cyan = intArrayOf(0, 229, 255)      // normal, parang matang robot
    private val red = intArrayOf(255, 77, 77)       // galit
    private val pink = intArrayOf(255, 92, 160)     // in love
    private val blue = intArrayOf(77, 163, 255)     // nakikinig
    private val purple = intArrayOf(179, 136, 255)  // nag-iisip
    private val sadBlue = intArrayOf(90, 170, 230)  // malungkot
    private val dim = intArrayOf(0, 170, 200)       // inaantok

    private val backgroundPaint = Paint().apply { color = Color.BLACK }
    private val lidPath = Path()
    private val heartPath = Path()
    private val eyeRect = RectF()
    private val eyePaint = Paint().apply {
        color = Color.rgb(cyan[0], cyan[1], cyan[2])
        isAntiAlias = true
    }
    private val extraPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private var mood = Mood.IDLE

    // Pansamantalang mood (hal. GALIT kapag may natanggap na command). Kusa itong nawawala pagkatapos
    // ng itinakdang oras at bumabalik ang mata sa dating mood - hindi na kailangang i-restore sa MainActivity.
    @Volatile private var overrideMood: Mood? = null
    @Volatile private var overrideUntilMs = 0L

    // 0f = wide open, 1f = fully closed (blink)
    private var blinkAmount = 0f

    // Smooth na transition ng bawat expression (0f = wala, 1f = buo)
    private var happyAmount = 0f
    private var angryAmount = 0f
    private var sleepyAmount = 0f
    private var sadAmount = 0f
    private var loveAmount = 0f
    private var wScale = 1f
    private var hScale = 1f
    private var roundK = 0.35f
    private var extraAmount = 0f // fade-in ng mga dagdag (z, luha, tuldok, bars, !)
    private var lastEffective = Mood.IDLE
    private val curColor = floatArrayOf(cyan[0].toFloat(), cyan[1].toFloat(), cyan[2].toFloat())

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

    /**
     * Ipakita ang [flash] mood nang [durationMs] milliseconds, tapos kusang babalik sa dating mood.
     * Ligtas tawagin kahit sa background thread.
     */
    fun flashMood(flash: Mood, durationMs: Long = 2000L) {
        overrideMood = flash
        overrideUntilMs = System.currentTimeMillis() + durationMs
        postInvalidate()
    }

    /** Agad na tanggalin ang pansamantalang expression (bumabalik sa dating mood). */
    fun clearFlash() {
        overrideMood = null
        overrideUntilMs = 0L
        postInvalidate()
    }

    private fun effectiveMood(now: Long): Mood {
        val o = overrideMood
        return if (o != null && now < overrideUntilMs) o else mood
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
        val sleepy = effectiveMood(System.currentTimeMillis()) == Mood.SLEEPY
        nextBlinkAtMs = System.currentTimeMillis() +
            if (sleepy) random.nextLong(3500, 6000) else random.nextLong(1400, 3800)
    }

    private fun scheduleNextLookChange() {
        nextLookChangeAtMs = System.currentTimeMillis() + random.nextLong(1500, 3800)
        lookTargetX = if (mood == Mood.SEARCHING) random.nextFloat() * 2f - 1f else (random.nextFloat() * 1.2f - 0.6f)
        lookTargetY = random.nextFloat() * 0.6f - 0.3f
    }

    private fun approach(cur: Float, target: Float, k: Float): Float {
        val v = cur + (target - cur) * k
        return if (abs(target - v) < 0.01f) target else v
    }

    private var blinkPhase = 0 // 0 idle, 1 closing, 2 opening
    private fun tick() {
        val now = System.currentTimeMillis()
        val m = effectiveMood(now)

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
        // May mga expression na may sariling titig: galit = diretso, nag-iisip = nakatingin sa taas-kanan
        when (m) {
            Mood.ANGRY -> { lookTargetX = 0f; lookTargetY = 0f }
            Mood.THINKING -> { lookTargetX = 0.7f; lookTargetY = -0.9f }
            else -> {}
        }
        lookX += (lookTargetX - lookX) * 0.07f
        lookY += (lookTargetY - lookY) * 0.07f

        // Smooth na pagpasok/paglabas ng bawat expression
        happyAmount = approach(happyAmount, if (m == Mood.ALERT) 1f else 0f, 0.2f)
        angryAmount = approach(angryAmount, if (m == Mood.ANGRY) 1f else 0f, 0.25f)
        sleepyAmount = approach(sleepyAmount, if (m == Mood.SLEEPY) 0.62f else 0f, 0.12f)
        sadAmount = approach(sadAmount, if (m == Mood.SAD) 1f else 0f, 0.14f)
        loveAmount = approach(loveAmount, if (m == Mood.LOVE) 1f else 0f, 0.14f)

        val colorT: IntArray
        var wT = 1f
        var hT = 1f
        var rT = 0.35f
        when (m) {
            Mood.ANGRY -> colorT = red
            Mood.SLEEPY -> { colorT = dim; hT = 0.9f }
            Mood.SURPRISED -> { colorT = cyan; wT = 0.72f; hT = 1.3f; rT = 0.5f }
            Mood.LOVE -> colorT = pink
            Mood.SAD -> colorT = sadBlue
            Mood.THINKING -> { colorT = purple; hT = 0.8f }
            Mood.LISTENING -> { colorT = blue; wT = 1.1f; hT = 1.08f }
            else -> colorT = cyan
        }
        wScale += (wT - wScale) * 0.14f
        hScale += (hT - hScale) * 0.14f
        roundK += (rT - roundK) * 0.14f
        for (i in 0..2) curColor[i] += (colorT[i] - curColor[i]) * 0.14f

        if (m != lastEffective) {
            lastEffective = m
            extraAmount = 0f
        }
        extraAmount += (1f - extraAmount) * 0.14f

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val now = System.currentTimeMillis()
        val m = effectiveMood(now)
        val flashing = overrideMood != null && now < overrideUntilMs

        val eyeWidth = width * 0.22f * eyeWidthScale * wScale
        val eyeHeight = height * 0.28f * eyeHeightScale * hScale * (1f - blinkAmount).coerceAtLeast(0.06f)
        val spacing = width * 0.10f

        // Bumababa nang bahagya ang mata at humihinga habang inaantok
        val sleepyEff = if (sleepyAmount > 0.01f)
            (sleepyAmount + 0.07f * sin(now / 650.0).toFloat()).coerceAtLeast(0f) else 0f

        val centerY = height / 2f + lookY * height * 0.08f + sleepyEff * height * 0.05f
        // Bahagyang nanginginig (shake) ang mata habang galit na galit (command flash)
        val shake = if (flashing && angryAmount > 0.3f)
            (sin(now / 32.0) * width * 0.005 * angryAmount).toFloat() else 0f
        val shiftX = lookX * width * 0.06f + shake

        val leftCenterX = width / 2f - spacing / 2f - eyeWidth / 2f + shiftX
        val rightCenterX = width / 2f + spacing / 2f + eyeWidth / 2f + shiftX
        val cornerRadius = min(eyeWidth, eyeHeight) * roundK

        eyePaint.color = Color.rgb(curColor[0].toInt(), curColor[1].toInt(), curColor[2].toInt())
        val pulse = 1f + 0.08f * sin(now / 170.0).toFloat()

        drawEye(canvas, leftCenterX, centerY, eyeWidth, eyeHeight, cornerRadius, isLeft = true, sleepyEff, pulse)
        drawEye(canvas, rightCenterX, centerY, eyeWidth, eyeHeight, cornerRadius, isLeft = false, sleepyEff, pulse)

        drawExtras(canvas, m, now, leftCenterX, centerY, eyeWidth, eyeHeight)
    }

    private fun drawEye(
        canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, radius: Float,
        isLeft: Boolean, sleepyEff: Float, pulse: Float
    ) {
        val left = cx - w / 2f
        val right = cx + w / 2f
        val top = cy - h / 2f
        val bottom = cy + h / 2f

        // Karaniwang mata (kumukupas habang nagiging puso)
        if (loveAmount < 0.98f) {
            eyePaint.alpha = ((1f - loveAmount) * 255).toInt()
            eyeRect.set(left, top, right, bottom)
            canvas.drawRoundRect(eyeRect, radius, radius, eyePaint)
        }
        // LOVE: pusong mata na tumitibok
        if (loveAmount > 0.02f) {
            eyePaint.alpha = (loveAmount * 255).toInt()
            val s = w * 0.95f * pulse
            heartPath.reset()
            heartPath.moveTo(cx, cy + s * 0.45f)
            heartPath.cubicTo(cx - s * 0.9f, cy - s * 0.05f, cx - s * 0.55f, cy - s * 0.75f, cx, cy - s * 0.3f)
            heartPath.cubicTo(cx + s * 0.55f, cy - s * 0.75f, cx + s * 0.9f, cy - s * 0.05f, cx, cy + s * 0.45f)
            heartPath.close()
            canvas.drawPath(heartPath, eyePaint)
        }
        eyePaint.alpha = 255

        // HAPPY (inspired by FluxGarage RoboEyes): rounded na itim na "lower eyelid"
        // na itinataas mula sa ibaba ng mata - lumalabas na arko/ngiti
        if (happyAmount > 0f) {
            val offset = h * happySmile * happyAmount
            val pad = w * 0.03f
            eyeRect.set(left - pad, bottom - offset, right + pad, bottom - offset + h)
            canvas.drawRoundRect(eyeRect, radius, radius, backgroundPaint)
        }

        // ANGRY: itim na tatsulok sa itaas na mas mababa sa LOOB (ilong side) - nakakunot ang noo (\ /)
        if (angryAmount > 0f) {
            val lidH = h * angryLid * angryAmount
            lidPath.reset()
            lidPath.moveTo(left - 2f, top - 2f)
            lidPath.lineTo(right + 2f, top - 2f)
            if (isLeft) lidPath.lineTo(right + 2f, top + lidH + 2f) else lidPath.lineTo(left - 2f, top + lidH + 2f)
            lidPath.close()
            canvas.drawPath(lidPath, backgroundPaint)
        }

        // SAD: kabaligtaran ng galit - mas mababa ang LABAS na bahagi ng kilay (/ \)
        if (sadAmount > 0f) {
            val lidH = h * sadLid * sadAmount
            lidPath.reset()
            lidPath.moveTo(left - 2f, top - 2f)
            lidPath.lineTo(right + 2f, top - 2f)
            if (isLeft) lidPath.lineTo(left - 2f, top + lidH + 2f) else lidPath.lineTo(right + 2f, top + lidH + 2f)
            lidPath.close()
            canvas.drawPath(lidPath, backgroundPaint)
        }

        // SLEEPY: bumababang talukap mula sa itaas
        if (sleepyEff > 0.01f) {
            canvas.drawRect(left - 2f, top - 2f, right + 2f, top + h * sleepyEff, backgroundPaint)
        }
    }

    /** Mga dagdag na iginuguhit sa labas ng mata: z, luha, tuldok, bars, tandang padamdam. */
    private fun drawExtras(canvas: Canvas, m: Mood, now: Long, leftCx: Float, cy: Float, ew: Float, eh: Float) {
        val a = extraAmount.coerceIn(0f, 1f)
        val w = width.toFloat()
        val h = height.toFloat()
        extraPaint.style = Paint.Style.FILL

        when (m) {
            Mood.SLEEPY -> {
                extraPaint.textSize = h * 0.09f
                for (j in 0..2) {
                    val ph = ((now / 45.0 + j * 38) % 110.0).toFloat() / 110f
                    extraPaint.color = Color.argb(((1f - ph) * a * 255).toInt().coerceIn(0, 255), dim[0], dim[1], dim[2])
                    canvas.drawText("z", w * 0.8f + j * w * 0.025f + ph * w * 0.022f, h * 0.34f - ph * h * 0.19f, extraPaint)
                }
            }
            Mood.SAD -> {
                val ph = ((now / 16.0) % 70.0).toFloat() / 70f
                extraPaint.color = Color.argb(((1f - ph * 0.8f) * a * 255).toInt().coerceIn(0, 255), 120, 190, 255)
                val tx = leftCx - ew * 0.3f
                val ty = cy + eh / 2f + ph * h * 0.22f
                eyeRect.set(tx - h * 0.02f, ty - h * 0.033f, tx + h * 0.02f, ty + h * 0.033f)
                canvas.drawOval(eyeRect, extraPaint)
            }
            Mood.THINKING -> {
                extraPaint.color = Color.argb((a * 255).toInt().coerceIn(0, 255), purple[0], purple[1], purple[2])
                for (j in 0..2) {
                    val by = abs(sin(now / 230.0 - j * 0.7)).toFloat() * h * 0.05f
                    canvas.drawCircle(w * 0.76f + j * w * 0.031f, h * 0.2f - by, h * 0.02f, extraPaint)
                }
            }
            Mood.LISTENING -> {
                extraPaint.color = Color.argb((a * 255).toInt().coerceIn(0, 255), blue[0], blue[1], blue[2])
                val barW = w * 0.0125f
                for (j in 0..4) {
                    val bh = h * 0.04f + abs(sin(now / 150.0 + j * 1.1)).toFloat() * h * 0.115f
                    val bx = w / 2f + (j - 2) * w * 0.028f
                    eyeRect.set(bx - barW / 2f, h * 0.9f - bh, bx + barW / 2f, h * 0.9f)
                    canvas.drawRoundRect(eyeRect, barW / 2f, barW / 2f, extraPaint)
                }
            }
            Mood.SURPRISED -> {
                extraPaint.textSize = h * 0.14f
                extraPaint.color = Color.argb((a * 255).toInt().coerceIn(0, 255), cyan[0], cyan[1], cyan[2])
                canvas.drawText("!", w * 0.8f, h * 0.3f + sin(now / 120.0).toFloat() * 3f, extraPaint)
            }
            else -> {}
        }
    }
}
