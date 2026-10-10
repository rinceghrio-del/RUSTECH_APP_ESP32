package com.example.facerobot

import android.content.Context
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Smart-home talent ni RUSTECH: Broadlink RM mini 3 (IR, local LAN) at Lasco smart power strip
 * (Tuya Cloud).
 *
 * MGA PANUNTUNAN PARA HINDI MAAPEKTUHAN ANG ALEXA:
 *  - Walang ginagalaw sa pairing, account, o Alexa skill ng alinmang device.
 *  - Walang polling/keep-alive: may network traffic lang kapag may utos.
 *  - Hiwalay na thread/executor ang smart-home (hindi nakakaharang sa kontrol ng robot at vice versa).
 *  - Naka-cache ang session ng RM (hindi nag-a-auth kada utos); maiikli ang timeouts.
 *
 * FORMAT NG ACTION (isulat sa "ESP32 action" ng Mga Utos; puwedeng isama sa || combo):
 *   HOME:STRIP:<1..N | ALL>:<ON | OFF>   hal. HOME:STRIP:2:ON    HOME:STRIP:ALL:OFF
 *   HOME:IR:<PANGALAN>                   hal. HOME:IR:TV_POWER
 */
class SmartHome(context: Context) {

    companion object {
        const val PREFIX = "HOME:"
        const val DEFAULT_TUYA_ENDPOINT = "https://openapi-sg.iotbing.com"
        const val DEFAULT_SOCKET_CODES = "switch_1,switch_2,switch_3,switch_4"

        fun isHomeAction(action: String): Boolean = action.trim().uppercase().startsWith(PREFIX)

        /** Pangalan ng IR code: A-Z, 0-9, underscore lang, laging uppercase. */
        fun sanitizeName(raw: String): String =
            raw.trim().uppercase().replace(Regex("[^A-Z0-9_]+"), "_").trim('_')
    }

    private val prefs = context.applicationContext.getSharedPreferences("smarthome_prefs", Context.MODE_PRIVATE)

    // --- Settings -------------------------------------------------------------------------

    var rmIp: String
        get() = prefs.getString("rm_ip", "") ?: ""
        set(v) { prefs.edit().putString("rm_ip", v.trim()).apply() }

    var tuyaEndpoint: String
        get() = prefs.getString("tuya_endpoint", DEFAULT_TUYA_ENDPOINT) ?: DEFAULT_TUYA_ENDPOINT
        set(v) { prefs.edit().putString("tuya_endpoint", v.trim().ifEmpty { DEFAULT_TUYA_ENDPOINT }).apply() }

    var tuyaAccessId: String
        get() = prefs.getString("tuya_access_id", "") ?: ""
        set(v) { prefs.edit().putString("tuya_access_id", v.trim()).apply() }

    var tuyaAccessSecret: String
        get() = prefs.getString("tuya_access_secret", "") ?: ""
        set(v) { prefs.edit().putString("tuya_access_secret", v.trim()).apply() }

    var tuyaDeviceId: String
        get() = prefs.getString("tuya_device_id", "") ?: ""
        set(v) { prefs.edit().putString("tuya_device_id", v.trim()).apply() }

    var tuyaSocketCodes: String
        get() = prefs.getString("tuya_socket_codes", DEFAULT_SOCKET_CODES) ?: DEFAULT_SOCKET_CODES
        set(v) { prefs.edit().putString("tuya_socket_codes", v.trim().ifEmpty { DEFAULT_SOCKET_CODES }).apply() }

    // --- Naka-save na IR codes --------------------------------------------------------------

    fun irNames(): List<String> =
        (prefs.getString("ir_names", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun getIr(name: String): ByteArray? =
        prefs.getString("ir_" + sanitizeName(name), null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    fun saveIr(name: String, data: ByteArray) {
        val n = sanitizeName(name)
        if (n.isEmpty()) return
        val names = irNames().toMutableList()
        if (n !in names) names.add(n)
        prefs.edit()
            .putString("ir_$n", Base64.getEncoder().encodeToString(data))
            .putString("ir_names", names.joinToString(","))
            .apply()
    }

    fun deleteIr(name: String) {
        val n = sanitizeName(name)
        prefs.edit()
            .remove("ir_$n")
            .putString("ir_names", irNames().filter { it != n }.joinToString(","))
            .apply()
    }

    // --- Executors (hiwalay sa robot) ------------------------------------------------------

    private fun daemonSingle(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    private val rmExecutor = daemonSingle("smarthome-rm")
    private val cloudExecutor = daemonSingle("smarthome-cloud")

    @Volatile private var rmDevice: BroadlinkDevice? = null
    @Volatile private var tuyaClient: TuyaCloudClient? = null
    @Volatile private var tuyaClientKey: String = ""

    // --- Pagpapatakbo ng action -------------------------------------------------------------

    /** Callback ay tinatawag sa BACKGROUND thread - gumamit ng runOnUi sa MainActivity. */
    fun execute(action: String, onResult: (ok: Boolean, message: String) -> Unit) {
        val parts = action.trim().uppercase().split(":")
        if (parts.size < 3 || parts[0] + ":" != PREFIX) {
            onResult(false, "Hindi kilalang smart-home action: $action")
            return
        }
        when (parts[1]) {
            "IR" -> runIr(parts.drop(2).joinToString("_"), onResult)
            "STRIP" -> {
                if (parts.size != 4 || (parts[3] != "ON" && parts[3] != "OFF")) {
                    onResult(false, "Maling STRIP action: dapat HOME:STRIP:<numero|ALL>:<ON|OFF>")
                } else {
                    runStrip(parts[2], parts[3] == "ON", onResult)
                }
            }
            else -> onResult(false, "Hindi kilalang smart-home action: $action")
        }
    }

    private fun runIr(name: String, onResult: (Boolean, String) -> Unit) {
        val data = getIr(name)
        if (data == null) {
            onResult(false, "Walang naka-save na IR code na \"$name\" - turuan muna si RM sa Smart Home menu")
            return
        }
        rmExecutor.execute {
            try {
                resolveRm().sendData(data)
                onResult(true, "IR $name naipadala")
            } catch (e: Exception) {
                rmDevice = null // baka nagbago ang IP/session - hanapin ulit sa susunod
                onResult(false, friendlyError(e))
            }
        }
    }

    private fun runStrip(target: String, on: Boolean, onResult: (Boolean, String) -> Unit) {
        val deviceId = tuyaDeviceId
        if (tuyaAccessId.isBlank() || tuyaAccessSecret.isBlank() || deviceId.isBlank()) {
            onResult(false, "Kulang ang Tuya settings (Access ID, Secret, Device ID) sa Smart Home menu")
            return
        }
        val allCodes = parseCodes()
        val codes: List<String> = if (target == "ALL") {
            allCodes
        } else {
            val idx = target.toIntOrNull()
            val code = if (idx != null) allCodes.getOrNull(idx - 1) else null
            if (code == null) {
                onResult(false, "Walang socket #$target (may ${allCodes.size} socket code na naka-set)")
                return
            }
            listOf(code)
        }
        cloudExecutor.execute {
            val res = tuya().setSwitches(deviceId, codes, on)
            val what = if (target == "ALL") "lahat ng socket" else "socket $target"
            onResult(res.ok, if (res.ok) "Power strip: $what ${if (on) "ON" else "OFF"}" else res.message)
        }
    }

    // --- Para sa Smart Home dialog ---------------------------------------------------------

    /** Hanapin ang RM sa network (broadcast, o diretso kung may IP na). */
    fun discoverRm(onDone: (found: List<Broadlink.Found>, error: String?) -> Unit) {
        rmExecutor.execute {
            try {
                val ip = rmIp.ifBlank { null }
                onDone(Broadlink.discover(ip, 3500), null)
            } catch (e: Exception) {
                onDone(emptyList(), friendlyError(e))
            }
        }
    }

    /** Turuan si RM ng bagong IR code: hihintayin hanggang 30 segundo na pindutin mo ang remote. */
    fun learnIr(rawName: String, onStatus: (String) -> Unit, onDone: (ok: Boolean, message: String) -> Unit) {
        val name = sanitizeName(rawName)
        if (name.isEmpty()) {
            onDone(false, "Maglagay ng pangalan ng code (hal. TV_POWER)")
            return
        }
        rmExecutor.execute {
            try {
                val dev = resolveRm()
                dev.enterLearning()
                onStatus("🎓 Nakikinig na si RM - itutok ang remote sa RM mini 3 at pindutin ang button (30 segundo)")
                val deadline = System.currentTimeMillis() + 30_000L
                var data: ByteArray? = null
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(1000)
                    data = dev.checkData()
                    if (data != null) break
                }
                if (data == null) {
                    onDone(false, "Walang nahuling signal sa loob ng 30 segundo - subukan ulit")
                } else {
                    saveIr(name, data)
                    onDone(true, "Na-save ang $name (${data.size} bytes)")
                }
            } catch (e: Exception) {
                rmDevice = null
                onDone(false, friendlyError(e))
            }
        }
    }

    /** Test ng IR code na naka-save (walang kinalaman sa voice command). */
    fun testIr(name: String, onDone: (Boolean, String) -> Unit) = runIr(sanitizeName(name), onDone)

    /** Basahin ang status ng power strip - ipinapakita ang mga tamang switch code. */
    fun tuyaStatus(onDone: (ok: Boolean, message: String) -> Unit) {
        val deviceId = tuyaDeviceId
        if (tuyaAccessId.isBlank() || tuyaAccessSecret.isBlank() || deviceId.isBlank()) {
            onDone(false, "Kulang ang Tuya settings (Access ID, Secret, Device ID)")
            return
        }
        cloudExecutor.execute {
            val res = tuya().status(deviceId)
            onDone(res.ok, res.message)
        }
    }

    /** AUTO-DETECT: ilista ang mga device sa naka-link na Smart Life account (kailangan lang ng Access ID + Secret). */
    fun tuyaDevices(onDone: (TuyaCloudClient.DeviceList) -> Unit) {
        if (tuyaAccessId.isBlank() || tuyaAccessSecret.isBlank()) {
            onDone(TuyaCloudClient.DeviceList(false, "Ilagay muna ang Access ID at Access Secret ng Tuya project", emptyList()))
            return
        }
        cloudExecutor.execute { onDone(tuya().listDevices()) }
    }

    /** Piliin ang device: sine-save ang Device ID at awtomatikong kinukuha ang mga socket code nito. */
    fun adoptTuyaDevice(d: TuyaCloudClient.Device, onDone: (ok: Boolean, message: String) -> Unit) {
        tuyaDeviceId = d.id
        cloudExecutor.execute {
            val r = tuya().switchCodes(d.id)
            if (r.ok) {
                tuyaSocketCodes = r.message
                val n = r.message.split(",").size
                onDone(true, "Napili: ${d.name} - $n socket (${r.message})")
            } else {
                onDone(false, "Napili ang ${d.name}, pero ${r.message}")
            }
        }
    }

    /** Test ng power strip nang walang voice command. */
    fun testStrip(target: String, on: Boolean, onDone: (Boolean, String) -> Unit) =
        runStrip(target.trim().uppercase(), on, onDone)

    // --- Internal ---------------------------------------------------------------------------

    private fun parseCodes(): List<String> =
        tuyaSocketCodes.split(",").map { it.trim() }.filter { it.matches(Regex("[A-Za-z0-9_]+")) }

    private fun tuya(): TuyaCloudClient {
        val key = tuyaEndpoint + "|" + tuyaAccessId + "|" + tuyaAccessSecret
        val existing = tuyaClient
        if (existing != null && key == tuyaClientKey) return existing
        val created = TuyaCloudClient(tuyaEndpoint, tuyaAccessId, tuyaAccessSecret)
        tuyaClient = created
        tuyaClientKey = key
        return created
    }

    /** Ibalik ang naka-cache na RM, o hanapin (at i-save ang IP) kung wala pa. Tawagin sa rmExecutor. */
    private fun resolveRm(): BroadlinkDevice {
        val savedIp = rmIp
        rmDevice?.let { if (savedIp.isBlank() || it.host == savedIp) return it }

        val found = Broadlink.discover(savedIp.ifBlank { null }, 3500)
        val pick = found.firstOrNull { savedIp.isBlank() || it.host == savedIp }
            ?: throw BroadlinkException(
                if (savedIp.isBlank()) "Walang Broadlink na nakita sa WiFi - ilagay ang IP ng RM mini 3"
                else "Walang sumagot sa $savedIp - tama ba ang IP at nasa parehong WiFi?"
            )
        if (savedIp.isBlank()) rmIp = pick.host
        val dev = BroadlinkDevice(pick)
        rmDevice = dev
        return dev
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is BroadlinkException -> e.message ?: "Error sa RM mini 3"
        is java.net.UnknownHostException -> "Mali ang IP address"
        else -> "Error: ${e.message ?: e.javaClass.simpleName}"
    }
}
