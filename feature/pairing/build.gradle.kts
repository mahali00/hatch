plugins {
    id("hatch.android.feature")
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.serialization.json)
    // QR pairing: CameraX shows the preview and hands frames to zxing, which is far smaller than ML Kit's bundled model.
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
}
