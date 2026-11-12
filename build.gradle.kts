// The module graph is a rule, not a convention: the build fails if a module
// depends on anything not listed here. Dependencies point down only, features
// never depend on each other, and nothing depends on :app.
val allowedDependencies = mapOf(
    ":core" to emptySet(),
    ":protocol" to setOf(":core"),
    ":ghostty" to emptySet(),
    ":engine" to setOf(":core", ":protocol", ":ghostty"),
    ":renderer" to setOf(":ghostty"),
    ":design" to setOf(":core"),
    ":feature:sessions" to setOf(":core", ":design"),
    ":feature:pairing" to setOf(":core", ":design"),
    ":feature:settings" to setOf(":core", ":design"),
    ":feature:terminal" to setOf(":core", ":design", ":engine", ":renderer", ":protocol"),
    ":benchmark" to setOf(":app"), // measures the app; never shipped
)

gradle.projectsEvaluated {
    val violations = subprojects.flatMap { project ->
        val uses = project.configurations
            .flatMap { it.dependencies.withType<ProjectDependency>() }
            .map { it.path }
            .filter { it != project.path }
            .toSet()
        when {
            project.path == ":app" || !project.buildFile.exists() -> emptyList()
            project.path !in allowedDependencies -> listOf("${project.path} is not in the module graph in build.gradle.kts")
            else -> (uses - allowedDependencies.getValue(project.path)).map { "${project.path} must not depend on $it" }
        }
    }
    if (violations.isNotEmpty()) throw GradleException("Module graph violated:\n  " + violations.joinToString("\n  "))
}

// Feature and app code may not add raw `12.dp`, `13.sp`, `Color(0x…)`, token maths or `.copy(fontSize = …)`:
// per-file counts are held to design-tokens.baseline. `./gradlew checkDesignTokens -PupdateDesignTokens` rewrites it.
val checkDesignTokens by tasks.registering {
    // Only plain values below: the configuration cache cannot keep references to this script.
    val sources = files(
        subprojects.filter { it.path == ":app" || it.path.startsWith(":feature:") }.map { it.projectDir.resolve("src/main") },
    ).asFileTree.matching { include("**/*.kt") }
    val baseline = file("design-tokens.baseline")
    val update = providers.gradleProperty("updateDesignTokens").isPresent
    val root = rootDir.toPath()
    inputs.files(sources)
    if (baseline.exists()) inputs.file(baseline)
    doLast {
        val rawToken = Regex(
            """\b\d+(\.\d+)?\.(dp|sp)\b|Color\(0x""" +
                """|\b(Dimens|Hatch\.(size|space|layout|radius))\.\w+ *[-+*/] """ +
                """|\.copy\([^)]*\b(fontSize|fontWeight|fontFamily|lineHeight)\b""",
        )
        // A named component constant (`private val ArmedWidth = 34.dp`) is allowed; inline numbers are not.
        val namedConstant = Regex("""^\s*(private |internal )?val [A-Z]\w*(: Dp)? = \d+(\.\d+)?\.dp\s*(//.*)?$""")
        val counts = sources.files
            .associate { file ->
                root.relativize(file.toPath()).toString() to
                    file.readLines().filterNot { namedConstant.matches(it) }.sumOf { rawToken.findAll(it).count() }
            }
            .filterValues { it > 0 }
            .toSortedMap()
        if (update) {
            baseline.writeText(counts.entries.joinToString("\n", postfix = "\n") { "${it.value} ${it.key}" })
            return@doLast
        }
        val allowed = if (baseline.exists()) baseline.readLines().filter { it.isNotBlank() }
            .associate { line -> line.substringAfter(' ') to line.substringBefore(' ').toInt() } else emptyMap()
        val grown = counts.filter { (path, n) -> n > (allowed[path] ?: 0) }
        if (grown.isNotEmpty()) throw GradleException(
            "Raw sizes, colours, token maths or edited text styles added; use a Hatch.* token instead:\n  " +
                grown.entries.joinToString("\n  ") { "${it.key}: ${allowed[it.key] ?: 0} -> ${it.value}" },
        )
    }
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(checkDesignTokens) }
}
