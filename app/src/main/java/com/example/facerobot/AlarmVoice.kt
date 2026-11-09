package com.example.facerobot

import java.util.Calendar

/**
 * Alarm ng ESP32 (main.cpp V5.5.0) - mga data class, pag-intindi ng boses (Taglish/English), at pag-format.
 * Walang Android dependency ang file na ito (para madaling i-test).
 *
 * days bitmask: bit0 = Linggo ... bit6 = Sabado. 0 = kahit anong araw.
 */
data class EspAlarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val once: Boolean,
    val enabled: Boolean,
    val track: Int,
    val label: String
)

data class AlarmState(
    val timeValid: Boolean,
    val nowEpoch: Long,
    val ringing: Boolean,
    val snooze: Boolean,
    val setId: Int,
    val alarms: List<EspAlarm>
)

sealed class AlarmIntent {
    /** Mag-set ng alarm. [relativeMinutes] != null = "after 30 minutes" (timer-style). */
    data class Set(
        val hour: Int,
        val minute: Int,
        val days: Int,
        val once: Boolean,
        val relativeMinutes: Int? = null
    ) : AlarmIntent()

    /** Magbura. [hour] null = walang sinabing oras. [hour12Only] = walang AM/PM kaya tugma ang AM at PM. */
    data class Delete(val hour: Int?, val minute: Int?, val hour12Only: Boolean, val all: Boolean) : AlarmIntent()

    data class Snooze(val minutes: Int) : AlarmIntent()
    object StopRinging : AlarmIntent()
    object ListAlarms : AlarmIntent()
    /** May "alarm" pero walang oras na naintindihan. */
    object NeedTime : AlarmIntent()
}

object AlarmFormat {
    private val dayShort = listOf("Lin", "Lun", "Mar", "Miy", "Huw", "Biy", "Sab")

    /** "7:00 PM" */
    fun time(h: Int, m: Int): String {
        val ap = if (h >= 12) "PM" else "AM"
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "$h12:${m.toString().padStart(2, '0')} $ap"
    }

    /** "araw-araw", "Lun-Biy", "isang beses", "tuwing Lun, Miy" ... */
    fun days(mask: Int, once: Boolean): String {
        val m = mask and 0x7F
        if (m == 0) return if (once) "isang beses" else "araw-araw"
        val prefix = if (once) "sa " else "tuwing "
        val body = when (m) {
            0x7F -> return if (once) "isang beses" else "araw-araw"
            62 -> "Lun-Biy"
            65 -> "Sab at Lin"
            else -> (0..6).filter { m and (1 shl it) != 0 }.joinToString(", ") { dayShort[it] }
        }
        return prefix + body
    }

    /** Pangungusap para sa pagsasalita ng robot: "7:00 PM at 6:30 AM" */
    fun spokenList(alarms: List<EspAlarm>): String {
        val times = alarms.filter { it.enabled }.map { time(it.hour, it.minute) }
        return when (times.size) {
            0 -> ""
            1 -> times[0]
            else -> times.dropLast(1).joinToString(", ") + " at " + times.last()
        }
    }
}

object AlarmVoiceParser {

    private enum class Period { AM, PM, NIGHT, NOON }

    private val NUM = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        // Tagalog at Kastilang pambilang sa orasan
        "isa" to 1, "isang" to 1, "una" to 1, "dalawa" to 2, "dalawang" to 2, "dos" to 2,
        "tatlo" to 3, "tatlong" to 3, "tres" to 3, "apat" to 4, "kuwatro" to 4, "kwatro" to 4, "cuatro" to 4,
        "lima" to 5, "limang" to 5, "singko" to 5, "cinco" to 5, "anim" to 6, "sais" to 6, "seis" to 6,
        "pito" to 7, "pitong" to 7, "siyete" to 7, "siete" to 7, "walo" to 8, "walong" to 8, "otso" to 8, "ocho" to 8,
        "siyam" to 9, "nuwebe" to 9, "nueve" to 9, "sampu" to 10, "sampung" to 10, "diyes" to 10, "diez" to 10,
        "onse" to 11, "dose" to 12, "doce" to 12, "kinse" to 15, "bente" to 20, "trenta" to 30,
        "kuwarenta" to 40, "singkwenta" to 50, "labindalawa" to 12, "labing" to -1
    )

    private val DAY_BITS = listOf(
        "linggo" to 0, "sunday" to 0, "sun" to 0,
        "lunes" to 1, "monday" to 1, "mon" to 1,
        "martes" to 2, "tuesday" to 2, "tue" to 2, "tues" to 2,
        "miyerkules" to 3, "wednesday" to 3, "wed" to 3,
        "huwebes" to 4, "thursday" to 4, "thu" to 4, "thurs" to 4,
        "biyernes" to 5, "friday" to 5, "fri" to 5,
        "sabado" to 6, "saturday" to 6, "sat" to 6
    )

    private fun normalize(text: String): String {
        var t = text.lowercase().replace('’', '\'')
        t = t.replace("a.m.", "am").replace("p.m.", "pm").replace("o'clock", "oclock")
        t = t.replace('-', ' ').replace('\'', ' ')
        t = t.map { if (it.isLetterOrDigit() || it == ':' || it.isWhitespace()) it else ' ' }.joinToString("")
        t = t.replace(Regex("(\\d)(am|pm)\\b"), "$1 $2")
        t = t.replace(Regex("\\b(\\d{1,2}) (a m|p m)\\b")) { m -> m.groupValues[1] + " " + m.groupValues[2].replace(" ", "") }
        t = t.replace(Regex("\\s+"), " ").trim()
        return convertNumberWords(t)
    }

    /** "seven thirty" -> "7 30", "alas siyete" -> "alas 7", "labing isa" -> "11", "twenty five" -> "25" */
    private fun convertNumberWords(t: String): String {
        val tokens = t.split(' ')
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            val v = NUM[tok]
            if (tok == "labing" && i + 1 < tokens.size && NUM[tokens[i + 1]] != null && NUM[tokens[i + 1]]!! in 1..9) {
                out.add((10 + NUM[tokens[i + 1]]!!).toString())
                i += 2
                continue
            }
            if (v != null && v >= 0) {
                if (v in 20..50 && v % 10 == 0 && i + 1 < tokens.size) {
                    val nx = NUM[tokens[i + 1]]
                    if (nx != null && nx in 1..9) {
                        out.add((v + nx).toString())
                        i += 2
                        continue
                    }
                }
                out.add(v.toString())
            } else {
                out.add(tok)
            }
            i++
        }
        return out.joinToString(" ")
    }

    private fun findPeriod(t: String): Period? {
        val m = Regex("\\b(am|pm|umaga|hapon|gabi|tanghali|madaling araw|morning|afternoon|evening|night|tonight|noon)\\b").find(t)
            ?: return null
        return when (m.groupValues[1]) {
            "am", "umaga", "morning", "madaling araw" -> Period.AM
            "pm", "hapon", "afternoon", "evening" -> Period.PM
            "gabi", "night", "tonight" -> Period.NIGHT
            else -> Period.NOON   // tanghali, noon
        }
    }

    /** (hour 0-23 kung tiyak, minute). Hour 1..12 na walang period -> [hour12Only]. */
    private data class RawTime(val hour: Int, val minute: Int, val period: Period?)

    private val DURATION = "(hours?|hrs?|oras|minutes?|mins?|min|minuto)"

    private fun findAbsoluteTime(t: String): RawTime? {
        val period = findPeriod(t)

        Regex("\\b(half|quarter) past (\\d{1,2})\\b").find(t)?.let { m ->
            val h = m.groupValues[2].toInt()
            return RawTime(h, if (m.groupValues[1] == "half") 30 else 15, period)
        }
        Regex("\\bquarter to (\\d{1,2})\\b").find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            return RawTime(if (h == 1) 12 else h - 1, 45, period)
        }
        Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(t)?.let { m ->
            return RawTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), period)
        }
        val keyed = Regex("\\b(?:alas|ala|alarm|alarma|to|for|at|ng|sa|ako|mo) (\\d{1,2}) (\\d{2})\\b(?! $DURATION)").find(t)
        if (keyed != null && keyed.groupValues[1].toInt() in 0..24 && keyed.groupValues[2].toInt() in 0..59) {
            return RawTime(keyed.groupValues[1].toInt(), keyed.groupValues[2].toInt(), period)
        }
        Regex("\\b(\\d{1,2}) (am|pm)\\b").find(t)?.let { m ->
            return withMedya(t, RawTime(m.groupValues[1].toInt(), 0, period))
        }
        Regex("\\b(\\d{1,2}) oclock\\b").find(t)?.let { m ->
            return RawTime(m.groupValues[1].toInt(), 0, period)
        }
        Regex("\\b(?:alas|ala) (\\d{1,2})\\b").find(t)?.let { m ->
            return withMedya(t, RawTime(m.groupValues[1].toInt(), 0, period))
        }
        // "set alarm to 7", "alarm ng 6", "gisingin mo ako ng 5"
        Regex("\\b(?:alarm|alarma|gisingin|wake me up|wake me|timer)\\b[a-z ]*? (\\d{1,2})\\b(?! $DURATION)(?! (?:araw|days?))")
            .find(t)?.let { m -> return withMedya(t, RawTime(m.groupValues[1].toInt(), 0, period)) }
        // "7 ng umaga alarm", "6 ng gabi"
        Regex("\\b(\\d{1,2}) (?:ng |sa |in the |at )?(?:umaga|hapon|gabi|tanghali|madaling araw|morning|afternoon|evening|night)\\b")
            .find(t)?.let { m -> return withMedya(t, RawTime(m.groupValues[1].toInt(), 0, period)) }
        return null
    }

    private fun withMedya(t: String, r: RawTime): RawTime =
        if (Regex("\\b(medya|media|kalahati|and a half)\\b").containsMatchIn(t)) r.copy(minute = 30) else r

    /** Hal. "after 30 minutes", "sa loob ng 10 minuto", "pagkalipas ng isang oras" -> minuto. */
    private fun findRelativeMinutes(t: String): Int? {
        Regex("\\b(?:after|in|within|loob ng|pagkalipas ng|pagkalipas|makalipas ang|makalipas ng|makalipas) (?:half an hour|half hour|kalahating oras)\\b")
            .find(t)?.let { return 30 }
        Regex("\\b(?:after|in|within|loob ng|pagkalipas ng|pagkalipas|makalipas ang|makalipas ng|makalipas) (\\d+|an|a) $DURATION\\b")
            .find(t)?.let { return toMinutes(it.groupValues[1], it.groupValues[2]) }
        Regex("\\b(\\d+) $DURATION (?:from now|mula ngayon|pa|na lang)\\b")
            .find(t)?.let { return toMinutes(it.groupValues[1], it.groupValues[2]) }
        Regex("\\btimer (?:for|ng|na|of)? ?(\\d+|an|a) $DURATION\\b")
            .find(t)?.let { return toMinutes(it.groupValues[1], it.groupValues[2]) }
        Regex("\\b(?:alarm|alarma) (?:for|ng|na|of|in) (\\d+|an|a) $DURATION\\b")
            .find(t)?.let { return toMinutes(it.groupValues[1], it.groupValues[2]) }
        return null
    }

    private fun toMinutes(num: String, unit: String): Int {
        val n = num.toIntOrNull() ?: 1
        return if (unit.startsWith("h") || unit == "oras") n * 60 else n
    }

    private fun resolveHour(raw: RawTime, tomorrow: Boolean, now: Calendar): Pair<Int, Boolean>? {
        val h = raw.hour
        val m = raw.minute
        if (m !in 0..59 || h !in 0..24) return null
        if (h == 24) return 0 to false
        if (h == 0 || h >= 13) return h to false
        when (raw.period) {
            Period.AM -> return (if (h == 12) 0 else h) to false
            Period.PM -> return (if (h == 12) 12 else h + 12) to false
            Period.NIGHT -> return (if (h == 12) 0 else h + 12) to false
            Period.NOON -> return (if (h == 12) 12 else if (h <= 5) h + 12 else h) to false
            null -> {}
        }
        if (tomorrow) return (if (h == 12) 12 else h) to false
        // Walang AM/PM: kunin ang pinakamalapit na darating na oras
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val am = (h % 12)
        val pm = (h % 12) + 12
        val pick = listOf(am, pm).firstOrNull { it * 60 + m > nowMin } ?: am
        return pick to true
    }

    private fun parseDays(t: String, now: Calendar): Triple<Int, Boolean, Boolean> {
        // (days, once, tomorrowFlag)
        val repeatWord = Regex("\\b(araw araw|everyday|every day|daily|tuwing|every|palagi|lagi|weekly)\\b").containsMatchIn(t)
        var mask = 0
        var implied = false   // "weekdays"/"weekends" ay laging paulit-ulit
        if (Regex("\\b(weekdays|weekday)\\b").containsMatchIn(t)) { mask = mask or 62; implied = true }
        if (Regex("\\b(weekends|weekend)\\b").containsMatchIn(t)) { mask = mask or 65; implied = true }
        for ((name, bit) in DAY_BITS) {
            if (Regex("\\b$name\\b").containsMatchIn(t)) mask = mask or (1 shl bit)
        }
        val tomorrow = Regex("\\b(bukas|tomorrow)\\b").containsMatchIn(t)
        if (tomorrow && mask == 0) {
            val c = now.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, 1)
            mask = 1 shl (c.get(Calendar.DAY_OF_WEEK) - 1)
            return Triple(mask, true, true)
        }
        if (mask == 0) return Triple(0, !repeatWord, false)
        val everyDay = mask == 0x7F
        return Triple(if (everyDay) 0 else mask, !repeatWord && !implied && !everyDay, false)
    }

    fun parse(text: String, now: Calendar = Calendar.getInstance()): AlarmIntent? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        val hasAlarmWord = Regex("\\b(alarm|alarma|alarms|gisingin|wake me up|wake me|timer)\\b").containsMatchIn(t)
        if (!hasAlarmWord) return null

        val mentionsAlarm = Regex("\\b(alarm|alarma|alarms)\\b").containsMatchIn(t)

        // 1) Listahan
        if (mentionsAlarm && Regex(
                "\\b(anong alarm|ano ang alarm|ano ang mga alarm|anong mga alarm|ano mga alarm|what alarms?|list alarms?|list of alarms?|" +
                    "show alarms?|mga alarm ko|alarms ko|ilan ang alarm|ilang alarm|may alarm ba|kailan ang alarm|" +
                    "what time is my alarm|anong oras ang alarm|check alarms?|do i have an alarm|do i have any alarms?)\\b"
            ).containsMatchIn(t)
        ) return AlarmIntent.ListAlarms

        // 2) Snooze
        if (Regex("\\b(snooze|isnooze|i snooze)\\b").containsMatchIn(t)) {
            val mins = Regex("\\b(\\d{1,2}) (?:minutes?|mins?|min|minuto)\\b").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            return AlarmIntent.Snooze(mins.coerceIn(1, 60))
        }

        val deleteVerb = Regex("\\b(cancel|icancel|i cancel|delete|remove|clear|burahin|iburahin|i burahin|tanggalin|itanggal|alisin|ialis|wag na|huwag na)\\b").containsMatchIn(t)
        val stopVerb = Regex("\\b(patayin|ipatay|i patay|i off|ioff|off|turn off|switch off|stop|silence|tigil|tigilan|tama na|okay na|ok na)\\b").containsMatchIn(t)
        val hasDigitsTime = findAbsoluteTime(t) != null

        // 3) Burahin / patayin
        if (mentionsAlarm && (deleteVerb || (stopVerb && hasDigitsTime))) {
            val all = Regex("\\b(lahat|all|every|bawat)\\b").containsMatchIn(t)
            val raw = findAbsoluteTime(t)
            if (raw == null) return AlarmIntent.Delete(null, null, false, all)
            // Para sa pagbura: kung walang AM/PM (1..12), tugma ang kahit AM o PM (hour12Only)
            val noPeriod = raw.period == null && raw.hour in 1..12
            if (noPeriod) return AlarmIntent.Delete(raw.hour % 12, raw.minute, true, all)
            val (h, _) = resolveHour(raw, false, now) ?: return AlarmIntent.Delete(null, null, false, all)
            return AlarmIntent.Delete(h, raw.minute, false, all)
        }
        if (mentionsAlarm && stopVerb) return AlarmIntent.StopRinging

        // 4) Mag-set
        findRelativeMinutes(t)?.let { mins ->
            val c = now.clone() as Calendar
            c.add(Calendar.MINUTE, mins)
            return AlarmIntent.Set(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), 0, true, mins)
        }
        val raw = findAbsoluteTime(t) ?: return AlarmIntent.NeedTime
        val (days, once, tomorrow) = parseDays(t, now)
        val (hour, _) = resolveHour(raw, tomorrow, now) ?: return AlarmIntent.NeedTime
        return AlarmIntent.Set(hour, raw.minute, days, once)
    }
}
