plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.genco.tercuman"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.genco.tercuman"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.8.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
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
    kotlinOptions {
        jvmTarget = "17"
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

    // Ücretsiz, cihaz-içi Whisper.cpp AAR. Streaming yerine kısa WAV parçaları işlenir.
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")

    // Cihaz-içi Supertonic 3 / diğer ONNX TTS modelleri için sherpa-onnx.
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")

    // Supertonic model paketini (.tar.bz2) telefonda açmak için.
    implementation("org.apache.commons:commons-compress:1.27.1")
}
