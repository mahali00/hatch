plugins {
    id("hatch.android.library")
}

dependencies {
    api(project(":ghostty"))
    implementation(libs.androidx.collection)
    implementation(libs.androidx.core)
}
