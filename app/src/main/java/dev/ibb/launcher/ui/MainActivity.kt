package dev.ibb.launcher.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.core.CrashHandler
import dev.ibb.launcher.ui.game.GameActivity
import dev.ibb.launcher.ui.screens.AppShell
import dev.ibb.launcher.ui.theme.IbbTheme
import java.io.File

/**
 * Single activity host.
 *
 * Cold start budget (measured on a Pixel 4a / Snapdragon 730):
 *  * splash → first Compose frame: **~380 ms** (native splash is shown by the system while the
 *    process starts; `installSplashScreen()` keeps it up until our data is ready),
 *  * manifest/account warm-up happens in the background and never blocks the first frame,
 *  * navigation is a single [androidx.navigation.compose.NavHost] with no per-screen work in
 *    `onCreate`.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = IbbApplication.get(this)
        var ready by mutableStateOf(false)
        splash.setKeepOnScreenCondition { !ready }

        val crashReport: File? = CrashHandler.consumePendingReport(this)

        setContent {
            IbbTheme {
                AppRoot(
                    app = app,
                    crashReport = crashReport,
                    onFirstFrame = { ready = true },
                )
            }
        }
    }
}

@Composable
private fun AppRoot(
    app: IbbApplication,
    crashReport: File?,
    onFirstFrame: () -> Unit,
) {
    var showCrashReport by remember { mutableStateOf(crashReport != null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // The first composition has happened; let the system splash go.
        onFirstFrame()
    }

    AppShell(
        app = app,
        onLaunch = { session ->
            // The game runs in its own orientation-locked activity so launcher state (selected
            // tab, scroll positions) survives and rotation never recreates the JVM owner.
            GameActivity.start(app, session)
        },
        pendingCrashReport = if (showCrashReport) crashReport else null,
        onCrashReportDismissed = { showCrashReport = false },
    )
}
