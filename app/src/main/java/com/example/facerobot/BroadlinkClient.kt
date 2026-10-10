package com.example.facerobot

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Local (LAN) na kontrol ng Broadlink RM mini 3 - UDP lang, walang cloud, walang ginagalaw
 * sa account o pairing ng device. Kaya hindi naaapektuhan ang Alexa: ang Alexa ay dumadaan
 * sa BroadLink cloud, tayo ay direktang sa device sa loob ng WiFi.
 *
 * Sinunod ang protocol ng python-broadlink (AES-128-CBC, port 80). Pure Kotlin/JVM ito
 * (walang Android class) para ma-test sa PC.
 */
class BroadlinkException(message: String) : Exception(message)

object Broadlink {
    const val DEFAULT_PORT = 80

    internal val INIT_KEY: ByteArray = hexToBytes("097628343fe99e23765c1513accf8b02")
    internal val INIT_IV: ByteArray = hexToBytes("562e17996d093d28ddb3ba695a2e6f58")

    // Mga device type na gumagamit ng LUMANG (classic) packet format. Ang RM mini 3 na may bagong
    // firmware (0x5F36, 0x6507, 0x6508) at ang lahat ng RM4 ay gumagamit ng may "length prefix".
    // Kapag hindi kilala ang type, ipinapalagay na bago (length prefix).
    private val CLASSIC_TYPES = setOf(
        // RM mini
        0x2737, 0x278F, 0x27B7, 0x27C2, 0x27C7, 0x27CC, 0x27CD, 0x27D0, 0x27D1, 0x27D3, 0x27DC, 0x27DE,
        // RM pro / plus
        0x2712, 0x272A, 0x273D, 0x277C, 0x2783, 0x2787, 0x278B, 0x2797, 0x279D, 0x27A1, 0x27A6, 0x27A9, 0x27C3
    )

    fun usesLengthPrefix(devType: Int): Boolean = devType !in CLASSIC_TYPES

    fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    data class Found(
        val host: String,
        val devType: Int,
        val macRaw: ByteArray,
        val name: String,
        val locked: Boolean
    ) {
        val devTypeHex: String get() = "0x" + devType.toString(16).uppercase().padStart(4, '0')
    }

    internal fun putLe16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    internal fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    internal fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    internal fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /** sum(data, 0xBEAF) & 0xFFFF - opsyonal na hindi isama ang 2 byte ng checksum field (0x20-0x21). */
    internal fun checksum(data: ByteArray, length: Int = data.size, skipChecksumField: Boolean = false): Int {
        var sum = 0xBEAF
        for (i in 0 until length) {
            if (skipChecksumField && (i == 0x20 || i == 0x21)) continue
            sum += data[i].toInt() and 0xFF
        }
        return sum and 0xFFFF
    }

    internal fun aes(encrypt: Boolean, data: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(INIT_IV)
        )
        return c.doFinal(data)
    }

    /**
     * Naghahanap ng Broadlink device. Kung may targetIp - diretso (unicast) sa IP na iyon;
     * kung wala - broadcast sa buong WiFi network. Blocking ito, tawagin sa background thread.
     */
    fun discover(targetIp: String? = null, timeoutMs: Int = 3000, port: Int = DEFAULT_PORT): List<Found> {
        val packet = ByteArray(0x30)
        val now = ZonedDateTime.now()
        putLe32(packet, 0x08, now.offset.totalSeconds / 3600)
        putLe16(packet, 0x0C, now.year)
        packet[0x0E] = now.minute.toByte()
        packet[0x0F] = now.hour.toByte()
        packet[0x10] = (now.year % 100).toByte()
        packet[0x11] = now.dayOfWeek.value.toByte()
        packet[0x12] = now.dayOfMonth.toByte()
        packet[0x13] = now.monthValue.toByte()
        packet[0x26] = 6
        putLe16(packet, 0x20, checksum(packet))

        val target = InetAddress.getByName(if (targetIp.isNullOrBlank()) "255.255.255.255" else targetIp.trim())
        val found = ArrayList<Found>()
        val seen = HashSet<String>()

        DatagramSocket().use { sock ->
            sock.broadcast = true
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val t = System.currentTimeMillis()
                if (t >= nextSend) {
                    sock.send(DatagramPacket(packet, packet.size, target, port))
                    nextSend = t + 1000
                }
                sock.soTimeout = 250
                val dp = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(dp)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                if (dp.length < 0x40) continue
                val devType = (buf[0x34].toInt() and 0xFF) or ((buf[0x35].toInt() and 0xFF) shl 8)
                val mac = buf.copyOfRange(0x3A, 0x40)
                val host = dp.address.hostAddress ?: continue
                val key = host + mac.joinToString("") { "%02x".format(it) } + devType
                if (!seen.add(key)) continue
                var end = 0x40
                while (end < dp.length && buf[end].toInt() != 0) end++
                val name = String(buf, 0x40, end - 0x40, Charsets.UTF_8)
                val locked = dp.length > 0x7F && buf[0x7F].toInt() != 0
                found.add(Found(host, devType, mac, name, locked))
            }
        }
        return found
    }
}

/**
 * Isang Broadlink RM (mini 3 / RM4) na may sariling session. Naka-cache ang auth - hindi
 * nag-a-auth tuwing may utos, para magaan sa device at hindi nakakaabala sa cloud connection
 * nito (na ginagamit ng Alexa).
 */
class BroadlinkDevice(
    val host: String,
    val devType: Int,
    private val macRaw: ByteArray,
    val name: String = "",
    val locked: Boolean = false,
    private val port: Int = Broadlink.DEFAULT_PORT,
    private val timeoutMs: Int = 4000
) {
    private val lengthPrefixed = Broadlink.usesLengthPrefix(devType)
    private var count = 0x8000 + Random.nextInt(0x7FFF)
    private var devId = 0
    private var key: ByteArray = Broadlink.INIT_KEY
    private var authed = false

    constructor(f: Broadlink.Found, port: Int = Broadlink.DEFAULT_PORT, timeoutMs: Int = 4000) :
        this(f.host, f.devType, f.macRaw, f.name, f.locked, port, timeoutMs)

    @Synchronized
    fun auth() {
        authed = false
        devId = 0
        key = Broadlink.INIT_KEY

        val p = ByteArray(0x50)
        for (i in 0x04 until 0x14) p[i] = 0x31
        p[0x1E] = 0x01
        p[0x2D] = 0x01
        "Test 1".toByteArray(Charsets.US_ASCII).copyInto(p, 0x30)

        val resp = sendPacket(0x65, p)
        checkError(resp)
        val body = decryptBody(resp)
        if (body.size < 0x14) throw BroadlinkException("Maikli ang sagot ng device sa authentication")
        devId = Broadlink.le32(body, 0)
        key = body.copyOfRange(0x04, 0x14)
        authed = true
    }

    /** Magpadala ng na-learn na IR code (raw bytes). May isang awtomatikong retry na may bagong auth. */
    @Synchronized
    fun sendData(data: ByteArray) {
        commandWithRetry(0x2, data)
    }

    @Synchronized
    fun enterLearning() {
        commandWithRetry(0x3, ByteArray(0))
    }

    /** Ibinabalik ang huling nahuling code, o null kung wala pa. */
    @Synchronized
    fun checkData(): ByteArray? = try {
        command(0x4, ByteArray(0)).takeIf { it.isNotEmpty() }
    } catch (e: BroadlinkException) {
        null
    }

    // ---------------------------------------------------------------------------------------

    private fun commandWithRetry(cmd: Int, data: ByteArray): ByteArray = try {
        command(cmd, data)
    } catch (e: BroadlinkException) {
        authed = false // baka nag-expire ang session - subukan ulit na may bagong auth
        command(cmd, data)
    }

    private fun command(cmd: Int, data: ByteArray): ByteArray {
        if (!authed) auth()
        val payload = if (lengthPrefixed) {
            val h = ByteArray(6)
            Broadlink.putLe16(h, 0, data.size + 4)
            Broadlink.putLe32(h, 2, cmd)
            h + data
        } else {
            val h = ByteArray(4)
            Broadlink.putLe32(h, 0, cmd)
            h + data
        }
        val resp = sendPacket(0x6A, payload)
        checkError(resp)
        val dec = decryptBody(resp)
        return if (lengthPrefixed) {
            if (dec.size < 6) throw BroadlinkException("Maikli ang sagot ng device")
            val pLen = Broadlink.le16(dec, 0)
            dec.copyOfRange(6, minOf(pLen + 2, dec.size).coerceAtLeast(6))
        } else {
            if (dec.size < 4) throw BroadlinkException("Maikli ang sagot ng device")
            dec.copyOfRange(4, dec.size)
        }
    }

    private fun checkError(resp: ByteArray) {
        val err = Broadlink.le16(resp, 0x22).toShort().toInt()
        if (err != 0) {
            val hint = if (locked) " (naka-LOCK ang device sa BroadLink app - i-unlock muna)" else ""
            throw BroadlinkException("Error ng RM: $err$hint")
        }
    }

    private fun decryptBody(resp: ByteArray): ByteArray {
        val bodyLen = resp.size - 0x38
        val usable = bodyLen - bodyLen % 16
        if (usable <= 0) return ByteArray(0)
        return Broadlink.aes(false, resp.copyOfRange(0x38, 0x38 + usable), key)
    }

    private fun sendPacket(type: Int, payload: ByteArray): ByteArray {
        count = ((count + 1) or 0x8000) and 0xFFFF

        val header = ByteArray(0x38)
        Broadlink.hexToBytes("5aa5aa555aa5aa55").copyInto(header, 0)
        Broadlink.putLe16(header, 0x24, devType)
        Broadlink.putLe16(header, 0x26, type)
        Broadlink.putLe16(header, 0x28, count)
        macRaw.copyInto(header, 0x2A)
        Broadlink.putLe32(header, 0x30, devId)
        Broadlink.putLe16(header, 0x34, Broadlink.checksum(payload))

        val pad = (16 - payload.size % 16) % 16
        val encrypted = Broadlink.aes(true, payload + ByteArray(pad), key)
        val packet = header + encrypted
        Broadlink.putLe16(packet, 0x20, Broadlink.checksum(packet))

        val addr = InetAddress.getByName(host)
        DatagramSocket().use { sock ->
            sock.connect(addr, port) // sagot lang galing sa device na ito ang tatanggapin
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw BroadlinkException("Walang sagot ang RM mini 3 ($host) - timeout")
                sock.send(DatagramPacket(packet, packet.size))
                sock.soTimeout = minOf(1000L, remaining).toInt().coerceAtLeast(50)
                val dp = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(dp)
                } catch (e: SocketTimeoutException) {
                    continue // ulitin ang padala hanggang deadline
                }
                if (dp.length < 0x30) throw BroadlinkException("Maikli ang sagot ng device")
                val resp = buf.copyOf(dp.length)
                val nominal = Broadlink.le16(resp, 0x20)
                val real = Broadlink.checksum(resp, resp.size, skipChecksumField = true)
                if (nominal != real) throw BroadlinkException("Sirang sagot ng device (checksum)")
                return resp
            }
        }
    }
}
