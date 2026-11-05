package dev.ibb.launcher.game

import dev.ibb.launcher.core.IbbLog
import java.io.File

/**
 * Bridges the native game log into [IbbLog].
 *
 * `libpojavexec.so` redirects the JVM's `stdout`/`stderr` through
 * `net.kdt.pojavlaunch.Logger.appendToLog`, and hands each line back to the registered listener.
 * This is the one place that listener is installed, so the console tab, the crash screen and the
 * session log file all see the same text.
 *
 * Must be called **before** `VMLauncher.startJVM`.
 */
object GameLogSink {

    @Volatile
    private var attached = false

    fun attach(logFile: File) {
        if (attached) return
        val listener = object : net.kdt.pojavlaunch.Logger.EventLogListener {
            override fun onEventLogged(text: String) {
                // Called on the game thread for every line: keep it cheap and never throw.
                if (text.isNotBlank()) IbbLog.game(text)
            }
        }
        runCatching {
            net.kdt.pojavlaunch.Logger.start(logFile, listener)
            attached = true
            IbbLog.i("GameLog", "Game output → ${logFile.absolutePath}")
        }.onFailure {
            IbbLog.w("GameLog", "Could not attach the native log sink", it)
        }
    }
}
