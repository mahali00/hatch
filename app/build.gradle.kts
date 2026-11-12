import java.util.Properties

plugins {
    id("hatch.android.application")
}

// A development host from the untracked local.properties (hatch.devHost=https://...), so no
// private address is ever committed. Debug builds only.
val devHost: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}.let { properties ->
    // Keep existing developer checkouts working while they migrate the local key.
    properties.getProperty("hatch.devHost") ?: properties.getProperty("rex.devHost", "")
}

android {
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { buildConfigField("String", "DEV_HOST", "\"$devHost\"") }
        release { buildConfigField("String", "DEV_HOST", "\"\"") }
        // Measured on the development host like the debug build; never shipped.
        getByName("benchmark") { buildConfigField("String", "DEV_HOST", "\"$devHost\"") }
    }
    // The baselineprofile plugin feeds the generated profile to release only; measurements run on benchmark.
    sourceSets.getByName("benchmark").baselineProfiles.srcDir("src/main/generated/baselineProfiles")
}

baselineProfile {
    // One profile in src/main, not one per build type.
    mergeIntoMain = true
    dexLayoutOptimization = true
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":engine"))
    implementation(project(":design"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:settings"))
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.profileinstaller)
    baselineProfile(project(":benchmark"))
}
