plugins {
    `kotlin-dsl`
}

// Every third-party Gradle plugin enters the build here, and only here.
dependencies {
    implementation(libs.android.gradle)
    implementation(libs.kotlin.gradle)
    implementation(libs.compose.gradle)
    implementation(libs.serialization.gradle)
    implementation(libs.baselineprofile.gradle)
}
