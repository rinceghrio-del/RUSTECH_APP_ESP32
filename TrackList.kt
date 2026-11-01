package com.example.facerobot

import java.util.Random

/**
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
}
