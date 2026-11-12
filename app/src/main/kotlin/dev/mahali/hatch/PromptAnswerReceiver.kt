package dev.mahali.hatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mahali.hatch.core.PromptActionId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** An Intent selects a live choice; it never supplies terminal input. */
class PromptAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val value = intent.getStringExtra(NoticeDelivery.EXTRA_ACTION) ?: return
        if (runCatching { java.util.UUID.fromString(value) }.isFailure) return
        val receipt = goAsync()
        val app = context.applicationContext as HatchApp
        app.scope.launch {
            try {
                withTimeoutOrNull(8_000) { app.rex.promptActions.answer(PromptActionId(value)) }
            } finally {
                receipt.finish()
            }
        }
    }
}
