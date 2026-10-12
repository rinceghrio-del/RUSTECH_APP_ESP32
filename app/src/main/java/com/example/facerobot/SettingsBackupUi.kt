package com.example.facerobot

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.facerobot.SettingsBackupCore.Section

/**
 * Export / Import ng settings. Gumagamit ng Storage Access Framework (walang kailangang permission).
 * Ang pagbukas ng file picker (launchExport / launchImport) ay ibinibigay ng MainActivity.
 */
class SettingsBackupUi(
    private val activity: Activity,
    private val tr: (String) -> String,
    private val openDialog: (
        title: String,
        content: View,
        positive: String?,
        onPositive: (() -> Unit)?,
        negative: String?,
        onNegative: (() -> Unit)?
    ) -> Unit,
    private val onStatus: (String) -> Unit,
    private val launchExport: (suggestedName: String) -> Unit,
    private val launchImport: () -> Unit
) {
    // Ang export na naghihintay ng lugar na pagsusulatan (galing sa file picker).
    private var pendingJson: String? = null
    private var pendingHasSecrets = false

    private fun pad() = (16 * activity.resources.displayMetrics.density).toInt()

    private fun box(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad() * 3 / 2, pad(), pad() * 3 / 2, pad())
    }

    private fun label(text: String, topDp: Int = 12, size: Float = 13f): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        setPadding(0, (topDp * activity.resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun toggle(text: String, checked: Boolean): Switch = Switch(activity).apply {
        this.text = text
        isChecked = checked
        setPadding(0, pad() / 2, 0, pad() / 2)
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(activity).apply {
        this.text = text
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun message(title: String, text: String, buttonText: String = tr("OK"), then: (() -> Unit)? = null) {
        val c = box()
        c.addView(label(text, 0, 14f))
        openDialog(title, ScrollView(activity).apply { addView(c) }, buttonText, then, null, null)
    }

    // ---------------------------------------------------------------------------------------

    fun show() {
        val c = box()
        c.addView(label(tr("I-save ang settings sa file para ilipat sa ibang phone, o i-import ang file mula sa ibang phone."), 0))

        c.addView(label(tr("Isama sa EXPORT:"), 14, 14f))
        val sectionSwitches = Section.values().associateWith { toggle(tr(it.label), it.defaultOn) }
        sectionSwitches.values.forEach { c.addView(it) }

        val secrets = toggle(tr("Isama ang API keys at secrets (Gemini, ElevenLabs, Tuya, Local Key)"), false)
        c.addView(secrets)
        c.addView(label(
            tr("⚠️ Kapag isinama ang secrets, ang sinumang makakakuha ng file ay magagamit ang mga key mo. Huwag ibahagi sa iba. Kung hindi isasama, ilalagay mo na lang ulit ang mga key sa bagong phone."),
            4, 11f
        ))

        c.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(tr("📤 I-export")) {
                val chosen = sectionSwitches.filter { it.value.isChecked }.keys
                if (chosen.isEmpty()) {
                    onStatus(tr("Pumili ng kahit isang isasama sa export"))
                } else {
                    try {
                        pendingJson = SettingsBackup.export(activity, chosen, secrets.isChecked)
                        pendingHasSecrets = secrets.isChecked
                        launchExport(SettingsBackupCore.suggestedFileName())
                    } catch (e: Exception) {
                        message(tr("Hindi na-export"), e.message ?: e.javaClass.simpleName)
                    }
                }
            })
            addView(button(tr("📥 I-import")) { launchImport() })
        })
        c.addView(label(tr("Ang pag-import ay papalitan ang mga napiling settings sa phone na ito at mangangailangan ng pag-restart ng app."), 10, 11f))

        openDialog(tr("📦 Export / Import Settings"), ScrollView(activity).apply { addView(c) }, null, null, tr("Isara"), null)
    }

    // ---------------------------------------------------------------------------------------
    // Tinatawag ng MainActivity kapag nakapili na ng file ang user.

    fun onExportUri(uri: Uri) {
        val json = pendingJson
        pendingJson = null
        if (json == null) {
            message(tr("Hindi na-export"), tr("Walang naka-handang export. Subukan ulit."))
            return
        }
        try {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException(tr("Hindi mabuksan ang file na pagsusulatan"))
            val warn = if (pendingHasSecrets) "\n\n" + tr("⚠️ May laman itong mga API key at secret. Huwag ibahagi sa iba.") else ""
            onStatus(tr("Na-export ang settings"))
            message(tr("✅ Na-export"), tr("Na-save ang settings file. Ilipat ito sa ibang phone at gamitin ang I-import.") + warn)
        } catch (e: Exception) {
            message(tr("Hindi na-export"), e.message ?: e.javaClass.simpleName)
        }
    }

    fun onImportUri(uri: Uri) {
        val parsed = try {
            val bytes = activity.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                ?: throw SettingsBackupCore.ImportException(tr("Hindi mabuksan ang file"))
            SettingsBackupCore.parse(String(bytes, Charsets.UTF_8))
        } catch (e: SettingsBackupCore.ImportException) {
            message(tr("Hindi ma-import"), e.message ?: "")
            return
        } catch (e: Exception) {
            message(tr("Hindi ma-import"), e.message ?: e.javaClass.simpleName)
            return
        }
        showImportPreview(parsed)
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > SettingsBackupCore.MAX_FILE_BYTES) {
                throw SettingsBackupCore.ImportException(tr("Masyadong malaki ang file"))
            }
        }
        return out.toByteArray()
    }

    private fun showImportPreview(parsed: SettingsBackupCore.Parsed) {
        val c = box()
        c.addView(label(tr("Na-export noong: {0}").replace("{0}", parsed.created.ifBlank { "?" }), 0))
        c.addView(label(tr("Piliin ang ilalagay sa phone na ito:"), 12, 14f))

        val switches = parsed.sections.keys.associateWith { s ->
            toggle("${tr(s.label)} (${parsed.count(s)})", true)
        }
        switches.values.forEach { c.addView(it) }

        c.addView(label(
            if (parsed.hasSecrets) tr("May kasamang API keys at secrets ang file na ito.")
            else tr("WALANG API keys o secrets ang file na ito. Ilagay ulit ang mga ito pagkatapos (Gemini, ElevenLabs, Tuya, Local Key)."),
            8, 12f
        ))
        if (parsed.skippedKeys > 0) {
            c.addView(label(tr("May {0} item na hindi nakilala at nilaktawan.").replace("{0}", parsed.skippedKeys.toString()), 4, 11f))
        }
        c.addView(label(
            tr("⚠️ Papalitan nito ang mga katumbas na settings sa phone na ito, at isasara ang app pagkatapos. Buksan ulit ito para magamit ang bagong settings."),
            10, 11f
        ))

        openDialog(
            tr("📥 I-import ang Settings"),
            ScrollView(activity).apply { addView(c) },
            tr("I-import"),
            {
                val chosen = switches.filter { it.value.isChecked }.keys
                if (chosen.isEmpty()) {
                    onStatus(tr("Walang napili"))
                } else {
                    try {
                        val n = SettingsBackup.apply(activity, parsed, chosen)
                        message(
                            tr("✅ Na-import"),
                            tr("Naisulat ang {0} setting. Isasara na ang app - buksan ulit ito.").replace("{0}", n.toString()),
                            tr("Isara ang app")
                        ) { closeApp() }
                    } catch (e: Exception) {
                        message(tr("Hindi ma-import"), e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            tr("Cancel"),
            null
        )
    }

    /** Isara nang buo ang app para mabasa ng lahat ng bahagi ang bagong settings pagbukas ulit. */
    private fun closeApp() {
        activity.finishAffinity()
        Handler(Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 300)
    }
}
