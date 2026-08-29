package com.example.facerobot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nag-iimbak ng mga custom na voice command (trigger phrase -> sasabihing reply, at
 * opsyonal na ESP32 action gaya ng "LEFT"/"SPIN") na itina-type mismo ng user sa loob
 * ng app - gamit ang SharedPreferences bilang simpleng JSON, kagaya ng ginawa natin
 * sa FaceStore.
 *
 * Bersyon na ito: mas marami at mas "buhay" na mga default command, may randomized
 * replies (ilang variation bawat trigger, hindi laging pareho ang sasabihin) at mas
 * matalinong pagtutugma (pinaka-partikular/pinakamahabang trigger ang pinipili kapag
 * may sabay-sabay na tugma), para mas mukhang tunay na AI ang dating - kahit walang
 * offline Llama model na kasama.
 */
class CommandStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "command_store"
        private const val KEY_COMMANDS = "custom_commands_json"
        private const val KEY_DEFAULTS_SEEDED = "defaults_seeded_v2"

        // Ginagamit para paghiwalayin ang ilang variation ng reply sa loob ng isang
        // command, para random na pipiliin sa TTS - kaya kahit paulit-ulit tanungin
        // hindi laging pareho ang sagot niya.
        private const val REPLY_SEPARATOR = "||"
    }

    // action = "" kung walang ipapadalang utos sa ESP32, magsasalita lang.
    // reply: maaaring maglaman ng ilang variation na pinaghihiwalay ng "||" - gamitin
    // ang randomReply() para makakuha ng isang random na bersyon tuwing sasagot.
    data class VoiceCommand(val trigger: String, val reply: String, val action: String = "") {
        fun randomReply(): String {
            val variants = reply.split(REPLY_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
            return if (variants.isEmpty()) reply else variants.random()
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val commands = mutableListOf<VoiceCommand>()

    init {
        load()
    }

    private fun load() {
        commands.clear()
        val json = prefs.getString(KEY_COMMANDS, null) ?: return
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                commands.add(
                    VoiceCommand(
                        obj.getString("trigger"),
                        obj.getString("reply"),
                        obj.optString("action", "") // "" kung wala pa dating action noon (lumang data)
                    )
                )
            }
        } catch (e: Exception) {
            // Kung sira yung saved JSON sa kadahilanang ano man, mag-start na lang tayo ulit
            // sa blangkong listahan imbes na mag-crash.
            commands.clear()
        }
    }

    private fun persist() {
        val array = JSONArray()
        for (cmd in commands) {
            val obj = JSONObject()
            obj.put("trigger", cmd.trigger)
            obj.put("reply", cmd.reply)
            obj.put("action", cmd.action)
            array.put(obj)
        }
        prefs.edit().putString(KEY_COMMANDS, array.toString()).apply()
    }

    /** Idinadagdag o pinapalitan (kung existing na ang trigger phrase) ang isang command. */
        /** Idinadagdag o pinapalitan (kung existing na ang trigger phrase) ang isang command.
     * Pwedeng maglagay ng maraming variant sa trigger, pinaghihiwalay ng "|" (hal.
     * "idol|idul|i don't") - lahat sila magiging hiwalay na entry na parehong reply/action. */
    fun add(trigger: String, reply: String, action: String = "") {
        val variants = trigger.split("|").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (cleanTrigger in variants) {
            commands.removeAll { it.trigger == cleanTrigger }
            commands.add(VoiceCommand(cleanTrigger, reply.trim(), action.trim().uppercase()))
        }
        persist()
    }

    /**
     * Isang beses lang tatakbo ito (may naka-save na flag) - naglalagay ng mga paunang
     * custom command na ginawa na ni idol, para hindi na kailangan i-type ulit tuwing
     * bagong install/build. Kung may tatanggalin siya dito mamaya via "Mga Utos" menu,
     * hindi na ito babalik - isang beses lang talaga ito tumatakbo.
     *
     * KEY_DEFAULTS_SEEDED ay na-bump sa "v2" kaya kahit may lumang install na na-seed na
     * dati sa "v1", muli itong magse-seed ng mas kumpleto/mas maraming listahan - pero
     * hindi babaguhin/babawiin ang mga sarili niyang na-edit o na-delete na na command,
     * dahil `add()` ay palit-lang (upsert) at hindi nagbabawas ng ibang existing entries.
     */
    fun seedDefaultsIfNeeded() {
        if (prefs.getBoolean(KEY_DEFAULTS_SEEDED, false)) return

        val defaults = listOf(
            // ===== Galaw / movement demo =====
            VoiceCommand("abante", "ok sige||sige, abante na||heto na, abante ako", "FORWARD"),
            VoiceCommand("umatras", "sige, aatras ako||heto, atras na||ok, aatras muna ako", "BACKWARD"),
            VoiceCommand("kumaliwa", "sige, kaliwa||heto, papuntang kaliwa ako", "LEFT"),
            VoiceCommand("kumanan", "sige, kanan||heto, papuntang kanan ako", "RIGHT"),
            VoiceCommand("tumigil", "sige, tumitigil na ako||ok, hinto na", "STOP"),
            VoiceCommand("sayaw", "ok sige sasayaw ako.. wag ka tatawa ha!||sige, panoorin mo 'to", "DANCE"),
            VoiceCommand("buksan ang laser|laser on", "sige, binubuksan ko na||ok, laser on", "LASER_ON"),
            VoiceCommand("patayin ang laser|laser off", "sige pinapatay ko na||ok, laser off na", "LASER_OFF"),

            // ===== Saan/ano ka papunta / ginagawa (may kasamang galaw para buhay) =====
            VoiceCommand(
                "saan ka papunta",
                "wala akong pupuntahan paikot ikot lang ako dito||ikot ikot lang, wala namang malayo",
                "RIGHT"
            ),
            VoiceCommand(
                "ikaw saan papunta",
                "ikot ikot lang||ikot lng ng ikot dito",
                "LEFT"
            ),
            VoiceCommand(
                "ikaw sa'n papunta",
                "ikot lng ng ikot dito||ikot ikot lang ako dito",
                "LEFT"
            ),
            VoiceCommand(
                "anong gawa mo",
                "ikot ikot lang||wala, nagbabantay lang dito",
                "LEFT"
            ),
            VoiceCommand(
                "ikaw anong gawa mo",
                "paikot ikot lang||nagbabantay lang ako dito",
                "LEFT"
            ),

            // ===== Pagkakakilanlan / identity =====
            VoiceCommand(
                "sino ba si rasti|sino si rasti|sino si rusty",
                "si rusty yung magaling na developer! nagtatrabaho sa globe, at nakatira sa pagbilao",
                ""
            ),
            VoiceCommand(
                "sino ka",
                "ako yung robot na ginawa ni engineer rusty||isa akong robot na likha ni engineer rusty"
            ),
            VoiceCommand(
                "paano ka ginawa",
                "AKO AY BINUO SA LIKHANG ISIP NI RUSTY||ginawa ako ni rusty gamit ang esp32 at kaunting mahika"
            ),
            VoiceCommand(
                "ilang taon kana",
                "wala akong edad pero kagagawa lang sa akin ni engineer rusty||bagong gawa lang ako, wala pang taon"
            ),
            VoiceCommand(
                "saan ka galing",
                "sa laboratoryo ni rusty||galing ako sa workshop ni rusty"
            ),
            VoiceCommand(
                "saan ka nakatira",
                "nakatira ako sa laboratoryo ni rusty||dito lang ako nakatira, sa tabi ni rusty"
            ),
            VoiceCommand(
                "nakakain ka ba ng pagkain",
                "ayaw! ayaw! kuryente lang kinakain ko",
                "SHAKING"
            ),
            VoiceCommand(
                "ikaw ba kumain na",
                "hindi ako na kain||kuryente lang po ang kinakain ko",
                "SHAKING"
            ),

            // ===== Simpleng pang-araw-araw / small talk =====
            VoiceCommand("kumusta|kamusta", "ayos lang ako, ikaw kumusta ka naman?||ok lang ako, salamat sa pagtanong"),
            VoiceCommand("magandang umaga", "magandang umaga rin sa'yo!||umaga na pala, magandang umaga"),
            VoiceCommand("magandang hapon", "magandang hapon din sa'yo!"),
            VoiceCommand("magandang gabi", "magandang gabi rin, matulog ka ng maaga||magandang gabi, ingat ka"),
            VoiceCommand("salamat", "walang anuman!||okay lang yan, laging tutok ako para maka usap ka"),
            VoiceCommand("mahal kita", "mahal din kita, kaibigan!||salamat, mahal din kita"),
            VoiceCommand(
                "nakaupo",
                "sige upo ka lang diyan||ok, magpahinga ka muna"
            ),
            VoiceCommand("oo", "OK!, OK!.||sige, gets ko"),
            VoiceCommand("oo kanina pa", "sige, mabuti naman.||ok, matagal ka na palang naghihintay"),
            VoiceCommand("ok lang", "sige mabuti at ok lang||ayos, mabuti naman"),
            VoiceCommand("ayaw ko nga", "sige kung ayaw mo eh diwag||okay, respeto ako sa desisyon mo"),
            VoiceCommand("ayaw ko", "kung ayaw mo wag mo||ok lang, sabihin mo lang kung ano gusto mo"),
            VoiceCommand("power meter", "OK! OK!||maayos naman ang kuryente ko ngayon"),

            // ===== Banat / patawa (light banter, para may personality) =====
            VoiceCommand("oo ang pangit", "ikaw pangit din||pangit ka din sabihin mo na lang sa salamin yan ha ha ha"),
            VoiceCommand("mas pangit ka", "eh di wow||ay sige, ikaw na maganda"),
            VoiceCommand(
                "oo ang ganda",
                "oo naman., pogi kasi ang gumawa sa akin||salamat, marunong talaga si rusty gumawa",
                "FORWARD"
            ),
            VoiceCommand("bobo ka", "hindi ako bobo, robot lang ako na may limitasyon||sige, tama ka na po"),
            VoiceCommand("galing mo", "salamat! galing din ni rusty na gumawa sa akin||salamat, ikaw din galing mo"),

            // ===== Estado ng robot =====
            VoiceCommand("okay ka lang ba", "oo, okay lang ako||ayos naman ako, salamat sa pag-alala"),
            VoiceCommand("busy ka ba", "hindi naman, bakante ako ngayon||konti lang trabaho ko ngayon"),
            VoiceCommand("pagod ka na ba", "hindi ako napapagod, robot ako eh||konting recharge lang kailangan ko paminsan-minsan"),
        )

        for (cmd in defaults) {
            // Sinusuportahan ang maraming magkasingkahulugang trigger sa isang entry
            // (pinaghihiwalay ng "|") - hinahati dito bago i-save, dahil `add()`/matching
            // ay isang trigger lang bawat command.
            val triggerVariants = cmd.trigger.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            for (t in triggerVariants) {
                add(t, cmd.reply, cmd.action)
            }
        }

        prefs.edit().putBoolean(KEY_DEFAULTS_SEEDED, true).apply()
    }

    fun all(): List<VoiceCommand> = commands.toList()

    /**
     * Hinahanap ang pinaka-partikular (pinakamahabang trigger) na command na "nakapaloob"
     * sa sinabi ng user. Null kung wala. Mas mabuti ito kaysa sa unang tugma lang, dahil
     * kung halimbawa may "ayaw ko" at "ayaw ko nga" na parehong command, mas gusto natin
     * matugma yung mas partikular/mahabang trigger kapag pareho namang nasa loob ng sinabi.
     */
    fun findMatch(spokenText: String): VoiceCommand? {
        val normalized = spokenText.trim().lowercase()
        return commands
            .filter { normalized.contains(it.trigger) }
            .maxByOrNull { it.trigger.length }
    }
}
