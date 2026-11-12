package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockedKind
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.allBlocks
import dev.mahali.hatch.core.shellWords
import dev.mahali.hatch.core.statusApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveProgramStatusTest {
    @Test fun `real OSC reports retain child tasks kinds progress and record lifetimes`() = runBlocking {
        val url = System.getenv("REX_LIVE_URL")
        assumeTrue("REX_LIVE_URL not set", !url.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val control = RexControl(OkHttpClient(), url!!, { ClientInfo(version = "test", label = "status-protocol-test") }, scope)
        val sync = HostSync(Host(HostId("live"), "live", url), control, scope)
        var created: SessionId? = null
        sync.start()
        try {
            withTimeout(15_000) { sync.state.first { it.link == Link.Online } }
            val command = "python3 -u -c '" + script.replace("'", "'\"'\"'") + "'"
            assertEquals(script, shellWords(command).last())
            val createdReply = sync.api.call("session.create") {
                put("initial_windows", JsonArray(listOf(buildJsonObject {
                    putJsonObject("layout") { putJsonObject("block") {
                        put("flavor", "com.superlogical.terminal.shell")
                        putJsonObject("options") {
                            put("command", JsonArray(shellWords(command).map(::JsonPrimitive)))
                            putJsonObject("exit") { put("on_completion", false) }
                        }
                    } }
                })))
            }
            val session = SessionId(createdReply.str("session_id")!!)
            created = session
            sync.api.setSessionLabel(session, "Program status QA")
            val block = withTimeout(15_000) {
                sync.state.first { s -> s.sessionsById[session]?.windows?.firstOrNull()?.let { s.windowsById[it] }?.allBlocks()?.isNotEmpty() == true }
                    .let { s -> s.windowsById[s.sessionsById[session]!!.windows.first()]!!.allBlocks().first() }
            }
            val pane = withTimeout(15_000) { sync.state.first { it.blocksById[block]?.status?.size == 6 }.blocksById.getValue(block) }
            assertEquals(37, pane.status.getValue("").progress)
            assertEquals(BlockedKind.Permission, pane.status.getValue("build/test").kind)
            assertEquals(40, pane.status.getValue("build/test").progress)
            assertEquals("Allow integration tests?", pane.status.getValue("build/test").message)
            assertEquals(BlockedKind.Auth, pane.status.getValue("auth").kind)
            assertEquals(BlockedKind.Question, pane.status.getValue("question").kind)
            assertEquals("claude", pane.statusApp(pane.status.getValue("build/test")))
            println("PASS: real OSC root, children, all blocked kinds, messages and percentages")

            val hold = System.getenv("REX_STATUS_HOLD_FILE")?.let(::File)
            if (hold != null) {
                for (app in listOf("gemini", "opencode")) {
                    val childScript = "import sys; print('\\x1b]7501;state=idle:app=$app\\x1b\\\\', flush=True); sys.stdin.read()"
                    val childCommand = "python3 -u -c '" + childScript.replace("'", "'\"'\"'") + "'"
                    val child = sync.api.newSplit(session, block, childCommand)
                    assertTrue(child != null)
                    withTimeout(10_000) { sync.state.first { it.blocksById[child]?.status?.get("")?.app == app } }
                }
                File(hold.path + ".ready").writeText(buildJsonObject {
                    put("session", session.value); put("block", block.value); put("label", "Program status QA")
                }.toString())
                val deadline = System.currentTimeMillis() + 180_000
                while (!hold.exists() && System.currentTimeMillis() < deadline) delay(200)
            }
            suspend fun send(text: String) {
                sync.api.call("com.superlogical.terminal.write") {
                    put("session_id", session.value); put("block_id", block.value)
                    put("args", buildJsonObject { put("data", "$text\n") })
                }
            }
            send("unknown")
            withTimeout(10_000) { sync.state.first { it.blocksById[block]?.status?.get("ack")?.message == "unknown processed" } }
            val queried = sync.api.terminalReply(session, block, "program_status").payload.arr("records").objects().mapNotNull(::decodeStatusRecord).associateBy { it.id }
            assertEquals(ProgramState.Working, queried.getValue("").state)
            send("question")
            val replaced = withTimeout(10_000) { sync.state.first { it.blocksById[block]?.status?.get("build/test")?.kind == BlockedKind.Question } }
                .blocksById.getValue(block).status.getValue("build/test")
            assertNull(replaced.progress)
            assertEquals("Which test suite?", replaced.message)
            send("clear")
            val cleared = withTimeout(10_000) { sync.state.first { s -> s.blocksById[block]?.status?.keys?.none { it == "build" || it.startsWith("build/") } == true } }
                .blocksById.getValue(block)
            assertFalse("build/test" in cleared.status)
            assertTrue("auth" in cleared.status)
            send("exit")
            val exited = withTimeout(10_000) { sync.state.first { it.blocksById[block]?.exited == true } }.blocksById.getValue(block)
            assertTrue(exited.status.keys.containsAll(setOf("done", "error")))
            assertTrue(exited.status.values.none { it.state == ProgramState.Working || it.state == ProgramState.Blocked })
            val afterExit = sync.api.terminalReply(session, block, "program_status").payload.arr("records").objects().mapNotNull(::decodeStatusRecord)
            assertTrue(afterExit.map { it.id }.containsAll(setOf("done", "error")))
            assertTrue(afterExit.none { it.state == ProgramState.Working || it.state == ProgramState.Blocked })
            println("PASS: unknown reports ignored, replacement clears omitted fields, subtree clear, done/error survive exit")
        } finally {
            try {
                created?.let { id ->
                    sync.api.destroySession(id)
                    withTimeout(10_000) { sync.state.first { id !in it.sessions } }
                }
            } finally {
                sync.stop()
                scope.cancel()
            }
        }
    }

    private val script = """
        import sys, base64
        def report(state, id='', kind=None, title=None, msg=None, progress=None, app=None):
            values = {'state': state}
            if id: values['id'] = id
            if kind: values['kind'] = kind
            if app: values['app'] = app
            if progress is not None: values['progress'] = str(progress)
            for key, value in [('title', title), ('msg', msg)]:
                if value is not None: values[key] = base64.b64encode(value.encode()).decode()
            sys.stdout.write('\x1b]7501;' + ':'.join(k + '=' + v for k, v in values.items()) + '\x1b\\')
            sys.stdout.flush()
        report('working', app='claude', title='Agent build', msg='Compiling the project', progress=37)
        report('blocked', id='build/test', kind='permission', title='Integration tests', msg='Allow integration tests?', progress=40)
        report('blocked', id='auth', kind='auth', title='Deployment login', msg='Sign in to continue')
        report('blocked', id='question', kind='question', title='Target environment', msg='Choose staging or production')
        report('done', id='done', title='Lint', msg='No problems found')
        report('error', id='error', title='Deployment', msg='Example deployment failed')
        print('Program status QA ready', flush=True)
        for command in sys.stdin:
            command = command.strip()
            if command == 'unknown':
                report('future-state', app='claude')
                report('idle', id='ack', msg='unknown processed')
            if command == 'question': report('blocked', id='build/test', kind='question', msg='Which test suite?')
            if command == 'clear': report('clear', id='build')
            if command == 'exit': break
    """.trimIndent()
}
