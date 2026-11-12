package dev.mahali.hatch

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.mahali.hatch.core.Host
import dev.mahali.hatch.core.HostId
import dev.mahali.hatch.design.Themes
import dev.mahali.hatch.engine.FrameCache
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.engine.StateCache
import dev.mahali.hatch.feature.terminal.useDeviceClass
import dev.mahali.hatch.protocol.ClientInfo
import dev.mahali.hatch.protocol.DiagnosticEvent
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HatchApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var hosts: PrefsHostStore
        private set
    lateinit var settings: PrefsSettingsStore
        private set
    lateinit var lastTab: PrefsLastTab
        private set
    lateinit var rex: Rex
        private set
    lateinit var actions: AppActions
        private set
    lateinit var probe: ControlProbe
        private set
    lateinit var notices: NoticeDelivery
        private set

    internal val networkPath = MutableStateFlow(
        NetworkPathSnapshot("No network", SystemClock.elapsedRealtime()),
    )

    private fun clientInfo() = ClientInfo(
        version = BuildConfig.VERSION_NAME,
        label = settings.settings.value.clientLabel.ifBlank { Build.MODEL },
    )

    internal lateinit var crashLog: CrashLog
        private set

    override fun onCreate() {
        super.onCreate()
        crashLog = CrashLog(this).also { it.install() }
        // Decoding all of themes.json on the main thread cost the launch.
        scope.launch { Themes.all(this@HatchApp) }
        scope.launch { runCatching { crashLog.collectExits() } }
        hosts = PrefsHostStore(this)
        lastTab = PrefsLastTab(this)
        settings = PrefsSettingsStore(this)
        val deviceClass = deviceClass()
        useDeviceClass(deviceClass)
        rex = Rex(
            scope,
            ::clientInfo,
            log = { Log.i("Hatch", it) },
            frameCache = FrameCache(File(filesDir, "frames")),
            stateCache = StateCache(File(filesDir, "state")),
            deviceClass = deviceClass,
            previewOwner = previewOwner(),
        )
        actions = AppActions(rex, hosts, scope)
        probe = ControlProbe(rex, ::clientInfo, scope)
        notices = NoticeDelivery(this, rex, settings, scope)

        if (BuildConfig.DEV_HOST.isNotEmpty() && hosts.hosts.value.isEmpty()) {
            hosts.save(Host(HostId("dev"), "Development", BuildConfig.DEV_HOST))
        }
        scope.launch { hosts.hosts.collect { rex.setHosts(it) } }
        watchNetworkAndForeground()
    }

    /** Not backed up: restoring app data to a second phone must not duplicate helper ownership. */
    private fun previewOwner(): UUID {
        val file = File(noBackupFilesDir, "preview-owner")
        val saved = runCatching { UUID.fromString(file.readText().trim()) }.getOrNull()
        if (saved != null) return saved
        return UUID.randomUUID().also { file.writeText(it.toString()) }
    }

    // Reconnect as soon as a network is usable or an activity resumes, not on a backoff timer.
    private fun watchNetworkAndForeground() {
        val main = Handler(Looper.getMainLooper())
        val connectivity = getSystemService(ConnectivityManager::class.java)
        fun pathChanged() {
            val description = networkPath(this)
            val handle = connectivity.activeNetwork?.networkHandle
            if (networkPath.value.description != description || networkPath.value.networkHandle != handle) {
                val event = DiagnosticEvent(System.currentTimeMillis(), "Path $description")
                networkPath.value = NetworkPathSnapshot(
                    description,
                    SystemClock.elapsedRealtime(),
                    handle,
                    (listOf(event) + networkPath.value.events).take(100),
                )
            }
        }
        pathChanged()
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            // Callbacks arrive on a binder thread; panes are opened and released on the main thread.
            override fun onAvailable(network: Network) {
                main.post {
                    pathChanged()
                    rex.nudge()
                }
            }
            override fun onLost(network: Network) { main.post { pathChanged() } }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                main.post { pathChanged() }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                main.post {
                    pathChanged()
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) rex.nudge()
                }
            }
        })
        val underlyingNetworks = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(underlyingNetworks, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { pathChanged() } }
            override fun onLost(network: Network) { main.post { pathChanged() } }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                main.post { pathChanged() }
            }
        })
        var resumedActivities = 0
        fun appEvent(text: String) {
            val event = DiagnosticEvent(System.currentTimeMillis(), text)
            networkPath.value = networkPath.value.let { it.copy(events = (listOf(event) + it.events).take(100)) }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = rex.nudge()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (resumedActivities++ == 0) {
                    notices.refreshPermission()
                    appEvent("App active")
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (--resumedActivities == 0) appEvent("App inactive")
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
