@file:Suppress("UnstableApiUsage")

import java.util.Properties

val isFullBuild: Boolean by rootProject.extra

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.skintclub.odyssey"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.skintclub.odyssey"
        minSdk = 24
        targetSdk = 35
        versionCode = 31
        versionName = "0.5.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            if (System.getenv("MUSIC_DEBUG_SIGNING_STORE_PASSWORD") != null) {
                storeFile = file(System.getenv("MUSIC_DEBUG_KEYSTORE_FILE"))
                storePassword = System.getenv("MUSIC_DEBUG_SIGNING_STORE_PASSWORD")
                keyAlias = "debug"
                keyPassword = System.getenv("MUSIC_DEBUG_SIGNING_KEY_PASSWORD")
            }
        }
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            // TEMP: isolate the debuggable-vs-not perf difference without pulling in R8.
            // Revert before any real debug build.
            isDebuggable = false
        }
    }
    flavorDimensions += "version"
    productFlavors {
        create("foss") {
            dimension = "version"
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    lint {
        disable += "MissingTranslation"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.navigation)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.reorderable)

    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)

    implementation(libs.material3)
    implementation(libs.palette)
    implementation(projects.materialColorUtilities)

    implementation(libs.accompanist.swiperefresh)

    implementation(libs.coil)

    implementation(libs.shimmer)

    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.hls)
    implementation(libs.media3.ui)
    implementation(libs.squigglyslider)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)
    implementation(libs.gson)

    implementation(libs.hilt)
    kapt(libs.hilt.compiler)

    implementation(projects.innertube)
    implementation(projects.kugou)
    implementation(projects.lrclib)

    implementation(libs.ktor.client.core)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.timber)
}
