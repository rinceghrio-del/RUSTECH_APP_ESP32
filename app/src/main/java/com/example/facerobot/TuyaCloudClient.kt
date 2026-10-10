package com.example.facerobot

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Kontrol ng Lasco smart power strip (Tuya / Smart Life) gamit ang Tuya Cloud OpenAPI.
 *
 * Bakit hindi naaapektuhan ang Alexa: HINDI ginagalaw ang pairing, ang Smart Life account, o ang
 * Alexa skill link. Ang app ay gumagamit lang ng hiwalay na Tuya developer project na naka-link
 * sa PAREHONG Smart Life account (read/command lang) - parang isa pang "remote" sa parehong device.
 * Walang polling o keep-alive: may request lang kapag may utos.
 *
 * Pure Kotlin/JVM (HttpURLConnection) para ma-test sa PC.
 */
class TuyaCloudClient(
    endpoint: String,
    private val accessId: String,
    private val accessSecret: String,
    private val connectTimeoutMs: Int = 4000,
    private val readTimeoutMs: Int = 7000
) {
    private val base: String = endpoint.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
    }
    private var token: String = ""
    private var tokenExpiresAtMs: Long = 0L

    data class Result(val ok: Boolean, val message: String, val raw: String = "")

    private class ApiReply(val success: Boolean, val code: String, val msg: String, val raw: String)

    // ---------------------------------------------------------------------------------------

    /** I-on o i-off ang isa o ilang socket (mga switch code, hal. switch_1) sa isang request. */
    fun setSwitches(deviceId: String, codes: List<String>, on: Boolean): Result {
        if (codes.isEmpty()) return Result(false, "Walang socket code")
        val body = "{\"commands\":[" +
            codes.joinToString(",") { "{\"code\":\"$it\",\"value\":$on}" } + "]}"
        return runWithFallback(
            "POST", body,
            listOf("/v1.0/devices/$deviceId/commands", "/v1.0/iot-03/devices/$deviceId/commands")
        ) { "OK" }
    }

    /** Kasalukuyang status ng device (para makita ang tamang switch code). */
    fun status(deviceId: String): Result =
        runWithFallback(
            "GET", "",
            listOf("/v1.0/devices/$deviceId/status", "/v1.0/iot-03/devices/$deviceId/status")
        ) { raw ->
            val pairs = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*(\"[^\"]*\"|true|false|-?\\d+(?:\\.\\d+)?)")
                .findAll(raw).map { it.groupValues[1] + " = " + it.groupValues[2].trim('"') }.toList()
            if (pairs.isEmpty()) "Walang status na nabasa" else pairs.joinToString("\n")
        }

    // ---------------------------------------------------------------------------------------
    // AUTO-DETECT: ilista ang mga device ng naka-link na Smart Life account at kunin ang mga switch code.

    data class Device(
        val id: String,
        val name: String,
        val category: String,
        val online: Boolean,
        val productName: String
    ) {
        /** Mga category ng Tuya na karaniwang socket/power strip/switch (pc = power strip, cz = socket, kg = switch). */
        val looksLikeSwitch: Boolean get() = category in setOf("pc", "cz", "kg", "tdq")
    }

    data class DeviceList(val ok: Boolean, val message: String, val devices: List<Device>)

    /** Lahat ng device ng mga app account na naka-link sa Tuya project (hal. ang Smart Life mo). */
    fun listDevices(): DeviceList {
        val r = runWithFallback(
            "GET", "",
            listOf("/v1.0/iot-01/associated-users/devices?size=50")
        ) { "OK" }
        if (!r.ok) return DeviceList(false, r.message, emptyList())
        return try {
            val result = MiniJson.obj(MiniJson.obj(MiniJson.parse(r.raw))?.get("result"))
            val items = MiniJson.arr(result?.get("devices")) ?: MiniJson.arr(result?.get("list")) ?: emptyList()
            val devices = items.mapNotNull { MiniJson.obj(it) }.mapNotNull { d ->
                val id = MiniJson.str(d["id"]).ifEmpty { MiniJson.str(d["uuid"]) }
                if (id.isEmpty()) null else Device(
                    id = id,
                    name = MiniJson.str(d["name"]).ifEmpty { id },
                    category = MiniJson.str(d["category"]),
                    online = d["online"] == true,
                    productName = MiniJson.str(d["product_name"])
                )
            }
            val more = if (result?.get("has_more") == true) " (may iba pang device - ilagay ang Device ID nang manu-mano kung wala dito)" else ""
            if (devices.isEmpty()) {
                DeviceList(false, "Walang device na nakita. I-link muna ang Smart Life account sa Tuya project (Devices > Link App Account).", emptyList())
            } else {
                DeviceList(true, "${devices.size} device$more", devices)
            }
        } catch (e: Exception) {
            DeviceList(false, "Hindi mabasa ang listahan ng device: ${e.message}", emptyList())
        }
    }

    /** Ang mga switch code ng device (hal. switch_1..switch_4) mula sa specifications nito, nakaayos ayon sa numero. */
    fun switchCodes(deviceId: String): Result {
        val r = runWithFallback(
            "GET", "",
            listOf("/v1.0/devices/$deviceId/specifications", "/v1.0/iot-03/devices/$deviceId/specifications")
        ) { "OK" }
        if (!r.ok) return r
        return try {
            val result = MiniJson.obj(MiniJson.obj(MiniJson.parse(r.raw))?.get("result"))
            val fns = MiniJson.arr(result?.get("functions")) ?: emptyList()
            val codes = fns.mapNotNull { MiniJson.obj(it) }
                .filter { MiniJson.str(it["type"]).equals("Boolean", ignoreCase = true) }
                .map { MiniJson.str(it["code"]) }
                .filter { Regex("switch(_\\d+)?").matches(it) }
                .sortedBy { it.substringAfter("_", "0").toIntOrNull() ?: 0 }
            if (codes.isEmpty()) Result(false, "Walang switch code na nakita sa device na ito", r.raw)
            else Result(true, codes.joinToString(","), r.raw)
        } catch (e: Exception) {
            Result(false, "Hindi mabasa ang specifications: ${e.message}", r.raw)
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun runWithFallback(
        method: String,
        body: String,
        paths: List<String>,
        onSuccess: (String) -> String
    ): Result {
        var last = Result(false, "Hindi naabot ang Tuya cloud")
        for ((index, path) in paths.withIndex()) {
            val reply = try {
                authorized(method, path, body)
            } catch (e: Exception) {
                return Result(false, "Walang koneksyon sa Tuya cloud: ${e.message ?: e.javaClass.simpleName}")
            }
            if (reply.success) return Result(true, onSuccess(reply.raw), reply.raw)
            last = Result(false, explain(reply), reply.raw)
            // Subukan ang alternatibong API path LAMANG kung permission/path ang problema.
            val retryable = reply.code == "1106" || reply.code == "404" || reply.code == "1108"
            if (!retryable || index == paths.lastIndex) break
        }
        return last
    }

    private fun authorized(method: String, path: String, body: String): ApiReply {
        ensureToken(force = false)?.let { return it }
        var reply = call(method, path, body, withToken = true)
        if (!reply.success && reply.code == "1010") { // token invalid/expired
            ensureToken(force = true)?.let { return it }
            reply = call(method, path, body, withToken = true)
        }
        return reply
    }

    /** Null kung may valid na token na; ApiReply (error) kung hindi makakuha. */
    private fun ensureToken(force: Boolean): ApiReply? {
        val now = System.currentTimeMillis()
        if (!force && token.isNotEmpty() && now < tokenExpiresAtMs - 60_000L) return null
        token = ""
        val reply = call("GET", "/v1.0/token?grant_type=1", "", withToken = false)
        if (!reply.success) return reply
        val t = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(reply.raw)?.groupValues?.get(1)
            ?: return ApiReply(false, "no_token", "walang access_token sa sagot", reply.raw)
        val expireSec = Regex("\"expire_time\"\\s*:\\s*(\\d+)").find(reply.raw)?.groupValues?.get(1)?.toLongOrNull() ?: 7200L
        token = t
        tokenExpiresAtMs = now + expireSec * 1000L
        return null
    }

    private fun call(method: String, path: String, body: String, withToken: Boolean): ApiReply {
        val t = System.currentTimeMillis().toString()
        // Tuya "simple mode" signature: HMAC-SHA256(secret, clientId + [token] + t + stringToSign)
        val stringToSign = method + "\n" + sha256Hex(body) + "\n" + "" + "\n" + path
        val toSign = accessId + (if (withToken) token else "") + t + stringToSign
        val sign = hmacSha256Hex(accessSecret, toSign)

        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("client_id", accessId)
            conn.setRequestProperty("sign", sign)
            conn.setRequestProperty("t", t)
            conn.setRequestProperty("sign_method", "HMAC-SHA256")
            if (withToken) conn.setRequestProperty("access_token", token)
            conn.setRequestProperty("Content-Type", "application/json")
            if (body.isNotEmpty()) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val success = Regex("\"success\"\\s*:\\s*true").containsMatchIn(raw)
            val errCode = Regex("\"code\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1) ?: code.toString()
            val msg = Regex("\"msg\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1) ?: ""
            return ApiReply(success, errCode, msg, raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(r: ApiReply): String {
        val hint = when (r.code) {
            "1004" -> "sign invalid - tingnan ang Access ID/Secret at ang oras ng phone"
            "1010" -> "token invalid"
            "1106" -> "permission deny - i-link ang Smart Life account sa Tuya project at i-check ang API subscription"
            "1108" -> "uri path invalid - baka mali ang data center/endpoint"
            "2001", "2008" -> "hindi sinusuportahan ng device ang command o switch code - gamitin ang Status button para makita ang tamang code"
            "28841002" -> "wala pang subscription ang API sa Tuya project"
            "28841105" -> "walang authorization ang project - i-link ang account sa Tuya project"
            else -> ""
        }
        val detail = if (r.msg.isNotBlank()) r.msg else "walang detalye"
        return "Tuya error ${r.code}: $detail" + if (hint.isNotEmpty()) " ($hint)" else ""
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hmacSha256Hex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }
}
