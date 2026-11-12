package dev.mahali.hatch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.mahali.hatch.core.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectionWatch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rex = (application as HatchApp).rex
        // The special-use type exists from Android 14; earlier versions take no type.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val online = onlineHosts(rex.state.value)
        ServiceCompat.startForeground(this, ID, notification(online), type)
        if (connectionWatch?.isActive != true) connectionWatch = scope.launch {
            rex.state.map(::onlineHosts).distinctUntilChanged().collect { online ->
                getSystemService(android.app.NotificationManager::class.java).notify(ID, notification(online))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(online: List<String>) = NotificationCompat.Builder(this, NoticeDelivery.CHANNEL_CONNECTION)
        .setSmallIcon(R.drawable.ic_stat_hatch)
        .setContentTitle(connectionTitle(online))
        .setContentText("Lets terminals notify you while Hatch is closed")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setShowWhen(false)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val ID = 1

        private fun onlineHosts(state: dev.mahali.hatch.core.AppState): List<String> =
            state.hosts.mapNotNull { state.byHost[it]?.takeIf { h -> h.link == Link.Online }?.host?.name }

        internal fun connectionTitle(online: List<String>): String = when (online.size) {
            0 -> "Reconnecting…"
            1 -> "Connected to ${online.single()}"
            else -> "Connected to ${online.size} hosts"
        }

        // Call while the app is in the foreground: starting a foreground service from the background is refused.
        fun sync(context: Context, wanted: Boolean) {
            val intent = Intent(context, ConnectionService::class.java)
            if (wanted) runCatching { context.startForegroundService(intent) } else context.stopService(intent)
        }
    }
}
