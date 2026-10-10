package com.example.facerobot

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Smart Home menu: RM mini 3 (IR) at Lasco power strip. Hiwalay na file para hindi lumaki pa
 * ang MainActivity. Ang dialog mismo (ModernDialog) ay ipinapasa ng MainActivity bilang openDialog.
 */
class SmartHomeUi(
    private val activity: Activity,
    private val smartHome: SmartHome,
    private val commandStore: CommandStore,
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
    // Subukan ang DFPlayer track sa ESP32 (callback ay sa UI thread). Hindi muted ang test kahit naka-ON ang Gemini.
    private val testTrack: (Int, (Boolean, String) -> Unit) -> Unit,
    private val isGeminiOn: () -> Boolean
) {
    private fun ui(block: () -> Unit) = activity.runOnUiThread(block)

    private val dfRegex = Regex("dfplayer\\s*play\\s*(\\d+)", RegexOption.IGNORE_CASE)

    private fun actionParts(action: String): List<String> =
        action.split("||").map { it.trim() }.filter { it.isNotEmpty() }

    private fun dfTrackOf(action: String): String =
        actionParts(action).firstNotNullOfOrNull { dfRegex.find(it)?.groupValues?.get(1) } ?: ""

    private fun withoutDf(action: String): String =
        actionParts(action).filter { !dfRegex.containsMatchIn(it) }.joinToString("||")

    /** Madaling basahin na paglalarawan ng action, hal. "Socket 1 ON + DFPlayer track 12". */
    private fun describeAction(action: String): String = actionParts(action).joinToString(" + ") { part ->
        val p = part.uppercase().split(":")
        when {
            dfRegex.containsMatchIn(part) -> "DFPlayer track " + dfRegex.find(part)!!.groupValues[1]
            p.size >= 3 && p[0] == "HOME" && p[1] == "IR" -> "IR " + p.drop(2).joinToString(":")
            p.size == 4 && p[0] == "HOME" && p[1] == "STRIP" -> {
                val what = when (p[2]) { "ALL" -> tr("Lahat ng outlet"); "USB" -> "USB"; else -> "Socket ${p[2]}" }
                "$what ${p[3]}"
            }
            else -> part
        }
    }

    // True habang bukas ang Smart Home dialog (para hindi ito biglang bumukas ulit kung isinara na).
    @Volatile private var visible = false

    private fun plainInput(hint: String, value: String, secret: Boolean = false): EditText =
        EditText(activity).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            setText(value)
        }

    private fun label(text: String, topDp: Int = 14, size: Float = 13f): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = size
            val d = activity.resources.displayMetrics.density
            setPadding(0, (topDp * d).toInt(), 0, 0)
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            setAllCaps(false)
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
            for (v in views) {
                val lp = if (v is EditText || v is TextView && v !is Button) {
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                addView(v, lp)
            }
        }

    fun show(initialMessage: String? = null) {
        visible = true
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val statusView = TextView(activity).apply {
            textSize = 12f
            text = initialMessage ?: tr("Handa. Walang ginagalaw sa Alexa - hiwalay na daan ang gamit ni RUSTECH.")
        }
        fun say(msg: String) = ui { statusView.text = msg }

        // ---------------- RM mini 3 ----------------
        container.addView(label("📡 Broadlink RM mini 3 (IR)", 0, 15f))
        val ipInput = plainInput(tr("IP ng RM mini 3 (hal. 192.168.1.50)"), smartHome.rmIp)

        // ---------------- Power strip ----------------
        val endpointInput = plainInput("Tuya endpoint", smartHome.tuyaEndpoint)
        val idInput = plainInput("Access ID / Client ID", smartHome.tuyaAccessId)
        val secretInput = plainInput("Access Secret", smartHome.tuyaAccessSecret, secret = true)
        val deviceInput = plainInput("Device ID ng Lasco strip", smartHome.tuyaDeviceId)
        val codesInput = plainInput("Socket codes (hal. switch_1,switch_2,switch_3,switch_4)", smartHome.tuyaSocketCodes)
        val stripIpInput = plainInput(tr("IP ng strip (hal. 192.168.1.4)"), smartHome.stripIp)
        val localKeyInput = plainInput(tr("Local Key (16 characters)"), smartHome.tuyaLocalKey, secret = true)
        val dpsInput = plainInput(tr("DP ng mga outlet (hal. 1,2,3,4)"), smartHome.stripDps)
        val usbDpInput = plainInput(tr("USB DP (hal. 7)"), smartHome.usbDp)
        val versionInput = plainInput(tr("Version (3.3)"), smartHome.tuyaVersion)
        val socketInput = plainInput("#", "1").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val irNameInput = plainInput(tr("Pangalan ng IR code (hal. TV_POWER)"), "")

        fun saveFields() {
            smartHome.rmIp = ipInput.text.toString()
            smartHome.tuyaEndpoint = endpointInput.text.toString()
            smartHome.tuyaAccessId = idInput.text.toString()
            smartHome.tuyaAccessSecret = secretInput.text.toString()
            smartHome.tuyaDeviceId = deviceInput.text.toString()
            smartHome.tuyaSocketCodes = codesInput.text.toString()
            smartHome.stripIp = stripIpInput.text.toString()
            smartHome.tuyaLocalKey = localKeyInput.text.toString()
            smartHome.stripDps = dpsInput.text.toString()
            smartHome.usbDp = usbDpInput.text.toString()
            smartHome.tuyaVersion = versionInput.text.toString()
        }

        container.addView(ipInput)
        container.addView(row(
            button(tr("🔍 Hanapin si RM")) {
                saveFields()
                say(tr("Hinahanap si RM sa WiFi..."))
                smartHome.discoverRm { found, err ->
                    ui {
                        if (err != null) {
                            statusView.text = err
                        } else if (found.isEmpty()) {
                            statusView.text = tr("Walang sumagot. Ilagay ang IP at siguraduhing parehong WiFi ang phone at RM.")
                        } else if (found.size == 1) {
                            val f = found.first()
                            smartHome.rmIp = f.host
                            ipInput.setText(f.host)
                            statusView.text = describeRm(f)
                        } else {
                            // Maraming Broadlink device sa WiFi - hayaang pumili si idol.
                            showPicker(
                                tr("Piliin ang RM mini 3"),
                                found.map { f ->
                                    "${f.name.ifBlank { "Broadlink" }} (${f.devTypeHex}) @ ${f.host}" to {
                                        smartHome.rmIp = f.host
                                        show(describeRm(f))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ))

        container.addView(label(tr("Turuan si RM ng bagong IR code (puwede ito sa TV, aircon, fan, atbp.):")))
        container.addView(irNameInput)
        container.addView(row(
            button(tr("🎓 Turuan")) {
                saveFields()
                val name = irNameInput.text.toString()
                smartHome.learnIr(
                    name,
                    onStatus = { say(it) },
                    onDone = { ok, msg ->
                        ui {
                            statusView.text = (if (ok) "✅ " else "⚠️ ") + msg
                            if (ok && visible) show() // i-refresh ang listahan
                        }
                    }
                )
            }
        ))

        val names = smartHome.irNames()
        if (names.isEmpty()) {
            container.addView(label(tr("Wala pang naka-save na IR code."), 8, 12f))
        } else {
            for (n in names) {
                container.addView(row(
                    TextView(activity).apply { text = n; textSize = 14f },
                    button("▶") {
                        saveFields()
                        smartHome.testIr(n) { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
                    },
                    button("💬") {
                        saveFields()
                        showAddCommandDialog("HOME:IR:$n", tr("sige"))
                    },
                    button("🗑") {
                        smartHome.deleteIr(n)
                        saveFields()
                        show()
                    }
                ))
            }
        }

        // ---------------- Lasco strip ----------------
        container.addView(label("🔌 Lasco Smart Power Strip", 22, 15f))
        container.addView(label(tr("📴 Offline (local WiFi) - walang internet na kailangan:"), 8, 12f))
        container.addView(stripIpInput)
        container.addView(deviceInput)
        container.addView(localKeyInput)
        container.addView(dpsInput)
        container.addView(row(usbDpInput, versionInput))
        container.addView(row(
            button(tr("📥 I-paste mula sa devices.json")) {
                saveFields()
                showImportDialog()
            }
        ))

        container.addView(label(tr("☁️ Cloud (opsyonal - fallback at auto-detect ng device):"), 16, 12f))
        container.addView(endpointInput)
        container.addView(idInput)
        container.addView(secretInput)
        container.addView(row(
            button(tr("🔎 Hanapin ang mga device ko")) {
                saveFields()
                say(tr("Hinahanap ang mga device sa Smart Life account..."))
                smartHome.tuyaDevices { list ->
                    ui {
                        if (!list.ok) {
                            statusView.text = "⚠️ " + list.message
                        } else {
                            // Mga mukhang socket/power strip muna sa listahan.
                            val sorted = list.devices.sortedByDescending { it.looksLikeSwitch }
                            showPicker(
                                tr("Piliin ang power strip"),
                                sorted.map { d ->
                                    val state = if (d.online) "🟢" else "⚪"
                                    "$state ${d.name}" + (if (d.productName.isNotBlank()) " - ${d.productName}" else "") to {
                                        onStatus(tr("Kinukuha ang mga socket..."))
                                        smartHome.adoptTuyaDevice(d) { ok, msg ->
                                            ui { show((if (ok) "✅ " else "⚠️ ") + msg) }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ))
        container.addView(codesInput)

        container.addView(label(tr("Subukan ang strip:"), 16, 12f))
        container.addView(row(
            button(tr("📋 Status")) {
                saveFields()
                say(tr("Binabasa ang status..."))
                smartHome.stripStatus { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
            },
            button(tr("ON lahat")) {
                saveFields()
                smartHome.testStrip("ALL", true) { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
            },
            button(tr("OFF lahat")) {
                saveFields()
                smartHome.testStrip("ALL", false) { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
            }
        ))
        container.addView(row(
            TextView(activity).apply { text = tr("Socket #"); textSize = 13f },
            socketInput,
            button("ON") {
                saveFields()
                smartHome.testStrip(socketInput.text.toString(), true) { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
            },
            button("OFF") {
                saveFields()
                smartHome.testStrip(socketInput.text.toString(), false) { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
            }
        ))
        container.addView(row(
            TextView(activity).apply { text = "USB"; textSize = 13f },
            button("ON") {
                saveFields()
                smartHome.testStrip("USB", true) { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
            },
            button("OFF") {
                saveFields()
                smartHome.testStrip("USB", false) { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
            }
        ))
        container.addView(row(
            button(tr("💬 Gumawa ng voice command para sa socket")) {
                saveFields()
                val n = socketInput.text.toString().trim().ifEmpty { "1" }
                showAddCommandDialog("HOME:STRIP:$n:ON", tr("sige binubuksan ko na"))
            }
        ))

        // ---------------- Mga naka-save na voice command ----------------
        container.addView(label(tr("💬 Mga Voice Command (Smart Home)"), 22, 15f))
        val homeCmds = commandStore.all().filter { c -> actionParts(c.action).any { SmartHome.isHomeAction(it) } }
        if (homeCmds.isEmpty()) {
            container.addView(label(tr("Wala pang voice command para sa smart home."), 8, 12f))
        } else {
            container.addView(label(tr("Pindutin ang ✏️ para i-edit, ▶ para subukan, 🗑 para burahin."), 4, 11f))
            for (c in homeCmds) {
                container.addView(row(
                    TextView(activity).apply {
                        text = "\u201C${c.trigger}\u201D\n\u2192 ${describeAction(c.action)}"
                        textSize = 13f
                    },
                    button("▶") {
                        saveFields()
                        val homeParts = actionParts(c.action).filter { SmartHome.isHomeAction(it) }
                        for (part in homeParts) {
                            smartHome.execute(part) { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
                        }
                    },
                    button("✏️") {
                        saveFields()
                        showCommandEditor(c, c.trigger, c.reply, withoutDf(c.action), dfTrackOf(c.action))
                    },
                    button("🗑") {
                        saveFields()
                        confirmDelete(c)
                    }
                ))
            }
        }
        container.addView(row(
            button(tr("➕ Bagong voice command")) {
                saveFields()
                showCommandEditor(null, "", tr("sige"), "", "")
            }
        ))

        container.addView(label(
            tr("Tip: ang action ay maaari ring i-type nang manu-mano sa Mga Utos, hal. HOME:STRIP:2:OFF, HOME:STRIP:ALL:OFF, HOME:STRIP:USB:ON o HOME:IR:TV_POWER."),
            12, 11f
        ))

        // Naka-pin sa TAAS ang status (hindi kasama sa scroll) para laging kita ang resulta ng pinindot na button.
        val d = activity.resources.displayMetrics.density
        statusView.apply {
            textSize = 13f
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(activity).apply { addView(container) }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        openDialog(
            "🏠 Smart Home",
            root,
            tr("I-save"),
            { visible = false; saveFields(); onStatus(tr("Na-save ang Smart Home settings")) },
            tr("Isara"),
            { visible = false; saveFields() }
        )
    }

    /** I-paste ang entry mula sa TinyTuya (devices.json / snapshot.json) - kukunin ang id, local key, IP at version. */
    private fun showImportDialog() {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val box = EditText(activity).apply {
            hint = "{ \"name\": \"...\", \"id\": \"...\", \"key\": \"...\" }"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            setGravity(Gravity.TOP)
        }
        container.addView(label(
            tr("Buksan ang devices.json (o snapshot.json) ng TinyTuya, kopyahin ang entry ng strip (o ang buong file), at i-paste dito. Kukunin nito ang Device ID, Local Key, IP at version."),
            0, 12f
        ))
        container.addView(box)

        fun imported(d: SmartHome.Companion.TuyaImport): String =
            "✅ " + tr("Na-import: {0}").replace("{0}", d.name) +
                (if (d.ip.isEmpty()) " - " + tr("ilagay pa ang IP ng strip") else "")

        openDialog(
            tr("📥 I-import ang strip"),
            ScrollView(activity).apply { addView(container) },
            tr("I-import"),
            {
                val list = try {
                    SmartHome.parseTuyaImport(box.text.toString())
                } catch (e: IllegalArgumentException) {
                    show("⚠️ " + (e.message ?: "Hindi mabasa"))
                    null
                }
                if (list != null) {
                    if (list.size == 1) {
                        smartHome.applyImport(list.first())
                        show(imported(list.first()))
                    } else {
                        showPicker(
                            tr("Piliin ang strip"),
                            list.map { d ->
                                (d.name + (if (d.ip.isNotEmpty()) " @ ${d.ip}" else "")) to {
                                    smartHome.applyImport(d)
                                    show(imported(d))
                                }
                            }
                        )
                    }
                }
            },
            tr("Cancel"),
            { show() }
        )
    }

    private fun describeRm(f: Broadlink.Found): String {
        val lock = if (f.locked) " ⚠️ " + tr("NAKA-LOCK sa BroadLink app - i-unlock para gumana dito") else ""
        return "✅ ${f.name.ifBlank { "Broadlink" }} (${f.devTypeHex}) @ ${f.host}$lock"
    }

    /** Listahan ng mapagpipilian (isang button bawat isa). */
    private fun showPicker(
        title: String,
        entries: List<Pair<String, () -> Unit>>,
        onCancel: () -> Unit = { show() }
    ) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        for ((text, action) in entries) {
            container.addView(button(text) { action() })
        }
        openDialog(
            title,
            ScrollView(activity).apply { addView(container) },
            null,
            null,
            tr("Cancel"),
            { onCancel() }
        )
    }

    /** Gumawa ng bagong voice command na may nakahandang aksyon (mula sa 💬 button ng IR/socket). */
    private fun showAddCommandDialog(prefillAction: String, prefillReply: String) {
        showCommandEditor(null, "", prefillReply, prefillAction, "")
    }

    private fun confirmDelete(c: CommandStore.VoiceCommand) {
        openDialog(
            tr("🗑 Burahin ang utos?"),
            label("\u201C${c.trigger}\u201D \u2192 ${describeAction(c.action)}", 0, 14f),
            tr("Burahin"),
            {
                commandStore.remove(c.trigger)
                show("✅ " + tr("Nabura ang utos"))
            },
            tr("Cancel"),
            { show() }
        )
    }

    /** Lahat ng puwedeng aksyon: IR codes, bawat socket ON/OFF, lahat ng outlet, at USB. */
    private fun actionChoices(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (n in smartHome.irNames()) out.add("📡 IR: $n" to "HOME:IR:$n")
        for (i in 1..smartHome.socketCount()) {
            out.add("🔌 Socket $i ON" to "HOME:STRIP:$i:ON")
            out.add("🔌 Socket $i OFF" to "HOME:STRIP:$i:OFF")
        }
        out.add("🔌 ${tr("Lahat ng outlet")} ON" to "HOME:STRIP:ALL:ON")
        out.add("🔌 ${tr("Lahat ng outlet")} OFF" to "HOME:STRIP:ALL:OFF")
        out.add("🔌 USB ON" to "HOME:STRIP:USB:ON")
        out.add("🔌 USB OFF" to "HOME:STRIP:USB:OFF")
        return out
    }

    /**
     * Gumawa o mag-edit ng voice command. original = null kung bago. Ang DFPlayer track ay sine-save
     * bilang "DFPLAYER PLAY N" sa action (parehong format na ginagamit ng Mga Utos), kaya ang ESP32
     * ang magiging boses habang ginagawa ang aksyon.
     */
    private fun showCommandEditor(
        original: CommandStore.VoiceCommand?,
        trigger: String,
        reply: String,
        action: String,
        track: String,
        message: String? = null
    ) {
        val d = activity.resources.displayMetrics.density
        val statusView = TextView(activity).apply {
            textSize = 13f
            text = message ?: ""
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        fun say(msg: String) = ui { statusView.text = msg }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val triggerInput = plainInput(tr("hal. buksan ang ilaw"), trigger)
        val replyInput = plainInput(tr("Isasagot ng robot"), reply)
        val actionInput = plainInput("HOME:...", action)
        val trackInput = plainInput(tr("DFPlayer track # (opsyonal)"), track).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        container.addView(label(tr("Sasabihin (hiwalayin ng || kung maraming paraan ng pagsabi):"), 0))
        container.addView(triggerInput)
        container.addView(label(tr("Isasagot ng robot (puwede ring maraming variation na hiwalay ng ||):")))
        container.addView(replyInput)
        container.addView(label(tr("Aksyon:")))
        container.addView(actionInput)
        container.addView(row(
            button(tr("🎯 Piliin ang aksyon")) {
                val t = triggerInput.text.toString()
                val r = replyInput.text.toString()
                val tk = trackInput.text.toString()
                showPicker(
                    tr("Piliin ang aksyon"),
                    actionChoices().map { (text, act) ->
                        text to { showCommandEditor(original, t, r, act, tk) }
                    },
                    onCancel = { showCommandEditor(original, t, r, actionInput.text.toString(), tk) }
                )
            }
        ))
        container.addView(label(tr("DFPlayer track para sa boses ng ESP32 (blangko = wala):")))
        container.addView(trackInput)
        container.addView(row(
            button(tr("▶ Test aksyon")) {
                val homeParts = actionParts(actionInput.text.toString()).filter { SmartHome.isHomeAction(it) }
                if (homeParts.isEmpty()) {
                    say("⚠️ " + tr("Walang HOME: na aksyon"))
                } else {
                    for (part in homeParts) {
                        smartHome.execute(part) { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
                    }
                }
            },
            button(tr("▶ Test DFPlayer")) {
                val n = trackInput.text.toString().trim().toIntOrNull()
                if (n == null || n <= 0) {
                    say("⚠️ " + tr("Maglagay ng track number"))
                } else {
                    say(tr("Pinapatugtog ang track {0}...").replace("{0}", n.toString()))
                    testTrack(n) { ok, msg ->
                        ui {
                            statusView.text = (if (ok) "✅ " else "⚠️ ") + msg +
                                (if (isGeminiOn()) "\n" + tr("Tandaan: kapag naka-ON ang Gemini, naka-mute ang DFPlayer sa mga voice command.") else "")
                        }
                    }
                }
            }
        ))
        container.addView(label(
            tr("Kung gusto mo ng ON at OFF, gumawa ng dalawang command (hal. buksan ang ilaw / patayin ang ilaw)."),
            10, 11f
        ))
        container.addView(label(
            tr("Para marinig ang DFPlayer nang hindi nagsasabay sa boses ng phone, i-OFF ang boses ng app sa settings."),
            4, 11f
        ))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(activity).apply { addView(container) }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        openDialog(
            if (original == null) tr("💬 Bagong Voice Command") else tr("✏️ I-edit ang Voice Command"),
            root,
            tr("I-save"),
            {
                val t = triggerInput.text.toString().trim()
                val a = actionInput.text.toString().trim()
                val tk = trackInput.text.toString().trim()
                val trackNum = tk.toIntOrNull()
                val err = when {
                    t.isEmpty() -> tr("Kulang ang sasabihin")
                    actionParts(a).none { SmartHome.isHomeAction(it) } -> tr("Walang HOME: na aksyon - piliin ang aksyon")
                    tk.isNotEmpty() && (trackNum == null || trackNum <= 0) -> tr("Maling DFPlayer track number")
                    else -> null
                }
                if (err != null) {
                    showCommandEditor(original, t, replyInput.text.toString(), a, tk, "⚠️ $err")
                } else {
                    val finalAction = if (trackNum != null) "$a||DFPLAYER PLAY $trackNum" else a
                    if (original != null && original.trigger != t.lowercase()) commandStore.remove(original.trigger)
                    commandStore.add(t, replyInput.text.toString(), finalAction, original?.expression ?: "")
                    val saved = tr("Na-save ang command: {0}").replace("{0}", t)
                    onStatus(saved)
                    show("✅ $saved")
                }
            },
            tr("Cancel"),
            { show() }
        )
    }
}
