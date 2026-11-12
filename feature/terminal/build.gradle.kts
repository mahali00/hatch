plugins {
    id("hatch.android.feature")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":renderer"))
    implementation(project(":protocol"))
    implementation(libs.androidx.core)
    testImplementation(libs.okhttp.mockwebserver)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.activity.compose)
}
