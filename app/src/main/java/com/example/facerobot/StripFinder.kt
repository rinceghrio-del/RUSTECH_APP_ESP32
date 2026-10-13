package com.example.facerobot

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hinahanap ang Lasco/Tuya strip sa WiFi kapag nagbago ang IP nito. Dalawang hakbang:
 *  1) Alamin kung aling mga address sa network ang may bukas na port 6668 (ang local port ng Tuya) - mabilis, koneksyon lang.
 *  2) Sa mga iyon, subukan ang STATUS gamit ang Device ID at Local Key mo. Ang device LAMANG na tumugon nang tama
 *     ang tatanggapin - kaya hindi nito mapagkakamali ang ibang Tuya device (bombilya, atbp.).
 *
 * Pure Kotlin/JVM para ma-test sa PC.
 */
object StripFinder {

    /** Mga address sa mga prefix (hal. "192.168.1.") na may bukas na port. */
    fun openHosts(prefixes: List<String>, port: Int = 6668, threads: Int = 48, connectMs: Int = 400, skip: Set<String> = emptySet()): List<String> {
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "strip-finder").apply { isDaemon = true } }
        try {
            val tasks = ArrayList<Callable<String?>>()
            for (prefix in prefixes) {
                for (host in 1..254) {
                    val ip = prefix + host
                    if (ip in skip) continue
                    tasks.add(Callable {
                        val s = Socket()
                        try {
                            s.connect(InetSocketAddress(ip, port), connectMs)
                            ip
                        } catch (e: Exception) {
                            null
                        } finally {
                            try { s.close() } catch (_: Exception) { }
                        }
                    })
                }
            }
            return pool.invokeAll(tasks, 20, TimeUnit.SECONDS)
                .mapNotNull { f -> try { if (f.isCancelled) null else f.get() } catch (e: Exception) { null } }
                .sortedBy { ip -> ip.split(".").map { it.toIntOrNull() ?: 0 }.fold(0L) { acc, n -> acc * 256 + n } }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Ibinabalik ang IP ng strip na tumutugma sa Device ID at Local Key, o null kung wala.
     * Blocking - tawagin sa background thread.
     */
    fun find(
        prefixes: List<String>,
        deviceId: String,
        localKey: String,
        version: String,
        port: Int = 6668,
        connectMs: Int = 400,
        verifyReadMs: Int = 1500,
        maxVerify: Int = 8
    ): String? {
        if (deviceId.isBlank() || localKey.isBlank()) return null
        val open = openHosts(prefixes, port, connectMs = connectMs)
        for (ip in open.take(maxVerify)) {
            val r = TuyaLocalClient(ip, deviceId, localKey, version, port, connectTimeoutMs = 800, readTimeoutMs = verifyReadMs).status()
            if (r.ok) return ip
        }
        return null
    }
}
