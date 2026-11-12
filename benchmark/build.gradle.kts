plugins {
    id("hatch.android.benchmark")
}

dependencies {
    implementation(libs.benchmark.macro)
    implementation(libs.uiautomator)
    implementation(libs.androidx.test.junit)
}
