package dev.ibb.launcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.StrictMode
import dev.ibb.launcher.controls.ControlRepository
import dev.ibb.launcher.core.CrashHandler
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.core.IbbPaths
import dev.ibb.launcher.data.auth.MicrosoftAuth
import dev.ibb.launcher.data.network.MojangApi
import dev.ibb.launcher.data.store.AccountStore
import dev.ibb.launcher.data.store.SecureStore
import dev.ibb.launcher.data.store.SettingsStore
import dev.ibb.launcher.mods.LoaderRepository
import dev.ibb.launcher.repository.VersionInstaller
import dev.ibb.launcher.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point and tiny service locator.
 *
 * A hand-rolled container (instead of Hilt/Koin) is a conscious choice: the graph is ~12 objects
 * with no scoping subtleties, and this keeps the APK free of an annotation processor and ~800 KB
 * of generated code. Everything is constructed lazily on first use.
 */
class IbbApplication : Application() {

    /** Long-lived scope for work that should survive any single screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val secureStore: SecureStore by lazy { SecureStore(this) }
    val accountStore: AccountStore by lazy { AccountStore(secureStore).also { it.load() } }
    val mojangApi: MojangApi by lazy { MojangApi() }
    val microsoftAuth: MicrosoftAuth by lazy { MicrosoftAuth() }
    val loaderRepository: LoaderRepository by lazy { LoaderRepository() }
    val runtimeManager: RuntimeManager by lazy {
        RuntimeManager(
            rootDir = IbbPaths.runtimeRoot(this),
            arch = RuntimeManager.AndroidArch.fromAbis(Build.SUPPORTED_ABIS.toList()),
        )
    }
    val versionInstaller: VersionInstaller by lazy {
        VersionInstaller(mojangApi, loaderRepository, IbbPaths.gameRoot(this))
    }
    val controlRepository: ControlRepository by lazy {
        ControlRepository(IbbPaths.controlsDir(this)).also { it.load() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The AWT/Cacio bridge resolves net.kdt.pojavlaunch.MainActivity statically.
        net.kdt.pojavlaunch.MainActivity.attach(this)
        CrashHandler.install(this)
        IbbLog.i(TAG, "IBB Launcher starting on ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        createNotificationChannels()
        installStrictModeInDebug()
        warmUpCaches()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GAME,
                getString(R.string.notification_channel_game),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows that a Minecraft session is running"
                setShowBadge(false)
            },
        )
    }

    /** Surface accidental disk/network work on the main thread while developing. */
    private fun installStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * Pre-warm the pieces that would otherwise stall the first screen: the version manifest
     * (~300 KB JSON) and the account list decryption. Both are idempotent and failure-tolerant.
     */
    private fun warmUpCaches() {
        appScope.launch(Dispatchers.IO) {
            runCatching { mojangApi.manifest() }
                .onFailure { IbbLog.w(TAG, "Manifest warm-up failed (offline?)", it) }
        }
        appScope.launch(Dispatchers.IO) {
            val pending = CrashHandler.consumePendingReport(this@IbbApplication)
            if (pending != null) IbbLog.w(TAG, "Previous run crashed — report at ${pending.absolutePath}")
        }
    }

    companion object {
        private const val TAG = "IbbApplication"
        const val CHANNEL_GAME = "game_session"

        @Volatile private lateinit var instance: IbbApplication

        fun get(context: Context): IbbApplication =
            context.applicationContext as IbbApplication
    }
}
