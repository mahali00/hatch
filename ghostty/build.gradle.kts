plugins {
    id("hatch.android.library")
}

android {
    ndkVersion = "28.2.13676358"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
}
