package dev.mahali.hatch.engine

import dev.mahali.hatch.core.AppState
import dev.mahali.hatch.core.Block
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.BlockedKind
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.HostState
import dev.mahali.hatch.core.Layout
import dev.mahali.hatch.core.Link
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.core.PromptActionId
import dev.mahali.hatch.core.PromptChoice
import dev.mahali.hatch.core.PromptChoices
import dev.mahali.hatch.core.PromptFallback
import dev.mahali.hatch.core.PromptState
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.PromptView
import dev.mahali.hatch.core.Session
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.StatusRecord
import dev.mahali.hatch.core.Window
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.protocol.BlockEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticePolicyTest {
    private val host = HostId("host")
    private val session = SessionId("session")
    private val tabA = WindowId("a")
    private val tabB = WindowId("b")
    private val paneA = BlockId("pa")
    private val paneB = BlockId("pb")
    private val targetA = PromptTarget(WindowRef(host, session, tabA), paneA)
    private val targetB = PromptTarget(WindowRef(host, session, tabB), paneB)
    private val on = Settings(notifications = true)

    private fun state(statusA: StatusRecord? = null, owner: String? = null) = AppState(listOf(host), mapOf(host to HostState(
        Host(host, "Mac", "https://example.invalid"), clientId = "me", sessions = listOf(session),
        sessionsById = mapOf(session to Session(session, "Work", windows = listOf(tabA, tabB))),
        windowsById = mapOf(tabA to Window(tabA, "Build", Layout.Leaf(paneA)), tabB to Window(tabB, "", Layout.Leaf(paneB))),
        blocksById = mapOf(paneA to Block(paneA, owner = owner, status = listOfNotNull(statusA).associateBy { it.id }), paneB to Block(paneB, title = "vim")),
    )))

    private fun policy(settings: Settings = on, allowed: Boolean = true) = NoticePolicy().apply {
        onState(state())
        onSettings(settings)
        onDeliveryAllowed(allowed, allowed)
    }

    private fun status(block: BlockId, state: ProgramState, id: String = "", message: String = "", kind: BlockedKind? = null) =
        BlockEvent.StatusChanged(session, block, StatusRecord(id, state, message = message, kind = kind))

    private val NoticePolicy.now get() = notices.value

    @Test fun aClosedPaneTakesItsNoticesWithItButAnOfflineHostKeepsThem() {
        val online = { s: AppState -> s.copy(byHost = s.byHost.mapValues { it.value.copy(link = Link.Online) }) }
        val p = policy().apply { onState(online(state())) }
        p.onEvent(host, status(paneA, ProgramState.Done))
        val withoutA = { s: AppState -> s.copy(byHost = s.byHost.mapValues { it.value.copy(blocksById = it.value.blocksById - paneA) }) }
        p.onState(withoutA(state()))
        assertEquals("Finished", p.now[targetA]?.text) // offline: the list may be stale
        p.onState(online(withoutA(state())))
        assertNull(p.now[targetA])
    }

    @Test fun reReportsDoNotNotifyAgainUntilRemoved() {
        val p = policy()
        p.onEvent(host, status(paneA, ProgramState.Done))
        p.onScreen(WindowRef(host, session, tabA), targetA)
        p.onScreen(null, null)
        p.onEvent(host, status(paneA, ProgramState.Done))
        assertNull(p.now[targetA])
        p.onEvent(host, BlockEvent.StatusRemoved(session, paneA, setOf("")))
        p.onEvent(host, status(paneA, ProgramState.Done))
        assertEquals("Finished", p.now[targetA]?.text)
    }

    @Test fun otherStatesCancelAndReEnteringNotifiesAgain() {
        val p = policy()
        p.onEvent(host, status(paneA, ProgramState.Done))
        p.onEvent(host, status(paneA, ProgramState.Working))
        assertNull(p.now[targetA])
        p.onEvent(host, status(paneA, ProgramState.Done))
        assertEquals("Finished", p.now[targetA]?.text)
        assertFalse(p.now[targetA]!!.quiet)
    }

    @Test fun childTasksNeverNotify() {
        val p = policy()
        p.onEvent(host, status(paneA, ProgramState.Done, id = "task-1"))
        p.onEvent(host, status(paneA, ProgramState.Blocked, id = "task-2"))
        assertTrue(p.now.isEmpty())
    }

    @Test fun visiblePaneNeverNotifiesAndIsCancelledWhenShown() {
        val p = policy()
        p.onScreen(WindowRef(host, session, tabA), targetA)
        p.onEvent(host, status(paneA, ProgramState.Done))
        assertNull(p.now[targetA])
        p.onEvent(host, status(paneB, ProgramState.Done))
        assertEquals("Finished", p.now[targetB]?.text)
        p.onScreen(WindowRef(host, session, tabB), targetB)
        assertNull(p.now[targetB])
    }

    @Test fun settingsGateProgramActivity() {
        val off = policy(Settings(notifications = false))
        off.onEvent(host, status(paneA, ProgramState.Done))
        assertTrue(off.now.isEmpty())
        val noActivity = policy(on.copy(notifyProgramActivity = false))
        noActivity.onEvent(host, status(paneA, ProgramState.Done))
        assertTrue(noActivity.now.isEmpty())
        val noPermission = policy(allowed = false)
        noPermission.onEvent(host, status(paneA, ProgramState.Done))
        assertTrue(noPermission.now.isEmpty())
    }

    @Test fun disablingSettingsOrPermissionRemovesActivityNotices() {
        val p = policy()
        p.onEvent(host, status(paneA, ProgramState.Done))
        p.onEvent(host, BlockEvent.DesktopNotification(session, paneB, "Build", "ok"))
        assertTrue(p.activityEnabled.value)
        p.onSettings(on.copy(notifyProgramActivity = false))
        assertEquals(setOf(targetB), p.now.keys)
        assertFalse(p.activityEnabled.value)

        val q = policy()
        q.onEvent(host, status(paneA, ProgramState.Done))
        q.onDeliveryAllowed(false, false)
        assertTrue(q.now.isEmpty())
    }

    @Test fun desktopNotificationsRespectNotifyTerminal() {
        val p = policy()
        p.onEvent(host, BlockEvent.DesktopNotification(session, paneB, null, "Build done"))
        assertEquals(Notice(targetB, Notice.Kind.Terminal, "vim", "Build done", serial = 1), p.now[targetB])
        val off = policy(on.copy(notifyTerminal = false))
        off.onEvent(host, BlockEvent.DesktopNotification(session, paneB, "t", "b"))
        assertTrue(off.now.isEmpty())
    }

    @Test fun mutingActivityKeepsDesktopNotifications() {
        val p = policy()
        p.onDeliveryAllowed(activity = false, terminal = true)
        p.onEvent(host, status(paneA, ProgramState.Done))
        assertTrue(p.now[targetA] == null)
        p.onEvent(host, BlockEvent.DesktopNotification(session, paneB, null, "Build done"))
        assertEquals("Build done", p.now[targetB]?.text)
    }

    @Test fun blockedNoticeShowsChoicesFromPromptEntriesAndGoesWithIt() {
        val p = policy()
        p.onState(state(StatusRecord("", ProgramState.Blocked, title = "Claude", kind = BlockedKind.Permission)))
        val choices = PromptChoices(PromptChoice("Yes", PromptActionId("1")), listOf(PromptChoice("No", PromptActionId("2"))))
        p.onPrompts(mapOf(targetA to PromptState.Offered(PromptView("Run ls?", "ls -la", choices))))
        val notice = p.now[targetA]!!
        assertEquals("Claude", notice.title)
        assertEquals("Run ls?", notice.text)
        assertEquals(choices, notice.choices)
        assertEquals("ls -la", notice.detail)
        assertEquals(ProgramState.Blocked, notice.state)
        assertTrue(notice.silent) // already Blocked when observed: shown, not alerted

        p.onPrompts(mapOf(targetA to PromptState.Sending(PromptView("Run ls?", "ls -la", choices))))
        assertEquals("Sending your answer…", p.now[targetA]?.text)
        assertNull(p.now[targetA]?.choices)
        assertTrue(p.now[targetA]!!.quiet)
        p.onPrompts(mapOf(targetA to PromptState.OpenOnly(PromptFallback.Offline)))
        assertEquals("Approval needed", p.now[targetA]?.text)

        p.onPrompts(emptyMap())
        assertNull(p.now[targetA])
    }

    @Test fun blockedFoundOnConnectPostsSilentlyButANewBlockedSpellAlerts() {
        val choices = PromptChoices(PromptChoice("Yes", PromptActionId("1")), listOf(PromptChoice("No", PromptActionId("2"))))
        val offered = mapOf(targetA to PromptState.Offered(PromptView("Run ls?", "", choices)))
        val blocked = state(StatusRecord("", ProgramState.Blocked))

        val connect = policy()
        connect.onState(blocked)
        connect.onPrompts(mapOf(targetA to PromptState.Checking(PromptView("Run ls?", "", choices))))
        connect.onPrompts(offered)
        assertEquals("Run ls?", connect.now[targetA]?.text)
        assertTrue(connect.now[targetA]!!.quiet)
        assertTrue(connect.now[targetA]!!.silent)

        // The status event arrives before the pane is in the state; its prompt entry then alerts.
        val live = policy().apply { onState(AppState()) }
        live.onEvent(host, status(paneA, ProgramState.Blocked))
        live.onState(blocked)
        live.onPrompts(offered)
        assertEquals("Run ls?", live.now[targetA]?.text)
        assertFalse(live.now[targetA]!!.quiet)
        assertFalse(live.now[targetA]!!.silent)

        // Reconnecting re-probes the same spell: no second alert.
        live.onScreen(WindowRef(host, session, tabA), targetA)
        live.onScreen(null, null)
        live.onPrompts(emptyMap())
        live.onPrompts(offered)
        assertTrue(live.now[targetA]!!.silent)
    }

    @Test fun aRepeatedDoneWithANewMessageUpdatesTheTextQuietly() {
        val p = policy()
        p.onEvent(host, status(paneA, ProgramState.Done))
        assertEquals("Finished", p.now[targetA]?.text)
        p.onEvent(host, status(paneA, ProgramState.Done, message = "Tests pass"))
        assertEquals("Tests pass", p.now[targetA]?.text)
        assertTrue(p.now[targetA]!!.quiet)
        val serial = p.now[targetA]!!.serial
        p.onEvent(host, status(paneA, ProgramState.Done, message = "Tests pass"))
        assertEquals(serial, p.now[targetA]!!.serial)
    }

    @Test fun aNoticeNamesThePaneInTheReportedSessionFirst() {
        val other = SessionId("other")
        val p = policy()
        p.onState(AppState(listOf(host), mapOf(host to state().byHost.getValue(host).let {
            it.copy(sessions = listOf(other, session), sessionsById = it.sessionsById + (other to Session(other, "Old", windows = listOf(tabA))))
        })))
        p.onEvent(host, BlockEvent.DesktopNotification(session, paneA, null, "hi"))
        assertEquals(setOf(targetA), p.now.keys)
    }

    @Test fun moreThanThreeChoicesOnlyOpen() {
        val p = policy()
        p.onState(state(StatusRecord("", ProgramState.Blocked)))
        val four = PromptChoices(PromptChoice("1", PromptActionId("1")), (2..4).map { PromptChoice("$it", PromptActionId("$it")) })
        p.onPrompts(mapOf(targetA to PromptState.Offered(PromptView("Pick", "", four))))
        assertEquals("Pick", p.now[targetA]?.text)
        assertNull(p.now[targetA]?.choices)
    }

    @Test fun promptEntriesNeedBlockedRecordAndEnabled() {
        val p = policy(allowed = false)
        p.onState(state(StatusRecord("", ProgramState.Blocked)))
        val entries = mapOf(targetA to PromptState.OpenOnly(PromptFallback.Unsupported))
        p.onPrompts(entries)
        assertTrue(p.now.isEmpty())
        p.onDeliveryAllowed(true, true)
        assertEquals("Needs your input", p.now[targetA]?.text)

        val working = policy()
        working.onState(state(StatusRecord("", ProgramState.Working)))
        working.onPrompts(entries)
        assertTrue(working.now.isEmpty())
    }

    @Test fun repeatedDesktopNotificationPostsAgain() {
        val p = policy()
        p.onEvent(host, BlockEvent.DesktopNotification(session, paneB, null, "Build done"))
        val first = p.now[targetB]
        p.onEvent(host, BlockEvent.DesktopNotification(session, paneB, null, "Build done"))
        assertTrue(first != p.now[targetB])
        assertFalse(p.now[targetB]!!.quiet)
    }

    @Test fun clipboardNeedsOwnershipAndSizeLimit() {
        val p = NoticePolicy
        val mine = state(owner = "me")
        assertTrue(p.owns(mine, host, paneA))
        assertFalse(p.owns(state(owner = "other"), host, paneA))
        assertFalse(p.owns(state(owner = null), host, paneA))
        assertTrue(p.mayWriteClipboard(mine, host, paneA, "hello"))
        assertTrue(p.mayWriteClipboard(mine, host, paneA, "x".repeat(1 shl 20)))
        assertFalse(p.mayWriteClipboard(mine, host, paneA, "x".repeat((1 shl 20) + 1)))
        assertFalse(p.mayWriteClipboard(mine, host, paneA, ""))
        assertFalse(p.mayWriteClipboard(state(owner = "other"), host, paneA, "hello"))
        assertFalse(p.mayWriteClipboard(state(owner = null), host, paneA, "hello"))
        assertFalse(p.mayWriteClipboard(mine, HostId("elsewhere"), paneA, "hello"))
    }
}
