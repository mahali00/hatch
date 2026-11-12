package dev.mahali.hatch.protocol

import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.blocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Perf fixture, not a check. Writes HOLD_FILE.ready when the panes exist; holds until HOLD_FILE is deleted. */
class LiveManyPanesTest {
    @Test fun `hold many streaming panes`() = runBlocking {
        val url = System.getenv("REX_LIVE_URL")
        val hold = System.getenv("REX_PERF_HOLD_FILE")?.let(::File)
        assumeTrue("REX_LIVE_URL and REX_PERF_HOLD_FILE not set", !url.isNullOrBlank() && hold != null)
        val count = System.getenv("REX_PERF_PANES")?.toInt() ?: 8
        val interval = System.getenv("REX_PERF_INTERVAL") ?: "0.05"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val control = RexControl(OkHttpClient(), url!!, { ClientInfo(version = "test", label = "many-panes-test") }, scope)
        val sync = HostSync(Host(HostId("live-perf"), "live-perf", url), control, scope)
        var created: SessionId? = null
        sync.start()
        try {
            withTimeout(15_000) { sync.state.first { it.link == Link.Online } }
            val session = sync.api.createSession(stream(0, interval))
            created = session
            sync.api.setSessionLabel(session, "Many panes QA")
            val first = withTimeout(15_000) {
                sync.state.first { s -> s.sessionsById[session]?.windows?.firstOrNull()?.let { s.windowsById[it]?.layout } != null }
            }
            val window = first.sessionsById.getValue(session).windows.first()
            val panes = mutableListOf<BlockId>(first.windowsById.getValue(window).layout!!.blocks().first())
            for (i in 1 until count) {
                panes += sync.api.newSplit(session, panes.last(), stream(i, interval), below = i % 2 == 0)!!
            }
            withTimeout(15_000) { sync.state.first { it.windowsById[window]?.layout?.blocks()?.size == count } }
            File(hold!!.path + ".ready").writeText(session.value)
            val deadline = System.currentTimeMillis() + 60_000L * (System.getenv("REX_PERF_MINUTES")?.toLong() ?: 10)
            while (!hold.exists() && System.currentTimeMillis() < deadline) delay(200)
        } finally {
            try {
                created?.let { id ->
                    sync.api.destroySession(id)
                    withTimeout(10_000) { sync.state.first { id !in it.sessions } }
                }
            } finally { sync.stop(); scope.cancel() }
        }
    }

    private fun stream(i: Int, interval: String): String {
        val script = """
            import sys,time,base64
            title=base64.b64encode('Pane $i'.encode()).decode()
            print('\x1b]7501;state=working:app=terminal:title='+title+'\x1b\\',flush=True)
            n=0
            while $interval>0:
                n+=1
                print('\x1b[3%dm pane $i line %06d \x1b[0m'%(n%7+1,n)+'='*60,flush=True)
                time.sleep($interval)
            sys.stdin.read()
        """.trimIndent()
        return "python3 -u -c '" + script.replace("'", "'\"'\"'") + "'"
    }
}
