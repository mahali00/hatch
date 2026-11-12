package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchChip
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.HatchSpinner
import dev.mahali.hatch.design.HatchType
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostProbe
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.chrome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ScanPage(
    probe: HostProbe,
    existing: List<Host>,
    onBack: () -> Unit,
    onInstructions: (Os) -> Unit,
    onManual: () -> Unit,
    onReached: (url: String, name: String) -> Unit,
    onProblem: (address: String, problem: Attempt.Problem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<PairingCode?>(null) }
    var rejections by remember { mutableIntStateOf(0) }
    val onText: (String) -> Unit = { text ->
        val code = parsePairingCode(text)
        when {
            found != null -> Unit
            code == null -> rejections++
            else -> {
                found = code
                scope.launch {
                    when (val attempt = attemptConnect(probe, existing, code.url)) {
                        is Attempt.Reached -> onReached(attempt.url, code.name ?: attempt.suggestedName ?: defaultHostName(attempt.url))
                        is Attempt.Problem -> onProblem(code.url, attempt)
                    }
                }
            }
        }
    }
    var showRejection by remember { mutableStateOf(false) }
    LaunchedEffect(rejections) {
        showRejection = rejections > 0
        delay(3_000)
        showRejection = false
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenInset), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SheetTitle("Pair via QR Code", onBack)
        CameraBox(onText, onManual, highlight = found != null, rejected = showRejection)
        val code = found
        if (code != null) ConnectingRow(code.url) else InstructionChips(onInstructions)
    }
}

/** Never more than half the window, so in landscape the camera stays visible under the sheet. */
@Composable
private fun CameraBox(onText: (String) -> Unit, onManual: () -> Unit, highlight: Boolean, rejected: Boolean) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(min(maxWidth / 0.85f, windowHeight / 2)).clip(HatchShapes.Card).background(chrome.background),
            contentAlignment = Alignment.Center,
        ) {
            when (rememberCameraPermission()) {
                true -> {
                    QrCamera(onText, Modifier.fillMaxSize())
                    Viewfinder(if (highlight) chrome.accent else Color.White)
                }
                false -> CameraDenied(onManual)
                null -> Unit
            }
            if (rejected) {
                // Over the camera picture: dark in every theme.
                MessageCard(
                    NOT_A_REX_CODE, chrome.warning, Modifier.align(Alignment.BottomCenter).padding(14.dp),
                    background = Color.Black.copy(alpha = 0.82f), textColor = Color.White,
                )
            }
        }
    }
}

@Composable
private fun CameraDenied(onManual: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(Hatch.space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Hatch.space.m)) {
        Text(
            "Hatch needs access to the camera to scan a pairing code.",
            Modifier.padding(bottom = Hatch.space.xs), style = HatchType.Callout, color = chrome.text, textAlign = TextAlign.Center,
        )
        SheetButton("Open Settings", onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        })
        SheetButton("Set Up Manually", onClick = onManual, filled = false)
    }
}

@Composable
private fun ConnectingRow(url: String) {
    Row(
        Modifier.fillMaxWidth().clip(HatchShapes.Card).background(chrome.background).padding(horizontal = Dimens.CardPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.IconGap),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(chrome.control), contentAlignment = Alignment.Center) {
            Icon(HatchIcons.Laptop, null, Modifier.size(Dimens.Icon), tint = chrome.text)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(url, style = HatchType.Body, color = chrome.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Connecting…", style = HatchType.Footnote, color = chrome.secondary)
        }
        HatchSpinner()
    }
}

@Composable
private fun InstructionChips(onInstructions: (Os) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Hatch.space.s, Alignment.CenterHorizontally),
    ) {
        Icon(HatchIcons.Help, null, Modifier.size(Dimens.Icon), tint = chrome.secondary)
        Text("Instructions for", style = HatchType.Callout, color = chrome.secondary)
        Os.entries.forEach { os ->
            HatchChip(os.label, { onInstructions(os) }, clickLabel = "Instructions for ${os.label}")
        }
    }
}

@Composable
private fun Viewfinder(color: Color) {
    Canvas(Modifier.size(200.dp)) {
        val width = 3.6.dp.toPx()
        val radius = 16.dp.toPx()
        val arm = 38.dp.toPx()
        val inset = width / 2
        val corners = listOf(0f to 0f, size.width to 0f, size.width to size.height, 0f to size.height)
        val arcStarts = listOf(180f, 270f, 0f, 90f)
        corners.forEachIndexed { i, (x, y) ->
            val dx = if (x == 0f) 1f else -1f
            val dy = if (y == 0f) 1f else -1f
            val cx = x + dx * inset
            val cy = y + dy * inset
            drawLine(color, Offset(cx, cy + dy * radius), Offset(cx, cy + dy * arm), width, StrokeCap.Round)
            drawLine(color, Offset(cx + dx * radius, cy), Offset(cx + dx * arm, cy), width, StrokeCap.Round)
            val topLeft = Offset(if (dx > 0) cx else cx - 2 * radius, if (dy > 0) cy else cy - 2 * radius)
            drawArc(color, arcStarts[i], 90f, false, topLeft, Size(2 * radius, 2 * radius), style = Stroke(width, cap = StrokeCap.Round))
        }
    }
}

internal const val NOT_A_REX_CODE = "This QR code isn't a Rex pairing code."
