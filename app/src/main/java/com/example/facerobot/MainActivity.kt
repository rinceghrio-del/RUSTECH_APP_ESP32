package com.example.facerobot

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.facerobot.ui.RoboEyesView
import com.example.facerobot.vision.FaceEmbedder
import com.example.facerobot.vision.FaceStore
import com.example.facerobot.vision.ImageUtils
import com.example.facerobot.vision.ObstacleAnalyzer
import com.example.facerobot.vision.YoloPersonDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskListener
import org.vosk.android.SpeechService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * FaceRobot MainActivity - Face Centering / Tracking Only Mode
 * Voice recognition: offline Vosk (Filipino) instead of Android's built-in SpeechRecognizer.
 *
 * NOTE: Wake-word gating has been removed. Lahat na ngayon ng narinig ni Vosk
 * (na pumasa sa mic confidence threshold) ay direktang ipoproseso bilang command.
 *
 * CAMERA NAV (bago): kapag naka-STATE_MOVING (AUTO) ang ESP32 robot, ang camera ng app ay nagiging
 * obstacle sensor gamit ang MiDaS depth model (tignan ang vision/ObstacleAnalyzer.kt). Nagpapadala ang app ng
 * NAV_LEFT / NAV_RIGHT / NAV_BACK / NAV_CLEAR hints (+ servo tilt) sa ESP32. Ang ESP32 pa rin ang may huling
 * desisyon; ultrasonic/IR avoidance ang laging mauuna.
 */
@androidx.camera.core.ExperimentalGetImage
class MainActivity : ComponentActivity() {

    private enum class AppState { EYES, CAMERA }

    private lateinit var rootLayout: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var roboEyesView: RoboEyesView
    private lateinit var capturedPhotoView: ImageView
    private lateinit var statusText: TextView
    private lateinit var menuButton: Button
    private var canEnroll = false

    // ---------- TAKE A PICTURE ----------
    @Volatile private var pendingPhotoCapture = false
    private val photoTriggerPhrases = listOf(
        "kuha ng litrato", "kunan mo ako", "kunan mo ako ng litrato",
        "kunan mo ako ng picture", "kuha ng picture", "magpicture",
        "take a picture", "take picture", "picture mo ako", "kunan ng picture"
    )

    // ---------- PANINGIN NI GEMINI (vision) ----------
    // Continuation callback na tatawagin sa susunod na na-process na camera frame - dito ipinapasa
    // ang nakuhang Bitmap papunta sa askGeminiVision() flow.
    @Volatile private var pendingVisionCapture: ((Bitmap) -> Unit)? = null
    private val visionTriggerPhrases = listOf(
        "ano ang nakikita mo", "ano nakikita mo", "ano makikita mo",
        "ano ang meron sa harap", "anong meron sa harap", "ano meron sa harap mo",
        "ano ginagawa ko", "ano ang ginagawa ko", "ano ba ginagawa ko", "ano ang ginagawa niya",
        "ano yan", "ano 'yan", "ano ito", "ano 'to", "anong iyan", "anong ito",
        "kilalanin mo", "kilala mo ba ito", "kilala mo ba yan",
        "ano suot ko", "ano ang suot ko",
        "ano hawak ko", "ano ang hawak ko",
        "tignan mo ako", "tingnan mo ako", "tignan mo ito", "tingnan mo ito"
    )

    private lateinit var cameraExecutor: ExecutorService
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Para sa DFPlayer track na may kasunod na kilos: MAIKLI ang timeout (hanggang ~3s) imbes na 30s/60s ng
    // httpClient, para hindi maghintay nang matagal ang kasunod na movement kapag hindi sumasagot ang ESP32.
    private val dfThenClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    // Hiwalay na client para sa camera-nav hints + ping: MAIKLI ang timeouts at sarili nitong dispatcher.
    // Ang pangkalahatang httpClient ay may 30s/60s timeout - kapag nag-hang ang isang request, napupuno ang
    // dispatcher slots at naiipit ang lahat ng hints/ping (kaya "minsan hindi gumagana" ang camera avoidance).
    private val navClient = httpClient.newBuilder()
        .connectTimeout(700, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(900, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(900, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 4 })
        .build()

    private lateinit var yoloDetector: YoloPersonDetector
    private lateinit var faceEmbedder: FaceEmbedder
    private lateinit var faceStore: FaceStore
    private lateinit var commandStore: CommandStore
    private lateinit var smartHome: SmartHome

    // Export / Import ng settings (SettingsBackupUi.kt) - Storage Access Framework, walang kailangang permission
    private val settingsExportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) settingsBackupUi.onExportUri(uri) }

    private val settingsImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) settingsBackupUi.onImportUri(uri) }

    private val settingsBackupUi: SettingsBackupUi by lazy {
        SettingsBackupUi(
            activity = this,
            tr = { tr(it) },
            openDialog = { title, content, positive, onPositive, negative, onNegative ->
                val d = ModernDialog().setTitle(title).setView(content)
                if (positive != null) d.setPositiveButton(positive) { _, _ -> onPositive?.invoke() }
                if (negative != null) d.setNegativeButton(negative) { _, _ -> onNegative?.invoke() }
                d.showImmersive()
            },
            onStatus = { msg -> statusText.text = msg },
            launchExport = { name -> settingsExportLauncher.launch(name) },
            launchImport = {
                settingsImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            }
        )
    }

    // Smart Home (RM mini 3 + Lasco power strip) - dialog ay nasa SmartHomeUi.kt
    private val smartHomeUi: SmartHomeUi by lazy {
        SmartHomeUi(
            activity = this,
            smartHome = smartHome,
            commandStore = commandStore,
            tr = { tr(it) },
            openDialog = { title, content, positive, onPositive, negative, onNegative ->
                val d = ModernDialog().setTitle(title).setView(content)
                if (positive != null) d.setPositiveButton(positive) { _, _ -> onPositive?.invoke() }
                if (negative != null) d.setNegativeButton(negative) { _, _ -> onNegative?.invoke() }
                d.showImmersive()
            },
            onStatus = { msg -> statusText.text = msg },
            testTrack = { track, done -> testDfPlayerTrack(track, done) },
            isGeminiOn = { geminiEnabled }
        )
    }

    private var micConfidenceThreshold: Float
        get() = prefs.getFloat("mic_confidence_threshold", 0.5f)
        set(value) { prefs.edit().putFloat("mic_confidence_threshold", value.coerceIn(0f, 1f)).apply() }

    private var appState = AppState.EYES

    // true = RoboEyes ang ipapakita sa screen, false = Camera preview.
    // Kahit alin ang naka-display, tuloy pa rin ang camera analysis (face tracking,
    // recognition, ESP32 commands, voice) dahil tinatakpan lang ng eyes ang preview.
    private var showRoboEyes: Boolean
        get() = prefs.getBoolean("display_roboeyes", true)
        set(value) { prefs.edit().putBoolean("display_roboeyes", value).apply() }

    private val prefs by lazy { getSharedPreferences("facerobot_prefs", MODE_PRIVATE) }
    private var esp32BaseUrl: String
        get() = "http://" + prefs.getString("esp32_ip", "10.37.191.169")!!
        set(value) {
            val ipOnly = value.removePrefix("http://").removePrefix("https://").trim()
            prefs.edit().putString("esp32_ip", ipOnly).apply()
        }

    private var lastSendTime = 0L
    private val sendIntervalMs = 300L

    private var lastYoloCheckTime = 0L
    private val yoloIntervalMs = 400L

    private var consecutivePersonDetections = 0
    private val requiredConsecutiveDetections = 3

    // Pareho ng ginagawa sa person: kailangan ng ilang sunod-sunod na frame ng PAREHONG label
    // (aso o pusa) bago mag-greet, para hindi biglaang magreact sa isang noisy frame lang
    // (hal. mukha ng tao na minsan lang na-misclassify bilang "aso").
    private var consecutivePetDetections = 0
    private var lastPetLabelSeen: String? = null
    private val requiredConsecutivePetDetections = 3

    private var lastRecognitionTime = 0L
    private val recognitionIntervalMs = 600L

    private var closeFaceWidthRatio: Float
        get() = prefs.getFloat("close_face_ratio", 0.40f)
        set(value) { prefs.edit().putFloat("close_face_ratio", value).apply() }

    private var farFaceWidthRatio: Float
        get() = prefs.getFloat("far_face_ratio", 0.23f)
        set(value) { prefs.edit().putFloat("far_face_ratio", value).apply() }

    private var tooFarFaceWidthRatio: Float
        get() = prefs.getFloat("too_far_face_ratio", 0.15f)
        set(value) { prefs.edit().putFloat("too_far_face_ratio", value).apply() }

    private var unknownGreetingTracksRaw: String
        get() = prefs.getString("unknown_greeting_tracks", "") ?: ""
        set(value) { prefs.edit().putString("unknown_greeting_tracks", value).apply() }

    private var lastPersonSeenTime = System.currentTimeMillis()
    private var faceTooClose = false
    private val personTimeoutMs = 4000L

    private var lastUnknownFaceEmbedding: FloatArray? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Google TTS ang preferred engine para sa Filipino (fil-PH) voice; kapag wala, default engine ng phone.
    private val preferredTtsEngine = "com.google.android.tts"
    // "Rachel" - default/pre-made public voice ni ElevenLabs, palaging available sa bawat account.
    private val DEFAULT_ELEVENLABS_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
    private val ttsHandler = Handler(android.os.Looper.getMainLooper())
    private val resumeMicRunnable = Runnable {
        isSpeaking = false
        setMicPaused(false)
        // Nagsalita ang robot habang gising (may wake window): bigyan ng follow-up window pagkatapos
        // magsalita, para hindi mag-expire habang humahaba ang sagot ni Rustech
        if (extendWakeOnResume) {
            extendWakeOnResume = false
            awakeUntil = maxOf(awakeUntil, System.currentTimeMillis() + wakeFollowUpMs)
        }
    }

    // Safety net: kung hindi dumating ang onDone/onCompletion (nag-crash ang TTS engine, nawala ang
    // utterance, nag-hang ang network), ibalik pa rin ang mic - para hindi mabingi ang robot at
    // marinig pa rin ang "hinto". Ang normal na onDone ay papalit dito (scheduleMicResume).
    private fun armMicWatchdog(text: String, extraMs: Long = 0L) {
        val ms = (4000L + text.length * 90L + extraMs).coerceAtMost(90_000L)
        ttsHandler.removeCallbacks(resumeMicRunnable)
        ttsHandler.postDelayed(resumeMicRunnable, ms)
    }

    // Maikling call timeout para sa ElevenLabs: dati ay walang callTimeout ang httpClient, kaya ang
    // naka-hang na request ay nag-iiwan ng bingi na mic hanggang 60s.
    private val ttsClient by lazy {
        httpClient.newBuilder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    // ---------- GEMINI (utak ni Rustech) ----------
    private val geminiBrain by lazy { GeminiBrain(httpClient) }
    private var geminiEnabled: Boolean
        get() = prefs.getBoolean("gemini_enabled", true)
        set(value) { prefs.edit().putBoolean("gemini_enabled", value).apply() }
    private var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) { prefs.edit().putString("gemini_api_key", value.trim()).apply() }
    private var geminiModel: String
        get() = prefs.getString("gemini_model", GeminiBrain.DEFAULT_MODEL) ?: GeminiBrain.DEFAULT_MODEL
        set(value) { prefs.edit().putString("gemini_model", value.trim().ifEmpty { GeminiBrain.DEFAULT_MODEL }).apply() }
    private var geminiDailyLimit: Int
        get() = prefs.getInt("gemini_daily_limit", 500)
        set(value) { prefs.edit().putInt("gemini_daily_limit", value.coerceAtLeast(1)).apply() }

    // Kapag OFF: hindi na magsasalita ang APP (TextToSpeech) - ang ESP32/DFPlayer na ang boses,
    // para hindi magsabay ang dalawang audio source.
    private var appTtsEnabled: Boolean
        get() = prefs.getBoolean("app_tts_enabled", true)
        set(value) { prefs.edit().putBoolean("app_tts_enabled", value).apply() }

    // Pangalan ng partikular na boses na pinili sa lokal na Android/Google TTS - kung blangko,
    // gagamitin ang default na boses ng engine para sa napiling wika.
    private var selectedTtsVoiceName: String
        get() = prefs.getString("tts_voice_name", "") ?: ""
        set(value) { prefs.edit().putString("tts_voice_name", value.trim()).apply() }

    // ---------- ELEVENLABS TTS (opsyonal, mas magandang/natural na boses - may FREE TIER LIMIT) ----------
    // Kapag naubos na ang free tier ni ElevenLabs (o walang internet/mali ang API key), awtomatikong
    // babalik sa default na lokal na Android TTS - o pwede ring i-OFF na lang manually dito.
    private var elevenLabsEnabled: Boolean
        get() = prefs.getBoolean("elevenlabs_enabled", false)
        set(value) { prefs.edit().putBoolean("elevenlabs_enabled", value).apply() }
    private var elevenLabsApiKey: String
        get() = prefs.getString("elevenlabs_api_key", "") ?: ""
        set(value) { prefs.edit().putString("elevenlabs_api_key", value.trim()).apply() }
    private var elevenLabsVoiceId: String
        get() = prefs.getString("elevenlabs_voice_id", DEFAULT_ELEVENLABS_VOICE_ID) ?: DEFAULT_ELEVENLABS_VOICE_ID
        set(value) { prefs.edit().putString("elevenlabs_voice_id", value.trim().ifEmpty { DEFAULT_ELEVENLABS_VOICE_ID }).apply() }
    // Dagdag na "loudness" LAGPAS sa 100% system volume, gamit ang Android LoudnessEnhancer -
    // dito lang gumagana (ElevenLabs playback), dahil dito lang tayo may tunay na MediaPlayer.
    // 0 = walang extra boost, 10-15 = katamtaman, 20+ = medyo malakas na (posibleng may distortion
    // depende sa speaker ng phone).
    private var elevenLabsVolumeBoostDb: Int
        get() = prefs.getInt("elevenlabs_volume_boost_db", 10)
        set(value) { prefs.edit().putInt("elevenlabs_volume_boost_db", value.coerceIn(0, 40)).apply() }
    private var elevenLabsPlayer: MediaPlayer? = null
    private var elevenLabsLoudnessEnhancer: LoudnessEnhancer? = null
    @Volatile private var elevenLabsBusy = false

    // Nagre-reset ang daily quota ng Google ng midnight Pacific time (mga 3 PM sa Pilipinas).
    private fun geminiQuotaDay(): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("America/Los_Angeles")
        return f.format(Date())
    }

    private fun geminiUsedToday(): Int =
        if (prefs.getString("gemini_count_day", "") == geminiQuotaDay()) prefs.getInt("gemini_count", 0) else 0

    private fun bumpGeminiCount() {
        prefs.edit()
            .putString("gemini_count_day", geminiQuotaDay())
            .putInt("gemini_count", geminiUsedToday() + 1)
            .apply()
    }

    @Volatile private var geminiBusy = false
    private var lastGeminiRequestTime = 0L
    private val geminiMinIntervalMs = 4000L      // 4s pagitan = max 15 requests/min, sakto sa free-tier RPM
    private var geminiCooldownUntil = 0L         // pag na-429, pahinga muna
    private var lastGeminiErrorSpeakTime = 0L
    private var lastGreetedName: String? = null
    private var lastGreetedTime = 0L
    private val greetingCooldownMs = 60_000L
    private var lastUnknownGreetTime = 0L

    private var lastPetGreetTime = 0L
    private val petGreetingCooldownMs = 45_000L
    private val petGreetings: Map<String, List<String>> get() = mapOf(
        "pusa" to listOf(tr("Meow! Kumusta pusa!"), tr("Ay, may pusa! Ang cute!"), tr("Hi pusa, gusto mo bang makipaglaro?")),
        "aso" to listOf(tr("Woof woof! Kumusta aso!"), tr("Ay, may aso! Kaibigan ko yan."), tr("Hi doggi!"))
    )

    @Volatile private var voskModel: Model? = null
    @Volatile private var speechService: SpeechService? = null
    @Volatile private var voskRecognizer: Recognizer? = null
    @Volatile private var voskSetupRunning = false     // iwas dobleng download/load
    @Volatile private var voskRestartAttempts = 0      // na-reset kapag may narinig ulit
    private var voskRedownloadTried = false
    private var voskReady = false
    private var isSpeaking = false
    private var currentRecognizedName: String? = null

    private val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-tl-ph-generic-0.6.zip"
    private val voskModelDirName = "vosk-model-tl-ph-generic-0.6"

    private val voiceLog = mutableListOf<Triple<Long, String, String>>()
    private val voiceLogMaxSize = 100

    private val voiceMovementDurationMs = 3000L
    private val movementActions = setOf("FORWARD", "BACKWARD", "LEFT", "RIGHT")
    private var voiceOverrideActive = false

    // ======================= HANAPIN ANG TAO =======================
    // Kapag tinawag mo ang robot ("Rustech") pero WALA siyang nakikitang tao (hal. nakatalikod siya sa 'yo),
    // iikot siya nang paisa-isang hakbang: ikot sandali -> HINTO -> tingin ng camera -> ulit. (Step-and-settle,
    // kasi nasa robot mismo ang camera, kaya malabo at huli ang frame habang umiikot.)
    // Pagkakita ng tao (YOLO) hihinto siya, tapos ang dating face tracking (handleFaceFound) na ang
    // magse-center ng mukha. Kapag nakagitna na ang mukha nang ilang sunod na frame = "naka-lock".
    // STOP / ibang utos / nav mode = kanselado agad ang paghahanap.
    private enum class PersonSearchPhase { SWEEP, LOCKING }

    private var personSearchEnabled: Boolean
        get() = prefs.getBoolean("person_search_enabled", true)
        set(value) { prefs.edit().putBoolean("person_search_enabled", value).apply() }

    @Volatile private var personSearchActive = false
    @Volatile private var personSearchPhase = PersonSearchPhase.SWEEP
    @Volatile private var personSearchCenteredFrames = 0
    @Volatile private var personSearchLastFaceTime = 0L
    @Volatile private var personSearchYoloHit = false
    @Volatile private var lastTrackTurnCmd: String? = null   // huling LEFT/RIGHT na ipinadala ng face tracking
    @Volatile private var lastTrackTurnTime = 0L
    private var personSearchTurnCmd = "RIGHT"
    private var personSearchStartTime = 0L
    private var personSearchLockStart = 0L
    private val personSearchHandler = Handler(android.os.Looper.getMainLooper())

    // --- I-TUNE ITO KUNG KAILANGAN (depende sa bilis ng ikot ng robot mo) ---
    // Raw na ESP32 direction (kapareho ng ginagamit ng face tracking). Kung baliktad ang gusto mong unang ikot, palitan ng "LEFT".
    private val personSearchDefaultTurnCmd = "RIGHT"
    private val personSearchMaxMs = 25_000L        // pinakamahabang paghahanap bago sumuko
    // TRUE = tuloy-tuloy ang ikot hanggang makakita ng tao (hihinto lang pag may nakita).
    // FALSE = dating step-and-settle (ikot ng Burst, hinto ng Settle, ulit) - mas malinaw ang frame pero putol-putol.
    private val personSearchContinuous = true
    private val personSearchResendMs = 250L        // gaano kadalas ulitin ang utos na ikot (tuloy-tuloy na mode)
    private val personSearchBurstMs = 600L         // (step mode) gaano katagal umiikot bawat hakbang
    private val personSearchSettleMs = 900L        // hinto para makakuha ng malinaw na frame
    private val personSearchLockWaitMs = 4_500L    // gaano katagal maghihintay ng mukha pagkakita ng "tao"
    private val personSearchFreshMs = 2_500L       // kung may nakita sa loob nito, hindi na maghahanap
    private val personSearchLockFrames = 3         // ilang sunod na frame na nakagitna ang mukha = lock
    private val personSearchTiltPeriodMs = 9_000L  // palit ng taas/baba ng ulo bawat ganitong tagal
    private val personSearchTilts = intArrayOf(55, 72, 38)  // servo angles (0-110, mas mataas = pataas ang tingin)

    private val personSearchBlockVisible = "may nakikita nang tao"
    private val personSearchFoundLines: List<String> get() = listOf(
        tr("Ayan, nakita na kita!"),
        tr("Aha! Nakita kita!"),
        tr("Andyan ka pala!")
    )
    private val findPersonPhrases = listOf(
        "hanapin mo ako", "hanapin mo ko", "hanap ka ng tao", "hanapin mo ang tao",
        "humarap ka", "harap ka sa akin", "find me", "look for me"
    )

    private fun requiredPersonDetections(): Int =
        if (personSearchActive) 1 else requiredConsecutiveDetections

    private fun personSearchBlockedReason(): String? = when {
        !personSearchEnabled -> "naka-off sa settings"
        navActive || navCalibrating || navTestMode -> "naka-AUTO/nav mode"
        personSearchActive -> "naghahanap na"
        System.currentTimeMillis() - lastPersonSeenTime < personSearchFreshMs -> personSearchBlockVisible
        else -> null
    }

    /** Sinisimulan ang paghahanap. Ibinabalik ang true kung nagsimula talaga. */
    private fun startPersonSearch(source: String, flashSearchEyes: Boolean = true): Boolean {
        val blocked = personSearchBlockedReason()
        if (blocked != null) {
            if (blocked != personSearchBlockVisible) addVoiceLogEntry(source, "hanap-tao: hindi sinimulan ($blocked)")
            return false
        }
        val now = System.currentTimeMillis()
        personSearchActive = true
        personSearchPhase = PersonSearchPhase.SWEEP
        personSearchStartTime = now
        personSearchCenteredFrames = 0
        personSearchLastFaceTime = 0L
        personSearchYoloHit = false
        headScanActive = false          // itigil ang idle head-scan kung tumatakbo
        consecutivePersonDetections = 0

        // Kung kakakita lang ng tao kanina (nawala sa gilid), ituloy ang parehong direksyon ng ikot
        val lastTurn = lastTrackTurnCmd
        personSearchTurnCmd = if (lastTurn != null && now - lastTrackTurnTime < 60_000L) lastTurn
            else personSearchDefaultTurnCmd

        val tilt = personSearchTilts[0]
        outputServoAngle = tilt.toFloat()
        smoothedServoAngle = tilt.toFloat()
        sendCommandToEsp32("STOP", tilt)

        statusText.text = tr("🔍 Hinahanap ka...")
        if (flashSearchEyes) roboEyesView.flashMood(RoboEyesView.Mood.SEARCHING, personSearchMaxMs)
        addVoiceLogEntry(source, "hanap-tao: nagsimula (ikot $personSearchTurnCmd)")
        personSearchHandler.removeCallbacksAndMessages(null)
        personSearchHandler.postDelayed(personSearchStepRunnable, 500L)
        return true
    }

    private val personSearchStepRunnable = object : Runnable {
        override fun run() {
            if (!personSearchActive || shuttingDown) return
            val now = System.currentTimeMillis()
            if (navActive || navCalibrating || navTestMode) {
                cancelPersonSearch()
                return
            }
            if (now - personSearchStartTime > personSearchMaxMs) {
                // Kung may mukhang nakikita pa at gumagana ang tracking, ituring na nahanap na
                finishPersonSearch(found = now - personSearchLastFaceTime < 1500L, why = "timeout")
                return
            }
            when (personSearchPhase) {
                PersonSearchPhase.SWEEP -> {
                    val idx = ((now - personSearchStartTime) / personSearchTiltPeriodMs).toInt() % personSearchTilts.size
                    val tilt = personSearchTilts[idx]
                    val cmd = personSearchTurnCmd
                    if (personSearchContinuous) {
                        // Tuloy-tuloy na ikot: inuulit lang ang utos (para hindi mag-timeout ang ESP32) hanggang
                        // may makita ang YOLO/face detector. Ang paghinto ay ginagawa ng enterPersonSearchLocking().
                        sendCommandToEsp32(cmd, tilt)
                        personSearchHandler.postDelayed(this, personSearchResendMs)
                        return
                    }
                    // Ang ESP32 ay inuulit-ulit ang utos tuwing ~300ms (gaya ng sendTimedCommand), kaya ganito rin dito
                    sendCommandToEsp32(cmd, tilt)
                    personSearchHandler.postDelayed({
                        if (personSearchActive && personSearchPhase == PersonSearchPhase.SWEEP) sendCommandToEsp32(cmd, tilt)
                    }, 300L)
                    personSearchHandler.postDelayed({
                        if (personSearchActive && personSearchPhase == PersonSearchPhase.SWEEP) sendCommandToEsp32("STOP", tilt)
                    }, personSearchBurstMs)
                    personSearchHandler.postDelayed(this, personSearchBurstMs + personSearchSettleMs)
                }
                PersonSearchPhase.LOCKING -> {
                    val lastActivity = maxOf(personSearchLockStart, personSearchLastFaceTime)
                    if (now - lastActivity > personSearchLockWaitMs) {
                        if (personSearchYoloHit && personSearchLastFaceTime == 0L) {
                            // May "tao" na nakita pero walang mukha - malamang nakatalikod pa rin
                            finishPersonSearch(
                                found = false, why = "may tao pero walang mukha",
                                line = tr("Nakikita kita pero hindi ko makita ang mukha mo. Humarap ka naman sa akin.")
                            )
                            return
                        }
                        // Maling detect o nawala ang mukha - ituloy ang pag-ikot
                        personSearchPhase = PersonSearchPhase.SWEEP
                        personSearchYoloHit = false
                        personSearchCenteredFrames = 0
                        personSearchHandler.post(this)
                    } else {
                        personSearchHandler.postDelayed(this, 300L)
                    }
                }
            }
        }
    }

    private fun enterPersonSearchLocking() {
        if (!personSearchActive || personSearchPhase == PersonSearchPhase.LOCKING) return
        personSearchPhase = PersonSearchPhase.LOCKING
        personSearchLockStart = System.currentTimeMillis()
        personSearchCenteredFrames = 0
        sendCommandToEsp32("STOP")
        roboEyesView.clearFlash()
        statusText.text = tr("🔍 May nakita! Nilo-lock ang mukha...")
        personSearchHandler.removeCallbacks(personSearchStepRunnable)
        personSearchHandler.postDelayed(personSearchStepRunnable, 300L)
    }

    /** Tinatawag (main thread) kapag may "person" na nakita ng YOLO habang naghahanap. */
    private fun personSearchOnYoloPerson() {
        if (!personSearchActive) return
        personSearchYoloHit = true
        enterPersonSearchLocking()
    }

    /** Tinatawag (main thread) tuwing may mukhang nakita ng ML Kit; command = LEFT/RIGHT/FORWARD/BACKWARD/STOP. */
    private fun personSearchOnFace(command: String) {
        if (!personSearchActive) return
        personSearchLastFaceTime = System.currentTimeMillis()
        enterPersonSearchLocking()
        if (command == "LEFT" || command == "RIGHT") {
            personSearchCenteredFrames = 0
            return
        }
        personSearchCenteredFrames++
        if (personSearchCenteredFrames >= personSearchLockFrames) {
            finishPersonSearch(found = true, why = "naka-lock ang mukha")
        }
    }

    private fun finishPersonSearch(found: Boolean, why: String, line: String? = null) {
        if (!personSearchActive) return
        personSearchActive = false
        personSearchHandler.removeCallbacksAndMessages(null)
        roboEyesView.clearFlash()
        if (found) {
            // Tuloy na ang normal na face tracking (handleFaceFound) - hindi na kailangang mag-STOP dito
            roboEyesView.flashMood(RoboEyesView.Mood.fromKey("HAPPY") ?: RoboEyesView.Mood.ALERT, 2500L)
            statusText.text = tr("✅ Nakita ka na at naka-lock ang mukha")
            if (wakeEnabled) awakeUntil = maxOf(awakeUntil, System.currentTimeMillis() + wakeWindowSec * 1000L)
            speak(line ?: personSearchFoundLines.random())
        } else {
            sendCommandToEsp32("STOP")
            statusText.text = tr("🔍 Hindi kita nahanap")
            speak(line ?: tr("Hindi kita makita. Lumapit ka naman o humarap sa camera ko."))
        }
        addVoiceLogEntry("hanap-tao", "$why (${if (found) "nakita" else "hindi nakita"})")
    }

    /** Tahimik na kanselasyon (STOP, ibang utos, nav mode). */
    private fun cancelPersonSearch() {
        if (!personSearchActive) return
        personSearchActive = false
        personSearchHandler.removeCallbacksAndMessages(null)
        roboEyesView.clearFlash()
        addVoiceLogEntry("hanap-tao", "kinansela")
    }

    /** "hanapin mo ako" / "humarap ka" - lokal, hindi kailangan ng Gemini. */
    private fun tryHandleFindPersonCommand(candidates: List<String>): String? {
        if (candidates.none { t -> findPersonPhrases.any { t.contains(it) } }) return null
        flashCommandEyes()
        return when (val blocked = personSearchBlockedReason()) {
            null -> {
                startPersonSearch("voice")
                speak(tr("Sige, hahanapin kita."))
                "hanap-tao"
            }
            personSearchBlockVisible -> {
                speak(tr("Nakikita na kita, andito ka lang pala!"))
                "hanap-tao: may tao na"
            }
            else -> "hanap-tao: hindi pwede ngayon ($blocked)"
        }
    }

    // ======================= USAPAN NI GEMINI: pila, maikling sagot, sitwasyon =======================
    // Dati, kapag busy pa si Gemini o masyadong mabilis ang sunod na sinabi, NAIWAWALA ang sinabi mo
    // ("na-ignore"). Ngayon ang PINAKAHULING sinabi ay inilalagay sa pila at ipapadala pagkatapos ng sagot.
    private var queuedGeminiCandidates: List<String>? = null
    private var queuedGeminiAt = 0L
    private val geminiQueueHandler = Handler(android.os.Looper.getMainLooper())
    private val drainGeminiQueueRunnable = Runnable { drainGeminiQueue() }

    // Sinabi ng robot kamakailan? Kung oo, tatanggapin ang maiikling sagot ("oo", "hindi", "sige") bilang follow-up.
    @Volatile private var lastRobotReplyTime = 0L
    private val shortReplyWords = setOf(
        "oo", "opo", "po", "hindi", "ayaw", "sige", "ok", "okay", "ayos", "bakit", "ano", "sino", "saan",
        "paano", "talaga", "weh", "ha", "yes", "no", "yep", "nope", "ayun", "wag", "huwag", "tama", "mali", "ulit"
    )

    private fun isAcceptableShortReply(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.length >= 4) return true
        val recentlySpoke = System.currentTimeMillis() - lastRobotReplyTime < 12_000L
        return recentlySpoke && t in shortReplyWords
    }

    private fun queueGeminiUtterance(candidates: List<String>, heardText: String, why: String) {
        queuedGeminiCandidates = candidates
        queuedGeminiAt = System.currentTimeMillis()
        addVoiceLogEntry(heardText, "naka-pila ($why)")
        geminiQueueHandler.removeCallbacks(drainGeminiQueueRunnable)
        geminiQueueHandler.postDelayed(drainGeminiQueueRunnable, 600L)
    }

    private fun drainGeminiQueue() {
        val c = queuedGeminiCandidates ?: return
        val now = System.currentTimeMillis()
        if (now - queuedGeminiAt > 15_000L) {      // luma na, huwag nang sagutin
            queuedGeminiCandidates = null
            addVoiceLogEntry(c.first(), "na-ignore (naluma sa pila)")
            return
        }
        if (geminiBusy || isSpeaking || now - lastGeminiRequestTime < geminiMinIntervalMs) {
            geminiQueueHandler.postDelayed(drainGeminiQueueRunnable, 500L)
            return
        }
        queuedGeminiCandidates = null
        val canUse = geminiEnabled && geminiApiKey.isNotBlank() &&
            now >= geminiCooldownUntil && geminiUsedToday() < geminiDailyLimit
        if (canUse) askGemini(c) else addVoiceLogEntry(c.first(), processVoiceCommand(c))
    }

    /** Dagdag na konteksto para kay Gemini: nakikita ba niya ang tao, naghahanap ba, atbp. */
    private fun buildGeminiSituation(): GeminiBrain.Situation {
        val now = System.currentTimeMillis()
        val seenAgoSec = (now - lastPersonSeenTime) / 1000
        val parts = mutableListOf<String>()
        parts += when {
            personSearchActive -> "kasalukuyan kang umiikot at naghahanap ng tao gamit ang camera"
            seenAgoSec < 3 -> "may nakikita kang tao sa camera ngayon"
            seenAgoSec < 600 -> "walang nakikitang tao sa camera ngayon (huling nakita ${seenAgoSec} segundo na ang nakalipas)"
            else -> "matagal nang walang nakikitang tao sa camera"
        }
        if (navActive) parts += "naka-AUTO ka (gumagala mag-isa)"
        return GeminiBrain.Situation(currentRecognizedName, commandStore.all(), parts.joinToString("; "))
    }

    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)

    private val dfPlayerPlayRegex = Regex("dfplayer\\s*play\\s*(\\d+)", RegexOption.IGNORE_CASE)

    // ---------- CAMERA NAV (depth-based obstacle avoidance habang MOVING ang ESP32) ----------

    @Volatile private var obstacleAnalyzer: ObstacleAnalyzer? = null
    @Volatile private var depthModelBusy = false
    // Naka-set sa pinakaunang linya ng onDestroy - para huminto ang mga background thread
    // (model load, mic restart, atbp.). (Hindi "isDestroyed": nagko-clash iyon sa Activity.isDestroyed().)
    @Volatile private var shuttingDown = false
    private val depthModelFileName = "midas_small.tflite"
    private val depthModelUrl = "https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite"
    private val depthModelMinBytes = 50_000_000L

    @Volatile private var navActive = false          // naka-nav mode ba ngayon (MOVING ang ESP32)
    @Volatile private var navTestMode = false        // live scores lang, walang utos sa robot
    @Volatile private var navTestUntil = 0L
    @Volatile private var navCalibrating = false
    private var navCalibEndTime = 0L
    private val navCalibSamples = mutableListOf<Float>()

    private var lastNavAnalysisTime = 0L
    private val navAnalysisIntervalMs = 180L         // pinakamabilis na pag-analyze ng frame
    private val navSendIntervalMs = 100L             // tick ng pagpapadala ng hint (maliit para tumpak ang keep-alive)
    @Volatile private var navServoSettleUntil = 0L   // hintayin munang tumigil ang servo bago mag-analyze
    @Volatile private var navPose = 0                // 0 = tilt A, 1 = tilt B
    private var lastNavPoseSwitch = 0L
    private val navPoseSwitchMs = 1400L
    private var navNonMovingPolls = 0
    private var pingFailCount = 0

    // Auto-hanap ng IP ng ESP32 (Esp32Finder.kt) kapag nawala ang koneksyon at nagbago na marahil ang IP nito.
    private var esp32AutoFind: Boolean
        get() = prefs.getBoolean("esp32_autofind", true)
        set(value) { prefs.edit().putBoolean("esp32_autofind", value).apply() }
    private var autoFindBusy = false
    private var autoFindLastMs = 0L
    private var autoFindBackoffMs = 60_000L // dumodoble (hanggang 10 minuto) kung walang makita - para hindi paulit-ulit
    private var lastNavServoAngle = 0

    private var navLastDecision = ObstacleAnalyzer.Decision.CLEAR
    private var navLastDecisionTime = 0L

    // Isang hint / isang ping lang ang sabay na nasa ere - iwas-pile-up at iwas-lumang hints
    @Volatile private var navHintInFlight = false
    @Volatile private var pingInFlight = false
    private var lastNavHintName = ""
    private var lastNavHintSentTime = 0L
    private val navKeepAliveMs = 300L    // ESP32 hint timeout = 700ms; 300ms keep-alive = 400ms margin (tick ay 100ms kaya eksakto)

    private val navHandler = Handler(android.os.Looper.getMainLooper())
    private val navSendRunnable = object : Runnable {
        override fun run() {
            if (!navActive) return
            if (!navTestMode) {
                val age = System.currentTimeMillis() - navLastDecisionTime
                // Kapag luma na ang huling desisyon (natigil ang analysis), HUWAG magpadala ng hint.
                // Hindi puwedeng "CLEAR" ito (ibig sabihin tuloy-tuloy ang takbo nang bulag) - hayaan
                // ang ESP32 na mag-timeout ng hint (700ms) at gawin ang sarili niyang safe behavior.
                if (age <= 1200L) sendNavHint(navLastDecision)
            }
            navHandler.postDelayed(this, navSendIntervalMs)
        }
    }

    private var navEnabled: Boolean
        get() = prefs.getBoolean("nav_enabled", true)
        set(value) { prefs.edit().putBoolean("nav_enabled", value).apply() }

    private var navScanEnabled: Boolean
        get() = prefs.getBoolean("nav_scan_enabled", false)
        set(value) { prefs.edit().putBoolean("nav_scan_enabled", value).apply() }

    private var navInvert: Boolean
        get() = prefs.getBoolean("nav_invert", false)
        set(value) { prefs.edit().putBoolean("nav_invert", value).apply() }

    private var navServoA: Int
        get() = prefs.getInt("nav_servo_a", 30)
        set(value) { prefs.edit().putInt("nav_servo_a", value.coerceIn(0, 110)).apply() }

    private var navServoB: Int
        get() = prefs.getInt("nav_servo_b", 55)
        set(value) { prefs.edit().putInt("nav_servo_b", value.coerceIn(0, 110)).apply() }

    private var navBlockThreshold: Float
        get() = prefs.getFloat("nav_block_threshold", 0.75f)
        set(value) { prefs.edit().putFloat("nav_block_threshold", value.coerceIn(0.50f, 0.95f)).apply() }

    // Banda ng larawan (% ng taas) na sinusuri para sa harang. Ibaba ang dalawa kung hindi makatingin
    // pababa ang camera (hal. 45 at 90), para ang sahig ang masuri at hindi ang kisame/malayong pader.
    private var navRowTopPercent: Int
        get() = prefs.getInt("nav_row_top", 30)
        set(value) { prefs.edit().putInt("nav_row_top", value.coerceIn(0, 80)).apply() }

    private var navRowBottomPercent: Int
        get() = prefs.getInt("nav_row_bottom", 75)
        set(value) { prefs.edit().putInt("nav_row_bottom", value.coerceIn(20, 100)).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeminiBrain.language = appLanguage

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        forceWifiForEsp32()

        cameraExecutor = Executors.newSingleThreadExecutor()
        yoloDetector = YoloPersonDetector(this)
        faceEmbedder = FaceEmbedder(this)
        faceStore = FaceStore(this)
        commandStore = CommandStore(this)
        smartHome = SmartHome(this)
        commandStore.seedDefaultsIfNeeded()

        buildUi()
        applyImmersive(window)
        showEyesUi()
        startEspHeartbeat()
        setupDepthModel()
        headScanHandler.post(headScanWatcherRunnable)

        initTts(preferredTtsEngine)

        val missingPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (missingPermissions.isEmpty()) {
            startCamera()
            startMicEngine()
        } else {
            statusText.text = tr("Naghahanap ng tao... (hinihintay permissions...)")
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        }
    }

    private val pingHandler = Handler(android.os.Looper.getMainLooper())
    private val pingRunnable = object : Runnable {
        override fun run() {
            pingEsp32()
            pingHandler.postDelayed(this, 1000)
        }
    }

    /**
     * Pinag-uuri ang isang action field ("||"-separated) sa DFPlayer track command(s) at
     * sa iba pang ESP32 command(s), tapos ipinapadala nang maayos ang pagkakasunod-sunod:
     * hinihintay muna ang aktwal na resulta (tagumpay man o pagkabigo) ng DFPlayer request
     * bago ipadala ang movement/ibang commands, sa halip na basta mag-antay ng fixed delay.
     * Mas maaasahan ito kapag "busy" pa ang ESP32 sa pag-uusap sa DFPlayer module.
     */
    private fun executeEsp32Actions(actionField: String) {
        val parts = actionField.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        // Kapag ang TTS ng phone ang boses (naka-ON ang TTS switch), PATAY ang DFPlayer output ng
        // utos - para hindi sabay magsalita ang phone at ang robot. Tuloy pa rin ang movement/HOME.
        val ttsIsVoice = appTtsEnabled
        val dfTracks = if (ttsIsVoice) emptyList()
            else parts.mapNotNull { dfPlayerPlayRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val otherParts = parts
            .filterNot { dfPlayerPlayRegex.containsMatchIn(it) }
            .filterNot { ttsIsVoice && isDfPlayerRawCommand(it) }

        // Ang smart home (HOME:) ay HINDI naghihintay sa DFPlayer - tumatakbo agad, para hindi maantala
        // kapag mabagal o hindi sumasagot ang ESP32.
        val homeParts = otherParts.filter { SmartHome.isHomeAction(it) }
        val restParts = otherParts.filterNot { SmartHome.isHomeAction(it) }
        if (homeParts.isNotEmpty()) runMovementParts(homeParts)

        if (dfTracks.isEmpty()) {
            runMovementParts(restParts)
            return
        }

        // Hintayin munang matapos (onResponse) o mag-fail (onFailure) ang UNANG
        // DFPlayer track bago ipadala ang movement/ibang commands - kaysa manghula
        // tayo ng magic delay number na baka minsan hindi sapat.
        sendPlayTrackThen(dfTracks.first()) {
            runMovementParts(restParts)
        }
        // Kung sakaling may isa pang "dfplayer play N" sa parehong combo (bihira),
        // ipadala na lang agad ang mga sumunod nang walang hintayan.
        dfTracks.drop(1).forEach { sendPlayTrack(it) }
    }

    /** Raw na DFPlayer command ng ESP32, hal. PLAYSONG&TRACK=50&ADVERT=65 (hindi ang HOME: o movement). */
    private fun isDfPlayerRawCommand(part: String): Boolean {
        val u = part.trim().uppercase()
        if (u.startsWith("HOME:")) return false
        return u.startsWith("PLAYSONG") || u.contains("ADVERT=") || u.contains("TRACK=")
    }

    private fun unknownGreetingTrackList(): List<Int> =
        unknownGreetingTracksRaw.split(",").mapNotNull { it.trim().toIntOrNull() }

    private fun runMovementParts(otherParts: List<String>) {
        for (part in otherParts) {
            val partUpper = part.uppercase()
            if (SmartHome.isHomeAction(part)) {
                // RM mini 3 / Lasco power strip - hiwalay na daan, hindi dumadaan sa ESP32
                runSmartHomeAction(part)
                continue
            }
            if (partUpper in movementActions) {
                sendTimedCommand(partUpper, voiceMovementDurationMs)
            } else {
                sendCommandToEsp32(part)
            }
        }
    }

    /**
     * Tatanggapin ang HOME action mula kay Gemini LAMANG kung ito ay eksaktong action (buo, o isang bahagi ng "||" combo)
     * na ginawa mismo ni idol sa Mga Utos. Hindi puwedeng mag-imbento si Gemini ng sarili.
     */
    private fun isOwnerDefinedHomeAction(action: String): Boolean {
        val a = action.trim()
        if (!a.uppercase().contains("HOME:")) return false
        fun norm(s: String) = s.split("||").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.joinToString("||")
        val na = norm(a)
        return commandStore.all().any { c ->
            norm(c.action) == na || c.action.split("||").any { it.trim().equals(a, ignoreCase = true) }
        }
    }

    private fun runSmartHomeAction(action: String) {
        smartHome.execute(action.trim()) { ok, msg ->
            runOnUi {
                statusText.text = (if (ok) "🏠 " else "⚠️ ") + msg
                addVoiceLogEntry("smart home: ${action.trim()}", msg)
            }
        }
    }

    /**
     * Para sa Smart Home editor: subukan ang DFPlayer track. HINDI ito naka-mute kahit naka-ON ang Gemini,
     * para makumpirma kung gumagana ang ESP32 voice. Ang callback ay tinatawag sa UI thread.
     */
    private fun testDfPlayerTrack(track: Int, done: (Boolean, String) -> Unit) {
        val request = Request.Builder()
            .url("$esp32BaseUrl/command?dir=PLAY&track=$track")
            .build()
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUi { done(false, "DFPlayer track $track: ${e.message ?: "walang koneksyon sa ESP32"}") }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val ok = response.isSuccessful
                val code = response.code
                response.close()
                runOnUi { done(ok, if (ok) "DFPlayer track $track naipadala sa ESP32" else "DFPlayer track $track: HTTP $code") }
            }
        })
    }

    private fun sendPlayTrack(track: Int) {
        // Kapag naka-ON ang Gemini AI mode, app TTS na lang ang tanging boses - hindi na
        // pinapatugtog ang DFPlayer/ESP32 voice dito para hindi magsabay/magkagulo ang dalawang
        // audio habang nakikipag-usap kay Gemini.
        if (geminiEnabled) return

        val request = Request.Builder()
            .url("$esp32BaseUrl/command?dir=PLAY&track=$track")
            .build()
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUi { addVoiceLogEntry("DFPlayer track $track", "HTTP FAILED: ${e.message}") }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    runOnUi { addVoiceLogEntry("DFPlayer track $track", "HTTP ${response.code}") }
                }
                response.close()
            }
        })
    }

    /**
     * Kagaya ng sendPlayTrack() pero may onDone callback na tatawagin PAGKATAPOS ng
     * aktwal na resulta ng request (tagumpay man o pagkabigo) - dito isasabay ang
     * pagpapadala ng susunod na command sa combo, imbes na basta mag-antay ng fixed
     * delay na baka hindi sapat kung "busy" pa ang ESP32 (hal. nagsusulat pa sa
     * DFPlayer serial).
     */
    private fun sendPlayTrackThen(track: Int, onDone: () -> Unit) {
        // Pareho ng sendPlayTrack(): naka-mute ang DFPlayer voice habang naka-ON ang Gemini AI
        // mode. Tuloy pa rin agad ang onDone() para gumana pa rin ang kasunod na movement sa
        // isang "||"-combo action kahit naka-skip ang bahaging dfplayer.
        if (geminiEnabled) {
            onDone()
            return
        }

        val request = Request.Builder()
            .url("$esp32BaseUrl/command?dir=PLAY&track=$track")
            .build()
        dfThenClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUi {
                    addVoiceLogEntry("DFPlayer track $track", "HTTP FAILED: ${e.message}")
                    onDone()
                }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    runOnUi { addVoiceLogEntry("DFPlayer track $track", "HTTP ${response.code}") }
                }
                response.close()
                runOnUi { onDone() }
            }
        })
    }

    private fun forceWifiForEsp32() {
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivityManager.bindProcessToNetwork(network)
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------- UI setup ----------

    private fun buildUi() {
        rootLayout = FrameLayout(this)
        previewView = PreviewView(this)
        roboEyesView = RoboEyesView(this)
        capturedPhotoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }

        val accentColor = 0xFF00E5C7.toInt()
        val darkChip = 0xFF1E1E2E.toInt()
        val darkChipPressed = 0xFF2A2A3E.toInt()

        // ---------- Status pill: glass look, may kulay na dot ayon sa uri ng mensahe, at animation kada palit ----------
        val statusPillBg = GradientDrawable().apply {
            setColor(0xD90B0D13.toInt())
            cornerRadius = dpPx(22).toFloat()
            setStroke(dpPx(1), 0x6600E5C7)
        }
        val statusDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accentColor)
            setSize(dpPx(8), dpPx(8))
        }
        var lastStatusAnimMs = 0L

        statusText = TextView(this).apply {
            setTextColor(0xFFF1F3F9.toInt())
            textSize = 12.5f
            letterSpacing = 0.02f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dpPx(14), dpPx(9), dpPx(18), dpPx(9))
            compoundDrawablePadding = dpPx(10)
            setCompoundDrawablesWithIntrinsicBounds(statusDot, null, null, null)
            background = statusPillBg
            elevation = dpPx(6).toFloat()
            visibility = View.GONE // lalabas lang kapag may laman

            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val t = s?.toString()?.trim().orEmpty()
                    if (t.isEmpty()) {
                        statusText.visibility = View.GONE
                        return
                    }
                    // Kulay ng dot/outline: pula = error, amber = naglo-load, asul = mic, lila = AI, cyan = normal
                    val tone = when {
                        t.startsWith("❌") || t.startsWith("⚠️") || t.contains("error", ignoreCase = true) -> 0xFFFF5C5C.toInt()
                        t.startsWith("⬇️") || t.startsWith("📦") || t.startsWith("🧪") || t.startsWith("🎯") -> 0xFFFFB84D.toInt()
                        t.startsWith("[MIC]") || t.startsWith("🎤") -> 0xFF4DA3FF.toInt()
                        t.startsWith("🧠") || t.startsWith("🤖") || t.startsWith("👁") -> 0xFFB388FF.toInt()
                        else -> accentColor
                    }
                    statusDot.setColor(tone)
                    statusPillBg.setStroke(dpPx(1), (tone and 0x00FFFFFF) or 0x66000000)

                    if (statusText.visibility != View.VISIBLE) statusText.visibility = View.VISIBLE

                    // Maliit na "pop-in" animation (hindi kada-percent para hindi kumukurap habang nagda-download)
                    val now = System.currentTimeMillis()
                    if (now - lastStatusAnimMs > 700L) {
                        lastStatusAnimMs = now
                        statusText.alpha = 0.35f
                        statusText.translationY = -dpPx(6).toFloat()
                        statusText.animate().alpha(1f).translationY(0f).setDuration(200).start()
                    }
                }
            })
        }

        menuButton = Button(this).apply {
            text = "☰"
            textSize = 20f
            setTextColor(accentColor)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            stateListAnimator = null
            elevation = 10f
            background = RippleDrawable(
                ColorStateList.valueOf(0x40FFFFFF),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC12141C.toInt())
                    setStroke(dpPx(2), accentColor)
                },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            )
            setOnClickListener { showMainMenuDialog() }
            pressScale()
        }

        rootLayout.addView(
            previewView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        // Nasa ibabaw ng previewView (opaque na itim) - HUWAG gawing GONE ang previewView,
        // dahil titigil ang camera frames kapag nawala ang surface niya.
        rootLayout.addView(
            roboEyesView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        // Nasa ibabaw ng lahat (roboEyesView/previewView) - dito ipinapakita ang nakuhang
        // litrato pagkatapos mag-"take a picture". GONE by default.
        rootLayout.addView(
            capturedPhotoView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        rootLayout.addView(
            statusText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dpPx(14) }
        )
        rootLayout.addView(
            menuButton,
            FrameLayout.LayoutParams(dpPx(54), dpPx(54))
                .apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = dpPx(16); rightMargin = dpPx(16) }
        )

        setContentView(rootLayout)
        applyDisplayMode()
    }

    private fun applyDisplayMode() {
        roboEyesView.visibility = if (showRoboEyes) View.VISIBLE else View.GONE
    }

    private fun makeRippleRoundedDrawable(baseColor: Int, pressedColor: Int, radius: Float): Drawable {
        val shape = GradientDrawable().apply {
            setColor(baseColor)
            cornerRadius = radius
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, mask)
    }

    // ---------- Wika / Language ("fil" = Filipino, "en" = English) ----------
    @Volatile private var appLanguageCache: String? = null

    private var appLanguage: String
        get() = appLanguageCache ?: (prefs.getString("app_language", "fil") ?: "fil").also { appLanguageCache = it }
        set(value) {
            appLanguageCache = value
            prefs.edit().putString("app_language", value).apply()
            GeminiBrain.language = value
        }

    private val isEnglish: Boolean get() = appLanguage == "en"

    /**
     * Isinasalin ang Filipino na teksto sa English kapag English ang napiling wika (Translations.kt).
     * Ang {0}, {1}... ay napapalitan ng mga args. Kapag walang salin, ang orihinal na teksto ang lalabas.
     */
    private fun tr(fil: String, vararg args: Any?): String {
        var s = if (isEnglish) (Translations.en[fil] ?: fil) else fil
        for (i in args.indices) s = s.replace("{$i}", args[i].toString())
        return s
    }

    /** Itinatakda ang wika ng lokal na TTS ayon sa wika ng app. Ibinabalik kung okay ang boses (Filipino o English mode). */
    private fun applyTtsLanguage(): Boolean {
        val engine = tts ?: return true
        fun isOk(r: Int) = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        var langOk = true
        if (isEnglish) {
            engine.setLanguage(Locale.US)
        } else if (!isOk(engine.setLanguage(Locale("fil", "PH")))) {
            if (!isOk(engine.setLanguage(Locale("tl", "PH")))) {
                engine.setLanguage(Locale.US)
                langOk = false
            }
        }
        // Sariling napiling boses (kung meron). Sa English mode, English na boses lang ang gagamitin.
        if (selectedTtsVoiceName.isNotBlank()) {
            val match = try { engine.voices?.firstOrNull { it.name == selectedTtsVoiceName } } catch (e: Exception) { null }
            if (match != null && (!isEnglish || match.locale.language == "en")) engine.voice = match
        }
        return langOk
    }

    // ---------- Modern UI helpers (fullscreen + styling) ----------

    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** Rounded na background na may ripple at optional na stroke. */
    private fun modernBg(fill: Int, strokeColor: Int, radiusDp: Int, rippleColor: Int = 0x30FFFFFF): Drawable {
        val r = dpPx(radiusDp).toFloat()
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = r
            if (strokeColor != 0) setStroke(dpPx(1), strokeColor)
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = r
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask)
    }

    /** Konting "pindot" animation (lumiliit nang kaunti kapag hinawakan). Hindi nito hinaharang ang click. */
    private fun View.pressScale() {
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    /**
     * FULLSCREEN / IMMERSIVE: tinatanggal ang puting status bar sa taas (oras, wifi, notifications)
     * at ang navigation bar. Kapag nag-swipe ang user mula sa gilid, lalabas lang sandali ang bars.
     * Ginagamit din ito sa mga dialog para hindi bumalik ang bars kapag may nag-pop up.
     */
    private fun applyImmersive(w: android.view.Window?) {
        if (w == null) return
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT

        // Kung may notch / camera cutout, gamitin pa rin ang buong screen
        val lp = w.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        w.attributes = lp

        val controller = WindowInsetsControllerCompat(w, w.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Ibalik ang fullscreen pagkatapos ng dialog / keyboard / pag-balik mula sa ibang app
        if (hasFocus) applyImmersive(window)
    }

    // ---------- Modern dialog (kapalit ng AlertDialog.Builder para sa lahat ng sub-dialog) ----------

    // Isa lang ang bukas na sub-dialog sa isang oras: kapag may bagong binuksan, isasara ang luma
    // (para hindi nagpapatong-patong, hal. Mga Utos -> I-edit -> balik sa Mga Utos).
    private var activeModernDialog: android.app.Dialog? = null

    private fun setMarginsDp(v: View, leftDp: Int, topDp: Int, rightDp: Int, bottomDp: Int) {
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        lp.setMargins(dpPx(leftDp), dpPx(topDp), dpPx(rightDp), dpPx(bottomDp))
        v.layoutParams = lp
    }

    /**
     * Nire-restyle ang laman ng dialog (mga TextView, EditText, Switch, Button, divider, rows)
     * para umayon sa dark/modern na tema - hindi na kailangang baguhin ang bawat dialog nang isa-isa.
     */
    private fun restyleDialogContent(v: View) {
        if (v.tag == "keepstyle") return // sariling styling (hal. expression picker)
        val accent = 0xFF00E5C7.toInt()
        val onAccent = 0xFF04342C.toInt()
        val cardBg = 0xFF171A23.toInt()
        val cardStroke = 0x14FFFFFF
        val textMain = 0xFFE9EBF2.toInt()
        val textDim = 0xFF8B90A3.toInt()

        when (v) {
            is Switch -> {
                v.setTextColor(textMain)
                v.textSize = 14f
                v.setPadding(dpPx(14), dpPx(12), dpPx(14), dpPx(12))
                v.background = modernBg(cardBg, cardStroke, 14)
                v.thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, 0xFF9AA0B4.toInt())
                )
                v.trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0xFF00A896.toInt(), 0xFF3A3F52.toInt())
                )
                setMarginsDp(v, 0, 4, 0, 4)
            }
            is EditText -> {
                v.setTextColor(0xFFFFFFFF.toInt())
                v.setHintTextColor(0xFF6B7084.toInt())
                v.textSize = 14f
                v.setPadding(dpPx(14), dpPx(11), dpPx(14), dpPx(11))
                v.background = GradientDrawable().apply {
                    setColor(0xFF0E1016.toInt())
                    cornerRadius = dpPx(12).toFloat()
                    setStroke(dpPx(1), 0x26FFFFFF)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.textCursorDrawable = GradientDrawable().apply {
                        setColor(accent)
                        setSize(dpPx(2), -1)
                    }
                }
                setMarginsDp(v, 0, 4, 0, 6)
            }
            is Button -> {
                val t = v.text.toString()
                v.isAllCaps = false
                v.minWidth = 0
                v.minHeight = 0
                v.stateListAnimator = null
                v.typeface = Typeface.DEFAULT_BOLD
                v.textSize = 12f
                v.setPadding(dpPx(12), dpPx(8), dpPx(12), dpPx(8))
                when {
                    t.contains("Tanggalin") || t.contains("Delete") -> {
                        v.setTextColor(0xFFFF7B7B.toInt())
                        v.background = modernBg(0x26FF5C5C, 0, 12)
                    }
                    t.contains("Calibrate") -> {
                        v.textSize = 13f
                        v.setTextColor(accent)
                        v.background = modernBg(0x1A00E5C7, accent, 14)
                        v.setPadding(dpPx(14), dpPx(12), dpPx(14), dpPx(12))
                        setMarginsDp(v, 0, 12, 0, 4)
                    }
                    t.contains("Piliin") || t.contains("Choose") -> {
                        v.setTextColor(onAccent)
                        v.background = modernBg(accent, 0, 12)
                    }
                    else -> {
                        v.setTextColor(0xFFFFFFFF.toInt())
                        v.background = modernBg(0xFF262A3B.toInt(), 0, 12)
                    }
                }
                if (v.parent is LinearLayout && (v.parent as LinearLayout).orientation == LinearLayout.HORIZONTAL) {
                    setMarginsDp(v, 6, 0, 0, 0)
                }
            }
            is TextView -> {
                val sp = v.textSize / resources.configuration.fontScale / resources.displayMetrics.density
                val t = v.text.toString().trimEnd()
                when {
                    sp <= 11.5f -> v.setTextColor(textDim)
                    t.endsWith(":") -> {
                        v.setTextColor(0xFFC4C9DB.toInt())
                        v.typeface = Typeface.DEFAULT_BOLD
                    }
                    else -> v.setTextColor(textMain)
                }
            }
            else -> {
                // Divider (plain View na 2px ang taas)
                val lp = v.layoutParams
                if (v.javaClass == View::class.java && lp != null && lp.height in 1..3) {
                    v.setBackgroundColor(0x14FFFFFF)
                    lp.height = dpPx(1)
                    v.layoutParams = lp
                    setMarginsDp(v, 0, 12, 0, 12)
                }
            }
        }

        // Mga row na may button (hal. "pangalan -> tracks  [Tanggalin]") = card
        if (v is LinearLayout && v.orientation == LinearLayout.HORIZONTAL) {
            var hasButton = false
            for (i in 0 until v.childCount) if (v.getChildAt(i) is Button) hasButton = true
            if (hasButton) {
                v.setPadding(dpPx(14), dpPx(8), dpPx(8), dpPx(8))
                v.background = GradientDrawable().apply {
                    setColor(cardBg)
                    cornerRadius = dpPx(14).toFloat()
                    setStroke(dpPx(1), cardStroke)
                }
                setMarginsDp(v, 0, 0, 0, 8)
            }
        }

        if (v is ViewGroup) {
            for (i in 0 until v.childCount) restyleDialogContent(v.getChildAt(i))
        }
    }

    /**
     * Kapalit ng android.app.AlertDialog.Builder - pareho ang mga method (setTitle, setMessage, setView,
     * set*Button, showImmersive) kaya hindi na kailangang baguhin ang logic ng bawat dialog.
     */
    private inner class ModernDialog {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var contentView: View? = null
        private var posText: CharSequence? = null
        private var posListener: ((android.content.DialogInterface, Int) -> Unit)? = null
        private var negText: CharSequence? = null
        private var negListener: ((android.content.DialogInterface, Int) -> Unit)? = null
        private var neuText: CharSequence? = null
        private var neuListener: ((android.content.DialogInterface, Int) -> Unit)? = null

        fun setTitle(t: CharSequence): ModernDialog { title = t; return this }
        fun setMessage(m: CharSequence): ModernDialog { message = m; return this }
        fun setView(v: View): ModernDialog { contentView = v; return this }
        fun setPositiveButton(t: CharSequence, l: ((android.content.DialogInterface, Int) -> Unit)?): ModernDialog {
            posText = t; posListener = l; return this
        }
        fun setNegativeButton(t: CharSequence, l: ((android.content.DialogInterface, Int) -> Unit)?): ModernDialog {
            negText = t; negListener = l; return this
        }
        fun setNeutralButton(t: CharSequence, l: ((android.content.DialogInterface, Int) -> Unit)?): ModernDialog {
            neuText = t; neuListener = l; return this
        }

        fun showImmersive(): android.app.Dialog {
            val ctx = this@MainActivity
            val accent = 0xFF00E5C7.toInt()
            val accent2 = 0xFF00B4FF.toInt()
            val onAccent = 0xFF04342C.toInt()
            val textDim = 0xFF8B90A3.toInt()

            activeModernDialog?.let { if (it.isShowing) it.dismiss() }

            val dialog = android.app.Dialog(ctx)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

            val panel = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFF151926.toInt(), 0xFF0B0D13.toInt())
                ).apply {
                    cornerRadius = dpPx(24).toFloat()
                    setStroke(dpPx(1), 0x1FFFFFFF)
                }
                elevation = dpPx(16).toFloat()
                isClickable = true // para hindi magsara kapag pinindot ang loob
                clipToOutline = true
            }

            // ----- Title + message -----
            if (title != null || message != null) {
                val header = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpPx(22), dpPx(18), dpPx(22), dpPx(6))
                }
                title?.let { t ->
                    header.addView(TextView(ctx).apply {
                        text = t
                        textSize = 18f
                        setTextColor(0xFFFFFFFF.toInt())
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    // maliit na accent line sa ilalim ng title
                    header.addView(View(ctx).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(accent, accent2)
                        ).apply { cornerRadius = dpPx(2).toFloat() }
                        layoutParams = LinearLayout.LayoutParams(dpPx(36), dpPx(3))
                            .apply { topMargin = dpPx(8) }
                    })
                }
                message?.let { m ->
                    header.addView(TextView(ctx).apply {
                        text = m
                        textSize = 13f
                        setTextColor(textDim)
                        setPadding(0, dpPx(10), 0, dpPx(4))
                    })
                }
                panel.addView(header)
            }

            // ----- Content (scrollable) -----
            val cv = contentView
            if (cv != null) {
                val scroll: ScrollView
                if (cv is ScrollView) {
                    scroll = cv
                    val inner = cv.getChildAt(0)
                    inner?.setPadding(dpPx(22), dpPx(8), dpPx(22), dpPx(12))
                } else {
                    val wrapper = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dpPx(22), dpPx(8), dpPx(22), dpPx(12))
                        addView(cv)
                    }
                    scroll = ScrollView(ctx).apply { addView(wrapper) }
                }
                scroll.isVerticalScrollBarEnabled = false
                scroll.overScrollMode = View.OVER_SCROLL_NEVER
                restyleDialogContent(cv)
                panel.addView(scroll, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ))
            }

            // ----- Footer buttons -----
            fun footerButton(text: CharSequence, kind: Int, onTap: (() -> Unit)): TextView {
                // kind: 0 = neutral, 1 = negative (ghost), 2 = positive (accent)
                return TextView(ctx).apply {
                    this.text = text
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dpPx(18), dpPx(10), dpPx(18), dpPx(10))
                    when (kind) {
                        2 -> {
                            setTextColor(onAccent)
                            background = RippleDrawable(
                                ColorStateList.valueOf(0x40FFFFFF),
                                GradientDrawable(
                                    GradientDrawable.Orientation.LEFT_RIGHT,
                                    intArrayOf(accent, accent2)
                                ).apply { cornerRadius = dpPx(14).toFloat() },
                                GradientDrawable().apply {
                                    setColor(Color.WHITE)
                                    cornerRadius = dpPx(14).toFloat()
                                }
                            )
                        }
                        1 -> {
                            setTextColor(0xFFE9EBF2.toInt())
                            background = modernBg(0xFF1E212C.toInt(), 0x1FFFFFFF, 14)
                        }
                        else -> {
                            setTextColor(textDim)
                            background = modernBg(0x00000000, 0, 14)
                        }
                    }
                    setOnClickListener { onTap() }
                    pressScale()
                }
            }

            if (posText != null || negText != null || neuText != null) {
                val footer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpPx(16), dpPx(8), dpPx(16), dpPx(14))
                }
                neuText?.let { t ->
                    footer.addView(footerButton(t, 0) {
                        dialog.dismiss()
                        neuListener?.invoke(dialog, android.content.DialogInterface.BUTTON_NEUTRAL)
                    })
                }
                footer.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                negText?.let { t ->
                    footer.addView(footerButton(t, 1) {
                        dialog.dismiss()
                        negListener?.invoke(dialog, android.content.DialogInterface.BUTTON_NEGATIVE)
                    })
                }
                posText?.let { t ->
                    footer.addView(
                        footerButton(t, 2) {
                            dialog.dismiss()
                            posListener?.invoke(dialog, android.content.DialogInterface.BUTTON_POSITIVE)
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { leftMargin = dpPx(8) }
                    )
                }
                panel.addView(footer)
            }

            // ----- Root (scrim) -----
            val basePad = dpPx(16)
            val screenW = resources.displayMetrics.widthPixels
            val panelW = minOf((screenW * 0.92f).toInt(), dpPx(620))

            val root = FrameLayout(ctx).apply {
                setBackgroundColor(0xB3000000.toInt())
                setPadding(basePad, basePad, basePad, basePad)
                setOnClickListener { dialog.dismiss() }
                addView(
                    panel,
                    FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT)
                        .apply { gravity = Gravity.CENTER }
                )
            }

            // Keyboard: iangat ang panel para hindi matakpan ang mga input
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    v.setPadding(basePad, basePad, basePad, basePad + ime)
                    insets
                }
            } else {
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }

            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setDimAmount(0f)
                setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
            }
            dialog.setOnDismissListener {
                if (activeModernDialog === dialog) activeModernDialog = null
            }
            activeModernDialog = dialog
            dialog.show()
            applyImmersive(dialog.window)
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            ViewCompat.requestApplyInsets(root)

            // ----- Animation -----
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(150).start()
            panel.scaleX = 0.94f
            panel.scaleY = 0.94f
            panel.animate().scaleX(1f).scaleY(1f).setDuration(210)
                .setInterpolator(DecelerateInterpolator()).start()

            return dialog
        }
    }

    private fun showMainMenuDialog() {
        val ctx = this
        val accent = 0xFF00E5C7.toInt()
        val accent2 = 0xFF00B4FF.toInt()
        val cardBg = 0xFF171A23.toInt()
        val cardStroke = 0x14FFFFFF
        val textMain = 0xFFFFFFFF.toInt()
        val textDim = 0xFF8B90A3.toInt()
        val onAccent = 0xFF04342C.toInt()

        // true = magsasara ang menu kapag may pinili (laging fresh ang IP / mic % kapag binuksan ulit)
        val closeMenuOnPick = false

        val dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        fun pick(action: () -> Unit) {
            if (closeMenuOnPick) dialog.dismiss()
            action()
        }

        fun label(text: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        fun gap(hDp: Int): View = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(1, dpPx(hDp))
        }

        // Mga view na may entrance animation (staggered)
        val animViews = mutableListOf<View>()

        // ---------- Header ----------
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(label("Rustech", 20f, accent, bold = true).apply { letterSpacing = 0.14f })
        titleCol.addView(label("Control Center  •  FaceRobot", 12f, textDim))
        header.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val ipPill = label("●  ${esp32BaseUrl.removePrefix("http://")}", 12f, accent, bold = true).apply {
            setPadding(dpPx(12), dpPx(7), dpPx(12), dpPx(7))
            background = GradientDrawable().apply {
                setColor(0x1A00E5C7)
                cornerRadius = dpPx(20).toFloat()
            }
        }
        header.addView(ipPill)

        // Wika / Language: Filipino o English (nagre-refresh agad ang menu)
        val langWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpPx(3), dpPx(3), dpPx(3), dpPx(3))
            background = GradientDrawable().apply {
                setColor(0xFF0E1016.toInt())
                cornerRadius = dpPx(16).toFloat()
                setStroke(dpPx(1), cardStroke)
            }
        }
        fun langChip(text: String, code: String): TextView {
            val on = appLanguage == code
            return label(text, 11f, if (on) onAccent else textDim, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dpPx(10), dpPx(6), dpPx(10), dpPx(6))
                background = GradientDrawable().apply {
                    cornerRadius = dpPx(13).toFloat()
                    setColor(if (on) accent else Color.TRANSPARENT)
                }
                setOnClickListener {
                    if (appLanguage != code) {
                        appLanguage = code
                        applyTtsLanguage()
                        statusText.text = if (code == "en") "🌐 Language: English" else "🌐 Wika: Filipino"
                        dialog.dismiss()
                        showMainMenuDialog()
                    }
                }
            }
        }
        langWrap.addView(langChip("🇵🇭 FIL", "fil"))
        langWrap.addView(langChip("🇺🇸 EN", "en"))
        header.addView(
            langWrap,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dpPx(10) }
        )

        val closeBtn = label("✕", 15f, textMain, bold = true).apply {
            gravity = Gravity.CENTER
            background = modernBg(0xFF1E212C.toInt(), 0, 18)
            setOnClickListener { dialog.dismiss() }
            pressScale()
        }
        header.addView(
            closeBtn,
            LinearLayout.LayoutParams(dpPx(36), dpPx(36)).apply { leftMargin = dpPx(10) }
        )

        // ---------- Gemini (featured card) ----------
        val geminiShape = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, accent2)).apply {
            cornerRadius = dpPx(20).toFloat()
        }
        val geminiMask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpPx(20).toFloat()
        }
        val geminiCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPx(16), dpPx(14), dpPx(16), dpPx(14))
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), geminiShape, geminiMask)
            elevation = dpPx(4).toFloat()
            setOnClickListener { pick { showGeminiSettingsDialog() } }
            pressScale()
        }
        geminiCard.addView(label("🧠", 28f, onAccent))
        val geminiTexts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        geminiTexts.addView(label("Gemini AI", 16f, onAccent, bold = true))
        geminiTexts.addView(label(tr("Utak ni Rustech"), 12f, 0xCC04342C.toInt()))
        geminiCard.addView(
            geminiTexts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpPx(12) }
        )
        geminiCard.addView(label("›", 28f, onAccent, bold = true))

        // ---------- Display toggle (RoboEyes / Camera) ----------
        val displayCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(12), dpPx(10), dpPx(12), dpPx(10))
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dpPx(20).toFloat()
                setStroke(dpPx(1), cardStroke)
            }
        }
        displayCard.addView(label("DISPLAY", 10f, textDim, bold = true).apply { letterSpacing = 0.12f })
        displayCard.addView(gap(6))

        val segWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpPx(3), dpPx(3), dpPx(3), dpPx(3))
            background = GradientDrawable().apply {
                setColor(0xFF0E1016.toInt())
                cornerRadius = dpPx(14).toFloat()
            }
        }
        val segEyes = label("👀  RoboEyes", 13f, textDim, bold = true)
        val segCam = label("📷  Camera", 13f, textDim, bold = true)

        fun styleSegments() {
            fun style(tv: TextView, selected: Boolean) {
                tv.setTextColor(if (selected) onAccent else textDim)
                tv.background = GradientDrawable().apply {
                    cornerRadius = dpPx(11).toFloat()
                    setColor(if (selected) accent else Color.TRANSPARENT)
                }
            }
            style(segEyes, showRoboEyes)
            style(segCam, !showRoboEyes)
        }
        for (tv in listOf(segEyes, segCam)) {
            tv.gravity = Gravity.CENTER
            tv.setPadding(dpPx(8), dpPx(9), dpPx(8), dpPx(9))
            segWrap.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        segEyes.setOnClickListener { showRoboEyes = true; applyDisplayMode(); styleSegments() }
        segCam.setOnClickListener { showRoboEyes = false; applyDisplayMode(); styleSegments() }
        styleSegments()
        displayCard.addView(segWrap)

        val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(
            geminiCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        topRow.addView(
            displayCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dpPx(10) }
        )
        animViews.add(geminiCard)
        animViews.add(displayCard)

        // ---------- Settings cards (grid, 3 columns) ----------
        fun makeCard(icon: String, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit): View {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpPx(12), dpPx(12), dpPx(12), dpPx(12))
                background = modernBg(cardBg, cardStroke, 18)
                isEnabled = enabled
                isClickable = enabled
                alpha = if (enabled) 1f else 0.4f
            }
            val iconView = label(icon, 20f, textMain).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x2200E5C7)
                }
            }
            card.addView(iconView, LinearLayout.LayoutParams(dpPx(42), dpPx(42)))

            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(title, 14f, textMain, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(label(subtitle, 11f, textDim).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(
                texts,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpPx(10) }
            )

            if (enabled) {
                card.setOnClickListener { pick(onClick) }
                card.pressScale()
            }
            return card
        }

        val cards = listOf(
            makeCard("🗣️", tr("Boses"), tr("Lokal na TTS")) { showLocalVoiceDialog() },
            makeCard("📶", tr("IP ng Robot"), esp32BaseUrl.removePrefix("http://")) { showIpSettingDialog() },
            makeCard(
                "✨", tr("Mag-enroll ng mukha"),
                if (canEnroll) tr("Bagong mukha") else tr("Kailangan ng mukha"),
                enabled = canEnroll
            ) { showEnrollDialog() },
            makeCard("🎙️", tr("Mga Greeting"), tr("I-edit ang bati")) { showGreetingTracksDialog() },
            makeCard("📏", "Distance", tr("Layo ng tao")) { showDistanceSettingsDialog() },
            makeCard("🎤", "Mic Sensitivity", "${(micConfidenceThreshold * 100).toInt()}%") { showMicSensitivityDialog() },
            makeCard("🎙️", "Voice Engine", if (micEngineAndroid) "Android (Google)" else "Vosk (offline)") { showMicEngineDialog() },
            makeCard("🔔", "Wake Word", if (wakeEnabled) "\"Rustech\"" else tr("Naka-off")) { showWakeWordDialog() },
            makeCard("🔄", tr("Ikot (Voice)"), if (voiceInvertTurn) tr("Baligtad") else tr("Normal")) { showVoiceTurnDialog() },
            makeCard("💬", tr("Mga Utos"), "Voice commands") { showManageCommandsDialog() },
            makeCard("🏠", "Smart Home", "RM mini 3 + Lasco") { smartHomeUi.show() },
            makeCard("📦", "Export / Import", tr("Settings backup")) { settingsBackupUi.show() },
            makeCard("🧭", "Camera Nav", "Obstacle avoidance") { showNavSettingsDialog() },
            makeCard("🗒️", "Voice Log", tr("Kasaysayan")) { showVoiceLogDialog() }
        )

        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cards.chunked(3).forEachIndexed { rowIndex, rowCards ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowCards.forEachIndexed { i, c ->
                row.addView(
                    c,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (i > 0) leftMargin = dpPx(10) }
                )
                animViews.add(c)
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (rowIndex > 0) topMargin = dpPx(10) }
            )
        }

        // ---------- Buuin ang panel ----------
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(18), dpPx(20), dpPx(20))
        }
        content.addView(header)
        content.addView(gap(14))
        content.addView(topRow)
        content.addView(gap(12))
        content.addView(grid)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }

        val panel = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF151926.toInt(), 0xFF0B0D13.toInt())
            ).apply {
                cornerRadius = dpPx(28).toFloat()
                setStroke(dpPx(1), 0x1FFFFFFF)
            }
            elevation = dpPx(16).toFloat()
            isClickable = true // para hindi magsara kapag pinindot ang loob ng panel
            clipToOutline = true
            addView(scroll)
        }

        val screenW = resources.displayMetrics.widthPixels
        val panelW = minOf((screenW * 0.92f).toInt(), dpPx(900))

        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xB3000000.toInt())
            setOnClickListener { dialog.dismiss() }
            addView(
                panel,
                FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                    topMargin = dpPx(16)
                    bottomMargin = dpPx(16)
                }
            )
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0f)
            setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
        }
        dialog.show()
        applyImmersive(dialog.window)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        // ---------- Animations ----------
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(160).start()
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
        animViews.forEachIndexed { i, v ->
            val targetAlpha = if (v.isEnabled) 1f else 0.4f
            v.alpha = 0f
            v.translationY = dpPx(14).toFloat()
            v.animate()
                .alpha(targetAlpha)
                .translationY(0f)
                .setStartDelay(60L + i * 35L)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun showGreetingTracksDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        container.addView(TextView(this).apply {
            text = tr("Pindutin ang I-edit para baguhin ang bati, DFPlayer tracks, at expression ng mata ng bawat isa.")
            textSize = 11f
            setPadding(0, 0, 0, 16)
        })

        val builtins = listOf(greetUnknownKey, "aso", "pusa")
        val named = (greetMap("greeting_tracks").keys().asSequence().toList() +
            greetMap("greeting_lines").keys().asSequence().toList() +
            greetMap("greeting_expr").keys().asSequence().toList())
            .filter { it !in builtins }
            .distinct()

        fun subjectTitle(sub: String): String = when (sub) {
            greetUnknownKey -> tr("👤 Hindi kilalang tao")
            "aso" -> tr("🐶 Aso")
            "pusa" -> tr("🐱 Pusa")
            else -> "🙂 $sub"
        }

        fun subjectSummary(sub: String): String {
            val tracks = if (sub == greetUnknownKey) unknownGreetingTracksRaw else greetMapGet("greeting_tracks", sub)
            val parts = mutableListOf<String>()
            if (tracks.isNotBlank()) parts.add("Tracks $tracks")
            val custom = greetingLinesFor(sub)
            if (custom.isNotEmpty()) parts.add(tr("💬 {0} bati", custom.size))
            val expr = greetMapGet("greeting_expr", sub)
            if (expr.isNotEmpty()) parts.add(tr("mata: {0}", expressionLabel(expr)))
            return if (parts.isEmpty()) "default" else parts.joinToString(" • ")
        }

        for (subject in builtins + named) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply { text = subjectTitle(subject); textSize = 14f })
            texts.addView(TextView(this).apply { text = subjectSummary(subject); textSize = 11f })
            row.addView(texts)
            row.addView(Button(this).apply {
                text = tr("I-edit")
                textSize = 10f
                setOnClickListener { showEditGreetingDialog(subject) }
            })
            if (subject !in builtins) {
                row.addView(Button(this).apply {
                    text = tr("Tanggalin")
                    textSize = 10f
                    setOnClickListener {
                        greetMapSet("greeting_tracks", subject, "")
                        greetMapSet("greeting_lines", subject, "")
                        greetMapSet("greeting_expr", subject, "")
                        showGreetingTracksDialog()
                    }
                })
            }
            container.addView(row)
        }

        val scrollView = ScrollView(this).apply { addView(container) }

        ModernDialog()
            .setTitle(tr("🎙️ Mga Greeting"))
            .setView(scrollView)
            .setNeutralButton(tr("➕ Bagong greeting")) { _, _ -> showEditGreetingDialog(null) }
            .setPositiveButton(tr("Tapos"), null