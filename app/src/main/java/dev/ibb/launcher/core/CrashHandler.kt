package dev.ibb.launcher.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash handler.
 *
 * Writes a self-contained report (stack trace + device fingerprint + console tail) into
 * `logs/crash-<timestamp>.log` and records the path for the next launch, which then offers to
 * share it. We intentionally do **not** upload anything automatically: the report contains the
 * game directory path and account *name*, so the user decides.
 */
class CrashHandler private constructor(
    private val context: Context,
) : Thread.UncaughtExceptionHandler {

    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { writeReport(thread, throwable) }
        previous?.uncaughtException(thread, throwable)
    }

    private fun writeReport(thread: Thread, throwable: Throwable): File {
        IbbLog.crash("CrashHandler", "Uncaught exception on ${thread.name}", throwable)
        val dir = IbbPaths.logsDir(context)
        val stamp = FILE_STAMP.get().format(Date())
        val file = File(dir, "crash-$stamp.log")
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()

        file.writeText(
            buildString {
                appendLine("IBB Launcher crash report")
                appendLine("time        : ${Date()}")
                appendLine("thread      : ${thread.name} (${thread.priority})")
                appendLine("app version : ${appVersion()}")
                appendLine("device      : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("android     : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("abis        : ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                appendLine("ram total   : ${dev.ibb.launcher.data.store.SettingsStore.totalRamMb()} MB")
                appendLine("renderer    : ${dev.ibb.launcher.data.store.SettingsStore(context).settings.value.graphics.renderer.displayName}")
                appendLine()
                appendLine("--- stack trace ---")
                appendLine(stackTrace)
                appendLine("--- launcher console (last 200 lines) ---")
                appendLine(IbbLog.history.value.takeLast(200).joinToString("\n") { it.format() })
            },
        )
        pendingFile().writeText(file.absolutePath)
        IbbLog.crash("CrashHandler", "Report written to ${file.absolutePath}", throwable)
        return file
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    private fun pendingFile(): File = File(context.filesDir, "pending_crash.txt")

    companion object {
        private val FILE_STAMP = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        }

        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            if (Thread.getDefaultUncaughtExceptionHandler() !is CrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(handler)
            }
        }

        /** Returns the crash file recorded by the previous run (if any) and clears the marker. */
        fun consumePendingReport(context: Context): File? {
            val marker = File(context.filesDir, "pending_crash.txt")
            if (!marker.exists()) return null
            val path = runCatching { marker.readText().trim() }.getOrNull()
            marker.delete()
            return path?.let { File(it) }?.takeIf { it.exists() }
        }
    }
}
