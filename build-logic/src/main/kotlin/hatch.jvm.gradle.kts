import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin module: no Android APIs.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    "testImplementation"(versionCatalogs.named("libs").findLibrary("junit").get())
}
