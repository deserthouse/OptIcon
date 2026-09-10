// OptIcon App Module — LSPosed Xposed Module
// Kotlin DSL | libxposed API 102 | Material 3 Expressive

import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    // AGP 9.2+ auto-applies kotlin-android — do NOT apply manually
    id("org.jetbrains.kotlin.plugin.compose")
}

// Kotlin compiler options (top-level, AGP 9.2+ provides this extension)
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

val MODULE_VERSION_NAME = "0.3.2-alpha"

android {
    namespace = "io.github.deserthouse.opticon"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.deserthouse.opticon"
        minSdk = 31  // Android 12 minimum — full Material You generation (dynamic color since API 31)
        targetSdk = 37
        versionCode = 22
        versionName = MODULE_VERSION_NAME
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // keystore lives OUTSIDE the repo (never commit!).
            // Override via ~/.gradle/gradle.properties: OPTICON_STORE_FILE / _PASS / _KEY_PASS
            storeFile = file(providers.gradleProperty("OPTICON_STORE_FILE").getOrElse(
                "C:/Users/deser/.android/opticon-release.jks"))
            storePassword = providers.gradleProperty("OPTICON_STORE_PASS").getOrElse("opticon2026")
            keyAlias = providers.gradleProperty("OPTICON_KEY_ALIAS").getOrElse("opticon")
            keyPassword = providers.gradleProperty("OPTICON_KEY_PASS").getOrElse("opticon2026")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

// ━━━ APK Output Naming: OptIcon-vX.Y.Z-YYYYMMDD-HHMMSS.apk ━━━
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val buildTime = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            output.outputFileName.set("OptIcon-v$MODULE_VERSION_NAME-$buildTime.apk")
        }
    }
}

dependencies {
    // ━━━ libxposed API 102 ━━━
    compileOnly("io.github.libxposed:api:102.0.0")

    // ━━━ AndroidX Core ━━━
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.0")

    // ━━━ Material 3 (Compose) with Expressive APIs ━━━
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material 3 1.5+ includes Expressive APIs (no separate artifact needed)
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    // Material Icons Extended (for Icons.Default.*, Icons.Filled.* etc.)
    implementation("androidx.compose.material:material-icons-extended")

    // ━━━ Navigation ━━━
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // ━━━ Image Loading (Coil 2.x for Compose) ━━━
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ━━━ Coroutines ━━━
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // ━━━ Networking (OkHttp) ━━━
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ━━━ Debug ━━━
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ━━━ Silent package-and-bake: compiles, timestamps, copies to desktop ━━━
tasks.register("packageAndBakeApk") {
    dependsOn("assembleDebug")
    notCompatibleWithConfigurationCache("APK copy with runtime timestamp")
    doLast {
        val desktop = file("C:/Users/deser/Desktop/")
        val sourceDir = file("build/outputs/apk/debug/")
        sourceDir.listFiles()?.filter { it.extension == "apk" }?.forEach { apk ->
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            val newName = "OptIcon-v$MODULE_VERSION_NAME-$timestamp.apk"
            apk.copyTo(desktop.resolve(newName), overwrite = true)
        }
    }
}
