plugins {
    id("hatch.jvm")
    id("hatch.serialization")
}

dependencies {
    api(project(":core"))
    api(libs.serialization.json)
    api(libs.coroutines.core)
    api(libs.okhttp)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp.mockwebserver)
}
