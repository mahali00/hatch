import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "dev.mahali.hatch." + path.removePrefix(":").replace(':', '.').replace('-', '.')
    compileSdk = COMPILE_SDK
    defaultConfig {
        minSdk = MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    lint { hatchDefaults() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val libs = versionCatalogs.named("libs")
dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
    "androidTestImplementation"(libs.findLibrary("androidx-test-junit").get())
    "androidTestImplementation"(libs.findLibrary("androidx-test-runner").get())
}
