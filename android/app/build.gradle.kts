plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.ethic.messenger"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ethic.messenger"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "2.0.0"
    }

    buildTypes {
        debug {
            // Debug builds talk to a local test server (the emulator sees the PC as 10.0.2.2).
            buildConfigField("String", "API_BASE", "\"http://10.0.2.2:3999\"")
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            buildConfigField("String", "API_BASE", "\"https://176-124-220-95.sslip.io:8443\"")
            manifestPlaceholders["cleartext"] = "false"
            isMinifyEnabled = false
            // Phones only: keeps the WebRTC native libraries for other CPUs out of the APK.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // Compress the WebRTC native libraries: a much smaller download.
        jniLibs.useLegacyPackaging = true
        resources.excludes += listOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/**")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.livekit:livekit-android:2.29.1")
    implementation(platform("com.google.firebase:firebase-bom:35.0.2"))
    implementation("com.google.firebase:firebase-messaging")
}
