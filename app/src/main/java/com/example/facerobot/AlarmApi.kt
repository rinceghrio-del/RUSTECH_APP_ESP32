package com.example.facerobot

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Kausap ng alarm system ng ESP32 (main.cpp V5.5.0). Ang ESP32 ang may hawak at nagti-trigger ng alarm;
 * ang app ay taga-set, taga-tingin, at taga-patay lang.
 *
 * Lahat ng tawag ay asynchronous. Ang callback ay tinatawag sa BACKGROUND thread - ang tumatawag ang bahalang
 * lumipat sa UI thread. Ang `error` ay may laman kapag nabigo (walang koneksyon, ESP32 error, atbp.).
 */
class AlarmApi(client: OkHttpClient, private val baseUrl: () -> String) {

    private val http: OkHttpClient = client.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun call(params: List<Pair<String, String>>, cb: (state: AlarmState?, error: String?) -> Unit) {
        val url = baseUrl().trimEnd('/') + "/command?" + params.joinToString("&") { "${it.first}=${enc(it.second)}" }
        val request = Request.Builder().url(url).build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cb(null, "Hindi maabot ang robot")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = try { r.body?.string().orEmpty() } catch (_: Exception) { "" }
                    if (!r.isSuccessful) {
                        cb(null, if (body.isNotBlank()) body.trim() else "Error ${r.code}")
                        return
                    }
                    val st = parseState(body)
                    if (st == null) {
                        // Lumang firmware (walang alarm commands) ay sumasagot ng "IGNORED"/"OK" lang
                        cb(null, "Walang alarm feature ang firmware ng robot (kailangan ang main.cpp V5.5.0)")
                    } else {
                        cb(st, null)
                    }
                }
            }
        })
    }

    fun list(cb: (AlarmState?, String?) -> Unit) = call(listOf("dir" to "ALARM_LIST"), cb)

    fun set(
        hour: Int, minute: Int, days: Int, once: Boolean, track: Int = 0, label: String = "", id: Int? = null,
        buzz: Boolean = true,
        cb: (AlarmState?, String?) -> Unit
    ) {
        val p = ArrayList<Pair<String, String>>()
        p.add("dir" to "ALARM_SET")
        p.add("h" to hour.toString())
        p.add("m" to minute.toString())
        p.add("days" to days.toString())
        p.add("once" to (if (once) "1" else "0"))
        if (track > 0) p.add("track" to track.toString())
        if (!buzz) p.add("buzz" to "0")            // DFPlayer lang, walang buzzer
        if (label.isNotBlank()) p.add("label" to label.trim())
        if (id != null) p.add("id" to id.toString())
        call(p, cb)
    }

    fun delete(id: Int, cb: (AlarmState?, String?) -> Unit) = call(listOf("dir" to "ALARM_DEL", "id" to id.toString()), cb)
    fun deleteAll(cb: (AlarmState?, String?) -> Unit) = call(listOf("dir" to "ALARM_DEL", "id" to "ALL"), cb)
    fun enable(id: Int, on: Boolean, cb: (AlarmState?, String?) -> Unit) =
        call(listOf("dir" to "ALARM_ENABLE", "id" to id.toString(), "on" to (if (on) "1" else "0")), cb)

    fun stop(cb: (AlarmState?, String?) -> Unit) = call(listOf("dir" to "ALARM_STOP"), cb)
    fun snooze(minutes: Int, cb: (AlarmState?, String?) -> Unit) =
        call(listOf("dir" to "ALARM_SNOOZE", "min" to minutes.toString()), cb)

    /** Ibigay ang oras ng phone sa ESP32. Ina-apply lang ito ng firmware kung wala pa itong tamang oras (walang NTP). */
    fun syncTime(cb: (AlarmState?, String?) -> Unit) =
        call(listOf("dir" to "SETTIME", "epoch" to (System.currentTimeMillis() / 1000L).toString()), cb)

    companion object {
        fun parseState(body: String): AlarmState? {
            return try {
                val o = JSONObject(body)
                if (!o.has("alarms")) return null
                val arr = o.getJSONArray("alarms")
                val list = ArrayList<EspAlarm>()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    list.add(
                        EspAlarm(
                            id = a.optInt("id", i),
                            hour = a.optInt("h", 0),
                            minute = a.optInt("m", 0),
                            days = a.optInt("days", 0),
                            once = a.optInt("once", 0) == 1,
                            enabled = a.optInt("on", 1) == 1,
                            track = a.optInt("track", 0),
                            label = a.optString("label", ""),
                            buzz = a.optInt("buzz", 1) == 1
                        )
                    )
                }
                AlarmState(
                    timeValid = o.optInt("valid", 0) == 1,
                    nowEpoch = o.optLong("now", 0L),
                    ringing = o.optInt("ringing", 0) == 1,
                    snooze = o.optInt("snooze", 0) == 1,
                    setId = o.optInt("set", -1),
                    alarms = list.sortedWith(compareBy({ it.hour }, { it.minute }))
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
