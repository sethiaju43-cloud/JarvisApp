plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// LiteRT-LM (Google ka on-device LLM runtime). Version yahan se badal sakte ho.
val litertLmVersion = "0.16.1"

android {
    namespace = "com.jarvis.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        // LiteRT-LM ki native libraries sirf 64-bit ARM (aur x86_64) ke liye hain.
        // Redmi 13C 5G arm64 hai, isliye sirf arm64 rakha (APK chhota aur crash kam).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Local offline AI
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertLmVersion")

    // Unit tests (JVM par chalte hain, phone ki zaroorat nahi)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
