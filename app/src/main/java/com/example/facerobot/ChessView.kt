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
import android.view.animation.DecelerateInterpolator
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

enum class ChessResult { USER_WON, ROBOT_WON, DRAW }

/** Mga pangyayari sa chess na pwedeng reaksyunan ng robot (boses, shake). */
sealed class ChessEvent {
    object Started : ChessEvent()
    data class UserMove(val captured: Boolean, val givesCheck: Boolean) : ChessEvent()
    /** Nagsisimula nang gumalaw ang robot ("Ito ang tira ko"). mate = ito ang checkmate sa iyo. */
    data class RobotMove(val captured: Boolean, val givesCheck: Boolean, val mate: Boolean) : ChessEvent()
    /** Nakarating na ang piyesa ng robot (dito ang shake kapag nakakain). */
    data class RobotLanded(val captured: Boolean) : ChessEvent()
    object IllegalMove : ChessEvent()
    data class GameOver(val result: ChessResult, val detail: String) : ChessEvent()
}

/**
 * Buong screen ng chess laban sa robot.
 *
 * Kontrol (kamay o daliri):
 *   - PINCH sa sarili mong piyesa, i-DRAG sa parisukat na pupuntahan, bitawan = galaw.
 *   - O PINCH ang piyesa (bitawan nang hindi gumagalaw), tapos PINCH ang parisukat na pupuntahan (may mga tuldok na
 *     nagpapakita ng legal na galaw) - mas madali kapag nanginginig ang kamay.
 *   - Promotion: awtomatikong reyna.
 */
class ChessView(context: Context) : View(context) {

    companion object {
        private const val LIGHT = 0xFFEEEED2.toInt()
        private const val DARK = 0xFF769656.toInt()
        private const val BG = 0xFF0B1020.toInt()
        private const val PANEL = 0xFF18213B.toInt()
        private const val MIN_THINK_MS = 700L
    }

    var onExitRequested: (() -> Unit)? = null
    var onChessEvent: ((ChessEvent) -> Unit)? = null

    /** Totoo kung nagsasalita pa ang robot - hihintayin muna bago gumalaw ang robot (para hindi maputol ang boses). */
    var speechBusy: () -> Boolean = { false }

    var userIsWhite = true
    var level = 3

    var handStatus: String = ""
        set(value) { field = value; invalidate() }

    // ---- laro ----
    private var pos = Chess.Position.initial()
    private val seen = HashMap<String, Int>()
    private var lastFrom = -1
    private var lastTo = -1
    private var gameOver = false
    private var result = ChessResult.DRAW
    private var gameGen = 0

    private var thinking = false
    private var robotMovesPending = false
    private val ai = ChessAI()
    private val aiExecutor = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }

    // ---- napili / drag ----
    private var selected = -1
    private var legalDests: Set<Int> = emptySet()
    private var dragging = false
    private var dragMoved = false
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f

    // ---- galaw ng robot (animation) ----
    private var animating = false
    private var animFrom = -1
    private var animTo = -1
    private var animPiece = 0
    private var animProgress = 0f
    private var animator: ValueAnimator? = null
    private var animToken = 0

    // ---- layout ----
    private val d = resources.displayMetrics.density
    private var cell = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private val boardRect = RectF()
    private val exitRect = RectF()

    // ---- kamay ----
    private var handVisible = false
    private var handPinch = false
    private var handX = 0f
    private var handY = 0f

    // ---- paints ----
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }

    // =====================================================================================
    // MGA HUGIS NG PIYESA (vector - hindi umaasa sa font ng phone)
    // =====================================================================================

    private class Shape(val parts: List<Path>, val details: List<Path>)

    private val shapes: Array<Shape> = Array(7) { buildShape(it) }

    private fun poly(vararg pts: Float): Path {
        val p = Path()
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
        p.close()
        return p
    }

    private fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float): Path =
        Path().apply { addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW) }

    private fun circ(x: Float, y: Float, r: Float): Path = Path().apply { addCircle(x, y, r, Path.Direction.CW) }

    private fun buildShape(type: Int): Shape {
        val base = rrect(-0.55f, 0.58f, 0.55f, 0.84f, 0.10f)
        return when (type) {
            Chess.PAWN -> Shape(
                listOf(circ(0f, -0.42f, 0.27f), poly(-0.20f, -0.20f, 0.20f, -0.20f, 0.40f, 0.60f, -0.40f, 0.60f), base),
                emptyList()
            )
            Chess.ROOK -> Shape(
                listOf(
                    rrect(-0.36f, -0.25f, 0.36f, 0.60f, 0.03f),
                    rrect(-0.50f, -0.44f, 0.50f, -0.22f, 0.03f),
                    rrect(-0.50f, -0.74f, -0.26f, -0.42f, 0.03f),
                    rrect(-0.12f, -0.74f, 0.12f, -0.42f, 0.03f),
                    rrect(0.26f, -0.74f, 0.50f, -0.42f, 0.03f),
                    base
                ),
                emptyList()
            )
            Chess.BISHOP -> Shape(
                listOf(
                    Path().apply { addOval(RectF(-0.27f, -0.74f, 0.27f, 0.02f), Path.Direction.CW) },
                    circ(0f, -0.84f, 0.10f),
                    poly(-0.15f, -0.05f, 0.15f, -0.05f, 0.40f, 0.60f, -0.40f, 0.60f),
                    rrect(-0.32f, -0.10f, 0.32f, 0.07f, 0.04f),
                    base
                ),
                listOf(Path().apply { moveTo(-0.10f, -0.52f); lineTo(0.12f, -0.28f) })
            )
            Chess.KNIGHT -> Shape(
                listOf(
                    poly(
                        -0.42f, 0.60f, -0.36f, 0.12f, -0.60f, -0.10f, -0.60f, -0.30f, -0.36f, -0.46f,
                        -0.30f, -0.80f, -0.10f, -0.62f, 0.12f, -0.72f, 0.38f, -0.38f, 0.46f, 0.15f, 0.42f, 0.60f
                    ),
                    base
                ),
                listOf(circ(-0.28f, -0.36f, 0.03f))
            )
            Chess.QUEEN -> Shape(
                listOf(
                    poly(
                        -0.46f, 0.60f, -0.55f, -0.42f, -0.33f, -0.08f, -0.27f, -0.62f, -0.10f, -0.10f, 0f, -0.72f,
                        0.10f, -0.10f, 0.27f, -0.62f, 0.33f, -0.08f, 0.55f, -0.42f, 0.46f, 0.60f
                    ),
                    circ(-0.55f, -0.50f, 0.085f), circ(-0.27f, -0.70f, 0.085f), circ(0f, -0.80f, 0.085f),
                    circ(0.27f, -0.70f, 0.085f), circ(0.55f, -0.50f, 0.085f),
                    base
                ),
                emptyList()
            )
            Chess.KING -> Shape(
                listOf(
                    poly(
                        -0.46f, 0.60f, -0.42f, 0.0f, -0.46f, -0.25f, -0.20f, -0.40f, 0.20f, -0.40f,
                        0.46f, -0.25f, 0.42f, 0.0f, 0.46f, 0.60f
                    ),
                    rrect(-0.07f, -0.96f, 0.07f, -0.38f, 0.02f),
                    rrect(-0.24f, -0.80f, 0.24f, -0.66f, 0.02f),
                    base
                ),
                emptyList()
            )
            else -> Shape(emptyList(), emptyList())
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Int, cx: Float, cy: Float, size: Float) {
        val t = abs(piece)
        if (t < 1 || t > 6) return
        val white = piece > 0
        val fill = if (white) 0xFFF8F8F8.toInt() else 0xFF2A2A2A.toInt()
        val outline = if (white) 0xFF1B1B1B.toInt() else 0xFFE6E6E6.toInt()
        val shape = shapes[t]
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(size, size)
        // 1) balangkas muna (makapal), 2) saka ang laman - kaya balangkas lang ng buong hugis ang makikita
        piecePaint.style = Paint.Style.STROKE
        piecePaint.strokeWidth = 0.11f
        piecePaint.color = outline
        for (p in shape.parts) canvas.drawPath(p, piecePaint)
        piecePaint.style = Paint.Style.FILL
        piecePaint.color = fill
        for (p in shape.parts) canvas.drawPath(p, piecePaint)
        piecePaint.style = Paint.Style.STROKE
        piecePaint.strokeWidth = 0.05f
        piecePaint.color = outline
        for (p in shape.details) canvas.drawPath(p, piecePaint)
        canvas.restore()
    }

    // =====================================================================================
    // PUBLIC
    // =====================================================================================

    fun startNewGame() {
        gameGen++
        animToken++
        animator?.cancel()
        animating = false
        pos = Chess.Position.initial()
        seen.clear()
        seen[pos.key()] = 1
        lastFrom = -1
        lastTo = -1
        gameOver = false
        thinking = false
        robotMovesPending = false
        clearSelection()
        onChessEvent?.invoke(ChessEvent.Started)
        invalidate()
        if (!userIsWhite) startRobotTurn()   // ang robot ang puti: siya ang mauuna
    }

    fun stopAnimations() {
        gameGen++
        animToken++
        animator?.cancel()
        animating = false
        thinking = false
        robotMovesPending = false
        handVisible = false
        handPinch = false
        clearSelection()
    }

    /** Mula sa MediaPipe (UI thread). null = walang nakitang kamay. */
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

    private fun toRow(displayRow: Int) = if (userIsWhite) displayRow else 7 - displayRow
    private fun toCol(displayCol: Int) = if (userIsWhite) displayCol else 7 - displayCol

    private fun squareAt(x: Float, y: Float): Int {
        if (cell <= 0f || !boardRect.contains(x, y)) return -1
        val dc = ((x - boardLeft) / cell).toInt().coerceIn(0, 7)
        val dr = ((y - boardTop) / cell).toInt().coerceIn(0, 7)
        return toRow(dr) * 8 + toCol(dc)
    }

    private fun squareCenterX(sq: Int): Float {
        val dc = if (userIsWhite) Chess.col(sq) else 7 - Chess.col(sq)
        return boardLeft + (dc + 0.5f) * cell
    }

    private fun squareCenterY(sq: Int): Float {
        val dr = if (userIsWhite) Chess.row(sq) else 7 - Chess.row(sq)
        return boardTop + (dr + 0.5f) * cell
    }

    private fun userTurn() = !gameOver && !thinking && !animating && (pos.whiteToMove == userIsWhite)

    private fun isUserPiece(v: Int) = v != 0 && ((v > 0) == userIsWhite)

    private fun clearSelection() {
        selected = -1
        legalDests = emptySet()
        dragging = false
        dragMoved = false
    }

    private fun select(sq: Int) {
        selected = sq
        val set = HashSet<Int>()
        for (m in Chess.legalMoves(pos)) if (Chess.from(m) == sq) set.add(Chess.to(m))
        legalDests = set
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
        if (!userTurn()) return
        val sq = squareAt(x, y)
        if (sq < 0) { clearSelection(); invalidate(); return }

        // 2-pinch na paraan: napili na ang piyesa, ngayon ang pupuntahan
        if (selected >= 0 && legalDests.contains(sq)) {
            val from = selected
            clearSelection()
            attemptUserMove(from, sq)
            return
        }
        if (isUserPiece(pos.sq[sq])) {
            select(sq)
            dragging = true
            dragMoved = false
            downX = x; downY = y
            dragX = x; dragY = y
        } else {
            clearSelection()
        }
        invalidate()
    }

    private fun pointerMove(x: Float, y: Float) {
        if (!dragging) return
        dragX = x
        dragY = y
        if (!dragMoved && (abs(x - downX) > cell * 0.25f || abs(y - downY) > cell * 0.25f)) dragMoved = true
        invalidate()
    }

    private fun pointerUp() {
        if (!dragging) return
        dragging = false
        if (dragMoved && selected >= 0) {
            val to = squareAt(dragX, dragY)
            val from = selected
            if (to >= 0 && to != from && legalDests.contains(to)) {
                clearSelection()
                attemptUserMove(from, to)
            } else {
                clearSelection()   // binitawan sa maling lugar: kanselado
            }
        }
        // kung hindi gumalaw (tap lang): nananatiling napili, hintayin ang pupuntahan
        dragMoved = false
        invalidate()
    }

    // =====================================================================================
    // DALOY NG LARO
    // =====================================================================================

    private fun attemptUserMove(from: Int, to: Int) {
        val m = Chess.findMove(pos, from, to)
        if (m < 0) {
            onChessEvent?.invoke(ChessEvent.IllegalMove)
            invalidate()
            return
        }
        val captured = Chess.isCapture(m)
        pos = Chess.makeMove(pos, m)
        lastFrom = from
        lastTo = to
        val key = pos.key()
        seen[key] = (seen[key] ?: 0) + 1
        invalidate()

        val status = Chess.status(pos)
        val repetition = (seen[key] ?: 0) >= 3
        when {
            status == Chess.Status.CHECKMATE -> finishGame(ChessResult.USER_WON, "checkmate")
            status != Chess.Status.ONGOING -> finishGame(ChessResult.DRAW, status.name)
            repetition -> finishGame(ChessResult.DRAW, "threefold")
            else -> {
                onChessEvent?.invoke(ChessEvent.UserMove(captured, Chess.isInCheck(pos, pos.whiteToMove)))
                startRobotTurn()
            }
        }
    }

    private fun finishGame(r: ChessResult, detail: String) {
        gameOver = true
        thinking = false
        result = r
        clearSelection()
        onChessEvent?.invoke(ChessEvent.GameOver(r, detail))
        invalidate()
    }

    private fun startRobotTurn() {
        if (gameOver) return
        thinking = true
        invalidate()
        val gen = gameGen
        val snapshot = pos.copy()
        val seenCopy = HashMap(seen)
        val lvl = level
        val t0 = System.currentTimeMillis()
        aiExecutor.execute {
            val m = try { ai.chooseMove(snapshot, lvl, seenCopy) } catch (e: Exception) { -1 }
            val wait = max(0L, MIN_THINK_MS - (System.currentTimeMillis() - t0))   // para hindi "instant" ang pakiramdam
            postDelayed({
                if (gen == gameGen) waitForSpeechThenMove(m, System.currentTimeMillis())
            }, wait)
        }
    }

    private fun waitForSpeechThenMove(m: Int, since: Long) {
        if (m < 0) {
            thinking = false
            invalidate()
            return
        }
        if (speechBusy() && System.currentTimeMillis() - since < 5000L) {
            val gen = gameGen
            postDelayed({ if (gen == gameGen) waitForSpeechThenMove(m, since) }, 150L)
            return
        }
        performRobotMove(m)
    }

    private fun performRobotMove(m: Int) {
        val from = Chess.from(m)
        val to = Chess.to(m)
        val captured = Chess.isCapture(m)
        val np = Chess.makeMove(pos, m)
        val mate = Chess.status(np) == Chess.Status.CHECKMATE
        val check = Chess.isInCheck(np, np.whiteToMove)

        animPiece = pos.sq[from]
        animFrom = from
        animTo = to
        animProgress = 0f
        animating = true
        onChessEvent?.invoke(ChessEvent.RobotMove(captured, check, mate))

        val gen = gameGen
        runAnim(420L, DecelerateInterpolator(), { p -> animProgress = p }) {
            if (gen != gameGen) return@runAnim
            animating = false
            thinking = false
            pos = np
            lastFrom = from
            lastTo = to
            val key = pos.key()
            seen[key] = (seen[key] ?: 0) + 1
            onChessEvent?.invoke(ChessEvent.RobotLanded(captured))
            val status = Chess.status(pos)
            when {
                status == Chess.Status.CHECKMATE -> finishGame(ChessResult.ROBOT_WON, "checkmate")
                status != Chess.Status.ONGOING -> finishGame(ChessResult.DRAW, status.name)
                (seen[key] ?: 0) >= 3 -> finishGame(ChessResult.DRAW, "threefold")
            }
            invalidate()
        }
    }

    private fun runAnim(durationMs: Long, interpolator: TimeInterpolator, onUpdate: (Float) -> Unit, onEnd: () -> Unit) {
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

    // =====================================================================================
    // DRAWING
    // =====================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = h * 0.92f
        cell = side / 8f
        boardLeft = (w - side) / 2f + w * 0.04f
        boardTop = (h - side) / 2f
        boardRect.set(boardLeft, boardTop, boardLeft + side, boardTop + side)
        exitRect.set(w - 60f * d, 8f * d, w - 8f * d, 60f * d)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BG)
        drawHud(canvas)

        // ----- board -----
        overlayPaint.style = Paint.Style.FILL
        overlayPaint.color = PANEL
        canvas.drawRoundRect(
            boardLeft - 6f * d, boardTop - 6f * d, boardRect.right + 6f * d, boardRect.bottom + 6f * d,
            14f * d, 14f * d, overlayPaint
        )
        val whiteToMoveInCheck = if (!gameOver) Chess.isInCheck(pos, pos.whiteToMove) else false
        val kingSq = if (whiteToMoveInCheck) Chess.kingSquare(pos, pos.whiteToMove) else -1

        for (dr in 0 until 8) for (dc in 0 until 8) {
            val x = boardLeft + dc * cell
            val y = boardTop + dr * cell
            squarePaint.color = if ((dr + dc) % 2 == 0) LIGHT else DARK
            canvas.drawRect(x, y, x + cell, y + cell, squarePaint)
            val sq = toRow(dr) * 8 + toCol(dc)
            if (sq == lastFrom || sq == lastTo) {
                overlayPaint.color = 0x66F6F669
                canvas.drawRect(x, y, x + cell, y + cell, overlayPaint)
            }
            if (sq == selected) {
                overlayPaint.color = 0x99BBCB2B.toInt()
                canvas.drawRect(x, y, x + cell, y + cell, overlayPaint)
            }
            if (sq == kingSq) {
                overlayPaint.color = 0xAAFF3B30.toInt()
                canvas.drawCircle(x + cell / 2f, y + cell / 2f, cell * 0.48f, overlayPaint)
            }
            // mga label ng file/rank sa gilid
            if (dc == 0) {
                textPaint.color = if ((dr + dc) % 2 == 0) DARK else LIGHT
                textPaint.textSize = cell * 0.2f
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText((8 - toRow(dr)).toString(), x + cell * 0.05f, y + cell * 0.22f, textPaint)
            }
            if (dr == 7) {
                textPaint.color = if ((dr + dc) % 2 == 0) DARK else LIGHT
                textPaint.textSize = cell * 0.2f
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(('a' + toCol(dc)).toString(), x + cell * 0.95f, y + cell * 0.95f, textPaint)
            }
        }

        // ----- mga piyesa -----
        val size = cell * 0.44f
        for (sq in 0 until 64) {
            val v = pos.sq[sq]
            if (v == 0) continue
            if (animating && sq == animFrom) continue                  // gumagalaw ang piyesang ito
            if (dragging && dragMoved && sq == selected) continue      // hawak ng kamay/daliri
            drawPiece(canvas, v, squareCenterX(sq), squareCenterY(sq), size)
        }

        // mga legal na pupuntahan
        if (selected >= 0) {
            overlayPaint.color = 0x55000000
            for (dest in legalDests) {
                val cx = squareCenterX(dest)
                val cy = squareCenterY(dest)
                if (pos.sq[dest] != 0 || dest == pos.ep) {
                    overlayPaint.style = Paint.Style.STROKE
                    overlayPaint.strokeWidth = cell * 0.09f
                    canvas.drawCircle(cx, cy, cell * 0.42f, overlayPaint)
                    overlayPaint.style = Paint.Style.FILL
                } else {
                    canvas.drawCircle(cx, cy, cell * 0.14f, overlayPaint)
                }
            }
        }

        // gumagalaw na piyesa ng robot
        if (animating && animPiece != 0) {
            val x0 = squareCenterX(animFrom)
            val y0 = squareCenterY(animFrom)
            val x1 = squareCenterX(animTo)
            val y1 = squareCenterY(animTo)
            val lift = 1f + 0.25f * (1f - abs(2f * animProgress - 1f))   // bahagyang "buhat" habang gumagalaw
            drawPiece(canvas, animPiece, x0 + (x1 - x0) * animProgress, y0 + (y1 - y0) * animProgress, size * lift)
        }

        // hawak na piyesa (sumusunod sa kamay)
        if (dragging && dragMoved && selected >= 0) {
            drawPiece(canvas, pos.sq[selected], dragX, dragY, size * 1.2f)
        }

        drawExitButton(canvas)
        if (gameOver) drawGameOver(canvas)
        drawCursor(canvas)
    }

    private fun drawHud(canvas: Canvas) {
        val h = height.toFloat()
        val left = 20f * d
        textPaint.textAlign = Paint.Align.LEFT

        textPaint.color = 0xFFFFD54F.toInt()
        textPaint.textSize = h * 0.08f
        canvas.drawText("CHESS", left, h * 0.15f, textPaint)

        textPaint.color = 0xFF8FA0C8.toInt()
        textPaint.textSize = h * 0.04f
        canvas.drawText("LEVEL $level", left, h * 0.22f, textPaint)
        canvas.drawText(if (userIsWhite) "Ikaw: PUTI" else "Ikaw: ITIM", left, h * 0.28f, textPaint)

        val status: String
        val color: Int
        when {
            gameOver -> { status = "Tapos na"; color = 0xFFFFFFFF.toInt() }
            thinking || animating -> { status = "Nag-iisip ang robot..."; color = 0xFFFFB74D.toInt() }
            else -> { status = "Ikaw ang tira"; color = 0xFF66BB6A.toInt() }
        }
        textPaint.color = color
        textPaint.textSize = h * 0.05f
        drawWrapped(canvas, status, left, h * 0.42f, boardLeft - left - 24f * d, textPaint)

        if (!gameOver && Chess.isInCheck(pos, pos.whiteToMove)) {
            textPaint.color = 0xFFFF5252.toInt()
            textPaint.textSize = h * 0.07f
            canvas.drawText("CHECK!", left, h * 0.58f, textPaint)
        }
        textPaint.color = 0xFF8FA0C8.toInt()
        textPaint.textSize = h * 0.04f
        canvas.drawText("Galaw #${pos.fullmove}", left, h * 0.70f, textPaint)

        val right = boardRect.right + 22f * d
        val avail = width - right - 12f * d
        textPaint.color = if (handVisible) 0xFF66BB6A.toInt() else 0xFFFFB74D.toInt()
        textPaint.textSize = h * 0.04f
        drawWrapped(canvas, handStatus, right, h * 0.30f, avail, textPaint)
        textPaint.color = 0xFF8FA0C8.toInt()
        textPaint.textSize = h * 0.034f
        drawWrapped(
            canvas,
            "Pinch ang piyesa mo at i-drag sa parisukat na pupuntahan. O pinch ang piyesa, tapos pinch ang pupuntahan.",
            right, h * 0.50f, avail, textPaint
        )
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint) {
        if (maxWidth <= 0f) return
        paint.textAlign = Paint.Align.LEFT
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
        canvas.drawRoundRect(boardRect, 14f * d, 14f * d, cursorFill)
        textPaint.textAlign = Paint.Align.CENTER
        val cx = boardRect.centerX()
        val (title, color) = when (result) {
            ChessResult.USER_WON -> Pair("NANALO KA!", 0xFF66BB6A.toInt())
            ChessResult.ROBOT_WON -> Pair("NANALO ANG ROBOT", 0xFFFF7043.toInt())
            ChessResult.DRAW -> Pair("TABLA", 0xFFFFD54F.toInt())
        }
        textPaint.color = color
        textPaint.textSize = cell * 0.85f
        canvas.drawText(title, cx, boardRect.top + cell * 3.6f, textPaint)
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textSize = cell * 0.40f
        canvas.drawText("Pinch o i-tap para maglaro ulit", cx, boardRect.top + cell * 5.2f, textPaint)
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
        gameGen++
        animToken++
        animator?.cancel()
        aiExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }
}
