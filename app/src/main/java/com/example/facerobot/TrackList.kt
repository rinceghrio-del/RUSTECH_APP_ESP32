package com.example.facerobot

import java.util.Random

/**
 * === PATAKARAN SA LAHAT NG DFPLAYER TRACK SETTING (kasalukuyan at HINDI pa naidadagdag) ===
 * Ang bawat track field ay LISTAHAN na (hal. "12" o "12,13,14", random ang pipiliin) - hindi na isang numero lang.
 * Para sa bagong talent/feature ng robot, gamitin ang TrackSetting sa ibaba (storage + random pick + lumang
 * isahang-numero na setting), at sa UI gawing TYPE_CLASS_TEXT ang input (hindi NUMBER) na may hint
 * "hal. 12 o 12,13,14 (random)". Sa custom command action, "dfplayer play 12,13,14" ay random na rin.
 *
 * Listahan ng DFPlayer track na pinaghihiwalay ng kuwit, hal. "12,13,14". Random ang pipiliin tuwing tutugtog.
 * Tumatanggap din ng espasyo o semicolon bilang hiwalay ("12 13", "12;13"). Ang 0 o walang laman = walang track.
 */
object TrackList {
    fun parse(raw: String): List<Int> =
        raw.split(Regex("[,;\\s]+")).mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }

    /** Linisin para sa pag-save: "12, 13 ;x, 0, 14" -> "12,13,14". */
    fun normalize(raw: String): String = parse(raw).joinToString(",")

    /** Isang random na track mula sa listahan, o 0 kung walang laman. */
    fun pick(raw: String, rnd: Random = Random()): Int {
        val list = parse(raw)
        return if (list.isEmpty()) 0 else list[rnd.nextInt(list.size)]
    }

    /** Para sa action field ng custom command: "dfplayer play 12" o "dfplayer play 12,13,14" (random). */
    val actionRegex = Regex("dfplayer\\s*play\\s*(\\d+(?:\\s*[,;]\\s*\\d+)*)", RegexOption.IGNORE_CASE)
}

/**
 * Isang saved na DFPlayer track setting na listahan (random pick). Ilagay lang ang SharedPreferences, ang key na
 * gagamitin, at (kung dati itong isang numero lang) ang lumang int key at ang default nito para hindi mawala ang
 * dating naka-save. Gamitin ito para sa BAGONG talent ng robot: isang linya lang, may multi-track na agad.
 */
class TrackSetting(
    private val prefs: android.content.SharedPreferences,
    private val key: String,
    private val legacyIntKey: String? = null,
    private val legacyDefault: Int = 0
) {
    var raw: String
        get() = prefs.getString(key, null) ?: legacy()
        set(value) { prefs.edit().putString(key, TrackList.normalize(value)).apply() }

    /** Isang random na track (0 = wala). */
    fun pick(): Int = TrackList.pick(raw)

    fun hasTrack(): Boolean = TrackList.parse(raw).isNotEmpty()

    private fun legacy(): String {
        val k = legacyIntKey ?: return ""
        val v = try { prefs.getInt(k, legacyDefault) } catch (e: ClassCastException) { legacyDefault }
        return if (v > 0) v.toString() else ""
    }
}
