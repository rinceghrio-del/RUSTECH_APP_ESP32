package com.example.facerobot

import kotlin.math.abs
import kotlin.random.Random

/**
 * Candy-Crush-style na LOGIC lang (walang Android dependency, kaya madaling i-test).
 *
 * Daloy (ginagamit ng GameView para sa animation):
 *   1. swapCreatesMatch(a, b)  - may mabubuo bang 3+ kapag pinalitan? (hindi binabago ang board)
 *   2. commitSwap(a, b)        - palitan talaga + bawas ng 1 move
 *   3. loop: findMatches() -> clear() -> applyGravity()  hanggang wala nang match (cascade / combo)
 *   4. hasPossibleMove()       - kung wala, shuffleBoard()
 */
class MatchGame(
    val rows: Int = 8,
    val cols: Int = 8,
    val kinds: Int = 6,
    private val rnd: Random = Random.Default
) {
    companion object {
        const val EMPTY = -1
    }

    /** Isang candy na gumalaw (o bagong spawn kapag fromRow < 0) sa column na [col]. */
    data class Fall(val col: Int, val fromRow: Int, val toRow: Int)

    val board = Array(rows) { IntArray(cols) { EMPTY } }

    var score = 0
        private set
    var movesLeft = 0
        private set

    fun newGame(moves: Int = 30) {
        score = 0
        movesLeft = moves
        fillWithoutMatches()
    }

    private fun fillWithoutMatches() {
        do {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    var k: Int
                    do {
                        k = rnd.nextInt(kinds)
                    } while (
                        (c >= 2 && board[r][c - 1] == k && board[r][c - 2] == k) ||
                        (r >= 2 && board[r - 1][c] == k && board[r - 2][c] == k)
                    )
                    board[r][c] = k
                }
            }
        } while (!hasPossibleMove())
    }

    fun inBounds(r: Int, c: Int) = r in 0 until rows && c in 0 until cols

    fun adjacent(r1: Int, c1: Int, r2: Int, c2: Int): Boolean =
        inBounds(r1, c1) && inBounds(r2, c2) && abs(r1 - r2) + abs(c1 - c2) == 1

    private fun swapCells(r1: Int, c1: Int, r2: Int, c2: Int) {
        val t = board[r1][c1]
        board[r1][c1] = board[r2][c2]
        board[r2][c2] = t
    }

    /** May mabubuong match kapag pinalitan? (Ibinabalik agad ang board.) */
    fun swapCreatesMatch(r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        if (!adjacent(r1, c1, r2, c2)) return false
        if (board[r1][c1] == EMPTY || board[r2][c2] == EMPTY) return false
        swapCells(r1, c1, r2, c2)
        val found = findMatches().isNotEmpty()
        swapCells(r1, c1, r2, c2)
        return found
    }

    /** Palitan talaga at bawasan ang moves. Tawagin lang pagkatapos ng swapCreatesMatch() == true. */
    fun commitSwap(r1: Int, c1: Int, r2: Int, c2: Int) {
        swapCells(r1, c1, r2, c2)
        movesLeft--
    }

    /** Lahat ng cell (index = r * cols + c) na bahagi ng 3 o higit pang magkakasunod (pahalang o patayo). */
    fun findMatches(): Set<Int> {
        val out = HashSet<Int>()
        for (r in 0 until rows) {
            var c = 0
            while (c < cols) {
                val k = board[r][c]
                if (k == EMPTY) { c++; continue }
                var e = c + 1
                while (e < cols && board[r][e] == k) e++
                if (e - c >= 3) for (x in c until e) out.add(r * cols + x)
                c = e
            }
        }
        for (c in 0 until cols) {
            var r = 0
            while (r < rows) {
                val k = board[r][c]
                if (k == EMPTY) { r++; continue }
                var e = r + 1
                while (e < rows && board[e][c] == k) e++
                if (e - r >= 3) for (y in r until e) out.add(y * cols + c)
                r = e
            }
        }
        return out
    }

    /** Alisin ang mga match at magdagdag ng score. May bonus ang mas marami sa 3 at ang combo (cascade). */
    fun clear(cells: Set<Int>, combo: Int): Int {
        val points = (cells.size * 10 + maxOf(0, cells.size - 3) * 10) * maxOf(1, combo)
        for (idx in cells) board[idx / cols][idx % cols] = EMPTY
        score += points
        return points
    }

    /** Ibagsak ang mga candy at punuin ng bago ang itaas. Ibinabalik ang lahat ng gumalaw/bagong spawn. */
    fun applyGravity(): List<Fall> {
        val falls = ArrayList<Fall>()
        for (c in 0 until cols) {
            var write = rows - 1
            for (r in rows - 1 downTo 0) {
                val k = board[r][c]
                if (k != EMPTY) {
                    if (r != write) {
                        board[write][c] = k
                        board[r][c] = EMPTY
                        falls.add(Fall(c, r, write))
                    }
                    write--
                }
            }
            val n = write + 1   // ilan ang walang laman sa itaas
            for (r in 0..write) {
                board[r][c] = rnd.nextInt(kinds)
                falls.add(Fall(c, r - n, r))   // bagong candy na bumabagsak mula sa labas ng board
            }
        }
        return falls
    }

    fun hasPossibleMove(): Boolean {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (c + 1 < cols && swapCreatesMatch(r, c, r, c + 1)) return true
                if (r + 1 < rows && swapCreatesMatch(r, c, r + 1, c)) return true
            }
        }
        return false
    }

    /** Haluin ang kasalukuyang mga candy hanggang walang match at may pwedeng galaw. */
    fun shuffleBoard() {
        val all = ArrayList<Int>(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) if (board[r][c] != EMPTY) all.add(board[r][c])
        repeat(200) {
            all.shuffle(rnd)
            var i = 0
            for (r in 0 until rows) for (c in 0 until cols) board[r][c] = all[i++]
            if (findMatches().isEmpty() && hasPossibleMove()) return
        }
        fillWithoutMatches()   // pang-huli kung hindi nakabuo ng playable na haluan
    }
}
