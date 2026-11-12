package dev.mahali.hatch.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

public fun BlockSignal.toEvent(): BlockEvent? = when (name) {
    "program_status_changed" -> payload.obj("record")?.let { decodeStatusRecord(it, payload.str("id").orEmpty()) }
        ?.let { BlockEvent.StatusChanged(session, block, it) }
    "program_status_removed" -> BlockEvent.StatusRemoved(session, block, payload.arr("ids").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet())
    "desktop_notification" -> BlockEvent.DesktopNotification(session, block, payload.str("title"), payload.str("body").orEmpty())
    "clipboard_written" -> {
        val text = payload.str("text")
        val id = payload.long("id")
        if (text == null && id == null) null else BlockEvent.ClipboardWritten(session, block, text, id, payload.long("bytes") ?: 0)
    }
    "url_clicked" -> payload.str("url")?.let { BlockEvent.UrlClicked(session, block, it) }
    "bell" -> BlockEvent.Bell(session, block)
    else -> null
}
