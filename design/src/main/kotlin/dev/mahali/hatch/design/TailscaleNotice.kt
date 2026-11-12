package dev.mahali.hatch.design

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

public fun isTailscaleAddress(url: String): Boolean {
    val host = runCatching { java.net.URI(url.trim().let { if ("://" in it) it else "https://$it" }).host }.getOrNull()?.lowercase() ?: return false
    if (host.endsWith(".ts.net")) return true
    val parts = host.split('.').map { it.toIntOrNull() ?: return false }
    return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
}

/** Whether this phone can reach a Tailscale host now, as far as Android lets an app tell. */
internal enum class TailscaleState { Connected, Off, NotInstalled }

/** Android does not say which app owns a VPN: "connected" means Tailscale installed and a VPN up. */
@Composable
public fun TailscaleNotice(url: String) {
    if (!isTailscaleAddress(url)) return
    val context = LocalContext.current
    val state = rememberTailscaleState(context)
    val (text, action) = when (state) {
        TailscaleState.Connected -> "This device is connected to Tailscale. Make sure your host is connected to the same network." to null
        TailscaleState.Off -> "This host is on a Tailscale network. Turn Tailscale on on this device to reach it." to "Open"
        TailscaleState.NotInstalled -> "This host is on a Tailscale network. Install Tailscale on this device to reach it." to "Get"
    }
    HatchNotice(
        text, background = chrome.background, leading = { TailscaleMark(connected = state == TailscaleState.Connected) },
        action = action, onAction = { openTailscale(context, state) },
    )
}

@Composable
private fun TailscaleMark(connected: Boolean) {
    val check = chrome.online
    val ring = chrome.background
    Canvas(Modifier.size(34.dp)) {
        val tile = size.minDimension * 0.86f
        drawRoundRect(Color(0xFF242424), size = Size(tile, tile), cornerRadius = CornerRadius(tile * 0.24f))
        val step = tile / 4f
        val r = tile * 0.075f
        for (row in 0..2) for (col in 0..2) {
            val lit = row == 1 || (row == 2 && col == 1)
            drawCircle(Color.White.copy(alpha = if (lit) 1f else 0.35f), r, Offset(step * (col + 1), step * (row + 1)))
        }
        if (connected) {
            val c = Offset(size.width - size.minDimension * 0.2f, size.height - size.minDimension * 0.2f)
            val badge = size.minDimension * 0.2f
            drawCircle(ring, badge + 1.5.dp.toPx(), c)
            drawCircle(check, badge, c)
            val tick = Path().apply {
                moveTo(c.x - badge * 0.45f, c.y)
                lineTo(c.x - badge * 0.1f, c.y + badge * 0.38f)
                lineTo(c.x + badge * 0.5f, c.y - badge * 0.35f)
            }
            drawPath(tick, Color.White, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun rememberTailscaleState(context: Context): TailscaleState {
    var vpn by remember { mutableStateOf(false) }
    // Followed while the sheet shows, so turning Tailscale on in its app updates the card.
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val up = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { up += network; vpn = true }
            override fun onLost(network: Network) { up -= network; vpn = up.isNotEmpty() }
        }
        // A VPN network needs NOT_VPN removed from the request, or it is never matched.
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        cm.registerNetworkCallback(request, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
    val installed = remember(context) { tailscaleInstalled(context) }
    return when {
        !installed -> TailscaleState.NotInstalled
        vpn -> TailscaleState.Connected
        else -> TailscaleState.Off
    }
}

private const val TAILSCALE = "com.tailscale.ipn"

private fun tailscaleInstalled(context: Context): Boolean = try {
    context.packageManager.getPackageInfo(TAILSCALE, 0); true
} catch (_: PackageManager.NameNotFoundException) { false }

private fun openTailscale(context: Context, state: TailscaleState) {
    val intent = if (state == TailscaleState.NotInstalled) Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$TAILSCALE"))
    else context.packageManager.getLaunchIntentForPackage(TAILSCALE) ?: return
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
