package com.example.facerobot
 
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
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
import com.example.facerobot.vision.FaceEmbedder
import com.example.facerobot.vision.FaceStore
import com.example.facerobot.vision.ImageUtils
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
 */
@androidx.camera.core.ExperimentalGetImage
class MainActivity : ComponentActivity() {
 
    private enum class AppState { EYES, CAMERA }
 
    private lateinit var rootLayout: FrameLayout
    private lateinit var previewView: PreviewView
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
 
    private lateinit var yoloDetector: YoloPersonDetector
    private lateinit var faceEmbedder: FaceEmbedder
    private lateinit var faceStore: FaceStore
    private lateinit var commandStore: CommandStore
 
    private var micConfidenceThreshold: Float
        get() = prefs.getFloat("mic_confidence_threshold", 0.5f)
        set(value) { prefs.edit().putFloat("mic_confidence_threshold", value.coerceIn(0f, 1f)).apply() }
 
    private var appState = AppState.EYES
 
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
 
    private var lastPersonSeenTime = 0L
    private val personTimeoutMs = 4000L
 
    private var lastUnknownFaceEmbedding: FloatArray? = null
 
    private var tts: TextToSpeech? = null
    private var ttsReady = false
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
 
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(Locale("fil", "PH"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        runOnUi { speechService?.setPause(false) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        runOnUi { speechService?.setPause(false) }
                    }
                })
                ttsReady = true
            }
        }
 
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

  private fun executeEsp32Actions(actionField: String) {
    val parts = actionField.split("||").map { it.trim() }.filter { it.isNotEmpty() }

    // Ang DFPlayer/sound parts ang ipinapadala MUNA, bago ang movement commands.
    // Dahil paulit-ulit magpapadala ng FORWARD/LEFT/atbp ang sendTimedCommand()
    // (bawat 300ms sa loob ng ilang segundo), kung una itong ipoproseso, maiipit
    // ang PLAY request sa likod ng backlog ng mga paulit-ulit na movement request
    // papunta sa parehong ESP32 host - kaya delayed ang tunog. Sa pag-una sa
    // DFPlayer, hindi na ito maaantala.
    val dfParts = parts.filter { dfPlayerPlayRegex.containsMatchIn(it) }
    val otherParts = parts.filterNot { dfPlayerPlayRegex.containsMatchIn(it) }

    for (part in dfParts) {
        val track = dfPlayerPlayRegex.find(part)?.groupValues?.get(1)?.toIntOrNull()
        if (track != null) {
            sendPlayTrack(track)
        }
    }

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
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            response.close()
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
 
        fun createSpacer() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 24)
        }
 
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
                    text = "$name -> Track ${obj.getInt(name)}"
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
            hint = "Eksaktong pangalan (kagaya ng naka-enroll)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val trackInput = EditText(this).apply {
            hint = "Track number (hal. 25 para sa 0025.mp3)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        container.addView(nameInput)
        container.addView(trackInput)
 
        val scrollView = ScrollView(this).apply { addView(container) }
 
        android.app.AlertDialog.Builder(this)
            .setTitle("🎙️ Greeting Tracks (per pangalan)")
            .setView(scrollView)
            .setPositiveButton("Idagdag") { _, _ ->
                val name = nameInput.text.toString().trim()
                val track = trackInput.text.toString().toIntOrNull()
                if (name.isNotEmpty() && track != null) {
                    setGreetingTrack(name, track)
                    statusText.text = "Na-set: $name -> Track $track"
                }
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
    }
 
    private fun showCameraUi() {
        appState = AppState.CAMERA
        lastPersonSeenTime = System.currentTimeMillis()
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
 
    private fun greetingTrackFor(name: String): Int? {
        val json = prefs.getString("greeting_tracks", "{}") ?: "{}"
        val obj = JSONObject(json)
        return if (obj.has(name)) obj.getInt(name) else null
    }
 
    private fun setGreetingTrack(name: String, track: Int) {
        val json = prefs.getString("greeting_tracks", "{}") ?: "{}"
        val obj = JSONObject(json)
        obj.put(name, track)
        prefs.edit().putString("greeting_tracks", obj.toString()).apply()
    }
 
    private fun removeGreetingTrack(name: String) {
        val json = prefs.getString("greeting_tracks", "{}") ?: "{}"
        val obj = JSONObject(json)
        obj.remove(name)
        prefs.edit().putString("greeting_tracks", obj.toString()).apply()
    }
 
    private fun sendGreetingTrack(track: Int) {
        val request = Request.Builder()
            .url("$esp32BaseUrl/command?dir=GREET&track=$track")
            .build()
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }
 
    private fun greetIfNeeded(name: String) {
        val now = System.currentTimeMillis()
        val alreadyGreetedRecently = name == lastGreetedName && now - lastGreetedTime < greetingCooldownMs
        if (alreadyGreetedRecently) return
 
        lastGreetedName = name
        lastGreetedTime = now
 
        val track = greetingTrackFor(name)
        if (track != null) {
            sendGreetingTrack(track)
        }
    }
 
    private fun greetUnknownIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastUnknownGreetTime < greetingCooldownMs) return
        lastUnknownGreetTime = now
 
        if (!ttsReady) return
        speak(unknownGreetings.random())
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
        val resultLabel = processVoiceCommand(candidates)
        addVoiceLogEntry(heardText, resultLabel)
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
                    sendCommandToEsp32("STOP")
                    return "STOP"
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
 
    private fun speak(phrase: String) {
        if (!ttsReady) return
        isSpeaking = true
        speechService?.setPause(true)
        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}")
    }
 
    private fun handleNoFace() {
        sendCommandThrottled("STOP")
        val now = System.currentTimeMillis()
        if (now - lastPersonSeenTime > personTimeoutMs) {
            runOnUi { showEyesUi() }
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
 
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }
 
    // ---------- Enroll UI ----------
 
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
            hint = "ESP32 action (opsyonal - hal. LEFT, RIGHT, STOP - iwanan blangko kung wala)"
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
        cameraExecutor.shutdown()
        faceDetector.close()
        yoloDetector.close()
        faceEmbedder.close()
        tts?.stop()
        tts?.shutdown()
        speechService?.stop()
        speechService?.shutdown()
    }
}
 
