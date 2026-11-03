package com.inspect3d.viewer.core

import android.content.Context
import android.os.Build
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
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /** Installs the handler. Safe to call more than once; later calls replace the chain. */
    fun install(context: Context) {
        val appContext = context.applicationContext
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

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Reads the version from the package rather than BuildConfig, which stays opt-in. */
    fun versionName(context: Context): String = runCatching {
        val packageName = context.packageName
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}
