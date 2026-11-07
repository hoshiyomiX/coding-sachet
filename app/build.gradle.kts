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
    namespace = "id.apinex.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "id.apinex.mobile"
        minSdk = 26
        // 28 on purpose: proot executes binaries from the app data directory and
        // targetSdk >= 29 forbids exec from app_data_file (same reason as Termux).
        // The APK is distributed as a file, not through Play.
        targetSdk = 28
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
        // The gateway this app is built around (APInex, OpenAI-compatible;
        // models prefixed free/ bill $0 within the 20M-token daily quota).
        // Overridable only for local experiments.
        buildConfigField("String", "APINEX_BASE_URL", "\"https://api.apinex.bond/v1\"")
        buildConfigField("String", "SERVER_PORT_DEFAULT", "\"8765\"")
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

    testImplementation(libs.junit)
}
