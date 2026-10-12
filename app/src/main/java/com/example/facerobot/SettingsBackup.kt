package com.example.facerobot

import android.content.Context
import com.example.facerobot.SettingsBackupCore.Section
import com.example.facerobot.SettingsBackupCore.Value

/** Pagbasa/pagsulat ng aktwal na SharedPreferences para sa Export/Import Settings. */
object SettingsBackup {

    private fun snapshot(context: Context): Map<String, Map<String, Any?>> =
        Section.values().associate { s ->
            s.prefsName to context.getSharedPreferences(s.prefsName, Context.MODE_PRIVATE).all.toMap()
        }

    fun export(context: Context, sections: Set<Section>, includeSecrets: Boolean): String =
        SettingsBackupCore.buildExport(snapshot(context), sections, includeSecrets)

    /**
     * Isulat ang napiling sections. MERGE kada key: ang mga key na wala sa file (hal. secrets na hindi isinama)
     * ay HINDI binubura. Sync (commit) ito dahil magre-restart ang app pagkatapos.
     * @return bilang ng mga key na naisulat
     */
    fun apply(context: Context, parsed: SettingsBackupCore.Parsed, sections: Set<Section>): Int {
        var written = 0
        for (section in sections) {
            val values = parsed.sections[section] ?: continue
            val editor = context.getSharedPreferences(section.prefsName, Context.MODE_PRIVATE).edit()
            for ((key, v) in values) {
                when (v) {
                    is Value.Str -> editor.putString(key, v.v)
                    is Value.Int32 -> editor.putInt(key, v.v)
                    is Value.Int64 -> editor.putLong(key, v.v)
                    is Value.Flt -> editor.putFloat(key, v.v)
                    is Value.Bool -> editor.putBoolean(key, v.v)
                    is Value.StrSet -> editor.putStringSet(key, v.v)
                }
                written++
            }
            if (!editor.commit()) throw IllegalStateException("Hindi naisulat ang ${section.id}")
        }
        return written
    }
}
