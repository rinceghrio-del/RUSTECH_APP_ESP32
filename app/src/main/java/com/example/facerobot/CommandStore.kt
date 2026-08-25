package com.example.facerobot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nag-iimbak ng mga custom na voice command (trigger phrase -> sasabihing reply, at
 * opsyonal na ESP32 action gaya ng "LEFT"/"SPIN") na itina-type mismo ng user sa loob
 * ng app - gamit ang SharedPreferences bilang simpleng JSON, kagaya ng ginawa natin
 * sa FaceStore.
 */
class CommandStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "command_store"
        private const val KEY_COMMANDS = "custom_commands_json"
        private const val KEY_DEFAULTS_SEEDED = "defaults_seeded_v1"
    }

    // action = "" kung walang ipapadalang utos sa ESP32, magsasalita lang.
    data class VoiceCommand(val trigger: String, val reply: String, val action: String = "")

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
    fun add(trigger: String, reply: String, action: String = "") {
        val cleanTrigger = trigger.trim().lowercase()
        commands.removeAll { it.trigger == cleanTrigger }
        commands.add(VoiceCommand(cleanTrigger, reply.trim(), action.trim().uppercase()))
        persist()
    }

    fun remove(trigger: String) {
        commands.removeAll { it.trigger == trigger }
        persist()
    }

    /**
 * Isang beses lang tatakbo ito (may naka-save na flag) - naglalagay ng mga paunang
 * custom command na ginawa na ni idol, para hindi na kailangan i-type ulit tuwing
 * bagong install/build. Kung may tatanggalin siya dito mamaya via "Mga Utos" menu,
 * hindi na ito babalik - isang beses lang talaga ito tumatakbo.
 */
fun seedDefaultsIfNeeded() {
    if (prefs.getBoolean(KEY_DEFAULTS_SEEDED, false)) return

    val defaults = listOf(
        VoiceCommand("abante", "ok sige", "FORWARD"),
        VoiceCommand("saan ka papunta", "wala akong pupuntahan paikot ikot lang ako dito", "RIGHT"),
        VoiceCommand("sino ba si rasti", "si rusty yung magaling na developer! nagtatrabaho sa globe. at nakatira sa pagbilao"),
        VoiceCommand("sino si rasti", "si rusty yung magaling na developer! nagtatrabaho sa globe. at nakatira sa pagbilao"),
        VoiceCommand("ayaw ko nga", "sige kung ayaw mo eh diwag"),
        VoiceCommand("oo ang pangit", "ikaw pangit din"),
        VoiceCommand("nakaupo", "sige upo ka lang diyan"),
        VoiceCommand("mas pangit ka", "eh di wow"),
        VoiceCommand("ikaw saan papunta", "ikot ikot lang", "LEFT"),
        VoiceCommand("ikaw sa'n papunta", "ikot lng ng ikot dito", "LEFT"),
        VoiceCommand("ayaw ko", "kung ayaw mo wag mo"),
        VoiceCommand("ok lang", "sige mabuti at ok lang"),
        VoiceCommand("ikaw anong gawa mo", "paikot ikot lang", "LEFT"),
        VoiceCommand("anong gawa mo", "ikot ikot lang", "LEFT"),
        VoiceCommand("oo ang ganda", "oo naman., pogi kasi ang gumawa sa akin", "FORWARD"),
        VoiceCommand("sayaw", "ok sige sasayaw ako.. wag ka tatawa ha!", "DANCE"),
        VoiceCommand("ilang taon kana", "wala akong edad pero kagagawa lang sa akin ni engineer rusty"),
        VoiceCommand("saan ka galing", "sa laboratoryo ni rusty"),
        VoiceCommand("saan ka nakatira", "nakatira ako sa laboratoryo ni rusty"),
        VoiceCommand("oo kanina pa", "sige, mabuti naman."),
        VoiceCommand("oo", "OK!, OK!."),
        VoiceCommand("paano ka ginawa", "AKO AY BINUO SA LIKHANG ISIP NI RUSTY"),
        VoiceCommand("nakakain ka ba ng pagkain", "ayaw! ayaw! kuryente lang kinakain ko", "SHAKING"),
        VoiceCommand("ikaw ba kumain na", "hindi ako na kain", "SHAKING"),
        VoiceCommand("power meter", "OK! OK!"),
        VoiceCommand("buksan ang laser", "sige, binubuksan ko na", "LASER_ON"),
        VoiceCommand("patayin ang laser", "sige pinapatay ko na", "LASER_OFF"),
    )

    for (cmd in defaults) {
        add(cmd.trigger, cmd.reply, cmd.action)
    }

    prefs.edit().putBoolean(KEY_DEFAULTS_SEEDED, true).apply()
}

    fun all(): List<VoiceCommand> = commands.toList()

    /** Hinahanap ang unang command na "nakapaloob" sa sinabi ng user. Null kung wala. */
    fun findMatch(spokenText: String): VoiceCommand? {
        return commands.firstOrNull { spokenText.contains(it.trigger) }
    }
}
