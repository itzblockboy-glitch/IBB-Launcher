package dev.ibb.launcher.ui.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.model.VersionSummary
import dev.ibb.launcher.ui.theme.IbbTheme

/**
 * Dedicated, orientation-locked host for a running session.
 *
 * Why a second activity instead of a Compose route in [dev.ibb.launcher.ui.MainActivity]:
 *  * the game surface needs `sensorLandscape`, `turnScreenOn` and a black window background —
 *    none of which should be forced on the launcher UI;
 *  * configuration changes (rotation, split-screen, keyboard) must not recreate the composable
 *    tree that owns the JVM handle;
 *  * the launcher tab state survives untouched behind it, so "Back to launcher" is instant.
 *
 * The session is *always* passed explicitly by [start]; there is no implicit "last game" fallback,
 * so a stale process can never resume a deleted instance.
 */
class GameActivity : ComponentActivity() {

    private var session: GameSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = IbbApplication.get(this)
        session = readSession(app, intent)

        if (session == null) {
            // Nothing to play (e.g. the process was killed and the extras were dropped).
            IbbLog.w(TAG, "GameActivity started without a session — returning to the launcher")
            finish()
            return
        }

        setContent {
            IbbTheme {
                var active by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { active = true }
                GameSurfaceHost(
                    session = session!!,
                    app = app,
                    onExit = {
                        if (active) {
                            active = false
                            finish()
                        }
                    },
                )
            }
        }
    }

    /**
     * The JVM keeps running while the activity is backgrounded, so we deliberately do **not**
     * release the surface on every `onStop`: [GameSurfaceHost] detaches when the `SurfaceView`
     * goes away and re-attaches on return.
     */
    override fun onDestroy() {
        if (isFinishing) {
            IbbLog.i(TAG, "Game activity finished")
        }
        super.onDestroy()
    }

    private fun readSession(app: IbbApplication, intent: Intent?): GameSession? {
        val versionId = intent?.getStringExtra(EXTRA_VERSION_ID)?.takeIf { it.isNotBlank() } ?: return null
        // Prefer the account that was explicitly requested, then the active one.
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val account = app.accountStore.accounts.value.firstOrNull { it.id == accountId }
            ?: app.accountStore.active
            ?: return null
        // GameLauncher derives the metadata itself when no summary is supplied, so an uninstalled
        // or freshly repaired version still boots (it will fail with a readable message instead).
        val summary = app.versionInstaller.installedSummary(versionId)?.let {
            VersionSummary(id = it.versionId, type = it.type, url = "")
        }
        return GameSession(
            account = account,
            versionId = versionId,
            settings = app.settingsStore.settings.value,
            summary = summary,
            controlLayout = app.controlRepository.selected,
        )
    }

    companion object {
        private const val TAG = "GameActivity"
        private const val EXTRA_VERSION_ID = "ibb.version_id"
        private const val EXTRA_ACCOUNT_ID = "ibb.account_id"

        /** Launches the game in a fresh task so the launcher stays in the back stack. */
        fun start(context: Context, session: GameSession) {
            val intent = Intent(context, GameActivity::class.java).apply {
                putExtra(EXTRA_VERSION_ID, session.versionId)
                putExtra(EXTRA_ACCOUNT_ID, session.account.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /** Restores a session after the process was recreated by the system. */
        fun intentFor(context: Context, session: GameSession): Intent =
            Intent(context, GameActivity::class.java).apply {
                putExtra(EXTRA_VERSION_ID, session.versionId)
                putExtra(EXTRA_ACCOUNT_ID, session.account.id)
            }
    }
}
