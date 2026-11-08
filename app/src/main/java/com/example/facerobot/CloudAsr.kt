package com.example.facerobot

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cloud speech-to-text (Whisper sa Groq) para sa "Alexa-style" na pakikinig.
 *
 * Ang [AlexaMic] ang humahanap ng pangungusap (auto-gain, noise gate, atbp.) at ibinibigay ang audio dito.
 * Ipinapadala ito sa Groq (OpenAI-compatible endpoint, model: whisper-large-v3 o whisper-large-v3-turbo) at ibinabalik
 * ang text. Mas tumpak ito sa Taglish at sa malayuang boses kaysa sa Vosk, at may "prompt" tayo para makilala ang
 * "Rustech" at ang mga utos mo.
 *
 * Sunod-sunod ang pagproseso (isang thread) para hindi magkagulo ang pagkakasunod ng mga utos.
 */
class CloudAsr(
    client: OkHttpClient,
    private val apiKey: () -> String,
    private val model: () -> String,
    private val language: () -> String,
    private val prompt: () -> String,
    private val onText: (text: String, latencyMs: Long) -> Unit,
    private val onInfo: (message: String) -> Unit
) {
    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MAX_PENDING = 3

        /** Mga kilalang "multo" na isinusulat ng Whisper kapag ingay/katahimikan lang ang narinig. */
        private val HALLUCINATIONS = listOf(
            "thank you for watching", "thanks for watching", "please subscribe", "subscribe to my channel",
            "subtitles by", "subtitle by", "amara org", "salamat sa panonood", "www", "you"
        )

        /** WAV header + PCM 16-bit mono. */
        fun pcmToWav(pcm: ShortArray, sampleRate: Int): ByteArray {
            val dataLen = pcm.size * 2
            val out = ByteArrayOutputStream(44 + dataLen)
            fun w32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
            fun w16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.write("RIFF".toByteArray()); w32(36 + dataLen)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); w32(16); w16(1); w16(1); w32(sampleRate); w32(sampleRate * 2); w16(2); w16(16)
            out.write("data".toByteArray()); w32(dataLen)
            val bb = ByteArray(dataLen)
            for (i in pcm.indices) {
                bb[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                bb[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
            }
            out.write(bb)
            return out.toByteArray()
        }

        /**
         * Sinasala ang resulta ng Whisper kung mukhang hindi tunay na pagsasalita (mataas ang no_speech_prob,
         * mababa ang confidence, paulit-ulit na loop, o kilalang "multo" na parirala).
         * Ibinabalik ang malinis na text, o null kung dapat itapon.
         */
        fun filterResult(json: JSONObject): String? {
            val text = json.optString("text", "").trim()
            if (text.length < 2) return null

            val segs = json.optJSONArray("segments")
            if (segs != null && segs.length() > 0) {
                var allSilent = true
                var logprobSum = 0.0
                var maxCompression = 0.0
                for (i in 0 until segs.length()) {
                    val sg = segs.optJSONObject(i) ?: continue
                    val nsp = sg.optDouble("no_speech_prob", 0.0)
                    val lp = sg.optDouble("avg_logprob", 0.0)
                    logprobSum += lp
                    maxCompression = maxOf(maxCompression, sg.optDouble("compression_ratio", 0.0))
                    if (!(nsp > 0.6 && lp < -0.7)) allSilent = false
                }
                val avgLogprob = logprobSum / segs.length()
                if (allSilent) return null
                if (avgLogprob < -1.6) return null          // halos hulaan lang
                if (maxCompression > 2.6) return null       // paulit-ulit na loop
            }

            val norm = text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.replace(Regex("\\s+"), " ").trim()
            if (HALLUCINATIONS.any { norm == it || norm.startsWith("$it ") && norm.length < it.length + 12 }) return null
            return text
        }
    }

    private val http = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val exec = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    @Volatile private var blockedUntil = 0L

    fun submit(pcm: ShortArray, sampleRate: Int) {
        if (System.currentTimeMillis() < blockedUntil) return
        if (pending.get() >= MAX_PENDING) {
            onInfo("☁ masyadong maraming nakapila - nilaktawan ang isang pangungusap")
            return
        }
        pending.incrementAndGet()
        exec.execute {
            try {
                transcribe(pcm, sampleRate)
            } catch (e: Throwable) {
                onInfo("☁ error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    fun shutdown() {
        exec.shutdownNow()
    }

    private fun transcribe(pcm: ShortArray, sampleRate: Int) {
        val key = apiKey().trim()
        if (key.isEmpty()) {
            onInfo("☁ walang Groq API key")
            return
        }
        val t0 = System.currentTimeMillis()
        val wav = pcmToWav(pcm, sampleRate)

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model())
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
        val lang = language().trim()
        if (lang.isNotEmpty()) body.addFormDataPart("language", lang)
        val pr = prompt().trim()
        if (pr.isNotEmpty()) body.addFormDataPart("prompt", pr)

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .post(body.build())
            .build()

        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                when (resp.code) {
                    401 -> onInfo("☁ mali ang Groq API key (401)")
                    429 -> {
                        val wait = resp.header("retry-after")?.toDoubleOrNull() ?: 10.0
                        blockedUntil = System.currentTimeMillis() + (wait * 1000).toLong().coerceIn(2000L, 120000L)
                        onInfo("☁ naabot ang rate limit ng Groq - hintay ng ${wait.toInt()}s")
                    }
                    else -> onInfo("☁ Groq error ${resp.code}: ${raw.take(120)}")
                }
                return
            }
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (json == null) {
                onInfo("☁ hindi maintindihan ang sagot ng Groq")
                return
            }
            // multo/ingay -> walang laman na text (itatapon ng tumatawag, pero maibabalik ang status)
            onText(filterResult(json) ?: "", System.currentTimeMillis() - t0)
        }
    }
}
