package net.kdt.pojavlaunch

import dev.ibb.launcher.core.IbbLog
import java.io.File

/**
 * Game-side log sink, implemented natively by `libpojavexec.so`.
 *
 * `jre_launcher.c` and `stdio_is.c` redirect the JVM's `stdout`/`stderr` into
 * `Logger.appendToLog`, which the native layer binds by the literal name
 * `Java_net_kdt_pojavlaunch_Logger_appendToLog`. The package and class name are therefore part of
 * the ABI and must not change.
 *
 * The listener is invoked **from the game thread** for every line; it must not block. Lines are
 * pushed into [IbbLog]'s ring buffer, which the console UI reads on its own coroutine.
 */
object Logger {

    /** Receives every line the game JVM writes to stdout/stderr. */
    fun interface EventLogListener {
        fun onEventLogged(text: String)
    }

    private const val TAG = "GameJVM"

    @Volatile
    private var installed: EventLogListener? = null

    /**
     * Starts a fresh log file and (re)installs [listener].
     *
     * Called once per launch before `JLI_Launch`. Safe to call again for a relaunch.
     */
    @JvmStatic
    fun begin(logFile: File?, listener: EventLogListener? = null) {
        installed = listener ?: EventLogListener { text -> IbbLog.game(text) }
        setLogListener(installed)
        logFile?.parentFile?.mkdirs()
        begin(logFile?.absolutePath ?: "")
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
}
