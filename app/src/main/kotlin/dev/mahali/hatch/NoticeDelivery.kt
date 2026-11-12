package dev.mahali.hatch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mahali.hatch.core.PromptActionId
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.engine.Notice
import dev.mahali.hatch.engine.NoticePolicy
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.protocol.toEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NoticeDelivery(private val context: Context, private val rex: Rex, settings: SettingsStore, scope: CoroutineScope) {
    private val policy = NoticePolicy()
    private val screen = MutableStateFlow<WindowRef?>(null)
    private val manager = context.getSystemService(NotificationManager::class.java)

    private var shown = emptyMap<PromptTarget, Notice>()

    var visible: WindowRef?
        get() = screen.value
        set(value) { screen.value = value }

    init {
        createChannels()
        scope.launch { cancelStalePrompts() }
        update { onSettings(settings.settings.value) }
        refreshPermission()
        scope.launch { policy.activityEnabled.collect { rex.promptActions.setEnabled(it && Build.VERSION.SDK_INT >= 31) } }
        scope.launch { settings.settings.collect { update { onSettings(it) } } }
        scope.launch { rex.promptActions.entries.collect { update { onPrompts(it) } } }
        scope.launch { combine(screen, rex.focusedPane, ::Pair).collect { (visible, focused) -> update { onScreen(visible, focused) } } }
        // Closed panes' notices leave the tray: onState drops them.
        scope.launch { rex.state.map { s -> s.byHost.mapValues { it.value.blocksById.keys } }.distinctUntilChanged().collect { update { } } }
        scope.launch {
            rex.state.map { state -> state.hosts.mapNotNull { id -> rex.sync(id)?.let { id to it } } }.distinctUntilChanged()
                .flatMapLatest { hosts -> merge(*hosts.map { (id, sync) -> sync.blockSignals.map { id to it } }.toTypedArray()) }
                .mapNotNull { (host, signal) -> signal.toEvent()?.let { host to it } }
                .collect { (host, event) -> update { onEvent(host, event) } }
        }
    }

    // Choices are process-local: a previous process's prompt buttons must not stay usable. Runs off the main thread under the tray lock.
    @Synchronized private fun cancelStalePrompts() {
        val ours = shown.keys.mapTo(HashSet(), ::tag)
        manager.activeNotifications.filter { it.notification.extras.getBoolean(PROMPT_EXTRAS) && it.tag !in ours }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun refreshPermission() {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        fun open(channel: String) = enabled && manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
        update { onDeliveryAllowed(open(CHANNEL_ACTIVITY), open(CHANNEL_TERMINAL)) }
    }

    // Read the policy's result under the same lock: a quiet update arriving before an alert was shown would otherwise lose the alert.
    @Synchronized private fun update(input: NoticePolicy.() -> Unit) {
        policy.onState(rex.state.value)
        policy.input()
        val notices = policy.notices.value
        for (target in shown.keys - notices.keys) manager.cancel(tag(target), NOTICE_ID)
        for ((target, notice) in notices) if (shown[target] != notice) post(notice)
        shown = notices
    }

    private fun post(notice: Notice) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val target = notice.target
        val hostState = rex.state.value.byHost[target.window.host]
        val where = listOfNotNull(hostState?.sessionsById?.get(target.window.session)?.label, hostState?.host?.name)
            .filter { it.isNotBlank() }.joinToString(" on ")
        val channel = if (notice.kind == Notice.Kind.Activity) CHANNEL_ACTIVITY else CHANNEL_TERMINAL
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_hatch)
            .setContentTitle(notice.title)
            .setContentText("${notice.text} · $where")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(notice.text, notice.detail).joinToString("\n\n")))
            .setContentIntent(open(target))
            .setAutoCancel(true)
            .setOnlyAlertOnce(notice.quiet)
            .setSilent(notice.silent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        val choices = notice.choices
        if (Build.VERSION.SDK_INT >= 31 && choices != null) {
            builder.setExtras(Bundle().apply { putBoolean(PROMPT_EXTRAS, true) })
            choices.all.forEach { choice ->
                builder.addAction(NotificationCompat.Action.Builder(0, choice.label, answerIntent(choice.action))
                    .setAuthenticationRequired(true).build())
            }
        }
        runCatching { NotificationManagerCompat.from(context).notify(tag(target), NOTICE_ID, builder.build()) }
    }

    private fun tag(target: PromptTarget): String = "pane:${target.window.host.value}:${target.block.value}"

    private fun answerIntent(action: PromptActionId): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, PromptAnswerReceiver::class.java)
            .setData(Uri.Builder().scheme("hatch-prompt").authority("answer").appendPath(action.value).build())
            .putExtra(EXTRA_ACTION, action.value), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun open(target: PromptTarget): PendingIntent {
        val ref = target.window
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.Builder().scheme("hatch").authority("pane").appendPath(ref.host.value).appendPath(ref.session.value)
                .appendPath(ref.window.value).appendPath(target.block.value).build())
            .putExtra(EXTRA_HOST, ref.host.value).putExtra(EXTRA_SESSION, ref.session.value).putExtra(EXTRA_WINDOW, ref.window.value)
            .putExtra(EXTRA_BLOCK, target.block.value)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_ACTIVITY, "Program activity", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_TERMINAL, "Terminal notifications", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN),
        ))
    }

    companion object {
        const val CHANNEL_ACTIVITY = "activity"
        const val CHANNEL_TERMINAL = "terminal"
        const val CHANNEL_CONNECTION = "connection"
        const val EXTRA_HOST = "host"
        const val EXTRA_SESSION = "session"
        const val EXTRA_WINDOW = "window"
        const val EXTRA_BLOCK = "block"
        const val EXTRA_ACTION = "prompt_action"
        private const val PROMPT_EXTRAS = "hatch_prompt_actions"
        private const val NOTICE_ID = 1
    }
}
