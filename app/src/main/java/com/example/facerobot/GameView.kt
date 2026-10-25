package com.example.facerobot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Mga pangyayari sa laro na pwedeng reaksyunan ng robot (boses, mata). */
sealed class GameEvent {
    object Started : GameEvent()
    data class Combo(val level: Int) : GameEvent()
    data class BigClear(val count: Int) : GameEvent()
    object InvalidMove : GameEvent()
    object Shuffled : GameEvent()
    data class GameOver(val score: Int, val best: Int, val newBest: Boolean) : GameEvent()
}

/**
 * Ang buong screen ng Candy-Crush-style na laro: board, animation, HUD, at input.
 *
 * Input (iisa ang logic para sa lahat):
 *   - KAMAY (MediaPipe): onHandState(). Pinch = "hawak" ang candy sa ilalim ng pointer; i-drag papunta sa katabi = palit.
 *   - TOUCH (hawakan at i-drag ng daliri): para ma-test kahit walang hand model.
 *   - "✕" sa kanang-taas = labas (touch o pinch).
 */
class GameView(context: Context) : View(context) {

    companion object {
        const val ROWS = 8
        const val COLS = 8
        const val KINDS = 6
        const val START_MOVES = 30

        private val COLORS = intArrayOf(
            0xFFE53935.toInt(),   // pula  - bilog
            0xFF1E88E5.toInt(),   // asul  - parisukat
            0xFF43A047.toInt(),   // berde - tatsulok
            0xFFFDD835.toInt(),   // dilaw - diamond
            0xFF8E24AA.toInt(),   // lila  - heksagono
            0xFFFB8C00.toInt()    // orange - bituin
        )
        private const val BG = 0xFF0B1020.toInt()
        private const val PANEL = 0xFF18213B.toInt()
    }

    var onExitRequested: (() -> Unit)? = null
    var onGameEvent: ((GameEvent) -> Unit)? = null
    var onBestScoreChanged: ((Int) -> Unit)? = null
    var bestScore = 0

    /** Kasalukuyang score ng laro (para sa boses ng robot pagkalabas). */
    val currentScore: Int get() = game.score

    /** Teksto ng estado ng kamay (ipinapakita sa kanang bahagi). Itakda ng MainActivity. */
    var handStatus: String = ""
        set(value) { field = value; invalidate() }

    private val game = MatchGame(ROWS, COLS, KINDS)
    private val d = resources.displayMetrics.density

    // ---- animation state (sa yunit ng "cell") ----
    private val offX = Array(ROWS) { FloatArray(COLS) }
    private val offY = Array(ROWS) { FloatArray(COLS) }
    private val fallStart = Array(ROWS) { FloatArray(COLS) }
    private val scl = Array(ROWS) { FloatArray(COLS) { 1f } }
    private var clearing: Set<Int> = emptySet()
    private var clearProgress = 0f
    private var animator: ValueAnimator? = null
    private var animToken = 0
    private var busy = false
    private var gameOver = false

    private data class Popup(val cx: Float, val cy: Float, val text: String, val born: Long)
    private val popups = ArrayList<Popup>()

    // ---- layout ----
    private var cell = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private val boardRect = RectF()
    private val exitRect = RectF()

    // ---- pointer (kamay o daliri) ----
    private var handVisible = false
    private var handPinch = false
    private var handX = 0f
    private var handY = 0f
    private var selected = -1
    private var startX = 0f
    private var startY = 0f
    private var swipeConsumed = false

    // ---- paints ----
    private val candyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f * d
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val shapePaths: Array<Path> = Array(KINDS) { buildShape(it) }

    private fun buildShape(kind: Int): Path {
        val p = Path()
        when (kind) {
            0 -> p.addCircle(0f, 0f, 0.86f, Path.Direction.CW)
            1 -> p.addRoundRect(RectF(-0.78f, -0.78f, 0.78f, 0.78f), 0.28f, 0.28f, Path.Direction.CW)
            2 -> {
                p.moveTo(0f, -0.92f); p.lineTo(0.9f, 0.72f); p.lineTo(-0.9f, 0.72f); p.close()
            }
            3 -> {
                p.moveTo(0f, -0.96f); p.lineTo(0.8f, 0f); p.lineTo(0f, 0.96f); p.lineTo(-0.8f, 0f); p.close()
            }
            4 -> {
                for (i in 0 until 6) {
                    val a = Math.PI / 3.0 * i - Math.PI / 2.0
                    val x = (cos(a) * 0.9).toFloat()
                    val y = (sin(a) * 0.9).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            else -> {
                for (i in 0 until 10) {
                    val a = Math.PI / 5.0 * i - Math.PI / 2.0
                    val rad = if (i % 2 == 0) 0.96 else 0.42
                    val x = (cos(a) * rad).toFloat()
                    val y = (sin(a) * rad).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
        }
        return p
    }

    // =====================================================================================
    // PUBLIC
    // =====================================================================================

    fun startNewGame() {
        animToken++
        animator?.cancel()
        game.newGame(START_MOVES)
        gameOver = false
        busy = true
        selected = -1
        clearing = emptySet()
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            offX[r][c] = 0f; offY[r][c] = 0f; scl[r][c] = 0f
        }
        popups.clear()
        onGameEvent?.invoke(GameEvent.Started)
        // pop-in ng lahat ng candy
        runAnim(380L, DecelerateInterpolator(), { p ->
            for (r in 0 until ROWS) for (c in 0 until COLS) scl[r][c] = p
        }) {
            busy = false
        }
    }

    fun stopAnimations() {
        animToken++
        animator?.cancel()
        busy = false
        handVisible = false
        handPinch = false
        selected = -1
    }

    fun resetBest() {
        bestScore = 0
        onBestScoreChanged?.invoke(0)
        invalidate()
    }

    /** Mula sa MediaPipe (tawagin sa UI thread). null = walang nakitang kamay. */
    fun onHandState(s: HandState?) {
        if (s == null) {
            if (handVisible) {
                handVisible = false
                if (handPinch) { handPinch = false; pointerUp() }
                invalidate()
            }
            return
        }
        handVisible = true
        // Ang gitnang 84% ng frame = buong screen, para madaling abutin ang mga gilid
        val nx = ((s.x - 0.08f) / 0.84f).coerceIn(0f, 1f)
        val ny = ((s.y - 0.08f) / 0.84f).coerceIn(0f, 1f)
        handX = nx * width
        handY = ny * height
        if (s.pinching && !handPinch) {
            handPinch = true
            pointerDown(handX, handY)
        } else if (s.pinching && handPinch) {
            pointerMove(handX, handY)
        } else if (!s.pinching && handPinch) {
            handPinch = false
            pointerUp()
        }
        invalidate()
    }

    // =====================================================================================
    // INPUT
    // =====================================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pointerDown(event.x, event.y); return true }
            MotionEvent.ACTION_MOVE -> { pointerMove(event.x, event.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pointerUp(); return true }
        }
        return super.onTouchEvent(event)
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (cell <= 0f || !boardRect.contains(x, y)) return -1
        val c = ((x - boardLeft) / cell).toInt()
        val r = ((y - boardTop) / cell).toInt()
        return if (r in 0 until ROWS && c in 0 until COLS) r * COLS + c else -1
    }

    private fun pointerDown(x: Float, y: Float) {
        if (exitRect.contains(x, y)) {
            onExitRequested?.invoke()
            return
        }
        if (gameOver) {
            startNewGame()
            return
        }
        if (busy) return
        selected = cellAt(x, y)
        startX = x
        startY = y
        swipeConsumed = false
        invalidate()
    }

    private fun pointerMove(x: Float, y: Float) {
        if (selected < 0 || swipeConsumed || busy || gameOver) return
        val dx = x - startX
        val dy = y - startY
        val threshold = cell * 0.45f
        if (max(abs(dx), abs(dy)) < threshold) return
        val r1 = selected / COLS
        val c1 = selected % COLS
        var r2 = r1
        var c2 = c1
        if (abs(dx) > abs(dy)) {
            c2 += if (dx > 0) 1 else -1
        } else {
            r2 += if (dy > 0) 1 else -1
        }
        swipeConsumed = true
        selected = -1
        if (game.inBounds(r2, c2)) attemptSwap(r1, c1, r2, c2)
        invalidate()
    }

    private fun pointerUp() {
        selected = -1
        invalidate()
    }

    // =====================================================================================
    // GAME FLOW + ANIMATION
    // =====================================================================================

    private fun runAnim(
        durationMs: Long,
        interpolator: TimeInterpolator,
        onUpdate: (Float) -> Unit,
        onEnd: () -> Unit
    ) {
        val token = ++animToken
        animator?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = durationMs
        a.interpolator = interpolator
        a.addUpdateListener {
            if (token == animToken) {
                onUpdate(it.animatedValue as Float)
                invalidate()
            }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (token == animToken) {
                    onUpdate(1f)
                    onEnd()
                    invalidate()
                }
            }
        })
        animator = a
        a.start()
    }

    private fun setSwapOffsets(r1: Int, c1: Int, r2: Int, c2: Int, p: Float) {
        val dx = (c2 - c1).toFloat()
        val dy = (r2 - r1).toFloat()
        offX[r1][c1] = dx * p; offY[r1][c1] = dy * p
        offX[r2][c2] = -dx * p; offY[r2][c2] = -dy * p
    }

    private fun clearSwapOffsets(r1: Int, c1: Int, r2: Int, c2: Int) {
        offX[r1][c1] = 0f; offY[r1][c1] = 0f
        offX[r2][c2] = 0f; offY[r2][c2] = 0f
    }

    private fun attemptSwap(r1: Int, c1: Int, r2: Int, c2: Int) {
        if (busy || gameOver || !game.adjacent(r1, c1, r2, c2)) return
        val valid = game.swapCreatesMatch(r1, c1, r2, c2)
        busy = true
        runAnim(170L, LinearInterpolator(), { p -> setSwapOffsets(r1, c1, r2, c2, p) }) {
            if (valid) {
                clearSwapOffsets(r1, c1, r2, c2)
                game.commitSwap(r1, c1, r2, c2)
                resolveCascade(1)
            } else {
                onGameEvent?.invoke(GameEvent.InvalidMove)
                runAnim(170L, LinearInterpolator(), { p -> setSwapOffsets(r1, c1, r2, c2, 1f - p) }) {
                    clearSwapOffsets(r1, c1, r2, c2)
                    busy = false
                }
            }
        }
    }

    private fun resolveCascade(combo: Int) {
        val matches = game.findMatches()
        if (matches.isEmpty()) {
            afterResolve()
            return
        }
        clearing = matches
        clearProgress = 0f
        runAnim(230L, LinearInterpolator(), { p -> clearProgress = p }) {
            val points = game.clear(matches, combo)
            // lumulutang na "+puntos" sa gitna ng mga nabura
            var sr = 0f
            var sc = 0f
            for (idx in matches) { sr += (idx / COLS); sc += (idx % COLS) }
            popups.add(Popup(sc / matches.size + 0.5f, sr / matches.size + 0.5f, "+$points", System.currentTimeMillis()))
            clearing = emptySet()
            if (combo >= 2) onGameEvent?.invoke(GameEvent.Combo(combo))
            if (matches.size >= 6) onGameEvent?.invoke(GameEvent.BigClear(matches.size))

            val falls = game.applyGravity()
            var maxDist = 0
            for (f in falls) {
                val start = (f.fromRow - f.toRow).toFloat()
                fallStart[f.toRow][f.col] = start
                offY[f.toRow][f.col] = start
                maxDist = max(maxDist, f.toRow - f.fromRow)
            }
            runAnim(max(200L, maxDist * 75L), AccelerateInterpolator(1.1f), { p ->
                for (f in falls) offY[f.toRow][f.col] = fallStart[f.toRow][f.col] * (1f - p)
            }) {
                for (f in falls) { offY[f.toRow][f.col] = 0f; fallStart[f.toRow][f.col] = 0f }
                resolveCascade(combo + 1)
            }
        }
    }

    private fun afterResolve() {
        if (game.movesLeft <= 0) {
            gameOver = true
            busy = false
            val newBest = game.score > bestScore
            if (newBest) {
                bestScore = game.score
                onBestScoreChanged?.invoke(bestScore)
            }
            onGameEvent?.invoke(GameEvent.GameOver(game.score, bestScore, newBest))
            return
        }
        if (!game.hasPossibleMove()) {
            game.shuffleBoard()
            onGameEvent?.invoke(GameEvent.Shuffled)
            for (r in 0 until ROWS) for (c in 0 until COLS) scl[r][c] = 0f
            runAnim(380L, DecelerateInterpolator(), { p ->
                for (r in 0 until ROWS) for (c in 0 until COLS) scl[r][c] = p
            }) {
                busy = false
            }
            return
        }
        busy = false
    }

    // =====================================================================================
    // DRAWING
    // =====================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = h * 0.92f
        cell = side / ROWS
        boardLeft = (w - side) / 2f + w * 0.04f
        boardTop = (h - side) / 2f
        boardRect.set(boardLeft, boardTop, boardLeft + side, boardTop + side)
        exitRect.set(w - 60f * d, 8f * d, w - 8f * d, 60f * d)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BG)
        drawHud(canvas)

        // board
        canvas.drawRoundRect(boardRect, 18f * d, 18f * d, panelPaint)
        canvas.save()
        canvas.clipRect(boardRect)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if ((r + c) % 2 == 0) {
                canvas.drawRect(
                    boardLeft + c * cell, boardTop + r * cell,
                    boardLeft + (c + 1) * cell, boardTop + (r + 1) * cell, cellPaint
                )
            }
        }
        val radius = cell * 0.40f
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val k = game.board[r][c]
            if (k == MatchGame.EMPTY) continue
            var s = scl[r][c]
            var a = 255
            if (clearing.contains(r * COLS + c)) {
                s *= (1f - clearProgress)
                a = (255f * (1f - clearProgress)).toInt()
            }
            if (s <= 0.01f) continue
            val cx = boardLeft + (c + 0.5f + offX[r][c]) * cell
            val cy = boardTop + (r + 0.5f + offY[r][c]) * cell
            drawCandy(canvas, k, cx, cy, radius * s, a)
        }
        // napiling cell
        if (selected >= 0) {
            val sr = selected / COLS
            val sc = selected % COLS
            canvas.drawRoundRect(
                boardLeft + sc * cell + 3f * d, boardTop + sr * cell + 3f * d,
                boardLeft + (sc + 1) * cell - 3f * d, boardTop + (sr + 1) * cell - 3f * d,
                10f * d, 10f * d, selPaint
            )
        }
        canvas.restore()

        drawPopups(canvas)
        drawExitButton(canvas)
        if (gameOver) drawGameOver(canvas)
        drawCursor(canvas)

        if (popups.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawCandy(canvas: Canvas, kind: Int, cx: Float, cy: Float, radius: Float, alpha: Int) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        candyPaint.color = COLORS[kind]
        candyPaint.alpha = alpha
        canvas.drawPath(shapePaths[kind], candyPaint)
        hiPaint.alpha = (alpha * 0.30f).toInt()
        canvas.drawCircle(-0.28f, -0.34f, 0.26f, hiPaint)
        canvas.restore()
    }

    private fun drawHud(canvas: Canvas) {
        val h = height.toFloat()
        val left = 20f * d
        textPaint.textAlign = Paint.Align.LEFT

        textPaint.color = 0xFF8FA0C8.toInt()
        textPaint.textSize = h * 0.045f
        canvas.drawText("SCORE", left, h * 0.18f, textPaint)
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textSize = h * 0.11f
        canvas.drawText(game.score.toString(), left, h * 0.30f, textPaint)

        textPaint.color = 0xFF8FA0C8.toInt()
        textPaint.textSize = h * 0.045f
        canvas.drawText("MOVES", left, h * 0.46f, textPaint)
        textPaint.color = if (game.movesLeft <= 5) 0xFFFF7043.toInt() else 0xFFFFFFFF.toInt()
        textPaint.textSize = h * 0.11f
        canvas.drawText(game.movesLeft.toString(), left, h * 0.58f, textPaint)

        textPaint.color = 0xFF8FA0C8.toInt()
        textPaint.textSize = h * 0.045f
        canvas.drawText("BEST", left, h * 0.74f, textPaint)
        textPaint.color = 0xFFFFD54F.toInt()
        textPaint.textSize = h * 0.075f
        canvas.drawText(bestScore.toString(), left, h * 0.83f, textPaint)

        // kanan: estado ng kamay at gabay
        val right = boardRect.right + 16f * d
        textPaint.color = if (handVisible) 0xFF66BB6A.toInt() else 0xFFFFB74D.toInt()
        textPaint.textSize = h * 0.04f
        drawWrapped(canvas, handStatus, right, h * 0.30f, width - right - 12f * d, textPaint)
        textPaint.color = 0xFF8FA0C8.toInt()
        textPaint.textSize = h * 0.034f
        drawWrapped(
            canvas,
            "Pinch (thumb + index) sa candy, tapos i-drag papunta sa katabi para magpalit.",
            right, h * 0.50f, width - right - 12f * d, textPaint
        )
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint) {
        if (maxWidth <= 0f) return
        textPaint.textAlign = Paint.Align.LEFT
        var line = StringBuilder()
        var yy = y
        for (word in text.split(" ")) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(trial) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line.toString(), x, yy, paint)
                yy += paint.textSize * 1.3f
                line = StringBuilder(word)
            } else {
                line = StringBuilder(trial)
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line.toString(), x, yy, paint)
    }

    private fun drawPopups(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val it = popups.iterator()
        textPaint.textAlign = Paint.Align.CENTER
        while (it.hasNext()) {
            val p = it.next()
            val t = (now - p.born) / 900f
            if (t >= 1f) { it.remove(); continue }
            textPaint.color = 0xFFFFFFFF.toInt()
            textPaint.alpha = ((1f - t) * 255f).toInt()
            textPaint.textSize = cell * 0.55f
            val x = boardLeft + p.cx * cell
            val y = boardTop + (p.cy - t * 1.2f) * cell
            canvas.drawText(p.text, x, y, textPaint)
        }
        textPaint.alpha = 255
    }

    private fun drawExitButton(canvas: Canvas) {
        cursorFill.color = 0x44FFFFFF
        canvas.drawRoundRect(exitRect, 12f * d, 12f * d, cursorFill)
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = exitRect.height() * 0.6f
        canvas.drawText("✕", exitRect.centerX(), exitRect.centerY() + textPaint.textSize * 0.33f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawGameOver(canvas: Canvas) {
        cursorFill.color = 0xCC000000.toInt()
        canvas.drawRoundRect(boardRect, 18f * d, 18f * d, cursorFill)
        textPaint.textAlign = Paint.Align.CENTER
        val cx = boardRect.centerX()
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textSize = cell * 0.9f
        canvas.drawText("TAPOS NA!", cx, boardRect.top + cell * 3f, textPaint)
        textPaint.color = 0xFFFFD54F.toInt()
        textPaint.textSize = cell * 0.8f
        canvas.drawText("Score: ${game.score}", cx, boardRect.top + cell * 4.4f, textPaint)
        textPaint.color = 0xFF8FA0C8.toInt()
        textPaint.textSize = cell * 0.42f
        canvas.drawText("Best: $bestScore", cx, boardRect.top + cell * 5.3f, textPaint)
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textSize = cell * 0.40f
        canvas.drawText("Pinch o i-tap para maglaro ulit", cx, boardRect.top + cell * 6.5f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawCursor(canvas: Canvas) {
        if (!handVisible) return
        val rad = cell * (if (handPinch) 0.26f else 0.34f)
        cursorFill.color = if (handPinch) 0x88FFFFFF.toInt() else 0x33FFFFFF
        canvas.drawCircle(handX, handY, rad, cursorFill)
        cursorStroke.color = if (handPinch) 0xFF66BB6A.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawCircle(handX, handY, rad, cursorStroke)
    }

    override fun onDetachedFromWindow() {
        animToken++
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
