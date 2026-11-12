package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchPill
import dev.mahali.hatch.design.HatchPillButton
import dev.mahali.hatch.design.HatchPillText
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchSpinner
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.engine.PaneStatus
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.core.AnswerOutcome
import dev.mahali.hatch.core.PromptState
import dev.mahali.hatch.core.PromptTarget
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchMenuCard
import dev.mahali.hatch.design.HatchMenuItem
import dev.mahali.hatch.design.HeaderButton
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.mahali.hatch.protocol.DataStream

internal sealed interface PaneState {
    data object Opening : PaneState
    data object AttachFailed : PaneState
    data class Reconnecting(val canRetry: Boolean) : PaneState
}

private const val RETRY_AFTER = 3

internal fun paneState(status: PaneStatus, hostOnline: Boolean): PaneState? = when {
    !status.restored && status.failures >= RETRY_AFTER -> PaneState.AttachFailed
    // A saved frame on screen is not shown as Opening.
    !status.restored && !status.cached && status.stream != DataStream.State.BlockClosed -> PaneState.Opening
    // Reconnecting over a readable frame: the pill waits out a grace period (see TerminalPane).
    status.stream == DataStream.State.Reconnecting || !hostOnline -> PaneState.Reconnecting(status.failures >= RETRY_AFTER)
    else -> null
}

@Composable
internal fun BoxScope.PaneStatePill(state: PaneState, onRetry: () -> Unit, compact: Boolean = false) {
    val at = Modifier.align(Alignment.TopCenter).padding(start = Hatch.space.m, end = Hatch.space.m, top = Hatch.space.m)
    when (state) {
        PaneState.Opening -> HatchPill(at) { HatchSpinner(); HatchPillText("Opening terminal", chrome.text) }
        PaneState.AttachFailed -> HatchPill(at, end = 10.dp, gap = 14.dp) {
            HatchPillText(if (compact) "Attach Failed" else "Terminal Attachment Failed", chrome.text)
            HatchPillButton("Retry", onRetry)
        }
        is PaneState.Reconnecting -> HatchPill(at, end = if (state.canRetry) 10.dp else 18.dp) {
            HatchSpinner()
            if (!compact || !state.canRetry) HatchPillText("Reconnecting…", chrome.text)
            if (state.canRetry) HatchPillButton("Try Again", onRetry)
        }
    }
}

@Composable
internal fun BoxScope.ExitedBanner(status: PaneStatus, onClose: () -> Unit) {
    // A pane that exited before this device opened it has no exit status to show.
    val detail = status.exitError.ifEmpty { status.exitCode?.let { "exit status $it" }.orEmpty() }
    Row(
        Modifier.align(Alignment.BottomCenter).padding(Hatch.space.m).fillMaxWidth().clip(HatchShapes.Card)
            .background(chrome.card).border(1.dp, chrome.stroke, HatchShapes.Card).padding(horizontal = Dimens.CardPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.IconGap),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(chrome.secondary))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Hatch.space.xxs)) {
            Text("Process exited", style = HatchType.Subhead, color = chrome.text)
            if (detail.isNotEmpty()) {
                Text(detail, style = Hatch.type.mono, color = chrome.secondary)
            }
        }
        HatchPillButton("Close Pane", onClose, color = chrome.text)
    }
}

// Answers go through the engine's checked path. Shown only while the engine holds a live offer.
@Composable
internal fun PromptChoices(rex: Rex, target: PromptTarget?, shown: Boolean, modifier: Modifier = Modifier) {
    val actions = rex.promptActions
    // With program activity notifications off the engine does not watch prompts by itself: ask once for this pane.
    LaunchedEffect(actions, target) {
        if (target == null) return@LaunchedEffect
        delay(INSPECT_AFTER_MILLIS)
        if (actions.entries.value[target] !is PromptState.Offered) actions.inspect(target)
    }
    val state by remember(actions, target) { actions.entries.map { target?.let(it::get) }.distinctUntilChanged() }.collectAsState(null)
    val view = (state as? PromptState.Offered)?.view?.takeIf { target != null && shown } ?: return
    var hidden by remember(view) { mutableStateOf(false) }
    if (hidden) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    HatchMenuCard(modifier) {
        Row(Modifier.padding(start = Dimens.MenuPaddingH), verticalAlignment = Alignment.CenterVertically) {
            Text(view.question, Modifier.weight(1f), style = Hatch.type.callout, color = chrome.secondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            HeaderButton(HatchIcons.Close, "Hide Choices", { hidden = true })
        }
        for (choice in view.choices.all) HatchMenuItem(choice.label, {
            val answer = actions.answerAsync(choice.action)
            scope.launch {
                val failed = when (answer.await()) {
                    AnswerOutcome.InputAcknowledged -> return@launch
                    AnswerOutcome.DeliveryUnconfirmed -> "Couldn't confirm your answer. Check the terminal."
                    is AnswerOutcome.NotSent -> "Answer not sent. Check the prompt in the terminal."
                }
                Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
            }
        })
    }
}

private const val INSPECT_AFTER_MILLIS = 1_500L
