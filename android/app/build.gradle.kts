// Fly Lab (15 Nov 2026) - Ko's test bench for camera effects, separate from
// the Fly app (package com.aungdev.fly_lab, installs next to Fly and Fly
// Dev). An effect is built and tested here first; only when it works on
// Ko's phone is it copied into Fly. Debug-signed release builds only.
plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.aungdev.fly_lab"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.aungdev.fly_lab"
        // MediaPipe Tasks needs 24+; 26 keeps the camera code simple.
        minSdk = maxOf(26, flutter.minSdkVersion)
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // The MediaPipe model (assets/selfie_segmenter.tflite) must stay
    // uncompressed inside the APK so MediaPipe can map it from the assets.
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    buildTypes {
        release {
            // Lab only: signed with the debug key, never published.
            signingConfig = signingConfigs.getByName("debug")
            // 16 Nov 2026: Flutter shrinks release builds with R8, which
            // can strip classes MediaPipe / GPUPixel reach from native code
            // (first test: the segmenter FAILED) - keep rules in
            // proguard-rules.pro.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // GPUPixel v1.3.1 (Apache-2.0) - the same AAR as Fly
    // (fly\android\app\libs\gpupixel-release.aar, copied by hand).
    implementation(files("libs/gpupixel-release.aar"))
    // MediaPipe Tasks Vision (Apache-2.0) - person / background
    // segmentation (selfie_segmenter.tflite in src/main/assets).
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
}

flutter {
    source = "../.."
}
