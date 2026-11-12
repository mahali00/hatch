plugins {
    id("hatch.android.library")
    id("hatch.android.compose")
    id("hatch.serialization")
}

android {
    // Program logos are long single-path vectors; each is drawn once per tile size and cached.
    lint { disable += "VectorPath" }
}

dependencies {
    api(project(":core"))
    implementation(libs.serialization.json)
}
