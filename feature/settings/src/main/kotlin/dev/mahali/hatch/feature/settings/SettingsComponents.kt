package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchTopBar
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.chrome


@Composable
internal fun SettingsPage(
    title: String?,
    onBack: (() -> Unit)?,
    action: (@Composable () -> Unit)? = null,
    backIcon: ImageVector = HatchIcons.ChevronLeft,
    backDescription: String = "Back",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(chrome.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        HatchTopBar(title, onBack, backIcon, backDescription) { action?.invoke() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Hatch.space.xl, bottom = Hatch.space.xxxl), content = content)
    }
}

