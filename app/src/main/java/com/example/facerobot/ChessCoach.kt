package com.example.facerobot

import android.content.SharedPreferences

/**
 * Ang "bibig at katawan" ng robot sa chess: ayon sa nangyayari sa laro (ChessEvent), sinasabi nito ang tamang linya
 * at nagpapa-shake kapag nakakain ang robot. Lahat ng linya ay naka-edit sa settings (Menu > Chess).
 *
 * Mga default (ayon sa hiling):
 *  - Simula ........... "Sige, mag-chess tayo. Galingan mo ha!"
 *  - Bawat tira ng robot "Ito ang tira ko."
 *  - Nakakain ang robot  + "Galingan mo naman."  (at SHAKE ng ~100 ms)
 *  - Na-checkmate ka ... + "WALA KA PA LANG BINATBAT EH!"
 *  - Nakakain ka ...... "Huwag mo naman masyadong galingan."
 *  - Natalo ang robot . "Ang galing mo naman."
 *  - Tabla ............ "Tabla tayo!"
 *
 * Boses: ang [Host.say] ang nagpapasya ayon sa patakaran (DFPlayer lang kapag OFF ang Gemini at OFF ang TTS;
 * kung hindi, TTS). Sa DFPlayer mode, isang track lang ang kayang patugtugin kada tira, kaya ang pinaka-tiyak na
 * linya na may track ang gagamitin (mate > kain > tira).
 */
class ChessCoach(private val prefs: SharedPreferences, private val host: Host) {

    interface Host {
        /** [text] para sa TTS; [track] para sa DFPlayer (0 = walang track). */
        fun say(text: String, track: Int)
        /** Maikling shake ng robot, [ms] milliseconds. */
        fun shake(ms: Int)
    }

    enum class Line(val prefKey: String, val default: String, val label: String) {
        START("chess_line_start", "Sige, mag-chess tayo. Galingan mo ha!", "Pagsisimula"),
        ROBOT_MOVE("chess_line_robot_move", "Ito ang tira ko.", "Bawat tira ng robot"),
        ROBOT_CAPTURE("chess_line_robot_capture", "Galingan mo naman.", "Nakakain ang robot ng piyesa mo"),
        ROBOT_MATE("chess_line_robot_mate", "WALA KA PA LANG BINATBAT EH!", "Na-checkmate ka ng robot"),
        USER_CAPTURE("chess_line_user_capture", "Huwag mo naman masyadong galingan.", "Nakakain ka ng piyesa ng robot"),
        USER_WIN("chess_line_user_win", "Ang galing mo naman.", "Natalo mo ang robot"),
        DRAW("chess_line_draw", "Tabla tayo!", "Tabla"),
        EXIT("chess_line_exit", "Okay, sige. Salamat sa laro!", "Paglabas: ayaw mo na mag-chess")
    }

    companion object {
        /** Mga sasabihin para LUMABAS sa chess (dagdag sa "ayaw ko na maglalaro" na pangkalahatan). */
        const val DEFAULT_EXIT_PHRASES =
            "ayaw ko na mag chess\nayaw ko nang mag chess\nayoko na mag chess\nayoko nang mag chess\n" +
                "ayaw ko na mag-chess\nayoko na mag-chess\nayaw ko na maglaro ng chess\nayoko na maglaro ng chess\n" +
                "tama na ang chess\ntigil chess\nexit chess\n" +
                "ayaw ko na mag ches\nayaw ko na mag chest\nayaw ko na mag tses\nayoko na mag ches\nayoko na mag tses"

        const val DEFAULT_START_PHRASES =
            "maglaro tayo ng chess\nlaro tayo ng chess\nmag chess tayo\nmag-chess tayo\nchess tayo\n" +
                "let's play chess\nplay chess\nmaglaro tayo ng ahedres\nmag ahedres tayo\n" +
                "maglaro tayo ng ches\nmaglaro tayo ng chest\nmaglaro tayo ng tses"
    }

    fun text(l: Line): String = prefs.getString(l.prefKey, null)?.takeIf { it.isNotBlank() } ?: l.default
    fun track(l: Line): Int = prefs.getInt(l.prefKey + "_track", 0)
    fun setLine(l: Line, text: String, track: Int) {
        prefs.edit()
            .putString(l.prefKey, text.trim().ifEmpty { l.default })
            .putInt(l.prefKey + "_track", track.coerceAtLeast(0))
            .apply()
    }

    var startPhrasesRaw: String
        get() = prefs.getString("chess_start_phrases", null) ?: DEFAULT_START_PHRASES
        set(value) { prefs.edit().putString("chess_start_phrases", value).apply() }

    /** 1 = madali .. 4 = mahirap */
    var level: Int
        get() = prefs.getInt("chess_level", 3).coerceIn(1, 4)
        set(value) { prefs.edit().putInt("chess_level", value.coerceIn(1, 4)).apply() }

    var userIsWhite: Boolean
        get() = prefs.getBoolean("chess_user_white", true)
        set(value) { prefs.edit().putBoolean("chess_user_white", value).apply() }

    /** Haba ng shake kapag nakakain ang robot (ms). 0 = walang shake. */
    var shakeMs: Int
        get() = prefs.getInt("chess_shake_ms", 100).coerceIn(0, 1500)
        set(value) { prefs.edit().putInt("chess_shake_ms", value.coerceIn(0, 1500)).apply() }

    var exitPhrasesRaw: String
        get() = prefs.getString("chess_exit_phrases", null) ?: DEFAULT_EXIT_PHRASES
        set(value) { prefs.edit().putString("chess_exit_phrases", value).apply() }

    fun exitPhrases(): List<String> =
        exitPhrasesRaw.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Paglabas sa chess (ayaw mo na): sumasagot ang robot. */
    fun onExit() = say(Line.EXIT)

    fun startPhrases(): List<String> =
        startPhrasesRaw.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun say(l: Line) = host.say(text(l), track(l))

    fun onEvent(e: ChessEvent) {
        when (e) {
            is ChessEvent.Started -> say(Line.START)

            is ChessEvent.UserMove -> {
                // Nakakain ka ng piyesa ng robot
                if (e.captured) say(Line.USER_CAPTURE)
            }

            is ChessEvent.RobotMove -> {
                // Isang sabay na pagsasalita (para hindi maputol ng sumunod na speak()); sa TTS, pinagsama ang mga linya
                val special = when {
                    e.mate -> Line.ROBOT_MATE
                    e.captured -> Line.ROBOT_CAPTURE
                    else -> null
                }
                val spoken = text(Line.ROBOT_MOVE) + (if (special != null) " " + text(special) else "")
                // DFPlayer: isang track lang - ang pinaka-tiyak na may track
                val trackToPlay = listOfNotNull(special, Line.ROBOT_MOVE).map { track(it) }.firstOrNull { it > 0 } ?: 0
                host.say(spoken, trackToPlay)
            }

            is ChessEvent.RobotLanded -> {
                // "Kapag nakakain ang robot, mag-shake ng mga 100 ms"
                if (e.captured && shakeMs > 0) host.shake(shakeMs)
            }

            is ChessEvent.GameOver -> when (e.result) {
                ChessResult.USER_WON -> say(Line.USER_WIN)
                ChessResult.DRAW -> say(Line.DRAW)
                ChessResult.ROBOT_WON -> {}   // nasabi na ang "WALA KA PA LANG BINATBAT EH!" kasabay ng mating tira
            }

            is ChessEvent.IllegalMove -> {}
        }
    }
}
