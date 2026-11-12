package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.Axis
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.blocks
import dev.mahali.hatch.core.rects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LivePaneLayoutTest {
    @Test fun `real split resize swap and move preserve four live programs`() = runBlocking {
        val url = System.getenv("REX_LIVE_URL")
        assumeTrue("REX_LIVE_URL not set", !url.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val control = RexControl(OkHttpClient(), url!!, { ClientInfo(version = "test", label = "pane-layout-test") }, scope)
        val sync = HostSync(Host(HostId("live-layout"), "live-layout", url), control, scope)
        var created: SessionId? = null
        sync.start()
        try {
            withTimeout(15_000) { sync.state.first { it.link == Link.Online } }
            val session = sync.api.createSession(command("claude", "Keyboard handling"))
            created = session
            sync.api.setSessionLabel(session, "Split workspace QA")
            val initial = withTimeout(15_000) {
                sync.state.first { s -> s.sessionsById[session]?.windows?.firstOrNull()?.let { s.windowsById[it]?.layout } != null }
            }
            val window = initial.sessionsById.getValue(session).windows.first()
            val a = initial.windowsById.getValue(window).layout!!.blocks().first()
            val b = sync.api.newSplit(session, a, command("opencode", "Rendering review"))!!
            val c = sync.api.newSplit(session, a, command("terminal", "Build and tests"), below = true)!!
            val d = sync.api.newSplit(session, b, command("gemini", "Protocol review"), below = true)!!
            suspend fun waitLayout(predicate: (Layout) -> Boolean): Layout = withTimeout(10_000) {
                sync.state.first { it.windowsById[window]?.layout?.let(predicate) == true }.windowsById.getValue(window).layout!!
            }
            val grid = waitLayout { it.blocks().size == 4 }
            assertEquals(setOf(a, b, c, d), grid.blocks().toSet())
            assertTrue(grid.rects().values.all { it.width == 0.5f && it.height == 0.5f })
            sync.api.resizePane(session, a, b, Axis.Row, 0.65f)
            waitLayout { (it as? Layout.Split)?.ratio?.let { r -> kotlin.math.abs(r - 0.65f) < 0.001f } == true }
            sync.api.resizePane(session, a, b, Axis.Row, 0.5f)
            waitLayout { (it as? Layout.Split)?.ratio == 0.5f }
            sync.api.swapBlocks(session, a, b)
            waitLayout { it.rects()[a]?.x == 0.5f }
            sync.api.swapBlocks(session, a, b)
            waitLayout { it.rects()[a]?.x == 0f }
            sync.api.moveBlock(session, d, b, vertical = true, before = true)
            waitLayout { it.rects()[d]?.y == 0f }
            sync.api.moveBlock(session, d, b, vertical = true, before = false)
            waitLayout { it.rects()[d]?.y == 0.5f }
            println("PASS: real 2x2 layout, resize persistence, swap and directional move")

            val hold = System.getenv("REX_LAYOUT_HOLD_FILE")?.let(::File)
            if (hold != null) {
                File(hold.path + ".ready").writeText(buildJsonObject {
                    put("session", session.value); put("window", window.value)
                    put("label", "Split workspace QA")
                }.toString())
                val deadline = System.currentTimeMillis() + 600_000
                var revision = -1L
                while (!hold.exists() && System.currentTimeMillis() < deadline) {
                    val state = sync.state.value
                    val currentRevision = state.sessionsById[session]?.revision ?: -1L
                    if (currentRevision != revision) {
                        revision = currentRevision
                        File(hold.path + ".state").writeText(buildJsonObject {
                            put("revision", revision)
                            put("panes", JsonArray(state.windowsById[window]?.layout?.rects().orEmpty().map { (block, r) ->
                                buildJsonObject {
                                    put("block", block.value)
                                    put("title", state.blocksById[block]?.status?.get("")?.title.orEmpty())
                                    put("x", r.x); put("y", r.y); put("width", r.width); put("height", r.height)
                                }
                            }))
                        }.toString())
                    }
                    delay(200)
                }
            }
        } finally {
            try {
                created?.let { id ->
                    sync.api.destroySession(id)
                    withTimeout(10_000) { sync.state.first { id !in it.sessions } }
                }
            } finally { sync.stop(); scope.cancel() }
        }
    }

    private fun command(app: String, title: String): String {
        val script = """
            import sys,base64
            title=base64.b64encode('$title'.encode()).decode()
            print('\x1b]7501;state=working:app=$app:title='+title+'\x1b\\',flush=True)
            print('$title\n\nSplit workspace QA\nTemporary terminal for layout checks.\n\nReady for interaction.',flush=True)
            print('\n'.join('QA history line %03d' % i for i in range(100)),flush=True)
            sys.stdin.read()
        """.trimIndent()
        return "python3 -u -c '" + script.replace("'", "'\"'\"'") + "'"
    }
}
