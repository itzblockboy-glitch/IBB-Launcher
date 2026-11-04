package dev.ibb.launcher.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single log sink for the whole launcher.
 *
 * Everything the user might need to debug a failed boot ends up here: installer progress,
 * auth errors, JVM stdout/stderr and native crash dumps. The in-app console observes
 * [entries]; the crash reporter drains [history] when it needs to attach context.
 *
 * Thread-safety: [entries] is a [MutableSharedFlow] with replay, so collectors that come
 * online late (e.g. the console screen opened after the game started) still see recent output.
 */
object IbbLog {

    enum class Level(val priority: Int, val short: String) {
        VERBOSE(Log.VERBOSE, "V"),
        DEBUG(Log.DEBUG, "D"),
        INFO(Log.INFO, "I"),
        WARN(Log.WARN, "W"),
        ERROR(Log.ERROR, "E"),
        GAME(Log.INFO, "MC"),   // stdout/stderr coming from the game JVM
        JVM(Log.DEBUG, "JVM"),  // JVM launcher / native bridge diagnostics
        CRASH(Log.ASSERT, "!!"),
    }

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
    ) {
        /** `12:04:31.512  MC   [minecraft/Main] Loading 1.21.4` — copy/paste friendly. */
        fun format(): String {
            val stamp = TIME_FORMAT.get().format(Date(timestamp))
            val error = throwable?.let { "\n${Log.getStackTraceString(it)}" }.orEmpty()
            return "$stamp ${level.short.padEnd(3)} [$tag] $message$error"
        }

        private companion object {
            val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            }
        }
    }

    private const val MAX_HISTORY = 4_000
    private const val TAG = "IbbLauncher"

    private val _entries = MutableSharedFlow<Entry>(replay = 400, extraBufferCapacity = 256)
    val entries: SharedFlow<Entry> = _entries.asSharedFlow()

    private val _history = MutableStateFlow<List<Entry>>(emptyList())

    /** Full in-memory ring buffer (newest last). Used by the console & crash reports. */
    val history: StateFlow<List<Entry>> = _history.asStateFlow()

    fun v(tag: String, message: String) = log(Level.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) =
        log(Level.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        log(Level.ERROR, tag, message, error)

    fun crash(tag: String, message: String, error: Throwable? = null) =
        log(Level.CRASH, tag, message, error)

    /** Raw line from the game process (stdout or stderr, already stripped of newlines). */
    fun game(line: String) = log(Level.GAME, "minecraft", line)

    fun jvm(line: String) = log(Level.JVM, "jvm", line)

    fun log(level: Level, tag: String, message: String, error: Throwable? = null) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message, error)
        // Platform log first: it survives process death and shows up in logcat / bug reports.
        when (level) {
            Level.ERROR, Level.CRASH -> Log.e(TAG, "[$tag] $message", error)
            Level.WARN -> Log.w(TAG, "[$tag] $message", error)
            Level.DEBUG, Level.JVM -> Log.d(TAG, "[$tag] $message")
            Level.VERBOSE -> Log.v(TAG, "[$tag] $message")
            Level.INFO, Level.GAME -> Log.i(TAG, "[$tag] $message")
        }
        // tryEmit never blocks; if nobody is collecting the replay buffer still fills up.
        _entries.tryEmit(entry)
        val current = _history.value
        _history.value = if (current.size >= MAX_HISTORY) {
            current.drop(current.size - MAX_HISTORY + 1) + entry
        } else {
            current + entry
        }
    }

    fun clear() {
        _history.value = emptyList()
    }

    /** Plain-text snapshot for share sheets / crash files. */
    fun dump(): String = _history.value.joinToString("\n") { it.format() }
}
