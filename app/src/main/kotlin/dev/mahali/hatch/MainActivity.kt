package dev.mahali.hatch

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.core.SessionId
import dev.mahali.hatch.core.WindowId
import dev.mahali.hatch.core.WindowRef

class MainActivity : ComponentActivity() {
    private val opened = mutableStateOf<WindowRef?>(null)
    private val openedBlock = mutableStateOf<dev.mahali.hatch.core.BlockId?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMetaKeys()
        if (savedInstanceState == null) {
            opened.value = intent.windowRef()
            openedBlock.value = intent.getStringExtra(NoticeDelivery.EXTRA_BLOCK)?.let { dev.mahali.hatch.core.BlockId(it) }
        } else if (savedInstanceState.getBoolean("pending_notification")) {
            val h = savedInstanceState.getString(NoticeDelivery.EXTRA_HOST)
            val s = savedInstanceState.getString(NoticeDelivery.EXTRA_SESSION)
            val w = savedInstanceState.getString(NoticeDelivery.EXTRA_WINDOW)
            if (h != null && s != null && w != null) opened.value = WindowRef(HostId(h), SessionId(s), WindowId(w))
            openedBlock.value = savedInstanceState.getString(NoticeDelivery.EXTRA_BLOCK)?.let { dev.mahali.hatch.core.BlockId(it) }
        }
        val app = application as HatchApp
        // A cold launch opens through the launch curtain; rotation and unfolding do not replay it.
        val curtain = mutableStateOf(savedInstanceState == null)
        val startCurtain = mutableStateOf(Build.VERSION.SDK_INT < 31)
        if (curtain.value && Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                splash.remove()
                startCurtain.value = true
            }
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                AppRoot(app, opened.value, openedBlock = openedBlock.value, onOpenedConsumed = { opened.value = null; openedBlock.value = null })
                if (curtain.value) {
                    // A new activity in a live process (a notification, adb) gets no system splash, so its
                    // exit callback never comes and the curtain stayed up: a blank screen that still took
                    // touches. With a splash the callback follows the first frame well inside this wait.
                    LaunchedEffect(Unit) { withFrameNanos { }; delay(CURTAIN_FALLBACK_MILLIS); startCurtain.value = true }
                    LaunchCurtain(startCurtain.value, onDone = { curtain.value = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.windowRef()?.let {
            opened.value = it
            openedBlock.value = intent.getStringExtra(NoticeDelivery.EXTRA_BLOCK)?.let { value -> dev.mahali.hatch.core.BlockId(value) }
        }
    }

    // Hardware shortcuts first, ahead of whichever view has the keyboard.
    // super is the plain Activity dispatch; RestrictedApi on androidx.core's ComponentActivity is a false positive.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        (event.action == android.view.KeyEvent.ACTION_DOWN && Shortcuts.handle(event)) || super.dispatchKeyEvent(event)

    override fun onSaveInstanceState(outState: Bundle) {
        opened.value?.let { ref ->
            outState.putBoolean("pending_notification", true)
            outState.putString(NoticeDelivery.EXTRA_HOST, ref.host.value)
            outState.putString(NoticeDelivery.EXTRA_SESSION, ref.session.value)
            outState.putString(NoticeDelivery.EXTRA_WINDOW, ref.window.value)
            outState.putString(NoticeDelivery.EXTRA_BLOCK, openedBlock.value?.value)
        }
        super.onSaveInstanceState(outState)
    }

    private fun Intent.windowRef(): WindowRef? {
        val host = getStringExtra(NoticeDelivery.EXTRA_HOST) ?: return null
        val session = getStringExtra(NoticeDelivery.EXTRA_SESSION) ?: return null
        val window = getStringExtra(NoticeDelivery.EXTRA_WINDOW) ?: return null
        return WindowRef(HostId(host), SessionId(session), WindowId(window))
    }


    // Samsung keeps ⌘ for its own shortcuts unless the app requests it. No-op elsewhere.
    private fun requestMetaKeys() {
        runCatching {
            val manager = Class.forName("com.samsung.android.view.SemWindowManager")
            val instance = manager.getMethod("getInstance").invoke(null)
            manager.getMethod("requestMetaKeyEvent", android.content.ComponentName::class.java, Boolean::class.javaPrimitiveType)
                .invoke(instance, componentName, true)
        }
    }
}

private const val CURTAIN_FALLBACK_MILLIS = 400L
