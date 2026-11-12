package dev.mahali.hatch.design

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature and app code takes sizes, radii, icons and menus from :design. */
class ScaleUsageTest {
    private val banned = listOf(
        Regex("""fontSize\s*=\s*[0-9.]+\.sp"""),
        Regex("""RoundedCornerShape\("""),
        Regex("""material\.icons"""),
        Regex("""\bDropdownMenu(Item)?\("""),
        Regex("""\b(Text|Icon)Button\("""),
    )

    // Selection handles are teardrop shapes, not radii.
    private val allowed = setOf("Selection.kt")

    @Test fun featuresUseTheScale() {
        val root = File("..").canonicalFile
        val files = listOf("feature", "app").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }.toList()
        }
        assertTrue("found only ${files.size} sources under $root", files.size > 50)
        val offenders = files.filter { it.name !in allowed }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                "${file.relativeTo(root)}:${i + 1}: ${line.trim()}".takeIf { banned.any { it.containsMatchIn(line) } }
            }
        }
        assertEquals("", offenders.joinToString("\n"))
    }
}
