package dev.ibb.launcher.game

import dev.ibb.launcher.core.IbbLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Streams stdout/stderr from the game JVM into [IbbLog] (and therefore the in-app console).
 *
 * Two sources are supported:
 *  * **Pipe mode** — the JVM writes into a redirected `FileOutputStream` pair that we tail.
 *  * **Logcat mode** — the Pojav native layer funnels `System.out` into logcat tag `jrelog`;
 *    we spawn `logcat -s jrelog` and parse it. This is the mode used in production because it
 *    survives `SIGKILL` of the JVM and captures native crashes too.
 *
 * Parsing classifies each line so the console can filter/colour it (`[Render thread/INFO]`,
 * `Exception in thread`, native `Fatal signal 11`). Filters are plain regexes so the console can
 * add custom ones later without touching this class.
 */
class ConsoleStreamer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logcatJob: Job? = null

    /** Starts tailing logcat for the game's stdout channel. Safe to call twice. */
    fun startLogcat() {
        if (logcatJob?.isActive == true) return
        logcatJob = scope.launch {
            IbbLog.i(TAG, "Attaching console to logcat (tag: jrelog)")
            var process: Process? = null
            try {
                process = ProcessBuilder("logcat", "-v", "brief", "-s", "jrelog")
                    .redirectErrorStream(true)
                    .start()
                consume(process.inputStream) { line ->
                    val cleaned = stripLogcatPrefix(line)
                    if (cleaned.isNotBlank()) IbbLog.game(cleaned)
                }
            } catch (t: Throwable) {
                IbbLog.w(TAG, "logcat unavailable; falling back to file tail", t)
            } finally {
                runCatching { process?.destroy() }
            }
        }
    }

    /** Tails a plain text log file (used when stdout is redirected to disk). */
    fun tailFile(file: File): Job = scope.launch {
        var position = 0L
        while (true) {
            if (file.exists()) {
                if (file.length() < position) position = 0 // rotated/truncated
                withContext(Dispatchers.IO) {
                    file.inputStream().use { stream ->
                        stream.channel.position(position)
                        BufferedReader(InputStreamReader(stream)).useLines { lines ->
                            lines.forEach { line -> if (line.isNotBlank()) IbbLog.game(line) }
                        }
                        position = stream.channel.position()
                    }
                }
            }
            kotlinx.coroutines.delay(250)
        }
    }

    fun stop() {
        logcatJob?.cancel()
        logcatJob = null
    }

    private suspend fun consume(stream: InputStream, onLine: (String) -> Unit) = withContext(Dispatchers.IO) {
        BufferedReader(InputStreamReader(stream)).useLines { lines ->
            for (line in lines) {
                if (line.isNotBlank()) onLine(line)
            }
        }
    }

    companion object {
        private const val TAG = "ConsoleStreamer"

        /** `11-04 12:31:07.512 I/jrelog  (12345): [Render thread/INFO] text` → the payload. */
        private val LOGCAT_PREFIX = Regex("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+[VDIWEF]/jrelog\\s*\\(\\s*\\d+\\):\\s?")

        fun stripLogcatPrefix(line: String): String = line.replace(LOGCAT_PREFIX, "")

        private val CRASH_MARKERS = listOf(
            "fatal signal",
            "exception in thread",
            "java.lang.outofmemoryerror",
            "a fatal error has been detected",
            "caused by:",
            "crash report",
            "### failure",
        )

        /** Classify a line for the console: level + whether it deserves highlighting. */
        data class Classified(val level: IbbLog.Level, val isCrash: Boolean)

        fun classify(line: String): Classified {
            val lower = line.lowercase()
            val isCrash = CRASH_MARKERS.any { lower.contains(it) }
            val level = when {
                isCrash -> IbbLog.Level.CRASH
                lower.contains("/error") || lower.contains("[error]") || lower.startsWith("e/") -> IbbLog.Level.ERROR
                lower.contains("/warn") || lower.contains("[warn]") -> IbbLog.Level.WARN
                lower.contains("setting ") || lower.contains("loaded") -> IbbLog.Level.INFO
                else -> IbbLog.Level.GAME
            }
            return Classified(level, isCrash)
        }

        /** `[12:04:31] [Render thread/INFO]: text` → ("12:04:31", "Render thread", "INFO", "text") */
        fun parseMinecraftLine(line: String): MinecraftLogLine? {
            val match = Regex("^\\[(\\d{2}:\\d{2}:\\d{2})] \\[([^]/]+)/([A-Z]+)]:?\\s?(.*)$").find(line)
                ?: return null
            return MinecraftLogLine(
                time = match.groupValues[1],
                thread = match.groupValues[2].trim(),
                level = match.groupValues[3],
                message = match.groupValues[4],
            )
        }
    }

    data class MinecraftLogLine(val time: String, val thread: String, val level: String, val message: String)
}
