plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.livetranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.livetranslator"
        // AudioPlaybackCapture (capturing other apps' audio) needs Android 10+
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Offline speech recognition (English)
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // On-device translation (English -> Arabic)
    implementation("com.google.mlkit:translate:17.0.3")
}
