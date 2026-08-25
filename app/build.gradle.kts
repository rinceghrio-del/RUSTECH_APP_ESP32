plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.facerobot"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.facerobot"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        // wala tayong ginagamit na view binding o compose, plain code lang
    }
    androidResources {
        // huwag i-compress ang .tflite model files sa assets/, kailangan sila i-mmap
        // nang direkta ng TensorFlow Lite Interpreter
        noCompress += "tflite"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // CameraX - para sa camera preview at frame analysis
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Face Detection - on-device, walang kailangan na internet
    implementation("com.google.mlkit:face-detection:16.1.6")

    // TensorFlow Lite - para sa YOLO person detection at face recognition (embedding) models
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // OkHttp - para sa pagpapadala ng HTTP commands papunta sa ESP32
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
