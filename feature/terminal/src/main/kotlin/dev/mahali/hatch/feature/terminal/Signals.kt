package dev.mahali.hatch.feature.terminal

import android.content.Context
import dev.mahali.hatch.core.BlockId
import dev.mahali.hatch.core.WindowRef
import dev.mahali.hatch.engine.NoticePolicy
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.protocol.BlockEvent
import dev.mahali.hatch.protocol.toEvent

internal suspend fun followSignals(
    rex: Rex,
    tab: WindowRef,
    panes: () -> Collection<BlockId>,
    context: Context,
    haptics: Haptics,
    onLink: (BlockId, String) -> Unit,
) {
    val sync = rex.sync(tab.host) ?: return
    sync.blockSignals.collect { signal ->
        if (signal.session != tab.session || signal.block !in panes()) return@collect
        when (val event = signal.toEvent()) {
            is BlockEvent.ClipboardWritten -> {
                val text = event.text ?: event.id?.takeIf { event.bytes > 0 }
                    ?.let { id -> runCatching { sync.api.clipboardRead(tab.session, event.block, id) }.getOrNull() }
                if (text != null && NoticePolicy.mayWriteClipboard(rex.state.value, tab.host, event.block, text)) context.copyText(text)
            }
            // Program clicks go through the link menu: opening here would let a bad target cancel the collector.
            is BlockEvent.UrlClicked -> {
                if (isWebLink(event.url) && NoticePolicy.owns(rex.state.value, tab.host, event.block)) onLink(event.block, event.url)
            }
            is BlockEvent.Bell -> haptics.bell()
            else -> Unit
        }
    }
}
