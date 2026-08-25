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
    private val httpClient = OkHttpClient()

    private lateinit var yoloDetector: YoloPersonDetector
    private lateinit var faceEmbedder: FaceEmbedder
    private lateinit var faceStore: FaceStore
    private lateinit var commandStore: CommandStore

    private var appState = AppState.EYES

    private val prefs by lazy { getSharedPreferences("facerobot_prefs", MODE_PRIVATE) }
    private var esp32BaseUrl: String
        get() = "http://" + prefs.getString("esp32_ip", "192.168.1.25")!!
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

    private val closeFaceWidthRatio = 0.40f

    private var lastPersonSeenTime = 0L
    private val personTimeoutMs = 4000L

    private var lastUnknownFaceEmbedding: FloatArray? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastGreetedName: String? = null
    private var lastGreetedTime = 0L
    private val greetingCooldownMs = 60_000L
    private var lastUnknownGreetTime = 0L

    // ---------- Pet (aso/pusa) detection ----------
    private var lastPetGreetTime = 0L
    private val petGreetingCooldownMs = 45_000L
    private val petGreetings = mapOf(
        "pusa" to listOf("Meow! Kumusta pusa!", "Ay, may pusa! Ang cute!", "Hi pusa, gusto mo bang makipaglaro?"),
        "aso" to listOf("Woof woof! Kumusta aso!", "Ay, may aso! Kaibigan ko yan.", "Hi doggi!")
    )

    // ---------- Vosk offline speech recognition ----------
    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var voskReady = false
    private var isSpeaking = false
    private var currentRecognizedName: String? = null

    private val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-tl-ph-generic-0.6.zip"
    private val voskModelDirName = "vosk-model-tl-ph-generic-0.6"

    // ---------- Voice log (para ma-verify kung tama ba ang narinig ni Vosk) ----------
    // (oras, narinig na text, resulta/aksyon)
    private val voiceLog = mutableListOf<Triple<Long, String, String>>()
    private val voiceLogMaxSize = 100

    // Ilang ms ang paulit-ulit na pagpapadala ng movement command galing sa boses
    // (kaliwa/kanan/sulong/atras) bago mag-STOP. Dagdagan ito kung gusto ng mas
    // mahabang galaw bago tumigil ang robot.
    private val voiceMovementDurationMs = 3000L
    private val movementActions = setOf("FORWARD", "BACKWARD", "LEFT", "RIGHT")

    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)

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

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 24)
        }
        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 24)
        }
        val spacer3 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 24)
        }

        container.addView(
            ipOption,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        container.addView(spacer2)
        container.addView(
            enrollOption,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        container.addView(spacer)
        container.addView(
            commandsOption,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        container.addView(spacer3)
        container.addView(
            voiceLogOption,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        android.app.AlertDialog.Builder(this)
            .setView(container)
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

        // Kukunin lang ang LEFT/RIGHT o STOP (Paggitna)
        val command = computeCommand(box, frameWidth)
        sendCommandThrottled(command)

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

    private val greetings = listOf(
        "Kumusta, %s!",
        "Hi %s, kumusta ka?",
        "Ay, si %s! Kumusta?",
        "Magandang araw, %s!",
        "Gusto mo bang makipag laro sakin %s!",
        "%s! kumain kana ba",
        "%s! Tara laro tayo",
        "Ikaw ba %s! ay nakapag pahinga ng maayos, wag ka ka babad sa pagkocode, tumagay ka rin",
    )

    private val unknownGreetings = listOf(
        "Kumusta, ano ginagawa mo ngayon.",
        "Hi kaibigan na tao! Gusto mo bang makipag laro sa akin.",
        "Kumusta! Saan pala kayo papunta?",
        "Hello! Pwede mo ba akong Kausapin?",
        "Hi! kausapin mo ako",
        "Ngayon ka lan ba naka kita ng laruan na kagaya ko",
        "Kumain na ba kayo",
        "tara laro tayo",
        "Huwag mo ako kalimutan na e charge!",
        
        
    )

    private fun greetIfNeeded(name: String) {
        val now = System.currentTimeMillis()
        val alreadyGreetedRecently = name == lastGreetedName && now - lastGreetedTime < greetingCooldownMs
        if (alreadyGreetedRecently) return

        lastGreetedName = name
        lastGreetedTime = now

        if (!ttsReady) return
        val phrase = greetings.random().format(name)
        speak(phrase)
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

    /**
     * Sinisimulan ang setup ng Vosk. Kung wala pang na-download na Filipino model sa
     * internal storage, dina-download muna ito (isang beses lang) bago i-load.
     */
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

    private fun startVoskListening() {
        val model = voskModel ?: return
        try {
            val recognizer = Recognizer(model, 16000.0f)
            val service = SpeechService(recognizer, 16000.0f)
            speechService = service
            service.startListening(voskListener)
        } catch (e: Exception) {
            e.printStackTrace()
            statusText.text = "❌ Mic error: ${e.message}"
        }
    }

    private val voskListener = object : VoskListener {
        override fun onPartialResult(hypothesis: String?) {
            // Hindi ginagamit - hinihintay ang buong (final) resulta na lang sa onResult
        }

        override fun onResult(hypothesis: String?) {
            val text = try {
                JSONObject(hypothesis ?: "").optString("text", "").trim()
            } catch (e: Exception) {
                ""
            }
            if (text.isNotEmpty()) {
                runOnUi {
                    statusText.text = "[MIC] Narinig: $text"
                    handleVoiceCommand(listOf(text))
                }
            }
        }

        override fun onFinalResult(hypothesis: String?) {}

        override fun onError(exception: Exception?) {
            exception?.printStackTrace()
        }

        override fun onTimeout() {}
    }

    private fun handleVoiceCommand(candidates: List<String>) {
        val heardText = candidates.firstOrNull() ?: return
        val resultLabel = processVoiceCommand(candidates)
        addVoiceLogEntry(heardText, resultLabel)
    }

    /**
     * Sinusubukan ang bawat alternative na resulta ng recognizer hanggang may tumama.
     * Nag-re-return ng short label kung ano ang tumama, para ma-log sa Voice Log.
     */
    private fun processVoiceCommand(candidates: List<String>): String {
        for (text in candidates) {
            val custom = commandStore.findMatch(text)
            if (custom != null) {
                speak(custom.reply)
                if (custom.action.isNotBlank()) {
                    val actionUpper = custom.action.uppercase()
                    if (actionUpper in movementActions) {
                        // Movement action din ito (FORWARD/BACKWARD/LEFT/RIGHT) - paulit-ulit
                        // ipapadala para hindi ma-cut ng ESP32's FACE_COMMAND_TIMEOUT
                        sendTimedCommand(actionUpper, voiceMovementDurationMs)
                    } else {
                        sendCommandToEsp32(custom.action)
                    }
                }
                return "custom: \"${custom.trigger}\""
            }

            when {
                // Motion Voice Commands
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

                // Info Voice Commands
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
                text.contains("sino ka") -> {
                    speak("ako ay si rustech")
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
        // Kapag walang mukha, hihinto lang at mag-aabang hanggang bumalik sa eyes mode
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

   /**
 * Priyoridad muna ang distansya: kung sobrang lapit na ang mukha (malapad na ang
 * bounding box kumpara sa frame), mag-BACKWARD muna. Kung hindi naman malapit,
 * saka lang natin susuriin ang Kaliwa/Kanan/Gitna para sa centering.
 */
private fun computeCommand(box: Rect, frameWidth: Int): String {
    val faceWidthRatio = box.width().toFloat() / frameWidth.toFloat()
    if (faceWidthRatio > closeFaceWidthRatio) {
        return "BACKWARD"
    }

    val faceCenterX = box.centerX()
    val screenCenterX = frameWidth / 2

    // Pinalapad ang deadzone (ginawang frameWidth / 3.5)
    // Mas malapad na gitnang espasyo para may allowance bago mag-STOP
    val centerDeadzoneWidth = (frameWidth / 3.5 / 2).toInt()

    val leftBoundary = screenCenterX - centerDeadzoneWidth
    val rightBoundary = screenCenterX + centerDeadzoneWidth

    return when {
        // Mirrored ang front camera input:
        // Kapag ang mukha ay nasa kaliwa sa pixel coordinates (faceCenterX < leftBoundary),
        // kailangang pumaling ng robot sa KANAN (RIGHT) para pumunta sa gitna ang mukha.
        faceCenterX < leftBoundary -> "RIGHT"
        faceCenterX > rightBoundary -> "LEFT"
        else -> "STOP" // Kapag pasok na sa deadzone, hihinto agad!
    }
}

    private fun sendCommandThrottled(command: String) {
        val now = System.currentTimeMillis()
        if (now - lastSendTime < sendIntervalMs) return
        lastSendTime = now
        sendCommandToEsp32(command)
    }

    /**
     * Para sa mga voice-triggered na galaw (hal. "kaliwa"/"kanan" o custom FORWARD/BACKWARD):
     * paulit-ulit magpapadala ng command sa loob ng ilang segundo (bawat 300ms - mas mabilis
     * pa sa ESP32's FACE_COMMAND_TIMEOUT na 600ms) para hindi ma-override ng
     * autonomous/ultrasonic logic ng ESP32 bago pa matapos yung galaw. Dagdagan ang
     * durationMs kung gusto ng mas mahabang galaw.
     */
    private fun sendTimedCommand(command: String, durationMs: Long) {
        val handler = Handler(mainLooper)
        val endTime = System.currentTimeMillis() + durationMs
        val runnable = object : Runnable {
            override fun run() {
                sendCommandToEsp32(command)
                if (System.currentTimeMillis() < endTime) {
                    handler.postDelayed(this, 300)
                } else {
                    sendCommandToEsp32("STOP")
                }
            }
        }
        handler.post(runnable)
    }

    private fun sendCommandToEsp32(command: String) {
        val request = Request.Builder()
            .url("$esp32BaseUrl/command?dir=$command")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // Connection fail error handling
            }
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
                    text = "\"${cmd.trigger}\" -> \"${cmd.reply}\"$actionPart"
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
            hint = "Sasabihin (hal. anong oras na)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val replyInput = EditText(this).apply {
            hint = "Isasagot ng robot"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val actionInput = EditText(this).apply {
            hint = "ESP32 action (opsyonal - hal. LEFT, RIGHT, STOP - iwanan blangko kung wala)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(triggerInput)
        container.addView(replyInput)
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

    /**
     * Dialog para baguhin ang trigger/reply/action ng isang existing command. Kung binago
     * ang trigger text, tinatanggal muna natin ang luma bago idagdag ang bago - kasi
     * exact-match lang ang findMatch ng CommandStore.add() para mag-upsert.
     */
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
        container.addView(TextView(this).apply { text = "Sasabihin:" })
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
