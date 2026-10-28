package com.example.facerobot

import java.util.Random

/**
 * Chess engine na PURE Kotlin (walang Android dependency, kaya naka-test sa PC gamit ang perft).
 *
 *  - Kumpletong patakaran: castling, en passant, promotion, check, checkmate, stalemate, 50-move, kulang na piyesa.
 *  - Board: 64 na IntArray. index = row * 8 + col; row 0 = rank 8 (itaas), col 0 = file a.
 *    Puti = positibo (1=P 2=N 3=B 4=R 5=Q 6=K), Itim = negatibo.
 *  - Move: naka-pack sa isang Int (from, to, promotion, flags).
 *  - AI (ChessAI): negamax alpha-beta + quiescence + iterative deepening na may time limit, at 4 na level.
 */
object Chess {
    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    // castling rights
    const val WK = 1
    const val WQ = 2
    const val BK = 4
    const val BQ = 8

    // flags ng move
    const val F_CAPTURE = 1 shl 15
    const val F_EP = 1 shl 16
    const val F_CASTLE = 1 shl 17
    const val F_DOUBLE = 1 shl 18

    fun mk(from: Int, to: Int, promo: Int = 0, flags: Int = 0): Int = from or (to shl 6) or (promo shl 12) or flags
    fun from(m: Int): Int = m and 63
    fun to(m: Int): Int = (m shr 6) and 63
    fun promo(m: Int): Int = (m shr 12) and 7
    fun isCapture(m: Int): Boolean = (m and F_CAPTURE) != 0

    fun row(s: Int) = s shr 3
    fun col(s: Int) = s and 7

    fun squareName(s: Int): String = "${'a' + col(s)}${8 - row(s)}"

    fun moveToString(m: Int): String {
        val p = promo(m)
        val ps = when (p) { QUEEN -> "q"; ROOK -> "r"; BISHOP -> "b"; KNIGHT -> "n"; else -> "" }
        return squareName(from(m)) + squareName(to(m)) + ps
    }

    class Position {
        val sq = IntArray(64)
        var whiteToMove = true
        var castle = 15
        var ep = -1
        var halfmove = 0
        var fullmove = 1

        fun copy(): Position {
            val p = Position()
            System.arraycopy(sq, 0, p.sq, 0, 64)
            p.whiteToMove = whiteToMove
            p.castle = castle
            p.ep = ep
            p.halfmove = halfmove
            p.fullmove = fullmove
            return p
        }

        /** Susi para sa repetition (board + turn + castling + ep). */
        fun key(): String {
            val sb = StringBuilder(80)
            for (i in 0 until 64) sb.append((sq[i] + 7).toChar())
            sb.append(if (whiteToMove) 'w' else 'b').append(castle).append(',').append(ep)
            return sb.toString()
        }

        companion object {
            fun initial(): Position {
                val p = Position()
                val back = intArrayOf(ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK)
                for (c in 0 until 8) {
                    p.sq[c] = -back[c]
                    p.sq[8 + c] = -PAWN
                    p.sq[48 + c] = PAWN
                    p.sq[56 + c] = back[c]
                }
                return p
            }

            /** Para sa test (perft) - karaniwang FEN. */
            fun fromFen(fen: String): Position {
                val p = Position()
                val parts = fen.trim().split(" ")
                var r = 0
                var c = 0
                for (ch in parts[0]) {
                    when {
                        ch == '/' -> { r++; c = 0 }
                        ch.isDigit() -> c += ch - '0'
                        else -> {
                            val t = when (ch.lowercaseChar()) {
                                'p' -> PAWN; 'n' -> KNIGHT; 'b' -> BISHOP; 'r' -> ROOK; 'q' -> QUEEN; else -> KING
                            }
                            p.sq[r * 8 + c] = if (ch.isUpperCase()) t else -t
                            c++
                        }
                    }
                }
                p.whiteToMove = parts.getOrElse(1) { "w" } == "w"
                var cs = 0
                for (ch in parts.getOrElse(2) { "-" }) {
                    when (ch) { 'K' -> cs = cs or WK; 'Q' -> cs = cs or WQ; 'k' -> cs = cs or BK; 'q' -> cs = cs or BQ }
                }
                p.castle = cs
                val e = parts.getOrElse(3) { "-" }
                p.ep = if (e == "-" || e.length < 2) -1 else (e[0] - 'a') + (8 - (e[1] - '0')) * 8
                p.halfmove = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0
                p.fullmove = parts.getOrElse(5) { "1" }.toIntOrNull() ?: 1
                return p
            }
        }
    }

    class MoveList {
        val a = IntArray(256)
        var n = 0
        fun add(m: Int) { a[n++] = m }
    }

    private val KNIGHT_D = arrayOf(intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2),
        intArrayOf(1, -2), intArrayOf(1, 2), intArrayOf(2, -1), intArrayOf(2, 1))
    private val KING_D = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, -1),
        intArrayOf(0, 1), intArrayOf(1, -1), intArrayOf(1, 0), intArrayOf(1, 1))
    private val DIAG_D = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))
    private val STRAIGHT_D = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))

    /** Inaatake ba ng [byWhite] ang parisukat na [s]? */
    fun isAttacked(p: Position, s: Int, byWhite: Boolean): Boolean {
        val r = row(s)
        val c = col(s)
        val sq = p.sq
        // pawn
        if (byWhite) {
            if (r + 1 < 8) {
                if (c > 0 && sq[(r + 1) * 8 + c - 1] == PAWN) return true
                if (c < 7 && sq[(r + 1) * 8 + c + 1] == PAWN) return true
            }
        } else {
            if (r - 1 >= 0) {
                if (c > 0 && sq[(r - 1) * 8 + c - 1] == -PAWN) return true
                if (c < 7 && sq[(r - 1) * 8 + c + 1] == -PAWN) return true
            }
        }
        val sign = if (byWhite) 1 else -1
        for (d in KNIGHT_D) {
            val rr = r + d[0]
            val cc = c + d[1]
            if (rr in 0..7 && cc in 0..7 && sq[rr * 8 + cc] == KNIGHT * sign) return true
        }
        for (d in KING_D) {
            val rr = r + d[0]
            val cc = c + d[1]
            if (rr in 0..7 && cc in 0..7 && sq[rr * 8 + cc] == KING * sign) return true
        }
        for (d in DIAG_D) {
            var rr = r + d[0]
            var cc = c + d[1]
            while (rr in 0..7 && cc in 0..7) {
                val v = sq[rr * 8 + cc]
                if (v != 0) {
                    if (v == BISHOP * sign || v == QUEEN * sign) return true
                    break
                }
                rr += d[0]; cc += d[1]
            }
        }
        for (d in STRAIGHT_D) {
            var rr = r + d[0]
            var cc = c + d[1]
            while (rr in 0..7 && cc in 0..7) {
                val v = sq[rr * 8 + cc]
                if (v != 0) {
                    if (v == ROOK * sign || v == QUEEN * sign) return true
                    break
                }
                rr += d[0]; cc += d[1]
            }
        }
        return false
    }

    fun kingSquare(p: Position, white: Boolean): Int {
        val k = if (white) KING else -KING
        for (i in 0 until 64) if (p.sq[i] == k) return i
        return -1
    }

    fun isInCheck(p: Position, white: Boolean): Boolean {
        val k = kingSquare(p, white)
        return k >= 0 && isAttacked(p, k, !white)
    }

    /** Pseudo-legal moves (hindi pa sinasala kung naiiwang may check). capturesOnly = para sa quiescence. */
    fun generate(p: Position, ml: MoveList, capturesOnly: Boolean = false) {
        val sq = p.sq
        val white = p.whiteToMove
        val sign = if (white) 1 else -1
        for (from in 0 until 64) {
            val v = sq[from]
            if (v == 0 || (v > 0) != white) continue
            val r = row(from)
            val c = col(from)
            when (v * sign) {
                PAWN -> {
                    val dir = if (white) -1 else 1
                    val startRow = if (white) 6 else 1
                    val promoRow = if (white) 0 else 7
                    val r1 = r + dir
                    if (r1 in 0..7) {
                        val to1 = r1 * 8 + c
                        if (sq[to1] == 0) {
                            if (r1 == promoRow) {
                                if (!capturesOnly) {
                                    ml.add(mk(from, to1, QUEEN)); ml.add(mk(from, to1, ROOK))
                                    ml.add(mk(from, to1, BISHOP)); ml.add(mk(from, to1, KNIGHT))
                                } else {
                                    ml.add(mk(from, to1, QUEEN))   // promotion sa quiescence: reyna lang
                                }
                            } else if (!capturesOnly) {
                                ml.add(mk(from, to1))
                                if (r == startRow) {
                                    val to2 = (r + 2 * dir) * 8 + c
                                    if (sq[to2] == 0) ml.add(mk(from, to2, 0, F_DOUBLE))
                                }
                            }
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val cc = c + dc
                            if (cc !in 0..7) continue
                            val t = r1 * 8 + cc
                            val tv = sq[t]
                            if (tv != 0 && (tv > 0) != white) {
                                if (r1 == promoRow) {
                                    ml.add(mk(from, t, QUEEN, F_CAPTURE)); ml.add(mk(from, t, ROOK, F_CAPTURE))
                                    ml.add(mk(from, t, BISHOP, F_CAPTURE)); ml.add(mk(from, t, KNIGHT, F_CAPTURE))
                                } else {
                                    ml.add(mk(from, t, 0, F_CAPTURE))
                                }
                            } else if (tv == 0 && t == p.ep) {
                                ml.add(mk(from, t, 0, F_CAPTURE or F_EP))
                            }
                        }
                    }
                }
                KNIGHT -> for (d in KNIGHT_D) addStep(p, ml, from, r + d[0], c + d[1], capturesOnly)
                KING -> {
                    for (d in KING_D) addStep(p, ml, from, r + d[0], c + d[1], capturesOnly)
                    if (!capturesOnly) addCastling(p, ml, from)
                }
                BISHOP -> for (d in DIAG_D) addSlide(p, ml, from, d, capturesOnly)
                ROOK -> for (d in STRAIGHT_D) addSlide(p, ml, from, d, capturesOnly)
                QUEEN -> {
                    for (d in DIAG_D) addSlide(p, ml, from, d, capturesOnly)
                    for (d in STRAIGHT_D) addSlide(p, ml, from, d, capturesOnly)
                }
            }
        }
    }

    private fun addStep(p: Position, ml: MoveList, from: Int, rr: Int, cc: Int, capturesOnly: Boolean) {
        if (rr !in 0..7 || cc !in 0..7) return
        val t = rr * 8 + cc
        val tv = p.sq[t]
        if (tv == 0) {
            if (!capturesOnly) ml.add(mk(from, t))
        } else if ((tv > 0) != p.whiteToMove) {
            ml.add(mk(from, t, 0, F_CAPTURE))
        }
    }

    private fun addSlide(p: Position, ml: MoveList, from: Int, d: IntArray, capturesOnly: Boolean) {
        var rr = row(from) + d[0]
        var cc = col(from) + d[1]
        while (rr in 0..7 && cc in 0..7) {
            val t = rr * 8 + cc
            val tv = p.sq[t]
            if (tv == 0) {
                if (!capturesOnly) ml.add(mk(from, t))
            } else {
                if ((tv > 0) != p.whiteToMove) ml.add(mk(from, t, 0, F_CAPTURE))
                break
            }
            rr += d[0]; cc += d[1]
        }
    }

    private fun addCastling(p: Position, ml: MoveList, from: Int) {
        val sq = p.sq
        if (p.whiteToMove) {
            if (from != 60) return
            if ((p.castle and WK) != 0 && sq[61] == 0 && sq[62] == 0 && sq[63] == ROOK &&
                !isAttacked(p, 60, false) && !isAttacked(p, 61, false) && !isAttacked(p, 62, false)
            ) ml.add(mk(60, 62, 0, F_CASTLE))
            if ((p.castle and WQ) != 0 && sq[59] == 0 && sq[58] == 0 && sq[57] == 0 && sq[56] == ROOK &&
                !isAttacked(p, 60, false) && !isAttacked(p, 59, false) && !isAttacked(p, 58, false)
            ) ml.add(mk(60, 58, 0, F_CASTLE))
        } else {
            if (from != 4) return
            if ((p.castle and BK) != 0 && sq[5] == 0 && sq[6] == 0 && sq[7] == -ROOK &&
                !isAttacked(p, 4, true) && !isAttacked(p, 5, true) && !isAttacked(p, 6, true)
            ) ml.add(mk(4, 6, 0, F_CASTLE))
            if ((p.castle and BQ) != 0 && sq[3] == 0 && sq[2] == 0 && sq[1] == 0 && sq[0] == -ROOK &&
                !isAttacked(p, 4, true) && !isAttacked(p, 3, true) && !isAttacked(p, 2, true)
            ) ml.add(mk(4, 2, 0, F_CASTLE))
        }
    }

    /** Gumagawa ng BAGONG posisyon pagkatapos ng move [m] (hindi binabago ang [p]). */
    fun makeMove(p: Position, m: Int): Position {
        val np = p.copy()
        val sq = np.sq
        val from = from(m)
        val to = to(m)
        val piece = sq[from]
        val white = p.whiteToMove
        val isPawn = piece == PAWN || piece == -PAWN
        val wasCapture = (m and F_CAPTURE) != 0

        if ((m and F_EP) != 0) {
            val victim = to + (if (white) 8 else -8)
            sq[victim] = 0
        }
        val pr = promo(m)
        sq[to] = if (pr != 0) (if (white) pr else -pr) else piece
        sq[from] = 0

        if ((m and F_CASTLE) != 0) {
            when (to) {
                62 -> { sq[61] = sq[63]; sq[63] = 0 }
                58 -> { sq[59] = sq[56]; sq[56] = 0 }
                6 -> { sq[5] = sq[7]; sq[7] = 0 }
                2 -> { sq[3] = sq[0]; sq[0] = 0 }
            }
        }

        // castling rights
        if (piece == KING) np.castle = np.castle and (WK or WQ).inv()
        if (piece == -KING) np.castle = np.castle and (BK or BQ).inv()
        if (from == 63 || to == 63) np.castle = np.castle and WK.inv()
        if (from == 56 || to == 56) np.castle = np.castle and WQ.inv()
        if (from == 7 || to == 7) np.castle = np.castle and BK.inv()
        if (from == 0 || to == 0) np.castle = np.castle and BQ.inv()

        np.ep = if ((m and F_DOUBLE) != 0) (from + to) / 2 else -1
        np.halfmove = if (isPawn || wasCapture) 0 else p.halfmove + 1
        if (!white) np.fullmove = p.fullmove + 1
        np.whiteToMove = !white
        return np
    }

    /** Lahat ng LEGAL na move ng may turn. */
    fun legalMoves(p: Position): IntArray {
        val ml = MoveList()
        generate(p, ml)
        val out = IntArray(ml.n)
        var n = 0
        for (i in 0 until ml.n) {
            val m = ml.a[i]
            val np = makeMove(p, m)
            if (!isInCheck(np, p.whiteToMove)) out[n++] = m
        }
        return out.copyOf(n)
    }

    /** Hanapin ang legal na move from->to (promotion = reyna kung may promosyon). 0 kung wala (0 ay walang bisang move). */
    fun findMove(p: Position, from: Int, to: Int): Int {
        var fallback = -1
        for (m in legalMoves(p)) {
            if (from(m) == from && to(m) == to) {
                val pr = promo(m)
                if (pr == 0 || pr == QUEEN) return m
                if (fallback < 0) fallback = m
            }
        }
        return if (fallback >= 0) fallback else -1
    }

    enum class Status { ONGOING, CHECKMATE, STALEMATE, DRAW_FIFTY, DRAW_MATERIAL }

    fun status(p: Position): Status {
        if (legalMoves(p).isEmpty()) return if (isInCheck(p, p.whiteToMove)) Status.CHECKMATE else Status.STALEMATE
        if (p.halfmove >= 100) return Status.DRAW_FIFTY
        if (insufficientMaterial(p)) return Status.DRAW_MATERIAL
        return Status.ONGOING
    }

    fun insufficientMaterial(p: Position): Boolean {
        var minors = 0
        for (v in p.sq) {
            when (kotlin.math.abs(v)) {
                PAWN, ROOK, QUEEN -> return false
                KNIGHT, BISHOP -> minors++
            }
        }
        return minors <= 1
    }

    /** Para sa pagsusuri: ilang node ang umaabot hanggang [depth]. */
    fun perft(p: Position, depth: Int): Long {
        if (depth == 0) return 1L
        var total = 0L
        for (m in legalMoves(p)) total += perft(makeMove(p, m), depth - 1)
        return total
    }
}

// =====================================================================================================
// AI
// =====================================================================================================

class ChessAI(private val rnd: Random = Random()) {

    companion object {
        private const val INF = 1_000_000
        const val MATE = 100_000

        private val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 0)

        private val PST_PAWN = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        private val PST_KNIGHT = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50
        )
        private val PST_BISHOP = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20
        )
        private val PST_ROOK = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0
        )
        private val PST_QUEEN = intArrayOf(
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20
        )
        private val PST_KING_MID = intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20
        )
        private val PST_KING_END = intArrayOf(
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50
        )
    }

    private var deadline = Long.MAX_VALUE
    private var stop = false
    private var nodes = 0L

    /** Halaga ng posisyon mula sa pananaw ng may TURN (positibo = maganda para sa kanya). */
    fun evaluate(p: Chess.Position): Int {
        var queens = 0
        for (v in p.sq) if (v == Chess.QUEEN || v == -Chess.QUEEN) queens++
        val endgame = queens == 0
        var score = 0
        for (i in 0 until 64) {
            val v = p.sq[i]
            if (v == 0) continue
            val t = kotlin.math.abs(v)
            val white = v > 0
            val idx = if (white) i else i xor 56   // i-mirror ang table para sa itim
            val pst = when (t) {
                Chess.PAWN -> PST_PAWN[idx]
                Chess.KNIGHT -> PST_KNIGHT[idx]
                Chess.BISHOP -> PST_BISHOP[idx]
                Chess.ROOK -> PST_ROOK[idx]
                Chess.QUEEN -> PST_QUEEN[idx]
                else -> if (endgame) PST_KING_END[idx] else PST_KING_MID[idx]
            }
            val s = VALUE[t] + pst
            score += if (white) s else -s
        }
        return if (p.whiteToMove) score else -score
    }

    private fun orderScore(p: Chess.Position, m: Int): Int {
        var s = 0
        if (Chess.isCapture(m)) {
            val victim = if ((m and Chess.F_EP) != 0) Chess.PAWN else kotlin.math.abs(p.sq[Chess.to(m)])
            val attacker = kotlin.math.abs(p.sq[Chess.from(m)])
            s += 10 * VALUE[victim] - VALUE[attacker] / 10 + 10_000
        }
        if (Chess.promo(m) == Chess.QUEEN) s += 9_000
        return s
    }

    private fun sortMoves(p: Chess.Position, ml: Chess.MoveList): IntArray {
        val n = ml.n
        val moves = IntArray(n)
        val scores = IntArray(n)
        for (i in 0 until n) {
            moves[i] = ml.a[i]
            scores[i] = orderScore(p, moves[i])
        }
        // insertion sort (mataas muna)
        for (i in 1 until n) {
            val m = moves[i]
            val sc = scores[i]
            var j = i - 1
            while (j >= 0 && scores[j] < sc) {
                moves[j + 1] = moves[j]
                scores[j + 1] = scores[j]
                j--
            }
            moves[j + 1] = m
            scores[j + 1] = sc
        }
        return moves
    }

    private fun checkTime() {
        if ((nodes and 1023L) == 0L && System.nanoTime() > deadline) stop = true
    }

    private fun negamax(p: Chess.Position, depthIn: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        nodes++
        checkTime()
        if (stop) return 0
        if (p.halfmove >= 100) return 0
        var alpha = alphaIn
        val mover = p.whiteToMove
        val inCheck = Chess.isInCheck(p, mover)
        var depth = depthIn
        if (inCheck && ply < 8) depth++
        if (depth <= 0) return quiesce(p, alpha, beta, 0)

        val ml = Chess.MoveList()
        Chess.generate(p, ml)
        val moves = sortMoves(p, ml)
        var any = false
        for (m in moves) {
            val np = Chess.makeMove(p, m)
            if (Chess.isInCheck(np, mover)) continue
            any = true
            val v = -negamax(np, depth - 1, -beta, -alpha, ply + 1)
            if (stop) return 0
            if (v >= beta) return beta
            if (v > alpha) alpha = v
        }
        if (!any) return if (inCheck) -(MATE - ply) else 0
        return alpha
    }

    private fun quiesce(p: Chess.Position, alphaIn: Int, beta: Int, qd: Int): Int {
        nodes++
        checkTime()
        if (stop) return 0
        val stand = evaluate(p)
        if (qd >= 8) return stand
        if (stand >= beta) return beta
        var alpha = alphaIn
        if (stand > alpha) alpha = stand
        val mover = p.whiteToMove
        val ml = Chess.MoveList()
        Chess.generate(p, ml, capturesOnly = true)
        val moves = sortMoves(p, ml)
        for (m in moves) {
            val np = Chess.makeMove(p, m)
            if (Chess.isInCheck(np, mover)) continue
            val v = -quiesce(np, -beta, -alpha, qd + 1)
            if (stop) return 0
            if (v >= beta) return beta
            if (v > alpha) alpha = v
        }
        return alpha
    }

    /**
     * Pumili ng move para sa may turn. level 1 (madali) .. 4 (mahirap).
     * [seen] = bilang ng beses na lumabas ang bawat posisyon (para iwasan ang threefold kapag nangunguna).
     * Ibinabalik ang packed move, o -1 kung walang legal na move.
     */
    fun chooseMove(p: Chess.Position, level: Int, seen: Map<String, Int> = emptyMap()): Int {
        val legal = Chess.legalMoves(p)
        if (legal.isEmpty()) return -1
        if (legal.size == 1) return legal[0]

        val lvl = level.coerceIn(1, 4)
        stop = false
        nodes = 0L

        // May mate-in-1 ba? Palaging kunin (lahat ng level).
        for (m in legal) {
            val np = Chess.makeMove(p, m)
            if (Chess.status(np) == Chess.Status.CHECKMATE) return m
        }

        fun repetitionPenalty(m: Int, score: Int): Int {
            val key = Chess.makeMove(p, m).key()
            return if ((seen[key] ?: 0) >= 2) 0 else score   // magiging threefold = tabla (0)
        }

        if (lvl <= 2) {
            // Madali: fixed na lalim, hinahaluan ng random sa mga halos-pantay na move
            val depth = if (lvl == 1) 1 else 2
            val margin = if (lvl == 1) 200 else 40
            deadline = System.nanoTime() + 2_000_000_000L
            val scores = IntArray(legal.size)
            var best = -INF
            for (i in legal.indices) {
                val np = Chess.makeMove(p, legal[i])
                val s = repetitionPenalty(legal[i], -negamax(np, depth - 1, -INF, INF, 1))
                scores[i] = s
                if (s > best) best = s
            }
            val pool = ArrayList<Int>()
            for (i in legal.indices) if (scores[i] >= best - margin) pool.add(legal[i])
            return pool[rnd.nextInt(pool.size)]
        }

        // Level 3/4: iterative deepening na may time limit
        val maxDepth = if (lvl == 3) 5 else 7
        val timeMs = if (lvl == 3) 1200L else 3000L
        deadline = System.nanoTime() + timeMs * 1_000_000L

        var ordered = sortMoves(p, Chess.MoveList().also { ml -> for (m in legal) ml.add(m) })
        var bestMove = ordered[0]
        for (depth in 1..maxDepth) {
            var alpha = -INF
            var bestThis = ordered[0]
            var bestScore = -INF
            val scored = ArrayList<Pair<Int, Int>>()
            var completed = true
            for (m in ordered) {
                val np = Chess.makeMove(p, m)
                var v = -negamax(np, depth - 1, -INF, -alpha, 1)
                if (stop) { completed = false; break }
                v = repetitionPenalty(m, v)
                scored.add(Pair(m, v))
                if (v > bestScore) { bestScore = v; bestThis = m }
                if (v > alpha) alpha = v
            }
            if (!completed) break
            bestMove = bestThis
            // ilagay sa unahan ang pinakamahusay para mas mabilis ang susunod na lalim
            scored.sortByDescending { it.second }
            ordered = IntArray(scored.size) { scored[it].first }
            if (bestScore >= MATE - 100) break   // may nakitang sapilitang mate
            if (System.nanoTime() > deadline) break
        }
        return bestMove
    }
}
