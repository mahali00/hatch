package dev.mahali.hatch.feature.settings

import dev.mahali.hatch.design.StatusGlyph
import dev.mahali.hatch.core.mark
import dev.mahali.hatch.core.Settings
import dev.mahali.hatch.core.ProgramState
import dev.mahali.hatch.design.Hatch
import dev.mahali.hatch.design.HatchList
import dev.mahali.hatch.design.HatchSwitchRow
import dev.mahali.hatch.design.HatchIcons
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
import android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
import android.provider.Settings.EXTRA_APP_PACKAGE
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.mahali.hatch.core.SettingsStore
import dev.mahali.hatch.design.HatchListRow
import dev.mahali.hatch.design.RowChevron
import dev.mahali.hatch.design.chrome

@Composable
internal fun NotificationsPage(store: SettingsStore, back: (() -> Unit)?) {
    val s by store.settings.collectAsState()
    val context = LocalContext.current
    val turnOn = rememberNotificationsSwitch { on -> store.update { it.copy(notifications = on) } }

    // Read again on every return, since the user changes it in system settings.
    var unrestricted by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose {}
    }

    SettingsPage("Notifications", back) {
        HatchList {
            section(footer = "Get notified when a program finishes, fails or needs your input while you are away from it.") {
                row("Notifications") {
                    HatchSwitchRow(
                        "Notifications", s.notifications, { on -> if (on) turnOn() else store.update { it.copy(notifications = false) } },
                        titleWeight = FontWeight.SemiBold,
                    )
                }
            }
            section("Notify me about") {
                switch("Program activity", s.notifyProgramActivity, subtitle = "Finished, failed and needs input") { on ->
                    store.update { it.copy(notifyProgramActivity = on) }
                }
                switch("Terminal notifications", s.notifyTerminal, subtitle = "Sent by programs with OSC 9 or OSC 777") { on ->
                    store.update { it.copy(notifyTerminal = on) }
                }
            }
            section("Program Activity", footer = "How a program's state reaches you while you are away from its pane.", visible = s.notifyProgramActivity) {
                for ((state, alert) in listOf(ProgramState.Blocked to s.alertBlocked, ProgramState.Error to s.alertError, ProgramState.Done to s.alertDone)) {
                    choice(
                        stateName(state), alert, Settings.Alert.entries, { it.label },
                        leading = { StatusGlyph(state.mark, null, showDone = true) },
                    ) { a ->
                        store.update {
                            when (state) {
                                ProgramState.Blocked -> it.copy(alertBlocked = a)
                                ProgramState.Error -> it.copy(alertError = a)
                                else -> it.copy(alertDone = a)
                            }
                        }
                    }
                }
            }
            section(
                "Background",
                footer = "Keeps a connection to each host open so notifications arrive, and shows a quiet \"Connected to " +
                    "2 hosts\" notification. Set battery use to Unrestricted for Hatch, or Samsung's battery saver can still stop it.",
            ) {
                switch("Stay connected in background", s.stayConnected) { on -> store.update { it.copy(stayConnected = on) } }
                row("Battery use") {
                    HatchListRow(
                        "Battery use",
                        subtitle = if (unrestricted) "Unrestricted" else "Optimized · may stop notifications",
                        subtitleColor = if (unrestricted) chrome.secondary else chrome.warning,
                        onClick = { context.startActivity(Intent(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                        trailing = { RowChevron() },
                    )
                }
                row("System notification settings") {
                    HatchListRow(
                        "System notification settings",
                        onClick = { context.startActivity(appNotificationSettings(context)) },
                        trailing = { Icon(HatchIcons.OpenExternal, null, Modifier.size(Hatch.size.icon), tint = chrome.secondary) },
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberNotificationsSwitch(set: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return { set(true) }
    val permission = Manifest.permission.POST_NOTIFICATIONS
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> set(true)
            activity?.shouldShowRequestPermissionRationale(permission) == false -> context.startActivity(appNotificationSettings(context))
        }
    }
    return {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) set(true) else launcher.launch(permission)
    }
}

private fun appNotificationSettings(context: Context) =
    Intent(ACTION_APP_NOTIFICATION_SETTINGS).putExtra(EXTRA_APP_PACKAGE, context.packageName)

private fun stateName(state: ProgramState) = when (state) {
    ProgramState.Blocked -> "Needs input"
    ProgramState.Error -> "Failed"
    else -> "Finished"
}
