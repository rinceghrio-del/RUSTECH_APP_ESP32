package com.example.facerobot

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Alexa-style" na pakikinig para sa Vosk.
 *
 * Ang karaniwang SpeechService ng Vosk ay ipinapasa lang ang hilaw na audio ng mic sa recognizer. Kapag malayo ang
 * nagsasalita, mahina ang signal at nalulunod sa ingay (servo, fan, kwarto), kaya pumapalpak ang Vosk.
 * Ang class na ito ay may sariling AudioRecord at dumadaan ang audio sa "front-end" bago pumasok sa Vosk:
 *   1. High-pass filter  - tanggal ang rumble/hangin/ugong ng motor sa ibaba ng ~80 Hz
 *   2. Noise floor tracker + VAD - kusang natututo ng ingay ng paligid at alam kung kailan may nagsasalita
 *   3. AGC (auto gain) - kusang pinapalakas ang mahinang boses (hanggang [FarFieldDsp.maxGain]x) at pinipigilan ang sobrang lakas
 *   4. Noise gate + pre-roll - tahimik (digital silence) ang ipinapasok sa Vosk kapag walang nagsasalita, at may
 *      300 ms na "pre-roll" para hindi maputol ang simula ng salita
 *   5. Android NoiseSuppressor / AcousticEchoCanceler (kung meron ang phone)
 *
 * Pareho ang gamit (API) ng SpeechService: startListening(listener), setPause(), stop(), shutdown().
 * Ang mga callback ng listener ay tinatawag sa MAIN thread, gaya ng SpeechService.
 */
class FarFieldDsp(
    private val sampleRate: Int = 16000,
    var maxGain: Float = 15f,
    var gateEnabled: Boolean = true,
    var bypass: Boolean = false
) {
    companion object {
        const val PREROLL_FRAMES = 15         // ~300 ms kung 20 ms ang frame
        const val HANGOVER_FRAMES = 32        // ~640 ms na bukas pa ang gate pagkatapos ng huling boses
        const val ONSET_FRAMES = 2            // ilang magkasunod na frame bago ituring na boses
        const val TARGET_RMS = 2800f          // ~ -21 dBFS: komportableng antas para sa Vosk
        const val MIN_SPEECH_RMS = 70f        // pinakamababang antas na ituturing na boses (int16 units)
        const val MIN_NOISE_FLOOR = 15f
        const val HPF_COEFF = 0.97f           // ~80 Hz sa 16 kHz
    }

    // --- estado ---
    private var hpX1 = 0f
    private var hpY1 = 0f

    @Volatile var noiseFloor = 60f
        private set
    @Volatile var gain = 4f
        private set
    @Volatile var speechOpen = false
        private set
    /** Boses ba ang pinakahuling frame? (para sa pagbibilang ng tunay na boses sa utterance) */
    @Volatile var lastFrameLoud = false
        private set

    /** Ilang 20 ms na frame bukas pa ang gate pagkatapos ng huling boses (mas maikli = mas mabilis ang tugon). */
    var hangoverFrames: Int = HANGOVER_FRAMES

    private var onsetCount = 0
    private var hangover = 0
    private val pre = ArrayDeque<ShortArray>()   // pre-roll ng na-process na audio

    @Volatile var peakAbs = 0                    // pinakamalakas na raw sample mula huling takeStats()

    /** Ibalik sa simula ang gate (hal. pagkatapos mag-pause habang nagsasalita ang robot). Hindi binubura ang natutunang ingay. */
    fun resetGate() {
        onsetCount = 0
        hangover = 0
        speechOpen = false
        pre.clear()
        hpX1 = 0f
        hpY1 = 0f
    }

    /**
     * Pinoproseso ang isang frame ([n] samples) at isinusulat sa [out] ang dapat ipasok sa Vosk.
     * Ibinabalik ang bilang ng samples na naisulat. Ang [out] ay dapat kasya ang (PREROLL_FRAMES + 1) * n.
     */
    fun process(input: ShortArray, n: Int, out: ShortArray): Int {
        if (n <= 0) return 0

        var pk = 0
        for (i in 0 until n) {
            val a = abs(input[i].toInt())
            if (a > pk) pk = a
        }
        if (pk > peakAbs) peakAbs = pk

        if (bypass) {
            lastFrameLoud = false
            System.arraycopy(input, 0, out, 0, n)
            return n
        }

        // 1) High-pass filter
        val y = FloatArray(n)
        var sumSq = 0.0
        var x1 = hpX1
        var y1 = hpY1
        for (i in 0 until n) {
            val x = input[i].toFloat()
            val yy = x - x1 + HPF_COEFF * y1
            x1 = x
            y1 = yy
            y[i] = yy
            sumSq += (yy * yy).toDouble()
        }
        hpX1 = x1
        hpY1 = y1
        val rms = sqrt(sumSq / n).toFloat()

        // 2) Noise floor + VAD (may hysteresis)
        val openThr = max(noiseFloor * 2.5f, MIN_SPEECH_RMS)
        val keepThr = max(noiseFloor * 1.6f, MIN_SPEECH_RMS * 0.7f)
        val loud = if (speechOpen) rms > keepThr else rms > openThr
        lastFrameLoud = loud
        if (!speechOpen && !loud) {
            // hindi nagsasalita: i-update ang ingay ng paligid (mabilis bumaba, mabagal tumaas)
            val k = if (rms < noiseFloor) 0.2f else 0.01f
            noiseFloor = max(MIN_NOISE_FLOOR, noiseFloor + (rms - noiseFloor) * k)
        }

        var emitNow = false
        if (loud) {
            onsetCount++
            if (onsetCount >= ONSET_FRAMES || speechOpen) {
                if (!speechOpen) emitNow = true   // kabubukas lang ng gate -> ilabas ang pre-roll
                speechOpen = true
                hangover = hangoverFrames
            }
        } else {
            onsetCount = 0
            if (speechOpen) {
                hangover--
                if (hangover <= 0) speechOpen = false
            }
        }

        // 3) AGC: sa mga frame na may boses lang nag-a-adjust
        if (loud) {
            val desired = (TARGET_RMS / max(rms, 1f)).coerceIn(0.25f, maxGain)
            gain += if (desired < gain) (desired - gain) * 0.5f else (desired - gain) * 0.08f
        }
        gain = gain.coerceIn(0.25f, maxGain)

        // 4) Ilapat ang gain na may soft limiter
        val frame = ShortArray(n)
        val g = gain
        for (i in 0 until n) {
            var v = y[i] * g
            val av = abs(v)
            if (av > 24000f) v = (if (v < 0) -1f else 1f) * (24000f + (av - 24000f) * 0.25f)
            frame[i] = v.coerceIn(-32767f, 32767f).toInt().toShort()
        }

        // 5) Gate + pre-roll
        if (!gateEnabled) {
            System.arraycopy(frame, 0, out, 0, n)
            return n
        }
        pre.addLast(frame)
        while (pre.size > PREROLL_FRAMES + 1) pre.removeFirst()

        if (speechOpen) {
            var w = 0
            if (emitNow) {
                for (f in pre) {
                    System.arraycopy(f, 0, out, w, f.size)
                    w += f.size
                }
                pre.clear()
            } else {
                System.arraycopy(frame, 0, out, 0, n)
                w = n
            }
            return w
        }
        // tahimik: digital silence ang papasok sa Vosk (natural na natatapos ang pangungusap, walang "multo" na salita galing sa ingay)
        java.util.Arrays.fill(out, 0, n, 0.toShort())
        return n
    }
}

/**
 * Kinokolekta ang buong pangungusap (utterance) mula sa mga frame na lumalabas sa [FarFieldDsp] habang bukas ang gate.
 * Pagkasara ng gate (natapos magsalita), ibinabalik ang audio para ipadala sa cloud speech-to-text.
 * Ang mga napakaikling ingay (click, katok) ay itinatapon.
 */
class UtteranceCollector(
    private val sampleRate: Int,
    private val frameSize: Int,
    private val hangoverFrames: Int,
    private val maxSeconds: Int = 12,
    private val minLoudFrames: Int = 12          // ~240 ms ng tunay na boses
) {
    private var buf = ShortArray(sampleRate * 4)
    private var len = 0
    private var loudFrames = 0
    private var notified = false
    var active = false
        private set

    fun discard() {
        active = false
        len = 0
        loudFrames = 0
        notified = false
    }

    /** Ibinabalik ang kumpletong utterance (PCM 16-bit mono) kapag tapos na, kung hindi ay null. */
    fun feed(samples: ShortArray, w: Int, open: Boolean, loud: Boolean, onStart: () -> Unit): ShortArray? {
        if (open) {
            if (!active) {
                active = true
                len = 0
                loudFrames = 0
                notified = false
            }
            if (loud) loudFrames++
            // I-abiso lang kapag tunay na boses na (hindi click/katok): ~120 ms
            if (!notified && loudFrames >= 6) {
                notified = true
                onStart()
            }
            if (len + w > buf.size) buf = buf.copyOf(max(buf.size * 2, len + w))
            System.arraycopy(samples, 0, buf, len, w)
            len += w
            if (len >= maxSeconds * sampleRate) return finish(trimTail = false)   // masyadong mahaba: putulin
            return null
        }
        return if (active) finish(trimTail = true) else null
    }

    private fun finish(trimTail: Boolean): ShortArray? {
        val total = len
        val loudCount = loudFrames
        discard()
        if (loudCount < minLoudFrames || total <= 0) return null
        // Ang hangover ay puro halos-tahimik na dulo: panatilihin lang ang ~160 ms para mas mabilis ang upload
        val tail = if (trimTail) max(0, hangoverFrames - 8) * frameSize else 0
        val end = max(total - tail, total / 2)
        return buf.copyOf(end)
    }
}

class AlexaMic(
    private val recognizer: Recognizer?,                       // null = cloud mode (walang Vosk)
    private val sampleRate: Int = 16000,
    maxGain: Float = 15f,
    gateEnabled: Boolean = true,
    bypass: Boolean = false,
    private val useSystemEffects: Boolean = true,
    private val onUtterance: ((ShortArray) -> Unit)? = null,   // cloud mode: kumpletong pangungusap (PCM 16k mono)
    private val onSpeechStart: (() -> Unit)? = null,           // cloud mode: nagsimulang magsalita (tinatawag sa main thread)
    hangoverFrames: Int = FarFieldDsp.HANGOVER_FRAMES
) {
    private val dsp = FarFieldDsp(sampleRate, maxGain, gateEnabled, bypass).also { it.hangoverFrames = hangoverFrames }
    private val collector = UtteranceCollector(sampleRate, sampleRate / 50, hangoverFrames)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var listener: RecognitionListener? = null

    /** Isang linya para sa Voice Log: gain, ingay ng paligid, at peak ng boses mula huling tawag. */
    fun takeStats(): String {
        val peakPct = (dsp.peakAbs * 100 / 32767)
        dsp.peakAbs = 0
        return "🎚 gain ${"%.1f".format(dsp.gain)}x · ingay ${dsp.noiseFloor.toInt()} · peak $peakPct%"
    }

    fun startListening(l: RecognitionListener): Boolean {
        if (running) return false
        listener = l
        running = true
        thread = Thread({ runLoop() }, "AlexaMic").also { it.start() }
        return true
    }

    fun setPause(p: Boolean) {
        paused = p
    }

    /** Hihintayin matapos ang thread bago bumalik - ligtas nang isara ang Recognizer pagkatapos nito. */
    fun stop(): Boolean {
        running = false
        val t = thread
        thread = null
        try { t?.join(2500) } catch (_: InterruptedException) {}
        return true
    }

    fun shutdown() {
        stop()
        listener = null
    }

    private fun createRecord(source: Int, bufBytes: Int): AudioRecord? {
        return try {
            val r = AudioRecord(
                source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (_: Throwable) {
            null
        }
    }

    private fun runLoop() {
        val l = listener ?: return
        val frameSize = sampleRate / 50            // 20 ms
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf, sampleRate * 2)  // ~1 segundo para walang overrun kapag medyo mabigat ang main thread
        val record = createRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufBytes)
            ?: createRecord(MediaRecorder.AudioSource.MIC, bufBytes)
        if (record == null) {
            mainHandler.post { l.onError(Exception("Hindi mabuksan ang mic (AudioRecord)")) }
            running = false
            return
        }

        var ns: NoiseSuppressor? = null
        var aec: AcousticEchoCanceler? = null
        if (useSystemEffects) {
            try {
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true }
            } catch (_: Throwable) {}
            try {
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true }
            } catch (_: Throwable) {}
        }

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                mainHandler.post { l.onError(Exception("Ginagamit ng ibang app ang mic")) }
                return
            }

            val inBuf = ShortArray(frameSize)
            val outBuf = ShortArray(frameSize * (FarFieldDsp.PREROLL_FRAMES + 2))
            val chunk = ShortArray(sampleRate / 5 + outBuf.size)   // ~100 ms bago ipasok sa Vosk
            val chunkTarget = sampleRate / 10
            var chunkLen = 0
            var lastPartial = ""
            var wasPaused = false

            while (running) {
                val n = record.read(inBuf, 0, frameSize)
                if (n < 0) {
                    mainHandler.post { l.onError(Exception("Mic read error $n")) }
                    return
                }
                if (n == 0) continue

                if (paused) {
                    // Habang nagsasalita ang robot: ubusin lang ang audio at itapon (walang pinapasok sa Vosk)
                    if (!wasPaused) {
                        wasPaused = true
                        chunkLen = 0
                        collector.discard()
                    }
                    continue
                }
                if (wasPaused) {
                    wasPaused = false
                    dsp.resetGate()
                    chunkLen = 0
                }

                val w = dsp.process(inBuf, n, outBuf)

                val rec = recognizer
                if (rec == null) {
                    // CLOUD MODE: kolektahin ang buong pangungusap, ipadala kapag tapos nang magsalita
                    val done = collector.feed(outBuf, w, dsp.speechOpen, dsp.lastFrameLoud) {
                        mainHandler.post { onSpeechStart?.invoke() }
                    }
                    if (done != null) onUtterance?.invoke(done)
                    continue
                }

                System.arraycopy(outBuf, 0, chunk, chunkLen, w)
                chunkLen += w
                if (chunkLen < chunkTarget) continue

                val len = chunkLen
                chunkLen = 0
                if (rec.acceptWaveForm(chunk, len)) {
                    val res = rec.getResult()
                    lastPartial = ""
                    mainHandler.post { l.onResult(res) }
                } else {
                    val part = rec.getPartialResult()
                    if (part != lastPartial) {
                        lastPartial = part
                        mainHandler.post { l.onPartialResult(part) }
                    }
                }
            }
        } catch (t: Throwable) {
            mainHandler.post { l.onError(Exception(t.message ?: "mic error")) }
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            try { ns?.release() } catch (_: Throwable) {}
            try { aec?.release() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
            running = false
        }
    }
}
