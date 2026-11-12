plugins {
    id("hatch.android.library")
}

dependencies {
    api(project(":core"))
    api(project(":protocol"))
    api(project(":ghostty"))
    implementation(libs.coroutines.android)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
