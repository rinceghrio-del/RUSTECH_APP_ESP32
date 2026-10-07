package com.example.facerobot

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
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
        speechService?.setPause(false)
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
    private val petGreetings = mapOf(
        "pusa" to listOf("Meow! Kumusta pusa!", "Ay, may pusa! Ang cute!", "Hi pusa, gusto mo bang makipaglaro?"),
        "aso" to listOf("Woof woof! Kumusta aso!", "Ay, may aso! Kaibigan ko yan.", "Hi doggi!")
    )

    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
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

    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)

    private val dfPlayerPlayRegex = Regex("dfplayer\\s*play\\s*(\\d+)", RegexOption.IGNORE_CASE)

    // ---------- CAMERA NAV (depth-based obstacle avoidance habang MOVING ang ESP32) ----------

    private var obstacleAnalyzer: ObstacleAnalyzer? = null
    private var depthModelBusy = false
    private val depthModelFileName = "midas_small.tflite"
    private val depthModelUrl = "https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite"
    private val depthModelMinBytes = 50_000_000L

    @Volatile private var navActive = false          // naka-nav mode ba ngayon (MOVING ang ESP32)
    @Volatile private var navTestMode = false        // live scores lang, walang utos sa robot
    private var navTestUntil = 0L
    @Volatile private var navCalibrating = false
    private var navCalibEndTime = 0L
    private val navCalibSamples = mutableListOf<Float>()

    private var lastNavAnalysisTime = 0L
    private val navAnalysisIntervalMs = 180L         // pinakamabilis na pag-analyze ng frame
    private val navSendIntervalMs = 250L             // gaano kadalas ipadala ang hint sa ESP32
    private var navServoSettleUntil = 0L             // hintayin munang tumigil ang servo bago mag-analyze
    @Volatile private var navPose = 0                // 0 = tilt A, 1 = tilt B
    private var lastNavPoseSwitch = 0L
    private val navPoseSwitchMs = 1400L
    private var navNonMovingPolls = 0
    private var pingFailCount = 0
    private var lastNavServoAngle = 0

    private var navLastDecision = ObstacleAnalyzer.Decision.CLEAR
    private var navLastDecisionTime = 0L

    // Isang hint / isang ping lang ang sabay na nasa ere - iwas-pile-up at iwas-lumang hints
    @Volatile private var navHintInFlight = false
    @Volatile private var pingInFlight = false
    private var lastNavHintName = ""
    private var lastNavHintSentTime = 0L
    private val navKeepAliveMs = 400L    // ESP32 hint timeout = 700ms, kaya ulitin bago mag-expire

    private val navHandler = Handler(android.os.Looper.getMainLooper())
    private val navSendRunnable = object : Runnable {
        override fun run() {
            if (!navActive) return
            if (!navTestMode) {
                val age = System.currentTimeMillis() - navLastDecisionTime
                // Kapag luma na ang huling desisyon (natigil ang analysis), huwag ipagpatuloy ang liko - CLEAR na lang
                val decision = if (age > 1200L) ObstacleAnalyzer.Decision.CLEAR else navLastDecision
                sendNavHint(decision)
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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        forceWifiForEsp32()

        cameraExecutor = Executors.newSingleThreadExecutor()
        yoloDetector = YoloPersonDetector(this)
        faceEmbedder = FaceEmbedder(this)
        faceStore = FaceStore(this)
        commandStore = CommandStore(this)
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
            setupVosk()
        } else {
            statusText.text = "Naghahanap ng tao... (hinihintay permissions...)"
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
        val dfTracks = parts.mapNotNull { dfPlayerPlayRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val otherParts = parts.filterNot { dfPlayerPlayRegex.containsMatchIn(it) }

        if (dfTracks.isEmpty()) {
            runMovementParts(otherParts)
            return
        }

        // Hintayin munang matapos (onResponse) o mag-fail (onFailure) ang UNANG
        // DFPlayer track bago ipadala ang movement/ibang commands - kaysa manghula
        // tayo ng magic delay number na baka minsan hindi sapat.
        sendPlayTrackThen(dfTracks.first()) {
            runMovementParts(otherParts)
        }
        // Kung sakaling may isa pang "dfplayer play N" sa parehong combo (bihira),
        // ipadala na lang agad ang mga sumunod nang walang hintayan.
        dfTracks.drop(1).forEach { sendPlayTrack(it) }
    }

    private fun unknownGreetingTrackList(): List<Int> =
        unknownGreetingTracksRaw.split(",").mapNotNull { it.trim().toIntOrNull() }

    private fun runMovementParts(otherParts: List<String>) {
        for (part in otherParts) {
            val partUpper = part.uppercase()
            if (partUpper in movementActions) {
                sendTimedCommand(partUpper, voiceMovementDurationMs)
            } else {
                sendCommandToEsp32(part)
            }
        }
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
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
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

        statusText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(40, 22, 40, 22)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(0xE6121212.toInt())
                cornerRadius = 100f
                setStroke(2, 0x22FFFFFF)
            }
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
                    t.contains("Tanggalin") -> {
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
                    t.contains("Piliin") -> {
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
        geminiTexts.addView(label("Utak ni Rustech", 12f, 0xCC04342C.toInt()))
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
            makeCard("🗣️", "Boses", "Lokal na TTS") { showLocalVoiceDialog() },
            makeCard("📶", "IP ng Robot", esp32BaseUrl.removePrefix("http://")) { showIpSettingDialog() },
            makeCard(
                "✨", "Mag-enroll ng mukha",
                if (canEnroll) "Bagong mukha" else "Kailangan ng mukha",
                enabled = canEnroll
            ) { showEnrollDialog() },
            makeCard("🎙️", "Greeting Tracks", "Bati sa bawat tao") { showGreetingTracksDialog() },
            makeCard("📏", "Distance", "Layo ng tao") { showDistanceSettingsDialog() },
            makeCard("🎤", "Mic Sensitivity", "${(micConfidenceThreshold * 100).toInt()}%") { showMicSensitivityDialog() },
            makeCard("💬", "Mga Utos", "Voice commands") { showManageCommandsDialog() },
            makeCard("🧭", "Camera Nav", "Obstacle avoidance") { showNavSettingsDialog() },
            makeCard("🗒️", "Voice Log", "Kasaysayan") { showVoiceLogDialog() }
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

        val json = prefs.getString("greeting_tracks", "{}") ?: "{}"
        val obj = JSONObject(json)
        val keys = obj.keys().asSequence().toList()

        if (keys.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Wala pang naka-set na greeting track."
                setPadding(0, 0, 0, 24)
            })
        } else {
            for (name in keys) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = "$name -> Tracks ${obj.getString(name)}"
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this@MainActivity).apply {
                    text = "Tanggalin"
                    textSize = 10f
                    setOnClickListener {
                        removeGreetingTrack(name)
                        showGreetingTracksDialog()
                    }
                })
                container.addView(row)
            }
        }

        container.addView(View(this).apply {
            setBackgroundColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                .apply { topMargin = 32; bottomMargin = 32 }
        })

        container.addView(TextView(this).apply { text = "Magdagdag ng greeting track:" })
        container.addView(TextView(this).apply {
            text = "(Pwede ring \"aso\" o \"pusa\" ilagay bilang pangalan - para sa pet greeting)"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 12)
        })

        val nameInput = EditText(this).apply {
            hint = "Eksaktong pangalan (kagaya ng naka-enroll), o \"aso\"/\"pusa\" - IWANAN BLANGKO kung hindi kilalang tao"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val trackInput = EditText(this).apply {
            hint = "Track numbers, comma-separated (hal. 25,26,27)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(nameInput)
        container.addView(trackInput)

        container.addView(View(this).apply {
            setBackgroundColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                .apply { topMargin = 32; bottomMargin = 32 }
        })
        container.addView(TextView(this).apply {
            text = "🎲 Random tracks para sa HINDI kilalang tao (comma-separated):"
        })
        val unknownTracksInput = EditText(this).apply {
            hint = "hal. 32,33,34,35,36,37"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(unknownGreetingTracksRaw)
        }
        container.addView(unknownTracksInput)

        val scrollView = ScrollView(this).apply { addView(container) }

        ModernDialog()
            .setTitle("🎙️ Greeting Tracks (per pangalan)")
            .setView(scrollView)
            .setPositiveButton("Idagdag/I-save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val tracksCsv = trackInput.text.toString().trim()
                if (name.isNotEmpty() && tracksCsv.isNotEmpty()) {
                    setGreetingTracks(name, tracksCsv)
                }
                unknownGreetingTracksRaw = unknownTracksInput.text.toString().trim()
                statusText.text = "Na-save ang greeting tracks"
            }
            .setNegativeButton("Isara", null)
            .showImmersive()
    }

    private fun showEyesUi() {
        appState = AppState.EYES
        canEnroll = false
        statusText.text = if (yoloDetector.isReady) {
            "Naghahanap ng tao..."
        } else {
            "Naghahanap ng tao... (kulang: assets/yolo_person.tflite)"
        }
        lastGreetedName = null
        lastUnknownGreetTime = 0L
        consecutivePersonDetections = 0
        currentRecognizedName = null
        faceTooClose = false
        roboEyesView.setMood(RoboEyesView.Mood.IDLE)
    }

    private fun showCameraUi() {
        appState = AppState.CAMERA
        lastPersonSeenTime = System.currentTimeMillis()
        roboEyesView.setMood(RoboEyesView.Mood.ALERT)
        statusText.text = "May tao! Sinusubukang kilalanin..."
    }

    private fun runOnUi(block: () -> Unit) = runOnUiThread(block)

    // ---------- Camera setup ----------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy -> processFrame(imageProxy) }
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUi {
                    statusText.text = "Naghahanap ng tao... (camera setup error: ${e.javaClass.simpleName}: ${e.message})"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val grantedMap = permissions.zip(grantResults.toList()).toMap()

            if (grantedMap[Manifest.permission.CAMERA] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else if (permissions.contains(Manifest.permission.CAMERA)) {
                statusText.text = "Naghahanap ng tao... (TINANGGIHAN ang camera permission)"
            }

            if (grantedMap[Manifest.permission.RECORD_AUDIO] == PackageManager.PERMISSION_GRANTED) {
                setupVosk()
            }
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        // "Take a picture": kunin ang susunod na frame bilang litrato, kahit anong AppState/mode
        // ang kasalukuyan, bago dumaan sa normal na nav/face processing.
        if (pendingPhotoCapture) {
            capturePhotoFromFrame(imageProxy)
            return
        }

        // "Paningin" ni Gemini: kunin ang susunod na frame para ipadala kasama ng tanong
        // (hal. "ano ginagawa ko", "ano ang nakikita mo") - gaya rin ng take-a-picture, kahit
        // anong AppState/mode ang kasalukuyan.
        pendingVisionCapture?.let { callback ->
            pendingVisionCapture = null
            captureFrameForVision(imageProxy, callback)
            return
        }

        // Camera Nav: kapag naka-MOVING ang robot (o nagte-test/nagca-calibrate), ang frames ay napupunta
        // sa depth analysis at hindi sa face tracking.
        if (navTestMode && System.currentTimeMillis() > navTestUntil) navTestMode = false
        if (navActive || navCalibrating || navTestMode) {
            processNavFrame(imageProxy)
            return
        }

        when (appState) {
            AppState.EYES -> processEyesFrame(imageProxy)
            AppState.CAMERA -> processCameraFrame(imageProxy)
        }
    }

    private fun processEyesFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!yoloDetector.isReady || now - lastYoloCheckTime < yoloIntervalMs) {
            imageProxy.close()
            return
        }
        lastYoloCheckTime = now

        try {
            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
            val detections = yoloDetector.detect(
                bitmap,
                setOf(YoloPersonDetector.PERSON_CLASS_INDEX) + YoloPersonDetector.PET_CLASSES
            )
            val personDetections = detections.filter { it.classId == YoloPersonDetector.PERSON_CLASS_INDEX }
            val petDetections = detections.filter { it.classId in YoloPersonDetector.PET_CLASSES }

            if (personDetections.isNotEmpty()) {
                consecutivePersonDetections++
            } else {
                consecutivePersonDetections = 0
            }

            if (petDetections.isNotEmpty()) {
                val topPetLabel = petDetections.first().label
                if (topPetLabel == lastPetLabelSeen) {
                    consecutivePetDetections++
                } else {
                    lastPetLabelSeen = topPetLabel
                    consecutivePetDetections = 1
                }
                if (consecutivePetDetections >= requiredConsecutivePetDetections) {
                    runOnUi { greetPetIfNeeded(topPetLabel) }
                }
            } else {
                consecutivePetDetections = 0
                lastPetLabelSeen = null
            }

            if (consecutivePersonDetections >= requiredConsecutiveDetections) {
                rootLayout.postDelayed({
                    if (appState == AppState.EYES && consecutivePersonDetections >= requiredConsecutiveDetections) {
                        showCameraUi()
                    }
                }, 350)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUi {
                statusText.text = "Naghahanap ng tao... (crash: ${e.javaClass.simpleName}: ${e.message})"
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    handleFaceFound(faces[0], imageProxy, rotation)
                } else {
                    handleNoFace()
                }
            }
            .addOnFailureListener { it.printStackTrace() }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun handleFaceFound(face: Face, imageProxy: ImageProxy, rotation: Int) {
        lastPersonSeenTime = System.currentTimeMillis()


        val box = face.boundingBox
        val frameWidth = imageProxy.width
        val frameHeight = imageProxy.height

        val faceRatio = box.width().toFloat() / frameWidth.toFloat()
        if (!faceTooClose && faceRatio > closeFaceWidthRatio) faceTooClose = true
        else if (faceTooClose && faceRatio < closeFaceWidthRatio * 0.9f) faceTooClose = false
        runOnUi {
            roboEyesView.setMood(if (faceTooClose) RoboEyesView.Mood.ANGRY else RoboEyesView.Mood.ALERT)
        }

        val command = computeCommand(box, frameWidth)
        // Ang box ng ML Kit ay nasa UPRIGHT (naka-rotate) na coordinates, kaya para sa 90/270 ay
        // kabaligtaran ang tunay na taas ng frame (width ng raw image). Dati raw height ang ginamit -> sablay ang "gitna".
        val uprightFrameHeight = if (rotation == 90 || rotation == 270) frameWidth else frameHeight
        val servoAngle = computeServoAngle(box, uprightFrameHeight)
        if (!voiceOverrideActive) {
            sendCommandThrottled(command, servoAngle)
        }

        val now = System.currentTimeMillis()
        if (faceEmbedder.isReady && now - lastRecognitionTime > recognitionIntervalMs) {
            lastRecognitionTime = now
            try {
                val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                val adjustedBox = adjustBoxForRotation(box, frameWidth, frameHeight, rotation)
                val faceCrop = ImageUtils.safeCrop(bitmap, adjustedBox)

                if (faceCrop != null) {
                    val embedding = faceEmbedder.getEmbedding(faceCrop)
                    if (embedding != null) {
                        val match = faceStore.match(embedding)
                        runOnUi {
                            if (match != null) {
                                statusText.text = "Kilala: ${match.name} (${(match.similarity * 100).toInt()}%)"
                                canEnroll = false
                                lastUnknownFaceEmbedding = null
                                currentRecognizedName = match.name
                                greetIfNeeded(match.name)
                            } else {
                                statusText.text = if (faceStore.isEmpty()) {
                                    "May tao pero wala pang naka-enroll na mukha"
                                } else {
                                    "May Tao"
                                }
                                lastUnknownFaceEmbedding = embedding
                                canEnroll = true
                                currentRecognizedName = null
                                greetUnknownIfNeeded()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val unknownGreetings = listOf(
        "Kumusta, ano ginagawa mo ngayon.",
        "Hi kaibigan na tao! Gusto mo bang makipag laro sa akin.",
        "Kumusta! Saan pala kayo papunta?",
        "Hello! Pwede mo ba akong Kausapin?",
        "Hi! kausapin mo ako",
        "Ngayon ka lang ba naka kita ng laruan na kagaya ko",
        "Kumain na ba kayo",
        "tara laro tayo",
        "Nagyayaya ba ang tropa ng inuman?",
        "Huwag mo ako kalimutan na e charge!",
    )

    // ---------- Greeting Tracks (per-enrolled-name DFPlayer greeting) ----------
    // Ang bawat pangalan ay maaaring may MARAMING track (comma-separated sa JSON
    // value, hal. "25,26,27") - random na pipiliin tuwing may nakilala, para hindi
    // paulit-ulit kahit parehong tao palagi ang nakikita.
    private fun greetingTracksFor(name: String): List<Int> {
        val json = prefs.getString("greeting_tracks", "{}") ?: "{}"
        val obj = JSONObject(json)
        if (!obj.has(name)) return emptyList()
        return obj.getString(name).split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    private fun setGreetingTracks(name: String, tracksCsv: String) {
        val json = prefs.getString("greeting_tracks", "{}") ?: "{}"
        val obj = JSONObject(json)
        obj.put(name, tracksCsv.trim())
        prefs.edit().putString("greeting_tracks", obj.toString()).apply()
    }

    private fun removeGreetingTrack(name: String) {
        val json = prefs.getString("greeting_tracks", "{}") ?: "{}"
        val obj = JSONObject(json)
        obj.remove(name)
        prefs.edit().putString("greeting_tracks", obj.toString()).apply()
    }

    private fun greetIfNeeded(name: String) {
        val now = System.currentTimeMillis()
        val alreadyGreetedRecently = name == lastGreetedName && now - lastGreetedTime < greetingCooldownMs
        if (alreadyGreetedRecently) return

        lastGreetedName = name
        lastGreetedTime = now

        val tracks = greetingTracksFor(name)
        if (tracks.isNotEmpty() && !geminiEnabled) {
            sendPlayTrack(tracks.random())
        } else if (geminiEnabled) {
            // Naka-mute ang DFPlayer habang naka-ON ang Gemini AI mode - subukang gawing
            // bago/iba-iba ang bati sa pamamagitan ni Gemini, may fallback pa rin kung mabigo.
            sayDynamicOrFallback(
                "May nakita kang kilalang kaibigan/kasama na si $name sa harap mo ngayon - " +
                    "batiin mo siya nang maikli at natural, parang kakakita mo lang talaga sa kanya.",
                "Kumusta, $name!"
            )
        }
    }

    private fun greetUnknownIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastUnknownGreetTime < greetingCooldownMs) return
        lastUnknownGreetTime = now

        val tracks = unknownGreetingTrackList()
        if (tracks.isNotEmpty() && !geminiEnabled) {
            sendPlayTrack(tracks.random())
        } else if (geminiEnabled) {
            sayDynamicOrFallback(
                "May nakita kang BAGONG tao sa harap mo na hindi mo pa kilala - batiin mo siya " +
                    "nang friendly, maikli, at natural.",
                unknownGreetings.random()
            )
        } else if (ttsReady) {
            // Fallback sa TTS kung wala pang na-set na DFPlayer tracks (at OFF ang Gemini).
            speak(unknownGreetings.random())
        }
    }

    private fun greetPetIfNeeded(label: String) {
        val now = System.currentTimeMillis()
        if (now - lastPetGreetTime < petGreetingCooldownMs) return
        lastPetGreetTime = now

        // Pareho ng greetIfNeeded(name) sa tao: gamit ang parehong "greeting_tracks" store,
        // dahil "aso"/"pusa" mismo ang label - kaya kung nag-set ka ng tracks sa Greeting
        // Tracks dialog gamit "aso" o "pusa" bilang pangalan, gagana na agad ito dito.
        // (Naka-mute ang DFPlayer habang naka-ON ang Gemini AI mode - tuloy sa TTS fallback.)
        val tracks = greetingTracksFor(label)
        if (tracks.isNotEmpty() && !geminiEnabled) {
            sendPlayTrack(tracks.random())
            return
        }

        val options = petGreetings[label]
        if (geminiEnabled && options != null) {
            sayDynamicOrFallback(
                "May nakita kang $label sa harap ng camera mo ngayon - magreact ka nang maikli " +
                    "at masaya, parang kakakita mo lang talaga.",
                options.random()
            )
            return
        }

        // Walang naka-set na DFPlayer track, at OFF ang Gemini - fallback sa TTS, gaya ng dati.
        if (!ttsReady) return
        if (options != null) speak(options.random())
    }

    // ---------- Vosk offline voice recognition ----------

    private fun setupVosk() {
        val modelDir = voskModelDir()
        if (modelDir.exists() && modelDir.list()?.isNotEmpty() == true) {
            loadVoskModel(modelDir.absolutePath)
        } else {
            downloadAndExtractVoskModel()
        }
    }

    private fun voskModelDir(): File = File(filesDir, voskModelDirName)

    private fun downloadAndExtractVoskModel() {
        Thread {
            try {
                runOnUi { statusText.text = "⬇️ Dina-download ang Filipino voice model (~320MB, isang beses lang ito)..." }

                val request = Request.Builder().url(voskModelUrl).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                val body = response.body ?: throw java.io.IOException("Walang response body")

                val zipFile = File(cacheDir, "vosk_model.zip")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastUpdate = 0L

                body.byteStream().use { input ->
                    FileOutputStream(zipFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            val now = System.currentTimeMillis()
                            if (totalBytes > 0 && now - lastUpdate > 500) {
                                lastUpdate = now
                                val percent = (downloadedBytes * 100 / totalBytes).toInt()
                                runOnUi { statusText.text = "⬇️ Dina-download ang voice model... $percent%" }
                            }
                        }
                    }
                }
                response.close()

                runOnUi { statusText.text = "📦 Ina-extract ang voice model..." }
                extractZip(zipFile, filesDir)
                zipFile.delete()

                loadVoskModel(voskModelDir().absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUi { statusText.text = "❌ Hindi na-download ang voice model: ${e.message}" }
            }
        }.start()
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun loadVoskModel(modelPath: String) {
        Thread {
            try {
                val model = Model(modelPath)
                voskModel = model
                runOnUi {
                    voskReady = true
                    statusText.text = "🎤 Handa na makinig (offline)"
                    startVoskListening()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUi { statusText.text = "❌ Hindi na-load ang voice model: ${e.message}" }
            }
        }.start()
    }

    private val voskMaxAlternatives = 3

    private fun startVoskListening() {
        val model = voskModel ?: return
        try {
            val recognizer = Recognizer(model, 16000.0f)
            recognizer.setWords(true)
            recognizer.setMaxAlternatives(voskMaxAlternatives)
            val service = SpeechService(recognizer, 16000.0f)
            speechService = service
            service.startListening(voskListener)
        } catch (e: Exception) {
            e.printStackTrace()
            statusText.text = "❌ Mic error: ${e.message}"
        }
    }

    private val voskListener = object : VoskListener {
        override fun onPartialResult(hypothesis: String?) {}

        override fun onResult(hypothesis: String?) {
            val json = try { JSONObject(hypothesis ?: "") } catch (e: Exception) { null } ?: return

            val alternativesArray = json.optJSONArray("alternatives")
            val candidates: List<Pair<String, Float>> = if (alternativesArray != null && alternativesArray.length() > 0) {
                (0 until alternativesArray.length()).mapNotNull { i ->
                    val alt = alternativesArray.optJSONObject(i) ?: return@mapNotNull null
                    val altText = alt.optString("text", "").trim()
                    if (altText.isEmpty()) null else altText to averageConfidence(alt)
                }
            } else {
                val text = json.optString("text", "").trim()
                if (text.isEmpty()) emptyList() else listOf(text to averageConfidence(json))
            }

            if (candidates.isEmpty()) return

            val topText = candidates.first().first
            val topConfidence = candidates.first().second
            if (topConfidence < micConfidenceThreshold) {
                runOnUi {
                    addVoiceLogEntry(topText, "na-ignore (mababa ang confidence: ${(topConfidence * 100).toInt()}%)")
                }
                return
            }

            val candidateTexts = candidates.map { it.first }
            runOnUi {
                statusText.text = "[MIC] Narinig: $topText"
                handleVoiceCommand(candidateTexts)
            }
        }

        override fun onFinalResult(hypothesis: String?) {}

        override fun onError(exception: Exception?) {
            exception?.printStackTrace()
        }

        override fun onTimeout() {}
    }

    private fun averageConfidence(json: JSONObject?): Float {
        val resultArray = json?.optJSONArray("result") ?: return 1f
        if (resultArray.length() == 0) return 1f
        var sum = 0.0
        for (i in 0 until resultArray.length()) {
            sum += resultArray.getJSONObject(i).optDouble("conf", 1.0)
        }
        return (sum / resultArray.length()).toFloat()
    }

    private fun handleVoiceCommand(candidates: List<String>) {
        val heardText = candidates.firstOrNull() ?: return

        val useGemini = geminiEnabled && geminiApiKey.isNotBlank() &&
            System.currentTimeMillis() >= geminiCooldownUntil &&
            geminiUsedToday() < geminiDailyLimit

        // Walang Gemini (naka-off, walang key, o cooldown): dating lokal na behavior.
        if (!useGemini) {
            val reason = when {
                !geminiEnabled -> ""
                geminiApiKey.isBlank() -> " (walang Gemini API key)"
                geminiUsedToday() >= geminiDailyLimit -> " (naabot ang daily limit ng Gemini)"
                else -> " (Gemini cooldown)"
            }
            addVoiceLogEntry(heardText, processVoiceCommand(candidates) + reason)
            return
        }

        // 1) KALIGTASAN: STOP ay laging lokal at agad - hindi na dumadaan sa internet.
        if (candidates.any { it.contains("hinto") || it.contains("stop") || it.contains("tigil") }) {
            speak("Hihinto na po!")
            sendCommandToEsp32("FORCE_STOP")
            addVoiceLogEntry(heardText, "FORCE_STOP")
            return
        }

        // 1b) Display switch (camera/mata) - lokal din, hindi kailangan ng Gemini.
        tryHandleDisplayCommand(candidates)?.let { result ->
            addVoiceLogEntry(heardText, result)
            return
        }

        // 1c) "Take a picture" - lokal din, hindi kailangan ng Gemini.
        tryHandlePhotoCommand(candidates)?.let { result ->
            addVoiceLogEntry(heardText, result)
            return
        }

        // 2) Maiikling eksaktong utos (hal. "sayaw", "abante") - lokal at instant, gaya ng dati.
        val exact = findExactCustomCommand(candidates)
        if (exact != null) {
            speak(exact.randomReply())
            if (exact.action.isNotBlank()) executeEsp32Actions(exact.action)
            addVoiceLogEntry(heardText, "custom: \"${exact.trigger}\"")
            return
        }

        // 2b) Vision question (hal. "ano nakikita mo", "ano ginagawa ko") - hiwalay na Gemini
        // call na may kasamang larawan mula sa camera.
        tryHandleVisionCommand(candidates)?.let { result ->
            addVoiceLogEntry(heardText, result)
            return
        }

        // 3) Lahat ng iba - kay Rustech (Gemini) na.
        if (geminiBusy) {
            addVoiceLogEntry(heardText, "na-ignore (nag-iisip pa si Rustech)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastGeminiRequestTime < geminiMinIntervalMs) {
            addVoiceLogEntry(heardText, "na-ignore (masyadong mabilis)")
            return
        }
        askGemini(candidates)
    }

    /**
     * "ipakita mo ang camera" / "mag-switch sa camera" -> lumipat ang DISPLAY papuntang camera preview.
     * "balik sa mata" / "ipakita ang mata" -> bumalik sa RoboEyes. Ito ang parehong toggle na nasa
     * Menu > Display switch - hindi ito tinanggal, dalawa lang ang paraan papuntang parehong setting.
     */
    private fun tryHandleDisplayCommand(candidates: List<String>): String? {
        for (text in candidates) {
            val mentionsCamera = text.contains("camera") || text.contains("kamera")
            val mentionsEyes = text.contains("mata") || text.contains("eyes") || text.contains("roboeyes")
            val hasSwitchIntent = text.contains("ipakita") || text.contains("ilagay") || text.contains("ilipat") || text.contains("switch") ||
                text.contains("palitan") || text.contains("lumipat") || text.contains("tignan") ||
                text.contains("balik")

            if (mentionsCamera && hasSwitchIntent) {
                showRoboEyes = false
                applyDisplayMode()
                playTransitionCue("ipakita ang camera", "dfplayer play 47", "Sige, ipapakita ko na ang camera.")
                return "display: camera"
            }
            if (mentionsEyes && hasSwitchIntent) {
                showRoboEyes = true
                applyDisplayMode()
                speakTransitionCue("balik sa mata", "Sige, babalik na sa mata.")
                return "display: eyes"
            }
        }
        return null
    }

    /** Custom command na halos eksakto ang sinabi (hindi kasama sa mahabang usapan). */
    private fun findExactCustomCommand(candidates: List<String>): CommandStore.VoiceCommand? {
        for (text in candidates) {
            val cmd = commandStore.findMatch(text) ?: continue
            val words = text.trim().split(Regex("\\s+")).size
            val triggerWords = cmd.triggerVariants()
                .filter { text.contains(it) }
                .maxOfOrNull { it.trim().split(Regex("\\s+")).size } ?: continue
            if (words <= triggerWords + 1) return cmd
        }
        return null
    }

    private fun askGemini(candidates: List<String>) {
        val heardText = candidates.first()
        geminiBusy = true
        lastGeminiRequestTime = System.currentTimeMillis()
        bumpGeminiCount()
        statusText.text = "🧠 Nag-iisip... ($heardText)"

        geminiBrain.ask(
            apiKey = geminiApiKey,
            model = geminiModel,
            heard = candidates.take(3),
            situation = GeminiBrain.Situation(currentRecognizedName, commandStore.all())
        ) { result ->
            runOnUi {
                geminiBusy = false
                when (result) {
                    is GeminiBrain.Result.Ok -> onGeminiReply(heardText, result.reply)
                    is GeminiBrain.Result.Fail -> onGeminiFail(candidates, result)
                }
            }
        }
    }

    /**
     * "ano nakikita mo", "ano ginagawa ko", atbp -> hiwalay na Gemini call (geminiBrain.see())
     * na may kasamang aktwal na larawan mula sa camera - hindi ito bahagi ng regular na
     * usapan/history, kaya hiwalay ito sa normal na askGemini().
     */
    private fun tryHandleVisionCommand(candidates: List<String>): String? {
        val question = candidates.firstOrNull { text -> visionTriggerPhrases.any { text.contains(it) } }
            ?: return null

        if (geminiBusy) return "na-ignore (nag-iisip pa si Rustech)"
        val now = System.currentTimeMillis()
        if (now - lastGeminiRequestTime < geminiMinIntervalMs) return "na-ignore (masyadong mabilis)"

        askGeminiVision(question)
        return "gemini_vision: \"$question\""
    }

    private fun askGeminiVision(question: String) {
        geminiBusy = true
        lastGeminiRequestTime = System.currentTimeMillis()
        bumpGeminiCount()
        statusText.text = "👁️ Tumitingin... ($question)"

        // Kailangan ng live camera view para may makuhang larawan - lumipat muna papuntang
        // camera kung nasa mata (RoboEyes) pa, gamit ang parehong transition cue.
        if (showRoboEyes) {
            showRoboEyes = false
            applyDisplayMode()
            playTransitionCue("ipakita ang camera", "dfplayer play 47", "Sandali lang, tumitingin ako...")
        }

        pendingVisionCapture = { bitmap ->
            val imageBase64 = bitmapToBase64Jpeg(bitmap)
            geminiBrain.see(
                apiKey = geminiApiKey,
                model = geminiModel,
                imageBase64 = imageBase64,
                question = question,
                situation = GeminiBrain.Situation(currentRecognizedName, commandStore.all())
            ) { result ->
                runOnUi {
                    geminiBusy = false
                    when (result) {
                        is GeminiBrain.Result.Ok -> onGeminiReply(question, result.reply, allowAction = false)
                        is GeminiBrain.Result.Fail -> onGeminiFail(listOf(question), result)
                    }
                    // Pagkatapos "makita" at masagot, bumalik ng tahimik sa mata (RoboEyes) -
                    // walang dagdag na cue/tunog dito, dahil nasabi na ang sagot mismo kanina.
                    returnToEyesAfterVision()
                }
            }
        }
    }

    private fun returnToEyesAfterVision() {
        rootLayout.postDelayed({
            if (!showRoboEyes) {
                showRoboEyes = true
                applyDisplayMode()
            }
        }, 6000)
    }

    /**
     * Sinusubukang gumawa ng BAGO at IBA-IBA na linya kay Gemini (geminiBrain.sayLine())
     * base sa isinalaysay na sitwasyon - para sa mga pagkakataon na dating parehong salita lang
     * palagi (greeting, transition cue). Kung naka-OFF ang Gemini, walang API key, busy pa sa
     * ibang request, naabot na ang daily limit, o nabigo ang request - gagamitin ang "fallback"
     * (yung orihinal na naka-hardcode na linya) sa halip, kaya laging may boses pa rin.
     */
    private fun sayDynamicOrFallback(situationCue: String, fallback: String) {
        val now = System.currentTimeMillis()
        val canCallGemini = geminiEnabled && geminiApiKey.isNotBlank() && !geminiBusy &&
            now - lastGeminiRequestTime >= geminiMinIntervalMs &&
            now >= geminiCooldownUntil && geminiUsedToday() < geminiDailyLimit

        if (!canCallGemini) {
            speak(fallback)
            return
        }

        geminiBusy = true
        lastGeminiRequestTime = now
        bumpGeminiCount()
        geminiBrain.sayLine(geminiApiKey, geminiModel, situationCue) { result ->
            runOnUi {
                geminiBusy = false
                when (result) {
                    is GeminiBrain.Result.Ok -> {
                        val text = result.reply.text.trim()
                        speak(if (text.isNotEmpty()) text else fallback)
                    }
                    is GeminiBrain.Result.Fail -> speak(fallback)
                }
            }
        }
    }

    private fun onGeminiReply(heardText: String, reply: GeminiBrain.Reply, allowAction: Boolean = true) {
        val text = reply.text.trim()
        // Sa vision Q&A (allowAction = false), pinipilit nating "NONE" kahit ano pa ang ibalik
        // ni Gemini - puro impormasyon lang dapat ang sagot dito, hindi galaw/STOP papunta sa
        // ESP32 (na may sariling voice announcement ang ESP32 firmware kapag natanggap ang STOP).
        val action = if (allowAction) reply.action.uppercase() else "NONE"

        if (text.isEmpty() && action == "NONE") {
            statusText.text = "[MIC] (hindi para sa akin) $heardText"
            addVoiceLogEntry(heardText, "gemini: hindi pinansin")
            return
        }

        statusText.text = "🤖 Rustech: $text"
        if (text.isNotEmpty()) speak(text)

        val espAction = when (action) {
            "NONE" -> null
            "STOP" -> "FORCE_STOP"
            else -> if (action in GeminiBrain.ALLOWED_ACTIONS) action else null
        }
        if (espAction != null) executeEsp32Actions(espAction)

        addVoiceLogEntry(heardText, "gemini → \"${text.take(70)}\" [$action]")
    }

    private fun onGeminiFail(candidates: List<String>, fail: GeminiBrain.Result.Fail) {
        val heardText = candidates.first()
        if (fail.kind == GeminiBrain.FailKind.RATE_LIMIT) {
            geminiCooldownUntil = System.currentTimeMillis() + 20_000L
        }

        // Fallback: subukan ang dating lokal na pagtutugma para gumana pa rin ang mga utos.
        val local = processVoiceCommand(candidates)
        val label = "gemini FAILED [${fail.kind}] ${fail.detail}"
        addVoiceLogEntry(heardText, if (local == "walang tumugma") label else "$label → lokal: $local")

        if (local == "walang tumugma") {
            val now = System.currentTimeMillis()
            if (now - lastGeminiErrorSpeakTime > 15_000L) {
                lastGeminiErrorSpeakTime = now
                speak(
                    when (fail.kind) {
                        GeminiBrain.FailKind.RATE_LIMIT -> "Sandali lang, napagod ang utak ko. Mamaya ulit tayo mag-usap."
                        GeminiBrain.FailKind.NETWORK -> "Wala akong signal ngayon, hindi ako makapag-isip nang malalim."
                        GeminiBrain.FailKind.BAD_KEY -> "Mukhang may mali sa API key ko. Pakitingnan sa menu."
                        GeminiBrain.FailKind.BLOCKED -> "Hmm, hindi ko masagot yan. Iba na lang."
                        else -> "May gumulo sa utak ko. Ulitin mo nga."
                    }
                )
            }
        }
    }

    private fun processVoiceCommand(candidates: List<String>): String {
        tryHandleDisplayCommand(candidates)?.let { return it }
        tryHandlePhotoCommand(candidates)?.let { return it }
        for (text in candidates) {
            val custom = commandStore.findMatch(text)
            if (custom != null) {
                speak(custom.randomReply())
                if (custom.action.isNotBlank()) {
                    executeEsp32Actions(custom.action)
                }
                return "custom: \"${custom.trigger}\""
            }

            when {
                text.contains("hinto") || text.contains("stop") || text.contains("tigil") -> {
                    speak("Hihinto na po!")
                    sendCommandToEsp32("FORCE_STOP")
                    return "FORCE_STOP"
                }
                text.contains("kaliwa") || text.contains("left") -> {
                    speak("Lilikot sa kaliwa.")
                    sendTimedCommand("LEFT", voiceMovementDurationMs)
                    return "LEFT"
                }
                text.contains("kanan") || text.contains("right") -> {
                    speak("Lilikot sa kanan.")
                    sendTimedCommand("RIGHT", voiceMovementDurationMs)
                    return "RIGHT"
                }
                text.contains("sino ako") || text.contains("sino po ako") || text.contains("sino ba ako") -> {
                    val name = currentRecognizedName
                    val reply = when {
                        name != null -> "Ikaw ay si $name!"
                        appState == AppState.CAMERA -> "Hindi pa kita kilala. Pwede mo akong i-enroll."
                        else -> "Wala akong nakikitang tao ngayon."
                    }
                    speak(reply)
                    return "sino ako"
                }
                text.contains("sino ka") || text.contains("ano pangalan mo") ||
                text.contains("ano ngalan mo") || text.contains("ano name mo") ||
                text.contains("sino ka ba") || text.contains("pangalan mo") || text.contains("name mo") -> {
                    val RustechReplies = listOf(
                        "Ako ay si Rustech.. Ang laruan mo na ROBOT!",
                        "Ako ay si Rustech, ang kaibigan mo!",
                        "Ako si Rustech! Handang maglingkod at makipaglaro sa 'yo.",
                        "Rustech ang pangalan ko, ang paborito mong robot companion!",
                        "Ako si Rustech, ang AI robot na laging handang tumulong sa 'yo!"
                    )
                    speak(RustechReplies.random())
                    return "sino ka"
                }
            }
        }
        return "walang tumugma"
    }

    private fun addVoiceLogEntry(heard: String, result: String) {
        voiceLog.add(0, Triple(System.currentTimeMillis(), heard, result))
        if (voiceLog.size > voiceLogMaxSize) {
            voiceLog.removeAt(voiceLog.size - 1)
        }
    }

    private fun showVoiceLogDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        if (voiceLog.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Wala pang narinig na boses sa session na ito."
                setPadding(0, 0, 0, 24)
            })
        } else {
            val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
            for ((timestamp, heard, result) in voiceLog) {
                container.addView(TextView(this).apply {
                    text = "${timeFormat.format(Date(timestamp))} — \"$heard\" → $result"
                    textSize = 12f
                    setPadding(0, 8, 0, 8)
                })
            }
        }

        val scrollView = ScrollView(this).apply { addView(container) }

        ModernDialog()
            .setTitle("🗒️ Voice Log")
            .setView(scrollView)
            .setPositiveButton("I-clear") { _, _ -> voiceLog.clear() }
            .setNegativeButton("Isara", null)
            .showImmersive()
    }

    /**
     * Nililista ang mga available na boses ng lokal na Android/Google TTS engine, para masubukan
     * at mapili ng user kung alin ang gusto - wala talagang direktang paraan ang Android para
     * malaman kung babae o lalaki ang isang boses bago ito i-preview (walang "gender" field ang
     * Voice class), kaya pakinggan muna bawat isa.
     */
    private fun showLocalVoiceDialog() {
        val engine = tts
        if (engine == null) {
            ModernDialog()
                .setTitle("🗣️ Boses (Lokal na TTS)")
                .setMessage("Hindi pa handa ang lokal na TTS engine. Subukan ulit mamaya.")
                .setPositiveButton("Sige", null)
                .showImmersive()
            return
        }

        val allVoices = try { engine.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        if (allVoices.isEmpty()) {
            ModernDialog()
                .setTitle("🗣️ Boses (Lokal na TTS)")
                .setMessage("Walang nakitang listahan ng boses sa TTS engine ng phone mo.")
                .setPositiveButton("Sige", null)
                .showImmersive()
            return
        }

        fun isFilipino(v: Voice) = v.locale.language in listOf("fil", "tl")
        val filipinoVoices = allVoices.filter { isFilipino(it) && !it.isNetworkConnectionRequired }
        val filipinoNetworkVoices = allVoices.filter { isFilipino(it) && it.isNetworkConnectionRequired }
        val otherVoices = allVoices.filter { !isFilipino(it) }.sortedBy { it.locale.toString() }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
        }

        container.addView(TextView(this).apply {
            text = "I-tap ang ▶️ para pakinggan ang bawat boses. Kapag nahanap mo yung gusto mo, " +
                "i-tap ang ✅ Piliin. (Walang paraan ang Android para malaman kung babae o lalaki " +
                "ang boses nang hindi pinapakinggan muna.)"
            textSize = 12f
            setPadding(0, 0, 0, 20)
        })

        fun addVoiceRow(voice: Voice, label: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER_VERTICAL
            })
            row.addView(Button(this@MainActivity).apply {
                text = "▶️"
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    val previous = engine.voice
                    engine.voice = voice
                    engine.speak("Kumusta, ako si Rustech!", TextToSpeech.QUEUE_FLUSH, null, "voice_preview")
                    // Ibalik ang dating gamit na boses pagkatapos ng ilang segundo - preview lang ito.
                    if (previous != null) {
                        rootLayout.postDelayed({ engine.voice = previous }, 3000)
                    }
                }
            })
            row.addView(Button(this@MainActivity).apply {
                text = "✅ Piliin"
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    selectedTtsVoiceName = voice.name
                    engine.voice = voice
                    statusText.text = "🗣️ Napiling boses: ${voice.name}"
                }
            })
            container.addView(row)
        }

        if (filipinoVoices.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "🇵🇭 Filipino/Tagalog (offline):"
                textSize = 13f
                setPadding(0, 8, 0, 4)
            })
            filipinoVoices.forEach { addVoiceRow(it, it.name) }
        }
        if (filipinoNetworkVoices.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "🇵🇭 Filipino/Tagalog (kailangan ng internet):"
                textSize = 13f
                setPadding(0, 16, 0, 4)
            })
            filipinoNetworkVoices.forEach { addVoiceRow(it, it.name) }
        }
        if (otherVoices.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "🌐 Iba pang wika/boses (kung sakaling wala kang Filipino options):"
                textSize = 13f
                setPadding(0, 16, 0, 4)
            })
            otherVoices.take(30).forEach { addVoiceRow(it, "${it.locale} - ${it.name}") }
        }

        val scrollView = ScrollView(this).apply { addView(container) }

        ModernDialog()
            .setTitle("🗣️ Boses (Lokal na TTS)")
            .setView(scrollView)
            .setNegativeButton("Isara") { _, _ ->
                // Siguraduhing naka-apply ang napiling boses (hindi yung ginamit lang sa preview).
                if (selectedTtsVoiceName.isNotBlank()) {
                    val match = allVoices.firstOrNull { it.name == selectedTtsVoiceName }
                    if (match != null) engine.voice = match
                }
            }
            .showImmersive()
    }

    // ---------- TTS (Filipino voice gamit ang Android TextToSpeech / Google TTS) ----------

    private fun initTts(enginePackage: String?) {
        tts = if (enginePackage != null) {
            TextToSpeech(this, { status -> onTtsInit(status, enginePackage) }, enginePackage)
        } else {
            TextToSpeech(this) { status -> onTtsInit(status, null) }
        }
    }

    private fun onTtsInit(status: Int, enginePackage: String?) {
        if (status != TextToSpeech.SUCCESS) {
            // Walang Google TTS sa phone - gamitin ang default engine.
            if (enginePackage != null) {
                tts?.shutdown()
                initTts(null)
            }
            return
        }
        val engine = tts ?: return

        fun isOk(r: Int) = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        var filipinoOk = true
        if (!isOk(engine.setLanguage(Locale("fil", "PH")))) {
            if (!isOk(engine.setLanguage(Locale("tl", "PH")))) {
                engine.setLanguage(Locale.US)
                filipinoOk = false
            }
        }
        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)

        // Gamitin ang sariling napiling boses (kung meron at available pa sa phone na ito).
        if (selectedTtsVoiceName.isNotBlank()) {
            val match = try { engine.voices?.firstOrNull { it.name == selectedTtsVoiceName } } catch (e: Exception) { null }
            if (match != null) engine.voice = match
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                // Huling piraso lang ng sagot ang nag-a-unpause ng mic.
                if (utteranceId?.endsWith("_last") == true) scheduleMicResume()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                scheduleMicResume()
            }
        })
        ttsReady = true

        if (!filipinoOk) {
            runOnUi {
                statusText.text = "⚠️ Walang Filipino voice ang TTS. I-install ang Filipino sa Settings > Text-to-speech (Google)."
            }
        }
    }

    // Kaunting pagitan bago ibalik ang mic para hindi marinig ng robot ang dulo ng sarili niyang boses.
    private fun scheduleMicResume() {
        runOnUi {
            ttsHandler.removeCallbacks(resumeMicRunnable)
            ttsHandler.postDelayed(resumeMicRunnable, 300L)
        }
    }

    private fun cleanForSpeech(raw: String): String {
        return raw
            .replace(Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "")
            .replace(Regex("[*_#`~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // Hinahati sa maiikling piraso (bawat pangungusap) para mabilis magsimulang magsalita at natural ang pahinga.
    private fun splitForSpeech(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in sentences) {
            if (sb.isNotEmpty() && sb.length + sentence.length > 180) {
                out.add(sb.toString())
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(sentence)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.flatMap { if (it.length > 3500) it.chunked(3500) else listOf(it) }
    }

    private fun speak(phrase: String) {
        if (!appTtsEnabled) return
        val clean = cleanForSpeech(phrase)
        if (clean.isEmpty()) return

        ensureMaxMediaVolume()

        if (elevenLabsEnabled && elevenLabsApiKey.isNotBlank()) {
            speakWithElevenLabs(clean)
        } else {
            speakWithLocalTts(clean)
        }
    }

    /** Siguraduhing naka-max ang STREAM_MUSIC volume ng phone bago magsalita, kahit may ibang
     * app o proseso na nagpaliit dito - para gamit ang buong lakas ng volume slider. */
    private fun ensureMaxMediaVolume() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < max) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            }
        } catch (e: Exception) {
            // hindi kritikal - basta hindi mag-crash
        }
    }

    private fun speakWithLocalTts(clean: String) {
        if (!ttsReady) return
        val chunks = splitForSpeech(clean)
        if (chunks.isEmpty()) return

        ttsHandler.removeCallbacks(resumeMicRunnable)
        isSpeaking = true
        speechService?.setPause(true)

        val base = "utt_${System.currentTimeMillis()}"
        chunks.forEachIndexed { i, chunk ->
            val id = if (i == chunks.lastIndex) "${base}_last" else "${base}_$i"
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, mode, null, id)
        }
    }

    /**
     * ElevenLabs TTS via REST API - mas natural/magandang boses kaysa sa lokal na Android TTS,
     * pero may FREE TIER LIMIT (character quota kada buwan). Kapag OFF ang toggle, walang API
     * key, o may nangyaring error (naubos na quota, mali ang key, walang internet), awtomatikong
     * babalik ito sa speakWithLocalTts() - hindi tuluyang matatahimik ang robot.
     */
    private fun speakWithElevenLabs(clean: String) {
        ttsHandler.removeCallbacks(resumeMicRunnable)
        isSpeaking = true
        speechService?.setPause(true)
        elevenLabsBusy = true

        val json = JSONObject().apply {
            put("text", clean)
            put("model_id", "eleven_multilingual_v2")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
            })
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$elevenLabsVoiceId")
            .addHeader("xi-api-key", elevenLabsApiKey)
            .addHeader("Accept", "audio/mpeg")
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUi {
                    elevenLabsBusy = false
                    addVoiceLogEntry("ElevenLabs TTS", "HTTP FAILED: ${e.message} - fallback sa lokal na TTS")
                    speakWithLocalTts(clean)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    // Kadalasang dahilan dito: naubos na ang free tier (401/429) o mali ang API key/voice ID.
                    runOnUi {
                        elevenLabsBusy = false
                        addVoiceLogEntry("ElevenLabs TTS", "HTTP ${response.code} - fallback sa lokal na TTS")
                        statusText.text = "⚠️ ElevenLabs error (${response.code}) - gamit muna lokal na TTS"
                    }
                    response.close()
                    runOnUi { speakWithLocalTts(clean) }
                    return
                }

                val audioBytes = try {
                    response.body?.bytes()
                } catch (e: Exception) {
                    null
                } finally {
                    response.close()
                }

                if (audioBytes == null || audioBytes.isEmpty()) {
                    runOnUi {
                        elevenLabsBusy = false
                        addVoiceLogEntry("ElevenLabs TTS", "Walang laman ang sagot - fallback sa lokal na TTS")
                        speakWithLocalTts(clean)
                    }
                    return
                }

                runOnUi { playElevenLabsAudio(audioBytes, clean) }
            }
        })
    }

    private fun playElevenLabsAudio(audioBytes: ByteArray, fallbackText: String) {
        try {
            val tempFile = File(cacheDir, "elevenlabs_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            elevenLabsLoudnessEnhancer?.release()
            elevenLabsLoudnessEnhancer = null
            elevenLabsPlayer?.release()
            elevenLabsPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener { mp ->
                    // Dagdag na "loudness" LAGPAS sa 100% system volume - kailangan ng aktwal
                    // na audioSessionId ng MediaPlayer na ito, kaya dito lang natin ito ikakabit,
                    // pagkatapos ma-prepare, bago tumugtog.
                    if (elevenLabsVolumeBoostDb > 0) {
                        try {
                            val enhancer = LoudnessEnhancer(mp.audioSessionId)
                            enhancer.setTargetGain(elevenLabsVolumeBoostDb * 100) // dB -> millibel
                            enhancer.enabled = true
                            elevenLabsLoudnessEnhancer = enhancer
                        } catch (e: Exception) {
                            // ilang phone/OEM audio chip ay hindi sumusuporta sa effect na ito -
                            // hindi kritikal, tutuloy lang nang walang extra boost.
                        }
                    }
                    mp.start()
                }
                setOnCompletionListener {
                    elevenLabsBusy = false
                    elevenLabsLoudnessEnhancer?.release()
                    elevenLabsLoudnessEnhancer = null
                    it.release()
                    tempFile.delete()
                    scheduleMicResume()
                }
                setOnErrorListener { mp, _, _ ->
                    elevenLabsBusy = false
                    elevenLabsLoudnessEnhancer?.release()
                    elevenLabsLoudnessEnhancer = null
                    mp.release()
                    tempFile.delete()
                    scheduleMicResume()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            elevenLabsBusy = false
            addVoiceLogEntry("ElevenLabs TTS", "Playback error: ${e.message} - fallback sa lokal na TTS")
            speakWithLocalTts(fallbackText)
        }
    }

    private fun handleNoFace() {
        sendCommandThrottled("STOP")
        val now = System.currentTimeMillis()
        if (now - lastPersonSeenTime > personTimeoutMs) {
            runOnUi { showEyesUi() }
        } else {
            runOnUi { roboEyesView.setMood(RoboEyesView.Mood.SEARCHING) }
        }
    }

    private fun adjustBoxForRotation(box: Rect, frameWidth: Int, frameHeight: Int, rotationDegrees: Int): Rect {
        return when (rotationDegrees) {
            90 -> Rect(frameHeight - box.bottom, box.left, frameHeight - box.top, box.right)
            180 -> Rect(frameWidth - box.right, frameHeight - box.bottom, frameWidth - box.left, frameHeight - box.top)
            270 -> Rect(box.top, frameWidth - box.right, box.bottom, frameWidth - box.left)
            else -> box
        }
    }

    private fun computeCommand(box: Rect, frameWidth: Int): String {
        val faceWidthRatio = box.width().toFloat() / frameWidth.toFloat()
        if (faceWidthRatio > closeFaceWidthRatio) {
            return "BACKWARD"
        }

        val faceCenterX = box.centerX()
        val screenCenterX = frameWidth / 2

        val centerDeadzoneWidth = (frameWidth / 3.5 / 2).toInt()

        val leftBoundary = screenCenterX - centerDeadzoneWidth
        val rightBoundary = screenCenterX + centerDeadzoneWidth

        return when {
            faceCenterX < leftBoundary -> "RIGHT"
            faceCenterX > rightBoundary -> "LEFT"
            faceWidthRatio < tooFarFaceWidthRatio -> "STOP"
            faceWidthRatio < farFaceWidthRatio -> "FORWARD"
            else -> "STOP"
        }
    }

    private val SERVO_MAX_ANGLE = 110

    // ---------- IDLE HEAD SCAN (taas-baba ng ulo kapag 1 minutong walang nakitang tao) ----------
    // Dahan-dahang pag-taas/baba ng ulo gamit ang parehong navServoA/navServoB na naka-calibrate
    // na sa Nav settings mo (kaya awtomatikong magkatugma ang itaas/ibaba na galaw sa totoong
    // limitasyon ng servo rig mo - hindi tayo gagamit ng bagong hulaan na anggulo).
    private val personMissingScanThresholdMs = 60_000L  // 1 minuto bago magsimula ang pag-scan
    private val headScanMoveDurationMs = 3000L           // 3 segundo bawat taas o baba = 6 segundo lahat-lahat
    private val headScanStepIntervalMs = 50L             // laki ng bawat hakbang (para smooth, hindi biglaan)
    // Hiwalay na mga anggulo para sa head-scan (hindi na gamit ang navServoA/B), para malaya
    // nating ma-adjust ito nang hindi naaapektuhan ang calibration ng Nav settings mo. Base sa
    // computeServoAngle() mo: mas MATAAS na numero = mas PATAAS ang tingin. I-adjust lang itong
    // dalawang numero (0-110) kung kailangan pang i-tweak ang dating ng galaw.
    private val headScanUpAngle = 95     // mas malapit sa SERVO_MAX_ANGLE (110) = mas pataas
    private val headScanDownAngle = 15   // mas malapit sa 0 = mas pababa
    @Volatile private var headScanActive = false
    private val headScanHandler = Handler(android.os.Looper.getMainLooper())
    private var servoTopRatio: Float
        get() = prefs.getFloat("servo_top_ratio", 0.15f)
        set(value) { prefs.edit().putFloat("servo_top_ratio", value).apply() }

    private var servoBottomRatio: Float
        get() = prefs.getFloat("servo_bottom_ratio", 0.70f)
        set(value) { prefs.edit().putFloat("servo_bottom_ratio", value).apply() }

    private var smoothedServoAngle: Float = 0f

    // FACE TRACKING NG SERVO (hinto-at-hintay / step-and-settle - ang OPTIMIZED version): nasa servo
    // ang camera, kaya ang nakikita ng camera ay laging huli sa aktwal na galaw (delay ng network +
    // servo + frame). Kapag tuloy-tuloy ang pagtatama habang gumagalaw pa ang servo, nalalampasan
    // ang mukha (overshoot/likot). Kaya: isang maliit na hakbang lang bawat ~400ms, tapos HINTAY
    // munang tumigil ang servo at mag-update ang frame bago tumingin ulit.
    private val servoTargetRatio = 0.5f        // 0.5 = eksaktong gitna ng frame; 0.42 = medyo mataas (mata sa gitna)
    private val servoDirection = 1f           // kapag baliktad ang galaw, gawing -1f
    private val servoCameraFovDeg = 45f       // tinatayang vertical FOV ng camera (para gawing degrees ang error)
    private val servoStepGain = 0.65f         // gaano karaming porsyento ng error ang itatama bawat hakbang
    private val servoMaxStepDeg = 10f         // pinakamalaking hakbang bawat ~250ms (dating 5 bawat 400ms - ~3x mas mabilis)
    private val servoDeadbandRatio = 0.06f    // sakop ng "gitna" (fraction ng taas ng frame) - dito hihinto
    private val servoStepIntervalMs = 250L    // pagitan ng bawat hakbang (dating 400ms)
    private var outputServoAngle = -1f        // -1 = wala pang naitakda
    private var lastServoStepTime = 0L

    private fun computeServoAngle(box: Rect, frameHeight: Int): Int {
        val verticalRatio = box.centerY().toFloat() / frameHeight.toFloat()
        if (outputServoAngle < 0f) outputServoAngle = SERVO_MAX_ANGLE / 2f

        val now = System.currentTimeMillis()
        if (now - lastServoStepTime >= servoStepIntervalMs) {
            lastServoStepTime = now
            val error = servoTargetRatio - verticalRatio   // positibo = mukha nasa itaas ng gitna -> itaas ang servo
            if (kotlin.math.abs(error) > servoDeadbandRatio) {
                val errorDeg = error * servoCameraFovDeg * servoDirection
                val step = (errorDeg * servoStepGain).coerceIn(-servoMaxStepDeg, servoMaxStepDeg)
                outputServoAngle = (outputServoAngle + step).coerceIn(0f, SERVO_MAX_ANGLE.toFloat())
            }
        }
        smoothedServoAngle = outputServoAngle
        return Math.round(outputServoAngle)
    }

    private fun sendCommandThrottled(command: String, servoAngle: Int? = null) {
        val now = System.currentTimeMillis()
        if (now - lastSendTime < sendIntervalMs) return
        lastSendTime = now
        sendCommandToEsp32(command, servoAngle)
    }

    /**
     * Tinitignan bawat 1 segundo kung karapat-dapat nang mag-"idle head scan" ang robot -
     * 1 minutong walang nakitang tao, nasa RoboEyes/idle talaga (hindi habang may camera
     * tracking, nav mode, kumukuha ng litrato, o tumitingin kay Gemini). Kapag may natuklasan
     * na tao ulit o may ibang nangyayaring aktibidad, tumitigil agad ang scan.
     */
    // Kailan natapos (o huling na-abort) ang pinakahuling scan cycle - ginagamit para hintayin
    // muna ang isa pang buong minuto bago pumayag ng panibagong scan (hindi dapat paulit-ulit
    // agad-agad).
    private var lastHeadScanEndTime = 0L

    private val headScanWatcherRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val idleTooLong = now - lastPersonSeenTime > personMissingScanThresholdMs

            // May nakita na palang tao habang kasalukuyang nag-i-scan pa - itigil agad, huwag
            // na tapusin ang buong 4 segundong galaw.
            if (headScanActive && !idleTooLong) {
                headScanActive = false
                lastHeadScanEndTime = now
            }

            val cooldownPassed = now - lastHeadScanEndTime > personMissingScanThresholdMs
            val eligible = idleTooLong && cooldownPassed && appState == AppState.EYES && showRoboEyes &&
                !navActive && !navCalibrating && !navTestMode &&
                !pendingPhotoCapture && pendingVisionCapture == null

            if (eligible && !headScanActive) {
                headScanActive = true
                runHeadScanCycle()
            }
            headScanHandler.postDelayed(this, 1000)
        }
    }

    /** Isang beses lang na taas (navServoB) saka baba (navServoA), mga 4 segundo lahat-lahat
     * (2 segundo bawat direksyon) - pagkatapos, titigil at maghihintay ng isa pang minuto
     * (sa headScanWatcherRunnable) bago pumayag ulit ng bagong scan. */
    private fun runHeadScanCycle() {
        if (!headScanActive) return
        animateServoTo(headScanUpAngle, headScanMoveDurationMs) {
            if (!headScanActive) return@animateServoTo
            animateServoTo(headScanDownAngle, headScanMoveDurationMs) {
                // Tapos na ang isang buong cycle (~4 segundo) - itigil at i-mark kung kailan ito
                // natapos, para masimulan ang paghihintay ng isa pang minuto.
                headScanActive = false
                lastHeadScanEndTime = System.currentTimeMillis()
            }
        }
    }

    /** Dahan-dahang ililipat ang servo mula sa kasalukuyang anggulo papunta sa targetAngle sa
     * loob ng durationMs, sa pamamagitan ng maliliit na hakbang - para "dahan-dahan" talaga ang
     * galaw, hindi biglaang tumalon. */
    private fun animateServoTo(targetAngle: Int, durationMs: Long, onDone: () -> Unit) {
        val startAngle = if (outputServoAngle >= 0f) outputServoAngle else SERVO_MAX_ANGLE / 2f
        val steps = (durationMs / headScanStepIntervalMs).toInt().coerceAtLeast(1)
        var stepCount = 0

        val stepRunnable = object : Runnable {
            override fun run() {
                if (!headScanActive) return
                stepCount++
                val t = stepCount.toFloat() / steps.toFloat()
                val angle = (startAngle + (targetAngle - startAngle) * t).coerceIn(0f, SERVO_MAX_ANGLE.toFloat())
                outputServoAngle = angle
                smoothedServoAngle = angle
                sendCommandToEsp32("STOP", Math.round(angle))
                if (stepCount < steps) {
                    headScanHandler.postDelayed(this, headScanStepIntervalMs)
                } else {
                    onDone()
                }
            }
        }
        headScanHandler.post(stepRunnable)
    }

    private fun startEspHeartbeat() {
        pingHandler.removeCallbacks(pingRunnable)
        pingHandler.post(pingRunnable)
    }

    /**
     * Ang ESP32 ay sumasagot na ng "PONG|<STATE>" (hal. "PONG|MOVING"). Ginagamit ito ng app para malaman
     * kung kailan papasok/lalabas sa Camera Nav mode, kahit galing sa voice module / Bluetooth ang AUTO.
     * (Ang lumang firmware na "PONG" lang ang sagot ay hindi papasok sa nav mode.)
     */
    private fun pingEsp32() {
        if (pingInFlight) return
        pingInFlight = true
        val request = Request.Builder().url("$esp32BaseUrl/ping").build()
        navClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                pingInFlight = false
                runOnUi { onEspPingResult(null) }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = try { response.use { it.body?.string() } } catch (e: Exception) { null }
                pingInFlight = false
                runOnUi { onEspPingResult(body) }
            }
        })
    }

    private fun onEspPingResult(body: String?) {
        if (body == null) {
            pingFailCount++
            if (navActive && pingFailCount >= 5) exitNavMode("nawala ang link sa robot")
            return
        }
        pingFailCount = 0

        val state = if (body.startsWith("PONG|")) body.substringAfter("|").trim() else ""
        val inNavState = state == "MOVING" || state.startsWith("AVOIDING")

        if (inNavState) {
            navNonMovingPolls = 0
            if (!navActive && navEnabled) {
                if (obstacleAnalyzer != null) {
                    enterNavMode()
                } else {
                    // MOVING na ang robot pero wala pang depth model - ipaalam para hindi mukhang "walang nangyayari"
                    statusText.text = if (depthModelBusy) {
                        "⏳ Camera Nav: hinihintay ang depth model..."
                    } else {
                        "⚠️ Camera Nav: walang depth model (tignan ang error sa taas / i-restart ang app)"
                    }
                }
            }
        } else if (navActive) {
            navNonMovingPolls++
            if (navNonMovingPolls >= 2) exitNavMode("hindi na MOVING ang robot")
        }
    }

    private fun sendTimedCommand(command: String, durationMs: Long) {
        voiceOverrideActive = true
        val handler = Handler(mainLooper)
        val endTime = System.currentTimeMillis() + durationMs
        val runnable = object : Runnable {
            override fun run() {
                sendCommandToEsp32(command)
                if (System.currentTimeMillis() < endTime) {
                    handler.postDelayed(this, 300)
                } else {
                    sendCommandToEsp32("STOP")
                    voiceOverrideActive = false
                }
            }
        }
        handler.post(runnable)
    }

    private fun sendCommandToEsp32(command: String, servoAngle: Int? = null) {
        var url = "$esp32BaseUrl/command?dir=$command"
        if (servoAngle != null) {
            url += "&servo=$servoAngle"
        }
        val request = Request.Builder().url(url).build()

        val isAuto = command.equals("AUTO", ignoreCase = true)
        val isForceStop = command.equals("FORCE_STOP", ignoreCase = true)

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!isAuto && !isForceStop) {
                    response.close()
                    return
                }
                // Camera Nav: kapag tinanggap ng ESP32 ang AUTO -> pasok agad sa nav mode;
                // kapag FORCE_STOP -> labas agad (hindi na hihintayin ang susunod na ping)
                val body = try { response.use { it.body?.string() } } catch (e: Exception) { null }
                if (body != null && body.startsWith("OK")) {
                    runOnUi {
                        if (isAuto) enterNavMode() else exitNavMode("FORCE_STOP")
                    }
                }
            }
        })
    }

    // ---------- CAMERA NAV: pag-analyze ng frames at pagpapadala ng hints ----------

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // ---------- TAKE A PICTURE ----------

    /**
     * Nag-a-apply ng "transition cue" (DFPlayer track sa robot + TTS sa phone) tuwing may
     * paglilipat ng display (mata <-> camera) o pagkuha ng litrato. Kung may custom command sa
     * CommandStore na ang trigger ay "nakapaloob" sa canonicalKey (dinagdag mo via app "Mga
     * Utos"), gagamitin ang sarili niyang action/reply sa halip na default - configurable
     * nang walang kailangang mag-rebuild ng app.
     */
    private fun playTransitionCue(canonicalKey: String, defaultAction: String, defaultReply: String) {
        val custom = commandStore.findMatch(canonicalKey)
        val action = custom?.action?.takeIf { it.isNotBlank() } ?: defaultAction
        val customReply = custom?.randomReply()?.takeIf { it.isNotBlank() }

        executeEsp32Actions(action)

        when {
            // Sarili mong na-configure na linya (hindi naka-hardcode ng app) - laging ito muna.
            customReply != null -> speak(customReply)
            // Walang custom override at naka-ON ang Gemini - subukang gawing bago/iba-iba.
            geminiEnabled -> sayDynamicOrFallback(situationCueFor(canonicalKey), defaultReply)
            else -> speak(defaultReply)
        }
    }

    /**
     * Kagaya ng playTransitionCue() pero WALANG DFPlayer/ESP32 track - TTS na lang. Ginagamit
     * ito SPESIPIKO sa paglipat PABALIK sa mata (RoboEyes): ang DFPlayer track 47 ay naka-laan
     * na lang sa paglipat PAPUNTANG camera, para may malinaw na pagkakaiba ang dalawang
     * direksyon ng transition.
     */
    private fun speakTransitionCue(canonicalKey: String, defaultReply: String) {
        val custom = commandStore.findMatch(canonicalKey)
        val customReply = custom?.randomReply()?.takeIf { it.isNotBlank() }

        when {
            customReply != null -> speak(customReply)
            geminiEnabled -> sayDynamicOrFallback(situationCueFor(canonicalKey), defaultReply)
            else -> speak(defaultReply)
        }
    }

    /** Maikling paglalarawan ng sitwasyon, ipinapasa kay Gemini (sayLine) para bumuo ng bagong
     * linya tungkol sa kasalukuyang ginagawang transition. */
    private fun situationCueFor(canonicalKey: String): String = when (canonicalKey) {
        "ipakita ang camera" ->
            "Lilipat ka ngayon mula sa mata/idle screen mo papuntang live camera view, dahil " +
                "inutusan kang ipakita ang camera - sabihin mo ito nang maikli at masaya, parang " +
                "totoong inaanunsyo mo habang ginagawa mo."
        "balik sa mata" ->
            "Babalik ka ngayon mula sa camera view papuntang mata/idle screen mo - sabihin mo " +
                "ito nang maikli at natural."
        "kuha ng litrato" ->
            "Kukuha ka ngayon ng litrato gamit ang camera mo - sabihin mo ito nang masaya bago " +
                "ka kumuha, parang totoong inaanunsyo mo."
        else ->
            "Sabihin mo ang isang maikli at masayang linya tungkol sa ginagawa mong aksyon ngayon."
    }

    /**
     * "kuha ng litrato" / "take a picture" -> lumilipat sa camera (kung nasa RoboEyes pa),
     * nagpapatugtog ng DFPlayer track bilang audio cue habang naglilipat, tapos kukunin ang
     * susunod na camera frame bilang litrato.
     */
    private fun tryHandlePhotoCommand(candidates: List<String>): String? {
        for (text in candidates) {
            if (photoTriggerPhrases.any { text.contains(it) }) {
                takePictureWithTransition()
                return "take_picture"
            }
        }
        return null
    }

    private fun takePictureWithTransition() {
        if (showRoboEyes) {
            showRoboEyes = false
            applyDisplayMode()
        }
        playTransitionCue("kuha ng litrato", "dfplayer play 47", "Kunan na kita ng litrato, ngiti ka!")
        // bigyan ng oras ang display na maka-switch sa camera preview bago kunin ang frame
        rootLayout.postDelayed({ pendingPhotoCapture = true }, 600)
    }

    private fun capturePhotoFromFrame(imageProxy: ImageProxy) {
        pendingPhotoCapture = false
        try {
            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
            val upright = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            runOnUi { showCapturedPhoto(upright) }
            savePhotoToGallery(upright)
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUi { statusText.text = "Hindi nakuha ang litrato (${e.javaClass.simpleName})" }
        } finally {
            imageProxy.close()
        }
    }

    private fun showCapturedPhoto(bitmap: Bitmap) {
        capturedPhotoView.setImageBitmap(bitmap)
        capturedPhotoView.visibility = View.VISIBLE
        // ipakita ng 5 segundo, tapos itago at bumalik sa mata (RoboEyes) - hindi lang basta
        // ibabalik sa live camera preview.
        rootLayout.postDelayed({
            capturedPhotoView.visibility = View.GONE
            if (!showRoboEyes) {
                showRoboEyes = true
                applyDisplayMode()
                speakTransitionCue("balik sa mata", "Sige, babalik na sa mata.")
            }
        }, 5000)
    }

    private fun savePhotoToGallery(bitmap: Bitmap) {
        try {
            val filename = "Rustech_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Rustech")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Kagaya ng capturePhotoFromFrame() pero para sa "paningin" ni Gemini - hindi ise-save sa
     * gallery o ipapakita sa screen, dadaan lang sa onCaptured() callback papunta kay Gemini. */
    private fun captureFrameForVision(imageProxy: ImageProxy, onCaptured: (Bitmap) -> Unit) {
        try {
            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
            val upright = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            runOnUi { onCaptured(upright) }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUi {
                geminiBusy = false
                statusText.text = "Hindi nakuha ang larawan para kay Gemini (${e.javaClass.simpleName})"
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun processNavFrame(imageProxy: ImageProxy) {
        val analyzer = obstacleAnalyzer
        val now = System.currentTimeMillis()
        if (analyzer == null || now - lastNavAnalysisTime < navAnalysisIntervalMs || now < navServoSettleUntil) {
            imageProxy.close()
            return
        }
        lastNavAnalysisTime = now

        try {
            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
            val upright = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            // Sa test/calibrate, laging tilt A lang ang gamit. Sa totoong nav, sumusunod sa kasalukuyang servo pose.
            val pose = if (navScanEnabled && navActive && !navCalibrating && !navTestMode) navPose else 0
            val cfg = ObstacleAnalyzer.Config(
                blockThreshold = navBlockThreshold,
                rowTop = navRowTopPercent / 100f,
                rowBottom = navRowBottomPercent / 100f
            )
            val result = analyzer.analyze(upright, pose, cfg, now)
            if (result != null) runOnUi { onNavResult(result) }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUi { statusText.text = "NAV error: ${e.javaClass.simpleName}: ${e.message}" }
        } finally {
            imageProxy.close()
        }
    }

    private fun onNavResult(r: ObstacleAnalyzer.Result) {
        val now = System.currentTimeMillis()

        val label = when (r.decision) {
            ObstacleAnalyzer.Decision.CLEAR -> "TULOY"
            ObstacleAnalyzer.Decision.LEFT -> "LIKO KALIWA"
            ObstacleAnalyzer.Decision.RIGHT -> "LIKO KANAN"
            ObstacleAnalyzer.Decision.BACK -> "ATRAS"
        }
        val prefix = when {
            navCalibrating -> "🎯 CALIBRATE"
            navTestMode -> "🧪 TEST"
            else -> "🧭 NAV"
        }
        statusText.text = String.format(
            Locale.US, "%s  L%.2f  C%.2f  R%.2f  (T%.2f)  servo %d → %s%s",
            prefix, r.left, r.center, r.right, navBlockThreshold,
            if (navScanEnabled && navPose == 1) navServoB else navServoA,
            label, if (r.flat) "  [flat]" else ""
        )

        if (navCalibrating) {
            navCalibSamples.add(r.center)
            if (now >= navCalibEndTime) finishNavCalibration()
            return
        }

        if (navActive && !navTestMode) {
            navLastDecision = r.decision
            navLastDecisionTime = now

            // Servo scan: palitan ang tilt A <-> B; hintayin munang tumigil ang servo bago mag-analyze ulit
            if (navScanEnabled && now - lastNavPoseSwitch > navPoseSwitchMs) {
                navPose = 1 - navPose
                lastNavPoseSwitch = now
                navServoSettleUntil = now + 500L
            }
        }
    }

    private fun sendNavHint(decision: ObstacleAnalyzer.Decision) {
        val name = when (decision) {
            ObstacleAnalyzer.Decision.CLEAR -> "NAV_CLEAR"
            ObstacleAnalyzer.Decision.BACK -> "NAV_BACK"
            ObstacleAnalyzer.Decision.LEFT -> if (navInvert) "NAV_RIGHT" else "NAV_LEFT"
            ObstacleAnalyzer.Decision.RIGHT -> if (navInvert) "NAV_LEFT" else "NAV_RIGHT"
        }
        val angle = if (navScanEnabled && navPose == 1) navServoB else navServoA
        val now = System.currentTimeMillis()

        // Ipadala agad kapag nagbago ang desisyon/servo; kung pareho pa rin, keep-alive lang bawat ~400ms
        val changed = name != lastNavHintName || angle != lastNavServoAngle
        if (!changed && now - lastNavHintSentTime < navKeepAliveMs) return
        // Huwag mag-pile up: kapag may hint pang hinihintay ang sagot, laktawan ito (susunod na cycle na)
        if (navHintInFlight) return

        lastNavHintName = name
        lastNavHintSentTime = now
        lastNavServoAngle = angle
        navHintInFlight = true

        val request = Request.Builder().url("$esp32BaseUrl/command?dir=$name&servo=$angle").build()
        navClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                navHintInFlight = false
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                navHintInFlight = false
            }
        })
    }

    private fun enterNavMode() {
        if (navActive || !navEnabled) return
        val analyzer = obstacleAnalyzer ?: return
        val now = System.currentTimeMillis()

        analyzer.reset()
        navActive = true
        navNonMovingPolls = 0
        navPose = 0
        lastNavPoseSwitch = now
        navLastDecision = ObstacleAnalyzer.Decision.CLEAR
        navLastDecisionTime = now
        lastNavHintName = ""
        lastNavHintSentTime = 0L
        navHintInFlight = false
        navServoSettleUntil = now + 500L
        roboEyesView.setMood(RoboEyesView.Mood.ALERT)
        statusText.text = "🧭 NAV mode: camera obstacle avoidance"

        navHandler.removeCallbacksAndMessages(null)
        navHandler.post(navSendRunnable)
    }

    private fun exitNavMode(reason: String) {
        if (!navActive) return
        navActive = false
        navHandler.removeCallbacksAndMessages(null)
        smoothedServoAngle = lastNavServoAngle.toFloat()
        outputServoAngle = smoothedServoAngle
        showEyesUi()
        statusText.text = "🧭 NAV off ($reason)"
    }

    private fun startNavTest() {
        if (obstacleAnalyzer == null) {
            statusText.text = "❌ Wala pang depth model (hintayin ang download/load)"
            return
        }
        obstacleAnalyzer?.reset()
        navTestUntil = System.currentTimeMillis() + 180_000L
        navTestMode = true
        sendCommandToEsp32("STOP", navServoA) // itakda ang tilt ng camera (gumagana kapag BOOT_WAIT)
        statusText.text = "🧪 Nav TEST (3 min): tignan ang L/C/R, walang utos sa robot"
    }

    private fun stopNavTest() {
        if (!navTestMode) return
        navTestMode = false
        if (!navActive) showEyesUi()
    }

    private fun startNavCalibration() {
        if (obstacleAnalyzer == null) {
            statusText.text = "❌ Wala pang depth model (hintayin ang download/load)"
            return
        }
        navTestMode = false
        statusText.text = "🎯 Calibrate: ilagay ang robot ~30cm sa harap ng harang. Magsisimula sa 4 segundo..."
        sendCommandToEsp32("STOP", navServoA) // itakda ang tilt ng camera (gumagana kapag BOOT_WAIT)
        rootLayout.postDelayed({
            obstacleAnalyzer?.reset()
            navCalibSamples.clear()
            navCalibEndTime = System.currentTimeMillis() + 2500L
            navCalibrating = true
        }, 4000L)
    }

    private fun finishNavCalibration() {
        navCalibrating = false
        if (navCalibSamples.size < 3) {
            statusText.text = "❌ Calibrate: kulang ang samples, subukan ulit"
            return
        }
        val avg = navCalibSamples.average().toFloat()
        navBlockThreshold = (avg * 0.92f).coerceIn(0.50f, 0.95f)
        obstacleAnalyzer?.reset()
        showEyesUi()
        statusText.text = String.format(
            Locale.US, "🎯 Na-calibrate: danger threshold = %.2f (center avg %.2f)", navBlockThreshold, avg
        )
    }

    // ---------- CAMERA NAV: depth model (MiDaS small) - auto-download isang beses ----------

    private fun setupDepthModel() {
        if (depthModelBusy || obstacleAnalyzer != null) return
        depthModelBusy = true
        Thread {
            try {
                val modelFile = File(filesDir, depthModelFileName)
                if (!modelFile.exists() || modelFile.length() < depthModelMinBytes) {
                    // 1) kung nilagay mo sa assets/midas_small.tflite, iyon ang gagamitin
                    // 2) kung wala, i-download mula sa GitHub (~66MB, isang beses lang)
                    if (!tryCopyDepthModelFromAssets(modelFile)) {
                        downloadDepthModel(modelFile)
                    }
                }
                obstacleAnalyzer = ObstacleAnalyzer(modelFile)
                runOnUi { statusText.text = "🧭 Depth model handa (camera obstacle avoidance)" }
            } catch (e: Throwable) {
                e.printStackTrace()
                runOnUi { statusText.text = "❌ Depth model: ${e.javaClass.simpleName}: ${e.message}" }
            } finally {
                depthModelBusy = false
            }
        }.start()
    }

    private fun tryCopyDepthModelFromAssets(target: File): Boolean {
        return try {
            assets.open(depthModelFileName).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target.length() >= depthModelMinBytes
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadDepthModel(target: File) {
        runOnUi { statusText.text = "⬇️ Dina-download ang depth model (~66MB, isang beses lang)..." }

        val partFile = File(filesDir, "$depthModelFileName.part")
        val request = Request.Builder().url(depthModelUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val body = response.body ?: throw java.io.IOException("Walang response body")

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            var lastUpdate = 0L

            body.byteStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val now = System.currentTimeMillis()
                        if (totalBytes > 0 && now - lastUpdate > 500) {
                            lastUpdate = now
                            val percent = (downloadedBytes * 100 / totalBytes).toInt()
                            runOnUi { statusText.text = "⬇️ Dina-download ang depth model... $percent%" }
                        }
                    }
                }
            }
        }

        if (partFile.length() < depthModelMinBytes) {
            partFile.delete()
            throw java.io.IOException("Kulang ang na-download na model file")
        }
        target.delete()
        if (!partFile.renameTo(target)) throw java.io.IOException("Hindi ma-save ang depth model")
    }

    private fun showNavSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val modelStatus = TextView(this).apply {
            text = if (obstacleAnalyzer != null) {
                "✅ Depth model: handa"
            } else {
                "⏳ Depth model: hindi pa handa (dina-download / nilo-load...)"
            }
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }

        val enabledSwitch = Switch(this).apply {
            text = "🧭  Gamitin ang camera bilang obstacle sensor (habang AUTO)"
            isChecked = navEnabled
            setPadding(0, 16, 0, 16)
        }
        val scanSwitch = Switch(this).apply {
            text = "↕️  Servo scan (palitan ang tilt A ↔ B)"
            isChecked = navScanEnabled
            setPadding(0, 16, 0, 16)
        }
        val invertSwitch = Switch(this).apply {
            text = "↔️  I-invert ang kaliwa/kanan (kung mali ang liko)"
            isChecked = navInvert
            setPadding(0, 16, 0, 16)
        }
        val testSwitch = Switch(this).apply {
            text = "🧪  Test mode (live scores, WALANG utos sa robot, 3 min)"
            isChecked = navTestMode
            setPadding(0, 16, 0, 16)
        }

        val servoAInput = EditText(this).apply {
            hint = "Servo tilt A (0-110), default 30"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(navServoA.toString())
        }
        val servoBInput = EditText(this).apply {
            hint = "Servo tilt B (0-110), default 55"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(navServoB.toString())
        }
        val thresholdInput = EditText(this).apply {
            hint = "Danger threshold (0.50-0.95), default 0.75"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", navBlockThreshold))
        }

        val rowTopInput = EditText(this).apply {
            hint = "Band itaas % (default 30)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(navRowTopPercent.toString())
        }
        val rowBottomInput = EditText(this).apply {
            hint = "Band baba % (default 75)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(navRowBottomPercent.toString())
        }

        val calibrateButton = Button(this).apply {
            text = "🎯  Calibrate danger (harang ~30cm)"
            isAllCaps = false
        }

        container.addView(modelStatus)
        container.addView(enabledSwitch)
        container.addView(scanSwitch)
        container.addView(invertSwitch)
        container.addView(testSwitch)
        container.addView(TextView(this).apply { text = "Tilt A (nakatingin pababa / malapit na sahig):"; setPadding(0, 24, 0, 0) })
        container.addView(servoAInput)
        container.addView(TextView(this).apply { text = "Tilt B (mas nakatingin sa unahan / malayo):"; setPadding(0, 24, 0, 0) })
        container.addView(servoBInput)
        container.addView(TextView(this).apply { text = "Danger threshold (mas mababa = mas maaga mag-iwas):"; setPadding(0, 24, 0, 0) })
        container.addView(thresholdInput)
        container.addView(TextView(this).apply { text = "Band na sinusuri (% ng taas ng larawan, 0 = itaas): itaas at baba"; setPadding(0, 24, 0, 0) })
        container.addView(rowTopInput)
        container.addView(rowBottomInput)
        container.addView(TextView(this).apply {
            text = "Kung hindi makatingin pababa ang camera at kisame/pader ang nakikita, ibaba ang band (hal. 45 at 90)."
            textSize = 11f
        })
        container.addView(calibrateButton)
        container.addView(TextView(this).apply {
            text = "Tip: sa Test mode, tignan ang L / C / R sa taas. Ilagay ang robot ~30cm sa harap ng harang - " +
                "dapat lumampas sa threshold ang C. Sa bukas na daan, dapat mas mababa ang C. " +
                "Kung baligtad ang liko ng robot, i-ON ang Invert. Ang score ay RELATIVE, kaya i-Calibrate kapag nagpalit ng sahig/ilaw."
            textSize = 11f
            setPadding(0, 16, 0, 0)
        })

        val scrollView = ScrollView(this).apply { addView(container) }

        var dialogRef: android.app.Dialog? = null
        calibrateButton.setOnClickListener {
            dialogRef?.dismiss()
            startNavCalibration()
        }

        dialogRef = ModernDialog()
            .setTitle("🧭 Camera Nav (obstacle avoidance)")
            .setView(scrollView)
            .setPositiveButton("I-save") { _, _ ->
                navEnabled = enabledSwitch.isChecked
                navScanEnabled = scanSwitch.isChecked
                navInvert = invertSwitch.isChecked
                servoAInput.text.toString().toIntOrNull()?.let { navServoA = it }
                servoBInput.text.toString().toIntOrNull()?.let { navServoB = it }
                thresholdInput.text.toString().toFloatOrNull()?.let { navBlockThreshold = it }
                val newTop = rowTopInput.text.toString().toIntOrNull()
                val newBottom = rowBottomInput.text.toString().toIntOrNull()
                if (newTop != null && newBottom != null && newBottom - newTop >= 15) {
                    navRowTopPercent = newTop
                    navRowBottomPercent = newBottom
                }

                if (testSwitch.isChecked) startNavTest() else stopNavTest()
                if (!navEnabled && navActive) exitNavMode("naka-OFF ang Camera Nav")
                if (!testSwitch.isChecked) statusText.text = "Na-save ang Camera Nav settings"
            }
            .setNegativeButton("Isara", null)
            .showImmersive()
    }

    // ---------- Enroll UI ----------

    private fun showGeminiSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val enabledSwitch = Switch(this).apply {
            text = "Gamitin si Gemini (matalinong usapan)"
            isChecked = geminiEnabled
            setPadding(0, 8, 0, 24)
        }
        val ttsSwitch = Switch(this).apply {
            text = "🔊 Gamitin ang App TTS (Filipino voice)"
            isChecked = appTtsEnabled
            setPadding(0, 0, 0, 8)
        }
        val ttsNote = TextView(this).apply {
            text = "OFF ito kapag ang boses ng ESP32/DFPlayer na ang gagamitin, para hindi magsabay ang dalawang audio."
            textSize = 11f
            setPadding(0, 0, 0, 24)
        }
        val elevenLabsSwitch = Switch(this).apply {
            text = "🎙️ Gamitin ang ElevenLabs (mas natural na boses)"
            isChecked = elevenLabsEnabled
            setPadding(0, 0, 0, 8)
        }
        val elevenLabsNote = TextView(this).apply {
            text = "May FREE TIER LIMIT ang ElevenLabs (character quota kada buwan). Pag naubos na o may error, " +
                "AWTOMATIKONG babalik sa lokal na Android TTS habang naka-ON pa rin ang switch na ito - " +
                "pero pwede mo ring i-OFF dito anumang oras para talagang bumalik sa lokal na TTS."
            textSize = 11f
            setPadding(0, 0, 0, 16)
        }
        val elevenLabsKeyLabel = TextView(this).apply { text = "ElevenLabs API key (kunin sa elevenlabs.io):" }
        val elevenLabsKeyInput = EditText(this).apply {
            hint = "sk_..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(elevenLabsApiKey)
            setSingleLine(true)
        }
        val elevenLabsVoiceLabel = TextView(this).apply {
            text = "Voice ID (default: Rachel):"
            setPadding(0, 16, 0, 0)
        }
        val elevenLabsVoiceInput = EditText(this).apply {
            hint = DEFAULT_ELEVENLABS_VOICE_ID
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(elevenLabsVoiceId)
            setSingleLine(true)
            setPadding(0, 0, 0, 24)
        }
        val elevenLabsBoostLabel = TextView(this).apply {
            text = "🔊 Extra Volume Boost (dB, 0-40 - lagpas sa system volume):"
        }
        val elevenLabsBoostInput = EditText(this).apply {
            hint = "10"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(elevenLabsVolumeBoostDb.toString())
            setSingleLine(true)
        }
        val elevenLabsBoostNote = TextView(this).apply {
            text = "0 = walang dagdag na boost. Mas mataas = mas malakas, pero posibleng magka-distortion/" +
                "crackle depende sa speaker ng phone mo - subukan lang at hanapin ang sweet spot."
            textSize = 11f
            setPadding(0, 0, 0, 24)
        }
        val usageText = TextView(this).apply {
            text = "📊 Nagamit ngayong araw: ${geminiUsedToday()} / $geminiDailyLimit requests\n(nagre-reset ~3 PM oras sa Pilipinas)"
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }
        val limitLabel = TextView(this).apply {
            text = "Daily limit (tingnan sa AI Studio, hal. 500):"
            setPadding(0, 24, 0, 0)
        }
        val limitInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(geminiDailyLimit.toString())
            setSingleLine(true)
        }
        val keyLabel = TextView(this).apply { text = "API key (kunin sa aistudio.google.com):" }
        val keyInput = EditText(this).apply {
            hint = "AIza..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(geminiApiKey)
            setSingleLine(true)
        }
        val modelLabel = TextView(this).apply {
            text = "Model:"
            setPadding(0, 24, 0, 0)
        }
        val modelInput = EditText(this).apply {
            hint = GeminiBrain.DEFAULT_MODEL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(geminiModel)
            setSingleLine(true)
        }
        val note = TextView(this).apply {
            text = "Ang STOP ay laging lokal at agad. Kapag walang internet o naubos ang free quota, babalik sa dating lokal na mga utos."
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }

        container.addView(enabledSwitch)
        container.addView(ttsSwitch)
        container.addView(ttsNote)
        container.addView(elevenLabsSwitch)
        container.addView(elevenLabsNote)
        container.addView(elevenLabsKeyLabel)
        container.addView(elevenLabsKeyInput)
        container.addView(elevenLabsVoiceLabel)
        container.addView(elevenLabsVoiceInput)
        container.addView(elevenLabsBoostLabel)
        container.addView(elevenLabsBoostInput)
        container.addView(elevenLabsBoostNote)
        container.addView(usageText)
        container.addView(keyLabel)
        container.addView(keyInput)
        container.addView(modelLabel)
        container.addView(modelInput)
        container.addView(limitLabel)
        container.addView(limitInput)
        container.addView(note)

        ModernDialog()
            .setTitle("🧠 Gemini AI")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                geminiEnabled = enabledSwitch.isChecked
                appTtsEnabled = ttsSwitch.isChecked
                elevenLabsEnabled = elevenLabsSwitch.isChecked
                elevenLabsApiKey = elevenLabsKeyInput.text.toString()
                elevenLabsVoiceId = elevenLabsVoiceInput.text.toString()
                elevenLabsBoostInput.text.toString().trim().toIntOrNull()?.let { elevenLabsVolumeBoostDb = it }
                geminiApiKey = keyInput.text.toString()
                geminiModel = modelInput.text.toString()
                limitInput.text.toString().trim().toIntOrNull()?.let { geminiDailyLimit = it }
                geminiCooldownUntil = 0L
                statusText.text = if (geminiEnabled && geminiApiKey.isNotBlank())
                    "🧠 Gemini naka-on (${geminiModel})" else "🧠 Gemini naka-off"
            }
            .setNeutralButton("I-reset ang usapan") { _, _ ->
                geminiBrain.resetConversation()
                statusText.text = "🧠 Nabura na ang memorya ng usapan"
            }
            .setNegativeButton("Cancel", null)
            .showImmersive()
    }

    private fun showIpSettingDialog() {
        val input = EditText(this).apply {
            hint = "hal. 192.168.1.25 o 192.168.43.100"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(esp32BaseUrl.removePrefix("http://"))
        }

        ModernDialog()
            .setTitle("I-set ang IP Address ng Robot")
            .setMessage("Tignan sa OLED screen ng robot o Serial Monitor ang kasalukuyang IP nito bago i-save.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newIp = input.text.toString().trim()
                if (newIp.isNotEmpty()) {
                    esp32BaseUrl = newIp
                    statusText.text = "IP na-update: $newIp"
                }
            }
            .setNegativeButton("Cancel", null)
            .showImmersive()
    }

    private fun showDistanceSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val closeInput = EditText(this).apply {
            hint = "close (default 0.40)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(closeFaceWidthRatio.toString())
        }
        val farInput = EditText(this).apply {
            hint = "far (default 0.23)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(farFaceWidthRatio.toString())
        }
        val tooFarInput = EditText(this).apply {
            hint = "too far (default 0.15)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(tooFarFaceWidthRatio.toString())
        }

        container.addView(TextView(this).apply { text = "Close (BACKWARD kapag lumagpas dito):" })
        container.addView(closeInput)
        container.addView(TextView(this).apply { text = "Far (FORWARD kapag mas mababa dito):"; setPadding(0, 24, 0, 0) })
        container.addView(farInput)
        container.addView(TextView(this).apply { text = "Too Far (STOP/give up kapag mas mababa dito):"; setPadding(0, 24, 0, 0) })
        container.addView(tooFarInput)
        container.addView(TextView(this).apply {
            text = "Tip: dapat close > far > too far. Mas mababa = mas maagang mag-react ang robot."
            textSize = 11f
            setPadding(0, 16, 0, 0)
        })

        val scrollView = ScrollView(this).apply { addView(container) }

        ModernDialog()
            .setTitle("📏 Distance Settings")
            .setView(scrollView)
            .setPositiveButton("I-save") { _, _ ->
                val newClose = closeInput.text.toString().toFloatOrNull()
                val newFar = farInput.text.toString().toFloatOrNull()
                val newTooFar = tooFarInput.text.toString().toFloatOrNull()

                if (newClose != null && newFar != null && newTooFar != null &&
                    newClose > newFar && newFar > newTooFar
                ) {
                    closeFaceWidthRatio = newClose
                    farFaceWidthRatio = newFar
                    tooFarFaceWidthRatio = newTooFar
                    statusText.text = "Na-update ang distance settings"
                } else {
                    statusText.text = "Invalid values — dapat close > far > too far"
                }
            }
            .setNeutralButton("I-reset sa default") { _, _ ->
                closeFaceWidthRatio = 0.40f
                farFaceWidthRatio = 0.23f
                tooFarFaceWidthRatio = 0.15f
                statusText.text = "Na-reset sa default ang distance settings"
            }
            .setNegativeButton("Cancel", null)
            .showImmersive()
    }

    private fun showMicSensitivityDialog() {
        val input = EditText(this).apply {
            hint = "0-100 (default 50)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((micConfidenceThreshold * 100).toInt().toString())
        }

        ModernDialog()
            .setTitle("🎤 Mic Sensitivity")
            .setMessage("Minimum confidence (%) bago ituring na valid ang narinig. Mas mataas = mas mahigpit/malinaw kailangan sabihin.")
            .setView(input)
            .setPositiveButton("I-save") { _, _ ->
                val percent = input.text.toString().toIntOrNull()
                if (percent != null && percent in 0..100) {
                    micConfidenceThreshold = percent / 100f
                    statusText.text = "Mic sensitivity na-update: $percent%"
                } else {
                    statusText.text = "Invalid value — dapat 0-100"
                }
            }
            .setNeutralButton("I-reset sa default (50%)") { _, _ ->
                micConfidenceThreshold = 0.5f
                statusText.text = "Na-reset sa default ang mic sensitivity"
            }
            .setNegativeButton("Cancel", null)
            .showImmersive()
    }

    private fun showEnrollDialog() {
        val embedding = lastUnknownFaceEmbedding
        if (embedding == null) {
            statusText.text = "Wala pang mukha na nakuha, subukan ulit"
            return
        }

        val input = EditText(this).apply {
            hint = "Pangalan (hal. Rusty)"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        ModernDialog()
            .setTitle("Mag-enroll ng mukha")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    faceStore.enroll(name, embedding)
                    statusText.text = "Na-enroll: $name"
                    canEnroll = false
                    lastUnknownFaceEmbedding = null
                }
            }
            .setNegativeButton("Cancel", null)
            .showImmersive()
    }

    private fun showManageCommandsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val existing = commandStore.all()
        if (existing.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Wala pang custom na command."
                setPadding(0, 0, 0, 24)
            })
        } else {
            for (cmd in existing) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this@MainActivity).apply {
                    val actionPart = if (cmd.action.isNotBlank()) " [ESP32: ${cmd.action}]" else ""
                    val replyDisplay = cmd.reply.replace("||", " / ")
                    val triggerDisplay = cmd.trigger.replace("||", " / ")
                    text = "\"$triggerDisplay\" -> \"$replyDisplay\"$actionPart"
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this@MainActivity).apply {
                    text = "I-edit"
                    textSize = 10f
                    setOnClickListener {
                        showEditCommandDialog(cmd)
                    }
                })
                row.addView(Button(this@MainActivity).apply {
                    text = "Tanggalin"
                    textSize = 10f
                    setOnClickListener {
                        commandStore.remove(cmd.trigger)
                        showManageCommandsDialog()
                    }
                })
                container.addView(row)
            }
        }

        container.addView(View(this).apply {
            setBackgroundColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                .apply { topMargin = 32; bottomMargin = 32 }
        })

        container.addView(TextView(this).apply { text = "Magdagdag ng bagong command:" })

        val triggerInput = EditText(this).apply {
            hint = "Sasabihin (hiwalayin ng || kung ibat-ibang paraan ng pagsabi)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(triggerInput)
        container.addView(
            TextView(this).apply {
                text = "Tip: pwede maglagay ng ibat-ibang paraan ng pagsabi na pinaghihiwalay ng || (hal. \"abante||punta ka sa unahan||forward\") - kahit alin ang sabihin, tutugma pa rin sa parehong command."
                textSize = 11f
                setPadding(0, 4, 0, 12)
            }
        )
        val replyInput = EditText(this).apply {
            hint = "Isasagot ng robot (hiwalayin ng || kung gusto ng ilang variation)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val actionInput = EditText(this).apply {
            hint = "ESP32 action (opsyonal - hal. LEFT, RIGHT, STOP, dfplayer play N - hiwalayin ng || kung gusto ng sabay)"
            // Walang autocapitalize/autocorrect - ang mga ESP32 param na gaya ng "track=" ay
            // case-sensitive, kaya delikado kung baguhin ng keyboard ang unang letra.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        container.addView(replyInput)
        container.addView(
            TextView(this).apply {
                text = "Tip: pwede maglagay ng maraming sagot na pinaghihiwalay ng || (hal. \"sige||ok sige||heto na\") - random na pipiliin ng robot para di paulit-ulit."
                textSize = 11f
                setPadding(0, 4, 0, 12)
            }
        )
        container.addView(actionInput)

        val scrollView = ScrollView(this).apply { addView(container) }

        ModernDialog()
            .setTitle("Mga Voice Command")
            .setView(scrollView)
            .setPositiveButton("Idagdag") { _, _ ->
                val trigger = triggerInput.text.toString().trim()
                val reply = replyInput.text.toString().trim()
                val action = actionInput.text.toString().trim()
                if (trigger.isNotEmpty() && reply.isNotEmpty()) {
                    commandStore.add(trigger, reply, action)
                    statusText.text = "Idinagdag na command: \"$trigger\""
                }
            }
            .setNegativeButton("Isara", null)
            .showImmersive()
    }

    private fun showEditCommandDialog(cmd: CommandStore.VoiceCommand) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val triggerInput = EditText(this).apply {
            hint = "Sasabihin"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(cmd.trigger)
        }
        val replyInput = EditText(this).apply {
            hint = "Isasagot ng robot"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(cmd.reply)
        }
        val actionInput = EditText(this).apply {
            hint = "ESP32 action (opsyonal)"
            // Walang autocapitalize/autocorrect - case-sensitive ang mga ESP32 param.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(cmd.action)
        }
        container.addView(TextView(this).apply { text = "Sasabihin (hiwalayin ng || kung ibat-ibang paraan ng pagsabi):" })
        container.addView(triggerInput)
        container.addView(TextView(this).apply { text = "Isasagot ng robot:"; setPadding(0, 24, 0, 0) })
        container.addView(replyInput)
        container.addView(TextView(this).apply { text = "ESP32 action:"; setPadding(0, 24, 0, 0) })
        container.addView(actionInput)

        val scrollView = ScrollView(this).apply { addView(container) }

        ModernDialog()
            .setTitle("I-edit ang Command")
            .setView(scrollView)
            .setPositiveButton("I-save") { _, _ ->
                val newTrigger = triggerInput.text.toString().trim()
                val newReply = replyInput.text.toString().trim()
                val newAction = actionInput.text.toString().trim()
                if (newTrigger.isNotEmpty() && newReply.isNotEmpty()) {
                    if (newTrigger != cmd.trigger) {
                        commandStore.remove(cmd.trigger)
                    }
                    commandStore.add(newTrigger, newReply, newAction)
                    statusText.text = "Na-update: \"$newTrigger\""
                }
                showManageCommandsDialog()
            }
            .setNegativeButton("Cancel") { _, _ -> showManageCommandsDialog() }
            .showImmersive()
    }

    override fun onDestroy() {
        super.onDestroy()
        pingHandler.removeCallbacksAndMessages(null)
        navHandler.removeCallbacksAndMessages(null)
        headScanActive = false
        headScanHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        faceDetector.close()
        yoloDetector.close()
        faceEmbedder.close()
        obstacleAnalyzer?.close()
        ttsHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        elevenLabsLoudnessEnhancer?.release()
        elevenLabsPlayer?.release()
        speechService?.stop()
        speechService?.shutdown()
    }
}
