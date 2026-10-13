package com.example.facerobot

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hinahanap ang ESP32 sa WiFi kapag nagbago ang IP nito. Pinapatunayan na robot ang isang device sa pamamagitan
 * ng sagot sa /ping: ang firmware ay sumasagot ng "PONG" o "PONG|<STATE>" - ang ibang device (router, camera)
 * na sumasagot ng ibang bagay ay HINDI tatanggapin.
 *
 * Pure Kotlin/JVM (walang Android class) para ma-test sa PC.
 */
object Esp32Finder {

    data class Candidate(val ip: String, val state: String)

    private val scanning = AtomicBoolean(false)

    /** Mga "192.168.1." na prefix ng mga WiFi/hotspot interface ng phone na ito (hindi kasama ang mobile data). */
    fun localPrefixes(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in ifaces) {
                val name = nif.name.lowercase()
                if (!nif.isUp || nif.isLoopback) continue
                if (listOf("rmnet", "ccmni", "tun", "ppp", "dummy", "v4-", "clat").any { name.startsWith(it) }) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    if (!a.isSiteLocalAddress) continue
                    val parts = a.hostAddress?.split(".") ?: continue
                    if (parts.size == 4) out.add(parts[0] + "." + parts[1] + "." + parts[2] + ".")
                }
            }
        } catch (e: Exception) {
            // walang magagawa - ibabalik ang nakuha na
        }
        return out.take(2) // sapat na ang 2 network (hal. WiFi + hotspot)
    }

    /** Sinusubukan ang isang IP. Ibinabalik ang Candidate kung "PONG" ang sagot ng /ping, kung hindi ay null. */
    fun probe(ip: String, port: Int = 80, connectMs: Int = 450, readMs: Int = 700): Candidate? {
        val conn = try {
            URL("http://$ip:$port/ping").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = false
            if (conn.responseCode != 200) return null
            // Binabasa lang ang unang 64 byte - hindi hihintayin ang buong sagot ng ibang device
            val body = conn.inputStream.use { s ->
                val buf = ByteArray(64)
                val n = s.read(buf)
                if (n > 0) String(buf, 0, n, Charsets.UTF_8).trim() else ""
            }
            if (!body.startsWith("PONG")) null
            else Candidate(ip, if (body.startsWith("PONG|")) body.substringAfter("|") else "")
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Blocking: hanapin ang ESP32 sa mga prefix. Kung may currentIp at sumasagot ito, iyon lang ang ibabalik
     * (hindi na magso-scan). Ang IP mismo ng phone ay nilalaktawan.
     */
    fun scan(
        prefixes: List<String>,
        currentIp: String? = null,
        port: Int = 80,
        threads: Int = 48,
        connectMs: Int = 450,
        readMs: Int = 700
    ): List<Candidate> {
        if (!currentIp.isNullOrBlank()) {
            probe(currentIp.trim(), port, connectMs, readMs)?.let { return listOf(it) }
        }
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "esp32-finder").apply { isDaemon = true } }
        try {
            val tasks = ArrayList<Callable<Candidate?>>()
            for (prefix in prefixes) {
                for (host in 1..254) {
                    val ip = prefix + host
                    if (ip == currentIp?.trim()) continue
                    tasks.add(Callable { probe(ip, port, connectMs, readMs) })
                }
            }
            return pool.invokeAll(tasks, 20, TimeUnit.SECONDS)
                .mapNotNull { f -> try { if (f.isCancelled) null else f.get() } catch (e: Exception) { null } }
                .sortedBy { c -> c.ip.split(".").map { it.toIntOrNull() ?: 0 }.fold(0L) { acc, n -> acc * 256 + n } }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Isang scan lang sa isang pagkakataon (kung may tumatakbo na, hindi na magsisimula ng isa pa).
     * Ang onDone ay tinatawag sa BACKGROUND thread. Ibinabalik ang false kung may tumatakbo na.
     */
    fun scanAsync(currentIp: String?, onDone: (List<Candidate>) -> Unit): Boolean {
        if (!scanning.compareAndSet(false, true)) return false
        Thread({
            val result = try {
                scan(localPrefixes(), currentIp)
            } catch (t: Throwable) {
                emptyList() // kahit anong error - walang lalabas sa thread na ito at hindi mag-crash ang app
            } finally {
                scanning.set(false)
            }
            try { onDone(result) } catch (t: Throwable) { }
        }, "esp32-finder-main").apply { isDaemon = true }.start()
        return true
    }
}
