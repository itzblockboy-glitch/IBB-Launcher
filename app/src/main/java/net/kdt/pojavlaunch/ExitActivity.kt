package net.kdt.pojavlaunch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ibb.launcher.core.IbbLog

/**
 * Crash / abnormal-exit screen opened **from the native layer**.
 *
 * `libpojavexec.so` (`stdio_is.c`) resolves this class during `JREUtils.setupExitMethod` and calls
 * `showExitMessage(Context, Int, Boolean)` on it when the game JVM exits with a non-zero status or
 * dies from a signal. Because the call comes from C, the class name, the method name and the
 * signature `(Landroid/content/Context;IZ)V` are part of the ABI and cannot change.
 *
 * Deliberately plain `Activity` + framework widgets: this screen must be able to open when the
 * Compose/AndroidX process state is already suspect after a native crash. It reads the last lines
 * of the session log and offers a copy/share action plus "back to launcher".
 */
class ExitActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, -1)
        val wasSignal = intent.getBooleanExtra(EXTRA_IS_SIGNAL, false)
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        column.addView(
            TextView(this).apply {
                text = when {
                    wasSignal -> "Minecraft crashed (signal)"
                    exitCode == 0 -> "Minecraft closed"
                    else -> "Minecraft stopped unexpectedly"
                }
                textSize = 22f
                gravity = Gravity.START
            },
        )

        column.addView(
            TextView(this).apply {
                text = buildString {
                    if (exitCode != 0) append("Exit code: ").append(exitCode).append('\n')
                    if (wasSignal) append("The process was killed by a signal — this is usually a GPU driver or out-of-memory fault.\n")
                    if (reason.isNotBlank()) append(reason)
                }.trim().ifBlank { "The session ended." }
                textSize = 14f
                setPadding(0, 24, 0, 24)
            },
        )

        column.addView(
            ScrollView(this).apply {
                addView(
                    TextView(this@ExitActivity).apply {
                        text = lastLogLines(40)
                        textSize = 11f
                        typeface = android.graphics.Typeface.MONOSPACE
                    },
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        column.addView(
            Button(this).apply {
                text = "Share log"
                setOnClickListener { shareLog() }
            },
        )

        column.addView(
            Button(this).apply {
                text = "Back to launcher"
                setOnClickListener {
                    startActivity(
                        Intent().setClassName(packageName, "dev.ibb.launcher.ui.MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                    finish()
                }
            },
        )

        setContentView(column)
    }

    private fun lastLogLines(count: Int): String =
        runCatching { IbbLog.history.value.takeLast(count).joinToString("\n") { it.format() } }
            .getOrElse { "Log unavailable: ${it.message}" }

    private fun shareLog() {
        runCatching {
            val text = lastLogLines(400)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "IBB Launcher crash log")
            }
            startActivity(Intent.createChooser(share, "Share log"))
        }.onFailure { IbbLog.w(TAG, "Could not share the log", it) }
    }

    companion object {
        private const val TAG = "ExitActivity"
        private const val EXTRA_EXIT_CODE = "exit_code"
        private const val EXTRA_IS_SIGNAL = "is_signal"
        private const val EXTRA_REASON = "reason"

        /**
         * Called by `libpojavexec.so` when the JVM exits abnormally.
         *
         * Runs on a native thread, so it must not touch the UI directly: it starts this activity
         * with `FLAG_ACTIVITY_NEW_TASK` and returns immediately. `context` is the application
         * context captured by `JREUtils.setupExitMethod`.
         */
        @JvmStatic
        fun showExitMessage(context: Context, exitCode: Int, isSignal: Boolean) {
            IbbLog.e(TAG, "Game exited abnormally (code=$exitCode signal=$isSignal)")
            runCatching {
                val intent = Intent(context, ExitActivity::class.java).apply {
                    putExtra(EXTRA_EXIT_CODE, exitCode)
                    putExtra(EXTRA_IS_SIGNAL, isSignal)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
            }.onFailure { IbbLog.w(TAG, "Could not open the exit screen", it) }
        }
    }
}
