package com.example.facerobot

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Format at lohika ng Export/Import Settings (pure Kotlin, walang Android class - kaya na-te-test sa PC).
 *
 * Ang file ay JSON na nababasa ng tao:
 * {
 *   "format": "rustech-settings", "version": 1, "created": "...", "secrets": true|false,
 *   "sections": { "app": { "key": {"t":"s|i|l|f|b|ss","v":...}, ... }, "commands": {...}, ... }
 * }
 */
object SettingsBackupCore {
    const val FORMAT = "rustech-settings"
    const val VERSION = 1
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    /** Mga seksyon at ang SharedPreferences file na kinakatawan nila. Ito LANG ang puwedeng i-export/import. */
    enum class Section(val id: String, val prefsName: String, val label: String, val defaultOn: Boolean) {
        APP("app", "facerobot_prefs", "Settings ng app (IP ng ESP32, boses, Gemini, nav, atbp.)", true),
        COMMANDS("commands", "command_store", "Mga Utos (voice commands)", true),
        SMARTHOME("smarthome", "smarthome_prefs", "Smart Home (RM, strip, mga IR code)", true),
        FACES("faces", "face_store", "Mga mukha (face data)", false);

        companion object {
            fun byId(id: String): Section? = values().firstOrNull { it.id == id }
        }
    }

    /** Hindi kailanman isinasama: mga bilang/cache na sa device lang na iyon. */
    private val NEVER_KEYS = setOf("gemini_count", "gemini_count_day", "rm_info")

    /** Mga lihim. Isinasama LAMANG kapag pinili ni user ang "isama ang secrets". */
    private val SECRET_KEYS = setOf(
        "gemini_api_key", "elevenlabs_api_key",
        "tuya_access_id", "tuya_access_secret", "tuya_local_key"
    )

    fun isSecret(key: String): Boolean = key in SECRET_KEYS

    /** Isang value mula sa SharedPreferences.getAll(): String, Int, Long, Float, Boolean, o Set<String>. */
    private fun encodeValue(v: Any): String? = when (v) {
        is String -> "{\"t\":\"s\",\"v\":${q(v)}}"
        is Int -> "{\"t\":\"i\",\"v\":${q(v.toString())}}"
        is Long -> "{\"t\":\"l\",\"v\":${q(v.toString())}}"
        is Float -> "{\"t\":\"f\",\"v\":${q(v.toString())}}"
        is Boolean -> "{\"t\":\"b\",\"v\":$v}"
        is Set<*> -> "{\"t\":\"ss\",\"v\":[${v.filterIsInstance<String>().sorted().joinToString(",") { q(it) }}]}"
        else -> null
    }

    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * @param all  prefsName -> (key -> value)  (galing sa SharedPreferences.getAll())
     */
    fun buildExport(
        all: Map<String, Map<String, Any?>>,
        sections: Set<Section>,
        includeSecrets: Boolean,
        now: ZonedDateTime = ZonedDateTime.now()
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": \"$FORMAT\",\n")
        sb.append("  \"version\": $VERSION,\n")
        sb.append("  \"created\": ${q(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))},\n")
        sb.append("  \"secrets\": $includeSecrets,\n")
        sb.append("  \"sections\": {\n")
        val parts = ArrayList<String>()
        for (section in Section.values()) {
            if (section !in sections) continue
            val entries = (all[section.prefsName] ?: emptyMap()).entries
                .filter { it.key !in NEVER_KEYS && (includeSecrets || it.key !in SECRET_KEYS) }
                .sortedBy { it.key }
                .mapNotNull { (k, v) -> v?.let { encodeValue(it) }?.let { "      ${q(k)}: $it" } }
            parts.add("    ${q(section.id)}: {\n${entries.joinToString(",\n")}${if (entries.isEmpty()) "" else "\n"}    }")
        }
        sb.append(parts.joinToString(",\n"))
        sb.append("\n  }\n}\n")
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------------

    sealed class Value {
        data class Str(val v: String) : Value()
        data class Int32(val v: Int) : Value()
        data class Int64(val v: Long) : Value()
        data class Flt(val v: Float) : Value()
        data class Bool(val v: Boolean) : Value()
        data class StrSet(val v: Set<String>) : Value()
    }

    class Parsed(
        val created: String,
        val hasSecrets: Boolean,
        /** section -> (key -> value) */
        val sections: Map<Section, Map<String, Value>>,
        val skippedKeys: Int
    ) {
        fun count(section: Section): Int = sections[section]?.size ?: 0
    }

    class ImportException(message: String) : Exception(message)

    fun parse(text: String): Parsed {
        if (text.length > MAX_FILE_BYTES) throw ImportException("Masyadong malaki ang file")
        val root = try {
            MiniJson.obj(MiniJson.parse(text))
        } catch (e: Exception) {
            throw ImportException("Hindi ito valid na settings file (hindi mabasa ang laman)")
        } ?: throw ImportException("Hindi ito valid na settings file")

        if (MiniJson.str(root["format"]) != FORMAT) throw ImportException("Hindi ito settings file ng RUSTECH")
        val version = (root["version"] as? Double)?.toInt() ?: throw ImportException("Walang version ang file")
        if (version > VERSION) throw ImportException("Mas bago ang file (v$version) kaysa sa app na ito - i-update muna ang app")
        if (version < 1) throw ImportException("Maling version ng file")

        val sectionsObj = MiniJson.obj(root["sections"]) ?: throw ImportException("Walang laman ang file")
        val out = LinkedHashMap<Section, Map<String, Value>>()
        var skipped = 0
        for ((sid, sval) in sectionsObj) {
            val section = Section.byId(sid) ?: run { skipped++; null } ?: continue
            val entries = MiniJson.obj(sval) ?: continue
            val values = LinkedHashMap<String, Value>()
            for ((key, raw) in entries) {
                if (key.isBlank() || key.length > 200 || key in NEVER_KEYS) { skipped++; continue }
                val v = decode(MiniJson.obj(raw))
                if (v == null) { skipped++; continue }
                values[key] = v
            }
            out[section] = values
        }
        if (out.isEmpty()) throw ImportException("Walang makikilalang settings sa file")
        return Parsed(
            created = MiniJson.str(root["created"]),
            hasSecrets = root["secrets"] == true,
            sections = out,
            skippedKeys = skipped
        )
    }

    private fun decode(o: Map<String, Any?>?): Value? {
        if (o == null) return null
        val v = o["v"]
        return try {
            when (MiniJson.str(o["t"])) {
                "s" -> (v as? String)?.let { Value.Str(it) }
                "i" -> Value.Int32(MiniJson.str(v).toInt())
                "l" -> Value.Int64(MiniJson.str(v).toLong())
                "f" -> Value.Flt(MiniJson.str(v).toFloat())
                "b" -> (v as? Boolean)?.let { Value.Bool(it) }
                "ss" -> MiniJson.arr(v)?.let { list ->
                    if (list.any { it !is String }) null else Value.StrSet(list.map { it as String }.toSet())
                }
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun suggestedFileName(now: ZonedDateTime = ZonedDateTime.now()): String =
        "rustech-settings-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".json"
}
