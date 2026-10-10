package com.example.facerobot

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * OFFLINE na kontrol ng Tuya device (hal. Lasco power strip) sa loob ng WiFi - TCP port 6668,
 * walang internet, walang cloud, walang subscription. Kapareho ng ginagawa ng TinyTuya.
 *
 * Sinusuportahan: protocol 3.2 at 3.3 (AES-128-ECB). Ang 3.4 / 3.5 ay hindi pa.
 *
 * MAHALAGA para sa Alexa: maiksi ang koneksyon (connect > utos > close) at hindi nagbabago
 * ang anumang setting ng device. Ang Alexa ay cloud ang daan, kaya hindi ito nagbabanggaan.
 * Ang Tuya device ay karaniwang isang local na koneksyon lang ang tinatanggap - kaya huwag
 * ring patakbuhin nang sabay ang lumang Python bridge (rustech_tuya_bridge.py).
 *
 * Pure Kotlin/JVM para ma-test sa PC.
 */
class TuyaLocalClient(
    private val host: String,
    private val deviceId: String,
    localKey: String,
    private val version: String = "3.3",
    private val port: Int = 6668,
    private val connectTimeoutMs: Int = 2000,
    private val readTimeoutMs: Int = 2500
) {
    data class Result(val ok: Boolean, val message: String, val dps: Map<String, String> = emptyMap())

    private class Frame(val cmd: Int, val retcode: Int, val payload: ByteArray)

    companion object {
        private const val PREFIX = 0x000055AA
        private const val SUFFIX = 0x0000AA55
        private const val CMD_CONTROL = 7
        private const val CMD_STATUS = 8
        private const val CMD_DP_QUERY = 0x0A
        private const val MAX_PAYLOAD = 4096
    }

    private val keyBytes: ByteArray = localKey.toByteArray(Charsets.UTF_8)
    private var seqno = 1

    // ---------------------------------------------------------------------------------------

    /** I-set ang mga DP (hal. "1" -> true). Maghihintay ng kumpirmasyon; kung wala, magve-verify sa status. */
    fun setDps(dps: Map<String, Boolean>): Result {
        validate()?.let { return it }
        if (dps.isEmpty() || dps.keys.any { !it.matches(Regex("\\d{1,3}")) }) {
            return Result(false, "Maling DP number")
        }
        return session { sock ->
            val dpsJson = dps.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
            val json = "{\"devId\":\"$deviceId\",\"uid\":\"$deviceId\",\"t\":\"${System.currentTimeMillis() / 1000}\",\"dps\":{$dpsJson}}"
            send(sock, CMD_CONTROL, withVersionHeader(encrypt(json.toByteArray(Charsets.UTF_8))))

            // 1) Hintayin ang ack/status push mula sa device.
            var confirmed = false
            var failure: String? = null
            val deadline = System.currentTimeMillis() + readTimeoutMs
            while (!confirmed && failure == null && System.currentTimeMillis() < deadline) {
                val f = readFrame(sock, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(100)) ?: break
                if (f.cmd == CMD_CONTROL) {
                    if (f.retcode == 0) confirmed = true else failure = "Tinanggihan ng device ang utos (code ${f.retcode})"
                } else if (f.cmd == CMD_STATUS) {
                    val got = parseDps(decodeJson(f.payload))
                    if (got.isNotEmpty() && dps.all { (k, v) -> got[k] == v.toString() }) confirmed = true
                }
            }
            if (failure != null) return@session Result(false, failure)
            if (confirmed) return@session Result(true, "OK")

            // 2) Walang ack: i-verify gamit ang status query sa parehong koneksyon.
            val st = queryStatus(sock)
            if (st.isEmpty()) {
                Result(false, "Walang sagot ang strip - tingnan ang IP, local key at version")
            } else if (dps.all { (k, v) -> st[k] == v.toString() }) {
                Result(true, "OK", st)
            } else {
                Result(false, "Hindi nasunod ng device ang utos", st)
            }
        }
    }

    /** Basahin ang kasalukuyang status ng lahat ng DP. */
    fun status(): Result {
        validate()?.let { return it }
        return session { sock ->
            val st = queryStatus(sock)
            if (st.isEmpty()) Result(false, "Walang sagot ang strip - tingnan ang IP, local key at version")
            else Result(true, st.entries.joinToString("\n") { "DP ${it.key} = ${it.value}" }, st)
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun validate(): Result? = when {
        host.isBlank() -> Result(false, "Walang IP ng strip")
        deviceId.isBlank() -> Result(false, "Walang Device ID")
        keyBytes.size != 16 -> Result(false, "Ang Local Key ay dapat eksaktong 16 na character (nasa ${keyBytes.size})")
        version != "3.3" && version != "3.2" ->
            Result(false, "Protocol $version ay hindi pa suportado sa local mode (3.2 at 3.3 lang)")
        else -> null
    }

    private fun session(block: (Socket) -> Result): Result {
        val sock = Socket()
        return try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host.trim(), port), connectTimeoutMs)
            block(sock)
        } catch (e: java.net.ConnectException) {
            Result(false, "Hindi maabot ang strip sa $host:$port - tama ba ang IP at parehong WiFi? Baka may ibang app o bridge na nakakonekta (isang koneksyon lang ang tinatanggap ng strip)")
        } catch (e: SocketTimeoutException) {
            Result(false, "Walang sagot ang strip sa $host (timeout)")
        } catch (e: IOException) {
            Result(false, "Error sa koneksyon sa strip: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            Result(false, "Error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { sock.close() } catch (_: Exception) { }
        }
    }

    private fun queryStatus(sock: Socket): Map<String, String> {
        val json = "{\"gwId\":\"$deviceId\",\"devId\":\"$deviceId\",\"uid\":\"$deviceId\",\"t\":\"${System.currentTimeMillis() / 1000}\"}"
        send(sock, CMD_DP_QUERY, encrypt(json.toByteArray(Charsets.UTF_8))) // walang version header ang DP_QUERY
        val deadline = System.currentTimeMillis() + readTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = readFrame(sock, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(100)) ?: return emptyMap()
            if (f.cmd == CMD_DP_QUERY || f.cmd == CMD_STATUS) {
                val dps = parseDps(decodeJson(f.payload))
                if (dps.isNotEmpty()) return dps
            }
        }
        return emptyMap()
    }

    private fun versionHeader(): ByteArray = version.toByteArray(Charsets.US_ASCII) + ByteArray(12)

    private fun withVersionHeader(encrypted: ByteArray): ByteArray = versionHeader() + encrypted

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        return c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        return c.doFinal(data)
    }

    private fun send(sock: Socket, cmd: Int, payload: ByteArray) {
        val bb = ByteBuffer.allocate(16 + payload.size + 8).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(PREFIX)
        bb.putInt(seqno++)
        bb.putInt(cmd)
        bb.putInt(payload.size + 8)
        bb.put(payload)
        val crc = CRC32()
        crc.update(bb.array(), 0, 16 + payload.size)
        bb.putInt(crc.value.toInt())
        bb.putInt(SUFFIX)
        sock.getOutputStream().apply { write(bb.array()); flush() }
    }

    /** Magbasa ng isang frame; null kung timeout. */
    private fun readFrame(sock: Socket, timeoutMs: Int): Frame? {
        sock.soTimeout = timeoutMs
        val input = sock.getInputStream()
        return try {
            val header = readFully(input, 16)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            if (hb.getInt() != PREFIX) throw IOException("Maling prefix mula sa device")
            hb.getInt() // seqno
            val cmd = hb.getInt()
            val len = hb.getInt()
            if (len < 12 || len > MAX_PAYLOAD) throw IOException("Maling laki ng packet ($len)")
            val body = readFully(input, len)
            val bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            val retcode = bb.getInt(0)
            val payload = body.copyOfRange(4, len - 8)
            val crcRx = bb.getInt(len - 8).toLong() and 0xFFFFFFFFL
            val suffix = bb.getInt(len - 4)
            val crc = CRC32()
            crc.update(header)
            crc.update(body, 0, len - 8)
            if (suffix != SUFFIX || crc.value != crcRx) throw IOException("Sirang packet mula sa device (CRC)")
            Frame(cmd, retcode, payload)
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArrayOutputStream(n)
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, 0, n - got)
            if (r < 0) throw IOException("Isinara ng device ang koneksyon")
            out.write(buf, 0, r)
            got += r
        }
        return out.toByteArray()
    }

    /** Payload ng device > JSON text (tinatanggal ang version header kung meron, tapos decrypt). Walang laman = "". */
    private fun decodeJson(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        var p = payload
        val vb = version.toByteArray(Charsets.US_ASCII)
        if (p.size > 15 && p.copyOfRange(0, vb.size).contentEquals(vb)) p = p.copyOfRange(15, p.size)
        return try {
            String(decrypt(p), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseDps(json: String): Map<String, String> {
        if (json.isEmpty()) return emptyMap()
        return try {
            val root = MiniJson.obj(MiniJson.parse(json)) ?: return emptyMap()
            val dps = MiniJson.obj(root["dps"]) ?: MiniJson.obj(MiniJson.obj(root["data"])?.get("dps")) ?: return emptyMap()
            dps.entries.associate { it.key to MiniJson.str(it.value) }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
