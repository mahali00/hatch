plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

pluginManager.withPlugin("com.android.library") {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> { buildFeatures.compose = true }
}
pluginManager.withPlugin("com.android.application") {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension> { buildFeatures.compose = true }
}

val libs = versionCatalogs.named("libs")
dependencies {
    "implementation"(platform(libs.findLibrary("compose-bom").get()))
    "implementation"(libs.findLibrary("compose-ui").get())
    "implementation"(libs.findLibrary("compose-foundation").get())
    "implementation"(libs.findLibrary("compose-material3").get())
    "implementation"(libs.findLibrary("compose-tooling-preview").get())
    "debugImplementation"(libs.findLibrary("compose-tooling").get())
}

composeCompiler { stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf")) }
