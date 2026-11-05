package dev.ibb.launcher.game

import dev.ibb.launcher.core.IbbLog
import java.io.File

/**
 * Bridges the native game log into [IbbLog].
 *
 * `libpojavexec.so` redirects the JVM's `stdout`/`stderr` through
 * `net.kdt.pojavlaunch.Logger.appendToLog`. That class hands each line back to a listener; this is
 * the one place where that listener is installed, so the console tab, the crash screen and the
 * session log file all see the same text.
 *
 * Must be called **before** `VMLauncher.startJVM`.
 */
object GameLogSink {

    @Volatile
    private var started = false

    fun attach(logFile: File) {
        if (started) return
        started = true
        val listener = net.kdt.pojavlaunch.Logger.EventLogListener { line ->
            // Called on the game thread for every line: keep it allocation-light and never throw.
            if (line.isNotBlank()) IbbLog.game(line)
        }
        runCatching {
            net.kdt.pojavlaunch.Logger.begin(logFile, listener)
            IbbLog.i("GameLog", "Game output → ${logFile.absolutePath}")
        }.onFailure {
            started = false
            IbbLog.w("GameLog", "Could not attach the native log sink", it)
        }
    }
}
