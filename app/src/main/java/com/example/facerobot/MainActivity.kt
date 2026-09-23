package com.example.facerobot

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import okhttp3.Request
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
    private lateinit var statusText: TextView
    private lateinit var menuButton: Button
    private var canEnroll = false

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

    private var lastPersonSeenTime = 0L
    private var faceTooClose = false
    private val personTimeoutMs = 4000L

    private var lastUnknownFaceEmbedding: FloatArray? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Google TTS ang preferred engine para sa Filipino (fil-PH) voice; kapag wala, default engine ng phone.
    private val preferredTtsEngine = "com.google.android.tts"
    private val ttsHandler = Handler(android.os.Looper.getMainLooper())
    private val resumeMicRunnable = Runnable {
        isSpeaking = false
        speechService?.setPause(false)
    }

    // ---------- GEMINI (utak ni RUSTECH) ----------
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
    @Volatile private var geminiBusy = false
    private var lastGeminiRequestTime = 0L
    private val geminiMinIntervalMs = 1500L      // iwas-spam sa free-tier quota (ingay/TV)
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
        showEyesUi()
        startEspHeartbeat()
        setupDepthModel()

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
            stateListAnimator = null
            elevation = 10f
            background = makeRippleRoundedDrawable(darkChip, darkChipPressed, 200f)
            setOnClickListener { showMainMenuDialog() }
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
        rootLayout.addView(
            statusText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 40 }
        )
        rootLayout.addView(
            menuButton,
            FrameLayout.LayoutParams(150, 150)
                .apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 32; rightMargin = 24 }
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

    private fun showMainMenuDialog() {
        val accentColor = 0xFF00E5C7.toInt()
        val accentPressed = 0xFF00A896.toInt()
        val darkChip = 0xFF1E1E2E.toInt()
        val darkChipPressed = 0xFF2A2A3E.toInt()
        val disabledChip = 0xFF3A3A3A.toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 32)
            setBackgroundColor(0xFF121212.toInt())
        }

        val displayToggle = Switch(this).apply {
            fun refreshText() {
                text = if (showRoboEyes) "👀  Display: RoboEyes" else "📷  Display: Camera"
            }
            refreshText()
            textSize = 14f
            isChecked = showRoboEyes
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(40, 36, 40, 36)
            background = makeRippleRoundedDrawable(darkChip, darkChipPressed, 24f)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentColor, 0xFF888888.toInt())
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentPressed, 0xFF444444.toInt())
            )
            setOnCheckedChangeListener { _, checked ->
                showRoboEyes = checked
                applyDisplayMode()
                refreshText()
            }
        }

        val ipOption = Button(this).apply {
            text = "📶  IP ng Robot (${esp32BaseUrl.removePrefix("http://")})"
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 36)
            background = makeRippleRoundedDrawable(darkChip, darkChipPressed, 24f)
            setOnClickListener { showIpSettingDialog() }
        }

        val enrollOption = Button(this).apply {
            text = "✨  Mag-enroll ng bagong mukha"
            textSize = 14f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 36)
            isEnabled = canEnroll
            if (canEnroll) {
                setTextColor(0xFF04342C.toInt())
                background = makeRippleRoundedDrawable(accentColor, accentPressed, 24f)
            } else {
                setTextColor(0xFF888888.toInt())
                background = GradientDrawable().apply { setColor(disabledChip); cornerRadius = 24f }
            }
            setOnClickListener { showEnrollDialog() }
        }

        val greetingTracksOption = Button(this).apply {
            text = "🎙️  Greeting Tracks"
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 36)
            background = makeRippleRoundedDrawable(darkChip, darkChipPressed, 24f)
            setOnClickListener { showGreetingTracksDialog() }
        }

        val distanceOption = Button(this).apply {
            text = "📏  Distance Settings"
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 36)
            background = makeRippleRoundedDrawable(darkChip, darkChipPressed, 24f)
            setOnClickListener { showDistanceSettingsDialog() }
        }

        val micSensitivityOption = Button(this).apply {
            text = "🎤  Mic Sensitivity (${(micConfidenceThreshold * 100).toInt()}%)"
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 36)
            background = makeRippleRoundedDrawable(darkChip, darkChipPressed, 24f)
            setOnClickListener { showMicSensitivityDialog() }
        }

        val commandsOption = Button(this).apply {
            text = "🎤  Mga Utos"
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 36)
            background = makeRippleRoundedDrawable(darkChip, darkChipPressed, 24f)
            setOnClickListener { showManageCommandsDialog() }
        }

        val navOption = Button(this).apply {
            text = "🧭  Camera Nav (obstacle avoidance)"
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 36)
            background = makeRippleRoundedDrawable(darkChip, darkChipPressed, 24f)
            setOnClickListener { showNavSettingsDialog() }
        }

        val voiceLogOption = Button(this).apply {
            text = "🗒️  Voice Log"
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 36)
            background = makeRippleRoundedDrawable(darkChip, darkChipPressed, 24f)
            setOnClickListener { showVoiceLogDialog() }
        }

        val geminiOption = Button(this).apply {
            text = "🧠  Gemini AI (utak ni RUSTECH)"
            textSize = 14f
            isAllCaps = false
            setTextColor(0xFF04342C.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 36, 40, 36)
            background = makeRippleRoundedDrawable(accentColor, accentPressed, 24f)
            setOnClickListener { showGeminiSettingsDialog() }
        }

        fun createSpacer() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 24)
        }

        container.addView(geminiOption)
        container.addView(createSpacer())
        container.addView(displayToggle)
        container.addView(createSpacer())
        container.addView(ipOption)
        container.addView(createSpacer())
        container.addView(enrollOption)
        container.addView(createSpacer())
        container.addView(greetingTracksOption)
        container.addView(createSpacer())
        container.addView(distanceOption)
        container.addView(createSpacer())
        container.addView(micSensitivityOption)
        container.addView(createSpacer())
        container.addView(commandsOption)
        container.addView(createSpacer())
        container.addView(navOption)
        container.addView(createSpacer())
        container.addView(voiceLogOption)

        val scrollView = ScrollView(this).apply { addView(container) }

        android.app.AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("Isara", null)
            .show()
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

        val nameInput = EditText(this).apply {
            hint = "Eksaktong pangalan (kagaya ng naka-enroll) - IWANAN BLANGKO kung hindi kilala"
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

        android.app.AlertDialog.Builder(this)
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
            .show()
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
                runOnUi { greetPetIfNeeded(petDetections.first().label) }
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
        val servoAngle = computeServoAngle(box, frameHeight)
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
        if (tracks.isNotEmpty()) {
            sendPlayTrack(tracks.random())
        }
    }

    private fun greetUnknownIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastUnknownGreetTime < greetingCooldownMs) return
        lastUnknownGreetTime = now

        val tracks = unknownGreetingTrackList()
        if (tracks.isNotEmpty()) {
            sendPlayTrack(tracks.random())
        } else if (ttsReady) {
            // Fallback sa TTS kung wala pang na-set na DFPlayer tracks
            speak(unknownGreetings.random())
        }
    }

    private fun greetPetIfNeeded(label: String) {
        val now = System.currentTimeMillis()
        if (now - lastPetGreetTime < petGreetingCooldownMs) return
        lastPetGreetTime = now
        if (!ttsReady) return
        val options = petGreetings[label] ?: return
        speak(options.random())
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
            System.currentTimeMillis() >= geminiCooldownUntil

        // Walang Gemini (naka-off, walang key, o cooldown): dating lokal na behavior.
        if (!useGemini) {
            val reason = when {
                !geminiEnabled -> ""
                geminiApiKey.isBlank() -> " (walang Gemini API key)"
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

        // 2) Maiikling eksaktong utos (hal. "sayaw", "abante") - lokal at instant, gaya ng dati.
        val exact = findExactCustomCommand(candidates)
        if (exact != null) {
            speak(exact.randomReply())
            if (exact.action.isNotBlank()) executeEsp32Actions(exact.action)
            addVoiceLogEntry(heardText, "custom: \"${exact.trigger}\"")
            return
        }

        // 3) Lahat ng iba - kay RUSTECH (Gemini) na.
        if (geminiBusy) {
            addVoiceLogEntry(heardText, "na-ignore (nag-iisip pa si RUSTECH)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastGeminiRequestTime < geminiMinIntervalMs) {
            addVoiceLogEntry(heardText, "na-ignore (masyadong mabilis)")
            return
        }
        askGemini(candidates)
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

    private fun onGeminiReply(heardText: String, reply: GeminiBrain.Reply) {
        val text = reply.text.trim()
        val action = reply.action.uppercase()

        if (text.isEmpty() && action == "NONE") {
            statusText.text = "[MIC] (hindi para sa akin) $heardText"
            addVoiceLogEntry(heardText, "gemini: hindi pinansin")
            return
        }

        statusText.text = "🤖 RUSTECH: $text"
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
                    val rustechReplies = listOf(
                        "Ako ay si Rustech.. Ang laruan mo na ROBOT!",
                        "Ako ay si Rustech, ang kaibigan mo!",
                        "Ako si Rustech! Handang maglingkod at makipaglaro sa 'yo.",
                        "Rustech ang pangalan ko, ang paborito mong robot companion!",
                        "Ako si Rustech, ang AI robot na laging handang tumulong sa 'yo!"
                    )
                    speak(rustechReplies.random())
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

        android.app.AlertDialog.Builder(this)
            .setTitle("🗒️ Voice Log")
            .setView(scrollView)
            .setPositiveButton("I-clear") { _, _ -> voiceLog.clear() }
            .setNegativeButton("Isara", null)
            .show()
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
        if (!ttsReady) return
        val clean = cleanForSpeech(phrase)
        if (clean.isEmpty()) return
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
    private var servoTopRatio: Float
        get() = prefs.getFloat("servo_top_ratio", 0.15f)
        set(value) { prefs.edit().putFloat("servo_top_ratio", value).apply() }

    private var servoBottomRatio: Float
        get() = prefs.getFloat("servo_bottom_ratio", 0.70f)
        set(value) { prefs.edit().putFloat("servo_bottom_ratio", value).apply() }

    private var smoothedServoAngle: Float = 0f
    private val servoSmoothingFactorFar = 0.2f
    private val servoSmoothingFactorNear = 0.12f
    private val servoSmoothingSwitchThreshold = 20f

    private fun computeServoAngle(box: Rect, frameHeight: Int): Int {
        val faceCenterY = box.centerY()
        val verticalRatio = faceCenterY.toFloat() / frameHeight.toFloat()

        val clamped = verticalRatio.coerceIn(servoTopRatio, servoBottomRatio)
        val normalized = (clamped - servoTopRatio) / (servoBottomRatio - servoTopRatio)
        val rawAngle = (1f - normalized) * SERVO_MAX_ANGLE

        val distance = kotlin.math.abs(rawAngle - smoothedServoAngle)
        val factor = if (distance > servoSmoothingSwitchThreshold) {
            servoSmoothingFactorFar
        } else {
            servoSmoothingFactorNear
        }

        smoothedServoAngle += (rawAngle - smoothedServoAngle) * factor

        return smoothedServoAngle.toInt().coerceIn(0, SERVO_MAX_ANGLE)
    }

    private fun sendCommandThrottled(command: String, servoAngle: Int? = null) {
        val now = System.currentTimeMillis()
        if (now - lastSendTime < sendIntervalMs) return
        lastSendTime = now
        sendCommandToEsp32(command, servoAngle)
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

        var dialogRef: android.app.AlertDialog? = null
        calibrateButton.setOnClickListener {
            dialogRef?.dismiss()
            startNavCalibration()
        }

        dialogRef = android.app.AlertDialog.Builder(this)
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
            .show()
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
        container.addView(keyLabel)
        container.addView(keyInput)
        container.addView(modelLabel)
        container.addView(modelInput)
        container.addView(note)

        android.app.AlertDialog.Builder(this)
            .setTitle("🧠 Gemini AI")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                geminiEnabled = enabledSwitch.isChecked
                geminiApiKey = keyInput.text.toString()
                geminiModel = modelInput.text.toString()
                geminiCooldownUntil = 0L
                statusText.text = if (geminiEnabled && geminiApiKey.isNotBlank())
                    "🧠 Gemini naka-on (${geminiModel})" else "🧠 Gemini naka-off"
            }
            .setNeutralButton("I-reset ang usapan") { _, _ ->
                geminiBrain.resetConversation()
                statusText.text = "🧠 Nabura na ang memorya ng usapan"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIpSettingDialog() {
        val input = EditText(this).apply {
            hint = "hal. 192.168.1.25 o 192.168.43.100"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(esp32BaseUrl.removePrefix("http://"))
        }

        android.app.AlertDialog.Builder(this)
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
            .show()
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

        android.app.AlertDialog.Builder(this)
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
            .show()
    }

    private fun showMicSensitivityDialog() {
        val input = EditText(this).apply {
            hint = "0-100 (default 50)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((micConfidenceThreshold * 100).toInt().toString())
        }

        android.app.AlertDialog.Builder(this)
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
            .show()
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

        android.app.AlertDialog.Builder(this)
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
            .show()
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
            inputType = InputType.TYPE_CLASS_TEXT
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

        android.app.AlertDialog.Builder(this)
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
            .show()
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
            inputType = InputType.TYPE_CLASS_TEXT
            setText(cmd.action)
        }
        container.addView(TextView(this).apply { text = "Sasabihin (hiwalayin ng || kung ibat-ibang paraan ng pagsabi):" })
        container.addView(triggerInput)
        container.addView(TextView(this).apply { text = "Isasagot ng robot:"; setPadding(0, 24, 0, 0) })
        container.addView(replyInput)
        container.addView(TextView(this).apply { text = "ESP32 action:"; setPadding(0, 24, 0, 0) })
        container.addView(actionInput)

        val scrollView = ScrollView(this).apply { addView(container) }

        android.app.AlertDialog.Builder(this)
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
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        pingHandler.removeCallbacksAndMessages(null)
        navHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        faceDetector.close()
        yoloDetector.close()
        faceEmbedder.close()
        obstacleAnalyzer?.close()
        ttsHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        speechService?.stop()
        speechService?.shutdown()
    }
}
