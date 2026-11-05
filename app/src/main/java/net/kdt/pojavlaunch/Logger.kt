package net.kdt.pojavlaunch

import dev.ibb.launcher.core.IbbLog
import java.io.File

/**
 * Game-side log sink, implemented natively by `libpojavexec.so`.
 *
 * `stdio_is.c` redirects the JVM's `stdout`/`stderr` into
 * `Java_net_kdt_pojavlaunch_Logger_appendToLog` and calls back into
 * `net.kdt.pojavlaunch.Logger$eventLogListener.onEventLogged(String)`. Both names are part of the
 * ABI: the class, the nested listener interface and the method signatures must not change.
 *
 * The listener runs **on the game thread for every line**, so it must be allocation-light and must
 * never throw.
 */
object Logger {

    /** Receives every line the game JVM writes to stdout/stderr. */
    interface EventLogListener {
        fun onEventLogged(text: String)
    }

    @Volatile
    private var installed: EventLogListener? = null

    /**
     * Truncates/creates the log file and registers [listener] (defaults to forwarding into
     * [IbbLog]). Called once per launch, before `JLI_Launch`.
     */
    @JvmStatic
    fun start(logFile: File?, listener: EventLogListener? = null) {
        val sink = listener ?: object : EventLogListener {
            override fun onEventLogged(text: String) {
                if (text.isNotBlank()) IbbLog.game(text)
            }
        }
        installed = sink
        runCatching { setLogListener(sink) }
            .onFailure { IbbLog.w("GameLog", "Could not install the log listener", it) }
        runCatching {
            logFile?.parentFile?.mkdirs()
            begin(logFile?.absolutePath ?: "")
        }.onFailure { IbbLog.w("GameLog", "Could not open the log file", it) }
    }

    /** Native: opens/truncates the log file the JVM output is mirrored to. */
    @JvmStatic
    external fun begin(logFilePath: String)

    /** Native: appends one line; called by the redirected stdout/stderr of the game JVM. */
    @JvmStatic
    external fun appendToLog(text: String)

    /** Native: registers the listener the native layer calls for every line. */
    @JvmStatic
    external fun setLogListener(listener: EventLogListener?)

    /** True once a listener has been installed for this process. */
    val isAttached: Boolean get() = installed != null
}
