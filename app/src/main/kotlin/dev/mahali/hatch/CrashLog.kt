package dev.mahali.hatch

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kotlin crashes are saved as they happen; native crashes and ANRs are read on the next launch
 * from ApplicationExitInfo (API 30+). Nothing leaves the phone unless the user shares it.
 */
internal class CrashLog(private val context: Context) {
    private val dir = File(context.filesDir, "crashes")
    private val prefs = context.getSharedPreferences("crash-log", Context.MODE_PRIVATE)

    /** Call first thing in Application.onCreate; then [collectExits], off the main thread. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save("crash", "Thread: ${thread.name}\n\n${error.stackTraceToString()}", System.currentTimeMillis()) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun reports(): List<File> = dir.listFiles()?.sortedByDescending { it.name }.orEmpty()

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    /** The newest reports as one text to share, capped so the share sheet takes it. */
    fun shareIntent(): Intent? {
        val all = reports().ifEmpty { return null }
        val text = buildString {
            for (f in all) {
                if (length > SHARE_LIMIT) break
                append(f.readText().take(SHARE_LIMIT - length)).append("\n\n----\n\n")
            }
        }
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Hatch crash reports").putExtra(Intent.EXTRA_TEXT, text),
            "Share crash reports",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun collectExits() {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(ActivityManager::class.java)
        val seen = prefs.getLong(SEEN, 0L)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .filter { it.timestamp > seen && (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR) }
        for (exit in exits) {
            val kind = if (exit.reason == ApplicationExitInfo.REASON_ANR) "anr" else "native"
            val trace = runCatching { exit.traceInputStream?.use { it.readBytes().decodeToString().take(TRACE_LIMIT) } }.getOrNull()
            save(kind, "${exit.description.orEmpty()}\n\n${trace ?: "(no trace)"}", exit.timestamp)
        }
        am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.let { prefs.edit { putLong(SEEN, it.timestamp) } }
    }

    private fun save(kind: String, body: String, at: Long) {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(at))
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val header = "Hatch ${info.versionName} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
            "$kind at $stamp\n\n"
        File(dir, "$stamp-$kind.txt").writeText(header + body)
        reports().drop(KEEP).forEach { it.delete() }
    }

    private companion object {
        const val SEEN = "exitSeen"
        const val KEEP = 20
        const val TRACE_LIMIT = 200_000
        const val SHARE_LIMIT = 300_000
    }
}
