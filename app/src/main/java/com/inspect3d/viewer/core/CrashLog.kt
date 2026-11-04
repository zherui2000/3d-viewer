package com.inspect3d.viewer.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the stack trace of a crash so it survives the process dying.
 *
 * A crash on a phone that is not plugged into a laptop leaves nothing behind: the app
 * closes, `logcat` has already rolled over by the time anyone looks, and the only
 * available description is "it keeps closing". This writes the trace to the app's own
 * storage on the way down, and [pending] hands it back on the next launch so the UI can
 * show something that can actually be acted on.
 *
 * The previously installed handler is always called afterwards, so the system still
 * records the crash and shows its own dialog. Suppressing that would hide the failure
 * from Play Console and from `adb logcat` as well.
 *
 * That handler only ever sees Java/Kotlin exceptions. A native crash (`SIGSEGV`,
 * `SIGABRT`) in Filament, the GPU driver or OpenCASCADE kills the process without passing
 * through it, so on Android 11 and later [install] also asks the system how the previous
 * process ended. If it was a native crash it has not reported yet, it writes that up —
 * the signal, abort message and backtrace from the tombstone the system kept, decoded by
 * [Tombstone] — into the same file, and the same dialog shows it.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    private const val PREFS = "crash-log"
    /** Timestamp of the newest process exit already looked at, so each is reported once. */
    private const val KEY_LAST_EXIT = "last-exit-timestamp"

    /** Upper bound on the tombstone read; real ones are tens to hundreds of kilobytes. */
    private const val MAX_TOMBSTONE_BYTES = 4 * 1024 * 1024

    /** Installs the handler. Safe to call more than once; later calls replace the chain. */
    fun install(context: Context) {
        val appContext = context.applicationContext

        // Before the handler, and before anything native is touched: if the last run died
        // in native code, this is the only chance to say so.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { collectNativeCrash(appContext) }
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Nothing in here may throw: an exception from an uncaught-exception handler
            // loses the original error, which is the one piece of information worth having.
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The trace from the last crash, or null if the previous run exited cleanly. */
    fun pending(context: Context): String? {
        val file = file(context)
        return runCatching {
            if (file.isFile && file.length() > 0) file.readText() else null
        }.getOrNull()
    }

    /** Forgets the stored trace, once it has been read or dismissed. */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }

        // The header matters as much as the trace. A Filament failure that only happens on
        // one GPU is indistinguishable from a general bug without the device and ABI.
        val report = buildString {
            appendLine("Inspect3D crash report")
            appendLine("when:      ${timestamp()}")
            appendLine("app:       ${versionName(context)}")
            appendLine("device:    ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android:   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abis:      ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("thread:    ${thread.name}")
            appendLine()
            append(trace)
        }

        file(context).writeText(report)
    }

    /**
     * Writes up the most recent native crash of this app that has not been reported yet.
     * A Java crash pending in the file takes precedence: it is the more precise of the two.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectNativeCrash(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0L)

        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        // Newest first. Only exits of this package's processes are returned.
        val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp > lastSeen }
        if (exits.isEmpty()) return

        // Mark everything looked at before doing anything that could fail, so a tombstone
        // that cannot be read is skipped once rather than retried on every launch.
        // Committed synchronously: a launch crash can follow within milliseconds.
        prefs.edit(commit = true) { putLong(KEY_LAST_EXIT, exits.maxOf { it.timestamp }) }

        val crash = exits.firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
            ?: return
        if (pending(context) != null) return

        // Android 12 and later attach the tombstone; on 11 only the description exists.
        val tombstone = runCatching {
            crash.traceInputStream?.use { stream ->
                val bytes = stream.readNBytesCompat(MAX_TOMBSTONE_BYTES)
                if (bytes.isNotEmpty()) Tombstone.format(bytes) else null
            }
        }.getOrNull()

        val report = buildString {
            appendLine("Inspect3D native crash report")
            appendLine("when:      ${timestamp(crash.timestamp)}")
            appendLine("app now:   ${versionName(context)}")
            appendLine("device:    ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android:   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abis:      ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("process:   ${crash.processName}")
            crash.description?.takeIf { it.isNotBlank() }?.let { appendLine("system:    $it") }
            appendLine()
            append(tombstone ?: "No tombstone was kept for this crash; `adb logcat -b crash -d` has the backtrace.")
        }
        file(context).writeText(report)
    }

    /** InputStream.readNBytes is API 33; this is the same, for API 30 onwards. */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun timestamp(millis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    /** Reads the version from the package rather than BuildConfig, which stays opt-in. */
    fun versionName(context: Context): String = runCatching {
        val packageName = context.packageName
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}
