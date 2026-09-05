import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableKeystorePath = System.getenv("TERCUMAN_KEYSTORE_PATH")
val stableKeystorePassword = System.getenv("TERCUMAN_KEYSTORE_PASSWORD")
val hasStableSigning = !stableKeystorePath.isNullOrBlank() && !stableKeystorePassword.isNullOrBlank()

android {
    namespace = "com.genco.tercuman"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.genco.tercuman"
        minSdk = 29
        targetSdk = 35
        versionCode = 31
        versionName = "1.7.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stableDebug") {
                storeFile = file(stableKeystorePath!!)
                storePassword = stableKeystorePassword
                keyAlias = "tercuman"
                keyPassword = stableKeystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    // Yerel AI Conversation Engine (Qwen3-0.6B INT4 / LiteRT-LM).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")

    // Ücretsiz, cihaz-içi Whisper.cpp AAR. Streaming yerine kısa WAV parçaları işlenir.
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")

    // Cihaz-içi Supertonic 3 / diğer ONNX TTS modelleri için sherpa-onnx.
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")

    // Supertonic model paketini (.tar.bz2) telefonda açmak için.
    implementation("org.apache.commons:commons-compress:1.27.1")
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
