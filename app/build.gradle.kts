import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing (optional): keystore/keystore.properties + keystore/release.jks.
// Without them CI falls back to the debug key so the APK is still installable.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "id.codingsachet.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "id.codingsachet.mobile"
        minSdk = 26
        // 28 on purpose: Android 9+ forbids executing binaries from the app
        // data directory, which proot needs. The APK is distributed as a file,
        // not through Play.
        targetSdk = 28
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
        // One APK per ABI: CI drives a build pass per ABI via -Pabi.
        ndk { abiFilters += (project.findProperty("abi") as String?) ?: "arm64-v8a" }
        // Gateway base URLs (both OpenAI-compatible). Overridable only for
        // local experiments.
        buildConfigField("String", "APINEX_BASE_URL", "\"https://api.apinex.bond/v1\"")
        buildConfigField("String", "NVIDIA_BASE_URL", "\"https://integrate.api.nvidia.com/v1\"")
        buildConfigField("String", "SERVER_PORT_DEFAULT", "\"8765\"")
        // Per-ABI guest facts, driven by CI (-PguestTriplet / -PnodeVersion).
        buildConfigField("String", "GUEST_TRIPLET", "\"${project.findProperty("guestTriplet") ?: "aarch64-linux-gnu"}\"")
        buildConfigField("String", "NODE_VERSION", "\"${project.findProperty("nodeVersion") ?: "26.13.0"}\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file("keystore/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    lint { disable += "ExpiredTargetSdkVersion" }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.getProperty("storeFile") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    androidResources {
        // The bundled environment archives (assets/env/*.tar.xz) are already
        // xz-compressed: storing them uncompressed in the APK avoids double
        // compression and lets AssetManager.openFd() report their length.
        noCompress += "xz"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        // proot and its libraries ship as lib*.so and must be extracted to disk,
        // otherwise they cannot be executed.
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/*.so"
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.xz)  // pure-Java xz decoder for the bundled env archives

    testImplementation(libs.junit)
}
