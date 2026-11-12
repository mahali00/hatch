import com.android.build.api.dsl.Lint

internal const val COMPILE_SDK = 37
internal const val TARGET_SDK = 37
internal const val MIN_SDK = 26

/** Lint warnings fail the build. Only checks that cannot be acted on are off. */
internal fun Lint.hatchDefaults() {
    warningsAsErrors = true
    disable += setOf(
        "NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion", // versions move on purpose, in libs.versions.toml and the wrapper
        "ObsoleteLintCustomCheck", // a lint jar inside an AndroidX library; nothing to fix here
        "ChromeOsAbiSupport", // libghostty is built for ARM only (no x86)
    )
}
