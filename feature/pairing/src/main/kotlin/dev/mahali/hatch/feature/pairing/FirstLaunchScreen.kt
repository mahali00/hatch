package dev.mahali.hatch.feature.pairing

import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchIcon
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.FloatingButton
import dev.mahali.hatch.design.HatchButton
import dev.mahali.hatch.design.HatchType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.chrome

@Composable
public fun FirstLaunchScreen(onAddHost: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(chrome.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth()
                .padding(start = 40.dp, end = 40.dp, bottom = maxHeight * 0.13f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Hatch.space.m, Alignment.CenterVertically),
        ) {
            HatchIcon(Modifier.size(100.dp))
            Text("Welcome to Hatch", Modifier.padding(top = 10.dp), style = HatchType.LargeTitle, color = chrome.text)
            Text(ADD_HOST_BODY, style = HatchType.Callout, color = chrome.secondary, textAlign = TextAlign.Center)
            HatchButton("Add Host", onAddHost, Modifier.padding(top = Hatch.space.m))
        }
        // iOS's gear is 24 pt in a 49 pt bar. This one sits where the session list's gear will be once a host is added.
        FloatingButton(HatchIcons.Settings, "Settings", onOpenSettings, Modifier.align(Alignment.BottomStart).padding(Dimens.ScreenInset))
    }
}
