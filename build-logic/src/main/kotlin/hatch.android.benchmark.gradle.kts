import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Baseline Profiles come from the nonMinifiedRelease variant: a profile from an obfuscated build names classes R8 renamed.
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "dev.mahali.hatch.benchmark"
    compileSdk = COMPILE_SDK
    defaultConfig {
        minSdk = 28
        targetSdk = TARGET_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" || it.buildType?.startsWith("nonMinified") == true }
}

// Generate on the phone that is plugged in, not a Gradle-managed emulator.
baselineProfile { useConnectedDevices = true }
