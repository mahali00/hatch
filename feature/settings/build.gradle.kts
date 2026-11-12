plugins {
    id("hatch.android.feature")
}

dependencies {
    // System Back between settings pages, and the notification permission request.
    implementation(libs.activity.compose)
}
