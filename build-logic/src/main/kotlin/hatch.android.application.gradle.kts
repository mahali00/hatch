import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("hatch.android.compose")
    // Consumes the profile :benchmark generates.
    id("androidx.baselineprofile")
}

android {
    namespace = "dev.mahali.hatch"
    compileSdk = COMPILE_SDK
    defaultConfig {
        applicationId = "dev.mahali.hatch"
        minSdk = MIN_SDK
        targetSdk = TARGET_SDK
        versionCode = 1
        versionName = "0.1.0"
    }
    lint { hatchDefaults() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            // Installs beside any other build, so testing never replaces the app you use.
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release code with a debug signature, so benchmarks measure what users run.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
