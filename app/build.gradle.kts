@file:Suppress("UnstableApiUsage")

import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

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
        versionCode = 31
        versionName = "0.5.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            // CI's custom debug keystore, when its secrets are configured. An unset GitHub
            // secret still arrives as an empty string (not null), so check for that too and
            // otherwise fall back to the standard auto-generated debug keystore.
            val debugKeystoreFile = System.getenv("MUSIC_DEBUG_KEYSTORE_FILE")?.let { file(it) }
            if (!System.getenv("MUSIC_DEBUG_SIGNING_STORE_PASSWORD").isNullOrEmpty() &&
                debugKeystoreFile?.let { it.exists() && it.length() > 0 } == true
            ) {
                storeFile = debugKeystoreFile
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
