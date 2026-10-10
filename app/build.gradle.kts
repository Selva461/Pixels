import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pixels.enhancer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pixels.enhancer"
        // 29+: MediaStore RELATIVE_PATH / IS_PENDING, so saving needs no storage permission.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    androidResources {
        // The segmentation model is read whole into memory; compressing it would only slow that down.
        noCompress += "tflite"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Any lint error fails CI; the HTML/XML/SARIF reports are uploaded as build artifacts.
        abortOnError = true
        checkReleaseBuilds = true
        htmlReport = true
        xmlReport = true
        sarifReport = true
        // "Newer version available" checks depend on the network and the day, not on this code.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // Play's target-API deadline is a release decision tracked in AUDIT.md, not a code defect.
        warning += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Substituted with the included ../engine build.
    implementation("com.pixels.enhancer:domain")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.exifinterface)
    // On-device people segmentation for Smart edit (model in assets/models; runs offline).
    implementation(libs.tensorflow.lite)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Hosts composables in tests (debug builds only; never in release).
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
