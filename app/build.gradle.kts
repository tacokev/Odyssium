@file:Suppress("UnstableApiUsage")

import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

// The Discord Social SDK (for the Discord status - see DiscordPresence) isn't ours to share, so it
// isn't in the repo. Download it from the Discord Developer Portal and put its include/ and
// lib/release/discord_partner_sdk.aar under discord_social_sdk/ to build with it; without it the
// app builds as before, just without the Discord option.
val discordSdkAar = rootProject.file("discord_social_sdk/lib/release/discord_partner_sdk.aar")
val hasDiscordSdk = discordSdkAar.exists()

// Optional, in local.properties: app.buildDirectory=<path> moves this module's build output
// elsewhere. Only needed on a machine where the native (Discord SDK) build trips over files in the
// usual build folder being locked; does nothing unless set.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
localProperties.getProperty("app.buildDirectory")?.let { layout.buildDirectory.set(file(it)) }

plugins {
    id("com.android.application")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.skintclub.odyssey"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.skintclub.odyssey"
        minSdk = 24
        targetSdk = 35
        versionCode = 32
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "DISCORD_SDK", hasDiscordSdk.toString())
        // The "Odyssium" app in the Discord Developer Portal, which the status is shown as.
        buildConfigField("long", "DISCORD_APPLICATION_ID", "1570491963122126998L")
    }
    if (hasDiscordSdk) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
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
        // The SDK's AAR hands its headers and native library to CMake through Prefab.
        prefab = hasDiscordSdk
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    lint {
        disable += "MissingTranslation"
        // Voice "play X" searches from Assistant aren't supported by the media session.
        disable += "MissingIntentFilterForMediaSearch"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    if (hasDiscordSdk) implementation(files(discordSdkAar))

    implementation(libs.guava)
    implementation(libs.coroutines.guava)

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
    ksp(libs.hilt.compiler)


    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.timber)
}
