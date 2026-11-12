package dev.mahali.hatch.feature.settings

import androidx.compose.foundation.layout.Arrangement
import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchList
import dev.mahali.hatch.design.HatchIcon
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.mahali.hatch.design.chrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AboutPage(env: PageEnv, back: (() -> Unit)?, open: (Page) -> Unit) {
    val context = LocalContext.current
    val settings by env.settings.settings.collectAsState()
    val about = env.about
    SettingsPage(title = null, back) {
        // The design's header, a sixth smaller to sit with iOS-sized rows.
        Column(
            Modifier.fillMaxWidth().padding(top = Hatch.space.s, bottom = Hatch.space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Hatch.space.s),
        ) {
            HatchIcon(Modifier.size(Hatch.size.appIcon))
            Text("Hatch", style = Hatch.type.largeTitle, color = chrome.text)
            Text("Version ${about.version}", style = Hatch.type.callout, color = chrome.secondary)
        }
        HatchList {
            section {
                value("Device", about.device)
                value("Terminal engine", about.engine, mono = true)
            }
            section(footer = "Hatch is an unofficial, experimental Android client for Rex. Debug info names hosts but never includes their addresses.") {
                action("Copy Version Info") { copy(context, "Version info", versionInfo(about)) }
                action("Copy Debug Info") { copy(context, "Debug info", debugInfo(about, settings, env.savedHosts, env.state)) }
                link("Acknowledgements") { open(Page.Acknowledgements) }
            }
        }
    }
}

private fun copy(context: Context, label: String, text: String) {
    // Android 13 and later confirm a copy themselves, so no message of our own is shown.
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
internal fun AcknowledgementsPage(back: (() -> Unit)?) {
    val context = LocalContext.current
    val licences by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) { readLicences(context) }
    }
    SettingsPage("Acknowledgements", back) {
        HatchList { licences.forEach { (name, text) -> section(name) { text(name, text, mono = true) } } }
    }
}

private fun readLicences(context: Context): List<Pair<String, String>> {
    val files = context.assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }.sorted()
    return files.map { file ->
        val text = context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
        licenceTitle(file) to text.trim()
    }
}

internal fun licenceTitle(file: String): String = file.removeSuffix(".txt").substringBefore('-')
