package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchMenuCard
import dev.mahali.hatch.design.HatchMenuItem
import dev.mahali.hatch.design.HatchMenuPopup
import dev.mahali.hatch.design.chrome
import kotlin.math.roundToInt

// [remote] is set for a program's click, which has no cell position.
internal class PressedLink(val url: String, val at: Offset, val remote: Boolean = false)

// Opens in whichever app registers the scheme.
internal fun isAppLink(url: String): Boolean {
    val end = url.indexOf(':')
    return end > 0 && url.substring(0, end).all { it.isLetterOrDigit() || it in "+.-" } && !isWebLink(url)
}

// Only http and https: a program can send any string, and other schemes are intents for other apps.
internal fun isWebLink(url: String): Boolean {
    val end = url.indexOf(':')
    if (end <= 0) return false
    val scheme = url.substring(0, end).lowercase()
    return (scheme == "http" || scheme == "https") && url.startsWith("//", end + 1)
}

internal sealed interface FileCheck {
    data object Checking : FileCheck
    data object Found : FileCheck
    data class Unavailable(val reason: String) : FileCheck
}

// Other schemes go to another app as an intent, so Open in App needs [appLinks] (the user's Ask First).
@Composable
internal fun LinkMenu(
    link: PressedLink, file: FileCheck?, appLinks: Boolean,
    onOpen: () -> Unit, onPreview: () -> Unit, onCopy: () -> Unit, onSelectText: () -> Unit, onDismiss: () -> Unit,
) {
    val anchor = if (link.remote) Modifier.padding(top = Hatch.space.s) else Modifier.offset { IntOffset(link.at.x.roundToInt(), link.at.y.roundToInt()) }
    Box(anchor) {
        HatchMenuPopup(onDismiss, alignEnd = false, below = 0.dp) {
            HatchMenuCard {
                Text(
                    if (file != null) link.url else link.url.substringAfter("://"),
                    Modifier.padding(start = Dimens.MenuPaddingH, end = Dimens.MenuPaddingH, top = 10.dp, bottom = Dimens.MenuPaddingV),
                    style = Hatch.type.mono, color = chrome.secondary, maxLines = 3,
                )
                when (file) {
                    null -> when {
                        isWebLink(link.url) -> HatchMenuItem("Open Link", onOpen, HatchIcons.OpenExternal)
                        appLinks && isAppLink(link.url) -> HatchMenuItem("Open in App", onOpen, HatchIcons.OpenExternal)
                    }
                    FileCheck.Checking -> HatchMenuItem("Preview", {}, HatchIcons.Eye, enabled = false)
                    FileCheck.Found -> HatchMenuItem("Preview", onPreview, HatchIcons.Eye)
                    is FileCheck.Unavailable -> HatchMenuItem(file.reason, {}, HatchIcons.Eye, enabled = false)
                }
                HatchMenuItem(if (file != null) "Copy Path" else "Copy Link", onCopy, HatchIcons.Copy)
                if (!link.remote) HatchMenuItem("Select Text", onSelectText, HatchIcons.SelectText)
            }
        }
    }
}

internal fun Context.openLink(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    } catch (_: RuntimeException) {
        // A disallowed or malformed target must not throw out of the click.
    }
}

internal fun Context.copyText(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
}
