package com.example.facerobot

import android.content.SharedPreferences

/**
 * Ang "bibig" ng robot sa Candy-Crush na hand game: ayon sa nangyayari sa laro (GameEvent), sinasabi ang tamang linya.
 * Lahat ng linya at DFPlayer track ay naka-edit sa Menu > Laro (Hand Game).
 *
 * Gamitin ang {score} sa linya para ilagay ang score (hal. "Tapos na ang laro! Ang score mo ay {score}.").
 * Boses: ang [Host.say] ang nagpapasya (DFPlayer lang kapag OFF ang Gemini at OFF ang TTS; kung hindi, TTS).
 */
class CandyCoach(
    private val prefs: SharedPreferences,
    private val host: Host,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    interface Host {
        /** [text] para sa TTS; [track] para sa DFPlayer (0 = walang track). */
        fun say(text: String, track: Int)
    }

    enum class Line(val prefKey: String, val default: String, val label: String) {
        START("candy_line_start", "Sige, maglaro tayo! I-pinch mo ang candy at i-drag papunta sa katabi.", "Pagsisimula"),
        COMBO("candy_line_combo", "Wow, combo!", "Combo o malaking pagsabog (may 6 na segundong pahinga)"),
        SHUFFLE("candy_line_shuffle", "Wala nang pwedeng galaw, hahaluin ko ulit.", "Kapag wala nang pwedeng galaw (hinahalo)"),
        GAME_OVER("candy_line_game_over", "Tapos na ang laro! Ang score mo ay {score}.", "Tapos na ang moves (pwede ang {score})"),
        NEW_BEST("candy_line_new_best", "Bagong rekord!", "Bagong best score (idinadagdag sa itaas)"),
        EXIT("candy_line_exit", "Okay, tapos na ang laro! Ang score mo ay {score}.", "Paglabas: ayaw mo na maglaro (pwede ang {score})")
    }

    private var lastSpokeAt = 0L

    fun text(l: Line): String = prefs.getString(l.prefKey, null)?.takeIf { it.isNotBlank() } ?: l.default
    fun track(l: Line): Int = prefs.getInt(l.prefKey + "_track", 0)
    fun setLine(l: Line, text: String, track: Int) {
        prefs.edit()
            .putString(l.prefKey, text.trim().ifEmpty { l.default })
            .putInt(l.prefKey + "_track", track.coerceAtLeast(0))
            .apply()
    }

    private fun fill(s: String, score: Int?) = if (score != null) s.replace("{score}", score.toString()) else s.replace("{score}", "")

    private fun say(l: Line, score: Int? = null) {
        lastSpokeAt = now()
        host.say(fill(text(l), score).trim(), track(l))
    }

    /** Pagpasok sa laro. */
    fun onStart() = say(Line.START)

    /** Paglabas (ayaw mo na maglaro) - [score] ang huling score. */
    fun onExit(score: Int) = say(Line.EXIT, score)

    fun onEvent(e: GameEvent) {
        when (e) {
            is GameEvent.Combo, is GameEvent.BigClear -> {
                if (now() - lastSpokeAt > 6000L) say(Line.COMBO)
            }
            is GameEvent.Shuffled -> say(Line.SHUFFLE)
            is GameEvent.GameOver -> {
                val text = fill(text(Line.GAME_OVER), e.score) + (if (e.newBest) " " + text(Line.NEW_BEST) else "")
                // DFPlayer: isang track lang - kung bagong rekord at may track ito, iyon; kung hindi, ang "tapos na"
                val tr = if (e.newBest && track(Line.NEW_BEST) > 0) track(Line.NEW_BEST) else track(Line.GAME_OVER)
                lastSpokeAt = now()
                host.say(text.trim(), tr)
            }
            else -> {}
        }
    }
}
