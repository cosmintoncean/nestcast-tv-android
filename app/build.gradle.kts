plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.castbox.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.castbox.tv"
        minSdk = 23
        targetSdk = 28
        versionCode = 5
        versionName = "0.5.1"
        // TV devices are ARM; skipping x86 keeps the FFmpeg native libraries from bloating the APK.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release {
            // Debuggable builds run with CheckJNI and no shrinking, which is far too slow for Compose on a TV
            // dongle. This release build is signed with the local debug key for personal sideloading only;
            // use a real signing config before distributing it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // targetSdk 28 is intentional for this sideloaded app; Play's minimum-target check doesn't apply.
    lint { disable += "ExpiredTargetSdkVersion" }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // Loads and caches show poster images.
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Software decoder for audio the TV can't play itself (E-AC-3/Dolby Digital Plus, AC-3, DTS, TrueHD...).
    // Jellyfin's prebuilt FFmpeg extension; GPL-3.0, so keep it in mind before distributing the APK.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
