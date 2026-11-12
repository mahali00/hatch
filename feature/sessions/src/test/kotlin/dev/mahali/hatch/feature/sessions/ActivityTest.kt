package dev.mahali.hatch.feature.sessions

import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.StatusRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityTest {
    private fun status(state: ProgramState, at: String, title: String = "") = mapOf("" to StatusRecord("", state, title = title, updatedAt = at))

    private val agent = pane("b1", "claude", title = "claude").copy(status = status(ProgramState.Working, "2026-11-09T10:00:00Z", "HANDOFF"))
    private val waiting = pane("b2", "codex", title = "codex").copy(status = status(ProgramState.Blocked, "2026-11-09T10:05:00Z"))
    private val finished = pane("b3", "opencode").copy(status = status(ProgramState.Done, "2026-11-09T09:00:00+01:00"))
    private val quiet = pane("b4", "fish").copy(status = status(ProgramState.Idle, "2026-11-09T10:00:00Z"))
    private val silent = pane("b5", "fish")
    private val state = host(
        session("s1", "rex") to listOf(tab("w1", agent, waiting), tab("w2", finished)),
        session("s2", "calm") to listOf(tab("w3", quiet, silent)),
    ).withBlocks(agent, waiting, finished, quiet, silent).app()

    @Test fun `blocked has its own group and idle stays hidden`() {
        val groups = activityGroups(state, preferGenerated = false, showIdle = false)
        assertEquals(listOf(ActivityGroup.NeedsInput, ActivityGroup.Working, ActivityGroup.Done), groups.map { it.first })
        assertEquals(listOf("codex"), groups[0].second.map { it.title })
        assertEquals(listOf("HANDOFF"), groups[1].second.map { it.title })
        assertEquals(listOf("rex"), groups[2].second.map { it.session }.distinct())
    }

    @Test fun `idle shows only when asked, and panes without records never do`() {
        val idle = activityGroups(state, preferGenerated = false, showIdle = true).single { it.first == ActivityGroup.Idle }
        assertEquals(listOf("b4"), idle.second.map { it.block.value })
    }

    @Test fun `an agent gone idle is listed as done, with its own icon`() {
        val idle = StatusRecord("", ProgramState.Idle, app = "claude-code", updatedAt = "2026-11-09T10:00:00Z")
        val b = pane("p", "node", title = "OC | Greeting").copy(status = mapOf("" to idle))
        val app = host(session("s", "Work") to listOf(tab("w", b))).withBlocks(b).app()
        val row = activityGroups(app, preferGenerated = false, showIdle = false).single { it.first == ActivityGroup.Done }.second.single()
        assertEquals("OC | Greeting", row.title)
        assertEquals(dev.mahali.hatch.core.ProgramKey("claude"), row.program)
    }

    @Test fun `root and child records stay separate with messages reasons progress and inherited app`() {
        val root = StatusRecord("", ProgramState.Working, title = "Agent", app = "claude", message = "Building", progress = 37)
        val child = StatusRecord("build/test", ProgramState.Blocked, title = "Tests", message = "Allow integration tests?",
            kind = dev.mahali.hatch.core.BlockedKind.Permission, progress = 40)
        val done = StatusRecord("build/lint", ProgramState.Done, title = "Lint", message = "No problems")
        val b = pane("p", "node", title = "Terminal").copy(status = listOf(root, child, done).associateBy { it.id })
        val app = host(session("s", "Work") to listOf(tab("w", b))).withBlocks(b).app()
        val rows = activityGroups(app, false, false).flatMap { it.second }
        assertEquals(setOf("", "build/test", "build/lint"), rows.map { it.recordId }.toSet())
        val blocked = rows.single { it.recordId == "build/test" }
        assertEquals(ActivityGroup.NeedsInput, blocked.group)
        assertEquals("Allow integration tests?", blocked.message)
        assertEquals(dev.mahali.hatch.core.BlockedKind.Permission, blocked.kind)
        assertEquals(40, blocked.progress)
        assertEquals(dev.mahali.hatch.core.ProgramKey("claude"), blocked.program)
        assertEquals("Work", blocked.source)
        assertEquals(37, rows.single { it.recordId.isEmpty() }.progress)
    }

    @Test fun `identical record ids on different panes remain different rows`() {
        val a = pane("a", "claude").copy(status = mapOf("job" to StatusRecord("job", ProgramState.Working)))
        val b = pane("b", "claude").copy(status = a.status)
        val app = host(session("s", "Work") to listOf(tab("w", a, b))).withBlocks(a, b).app()
        val rows = activityGroups(app, false, false).single().second
        assertEquals(setOf("a", "b"), rows.map { it.block.value }.toSet())
    }

}
