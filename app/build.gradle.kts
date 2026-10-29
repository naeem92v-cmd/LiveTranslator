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
        versionName = "1.1"

        // whisper.cpp is built for 64-bit ARM phones only (all current phones)
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    // A fixed key (created once by the GitHub workflow) so a new APK installs over the old one
    // and the saved sessions survive updates. Without it Gradle uses a random per-machine debug key.
    val fixedKey = rootProject.file("keystore/livetranslator.jks")
    if (fixedKey.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = fixedKey
                storePassword = "livetranslator"
                keyAlias = "livetranslator"
                keyPassword = "livetranslator"
            }
        }
    }

    ndkVersion = "26.1.10909125"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

    // WebSocket for the Gemini Live translation engine
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // the org.json in android.jar is only a stub in local unit tests
    testImplementation("org.json:json:20240303")
}
