package com.example.triggerfreeze

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized crash + error reporting.
 *
 * Two layers:
 *  1. [install] hooks the JVM's [Thread.UncaughtExceptionHandler] so any uncaught
 *     exception anywhere in the process is written to logcat and to a file in
 *     the app's external files dir. The original handler is still called so the
 *     OS gets to kill the process normally (no broken state).
 *  2. [logCaught] is for "I swallowed this on purpose" — still records it
 *     so a later crash can be correlated with the earlier failure.
 *
 * The log file is capped at [MAX_LOG_BYTES] so it can't grow unbounded.
 * Pull it with: `adb pull /sdcard/Android/data/com.example.triggerfreeze/files/crash.log`
 */
object CrashReporter {
    private const val TAG = "TriggerFreeze"
    const val LOG_FILE_NAME = "crash.log"
    const val MAX_LOG_BYTES = 64 * 1024

    @Volatile private var installed = false

    @Volatile private var appContext: Context? = null

    /** Call from `Application.onCreate` or any `Activity.attachBaseContext`. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = format(throwable, "Uncaught on ${thread.name}")
                Log.e(TAG, trace)
                appContext?.let { appendToFile(it, trace) }
            } catch (_: Throwable) {
                // never let the crash handler itself throw
            }
            // Delegate to the system default so the process dies cleanly and
            // the OS still shows the standard "app stopped" dialog.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Records a handled exception so it's visible in logcat and the log file. */
    fun logCaught(tag: String, throwable: Throwable) {
        try {
            val trace = format(throwable, "Caught: $tag")
            Log.e(TAG, trace)
            appContext?.let { appendToFile(it, trace) }
        } catch (_: Throwable) {
            // swallow — logging is best-effort
        }
    }

    /** Returns the full crash log text, or a placeholder if no log exists. */
    fun read(context: Context): String {
        val file = logFile(context)
        return file.takeIf { it.exists() }?.readText()?.ifBlank { "(空)" }
            ?: "(暂无错误日志)"
    }

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
    }

    fun logFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_FILE_NAME)

    private fun format(throwable: Throwable, prefix: String): String {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "=== $ts $prefix ===\n" +
            "${throwable.javaClass.name}: ${throwable.message ?: "(no message)"}\n" +
            sw.toString()
    }

    private fun appendToFile(context: Context, text: String) {
        val file = logFile(context)
        val existing = if (file.exists()) file.readText() else ""
        val combined = existing + text + "\n"
        val trimmed = if (combined.length > MAX_LOG_BYTES)
            combined.substring(combined.length - MAX_LOG_BYTES)
        else combined
        file.writeText(trimmed)
    }
}
