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
    private val onStatus: (String) -> Unit
) {
    private fun ui(block: () -> Unit) = activity.runOnUiThread(block)

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
        val socketInput = plainInput("#", "1").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val irNameInput = plainInput(tr("Pangalan ng IR code (hal. TV_POWER)"), "")

        fun saveFields() {
            smartHome.rmIp = ipInput.text.toString()
            smartHome.tuyaEndpoint = endpointInput.text.toString()
            smartHome.tuyaAccessId = idInput.text.toString()
            smartHome.tuyaAccessSecret = secretInput.text.toString()
            smartHome.tuyaDeviceId = deviceInput.text.toString()
            smartHome.tuyaSocketCodes = codesInput.text.toString()
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
        container.addView(label("🔌 Lasco Smart Power Strip (Tuya Cloud)", 22, 15f))
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
        container.addView(deviceInput)
        container.addView(codesInput)
        container.addView(row(
            button(tr("📋 Status")) {
                saveFields()
                say(tr("Binabasa ang status..."))
                smartHome.tuyaStatus { ok, msg -> say((if (ok) "✅ " else "⚠️ ") + msg) }
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
            button(tr("💬 Gumawa ng voice command para sa socket")) {
                saveFields()
                val n = socketInput.text.toString().trim().ifEmpty { "1" }
                showAddCommandDialog("HOME:STRIP:$n:ON", tr("sige binubuksan ko na"))
            }
        ))

        container.addView(label(tr("Resulta:"), 20, 12f))
        container.addView(statusView)
        container.addView(label(
            tr("Tip: ang action ay maaari ring i-type nang manu-mano sa Mga Utos, hal. HOME:STRIP:2:OFF, HOME:STRIP:ALL:OFF o HOME:IR:TV_POWER."),
            12, 11f
        ))

        val scroll = ScrollView(activity).apply { addView(container) }
        openDialog(
            "🏠 Smart Home",
            scroll,
            tr("I-save"),
            { visible = false; saveFields(); onStatus(tr("Na-save ang Smart Home settings")) },
            tr("Isara"),
            { visible = false; saveFields() }
        )
    }

    private fun describeRm(f: Broadlink.Found): String {
        val lock = if (f.locked) " ⚠️ " + tr("NAKA-LOCK sa BroadLink app - i-unlock para gumana dito") else ""
        return "✅ ${f.name.ifBlank { "Broadlink" }} (${f.devTypeHex}) @ ${f.host}$lock"
    }

    /** Listahan ng mapagpipilian (isang button bawat isa). */
    private fun showPicker(title: String, entries: List<Pair<String, () -> Unit>>) {
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
            { show() }
        )
    }

    /** Gumawa ng voice command (trigger -> reply + HOME action) nang direkta sa CommandStore. */
    private fun showAddCommandDialog(prefillAction: String, prefillReply: String) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val trigger = plainInput(tr("hal. buksan ang ilaw"), "")
        val reply = plainInput(tr("Isasagot ng robot"), prefillReply)
        val action = plainInput("HOME:...", prefillAction)

        container.addView(label(tr("Sasabihin (hiwalayin ng || kung maraming paraan ng pagsabi):"), 0))
        container.addView(trigger)
        container.addView(label(tr("Isasagot ng robot:")))
        container.addView(reply)
        container.addView(label("Action:"))
        container.addView(action)
        container.addView(label(
            tr("Kung gusto mo ng ON at OFF, gumawa ng dalawang command (hal. buksan ang ilaw / patayin ang ilaw)."),
            10, 11f
        ))

        openDialog(
            tr("💬 Bagong Voice Command"),
            ScrollView(activity).apply { addView(container) },
            tr("I-save"),
            {
                val t = trigger.text.toString().trim()
                val a = action.text.toString().trim()
                if (t.isEmpty() || !SmartHome.isHomeAction(a)) {
                    onStatus(tr("Kulang ang sasabihin o hindi HOME: ang action"))
                } else {
                    commandStore.add(t, reply.text.toString(), a)
                    onStatus(tr("Na-save ang command: {0}").replace("{0}", t))
                }
                show()
            },
            tr("Cancel"),
            { show() }
        )
    }
}
