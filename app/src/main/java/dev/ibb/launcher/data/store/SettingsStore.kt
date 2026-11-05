package dev.ibb.launcher.data.store

import android.content.Context
import android.content.SharedPreferences
import dev.ibb.launcher.data.model.GraphicsOptions
import dev.ibb.launcher.data.model.InputSettings
import dev.ibb.launcher.data.model.LauncherSettings
import dev.ibb.launcher.data.model.PerformanceOptions
import dev.ibb.launcher.data.model.Renderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Launcher preferences.
 *
 * Plain [SharedPreferences] is the right tool here: the values are non-sensitive tuning knobs,
 * they are read on the launch hot-path (synchronously, off the main thread) and Compose observes
 * a [StateFlow] copy so recomposition never touches disk.
 *
 * RAM defaults are derived from the device's *physical* RAM, not the `ActivityManager` memory
 * class (which reports the per-app heap cap and is wildly conservative on modern phones).
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ibb_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<LauncherSettings> = _settings.asStateFlow()

    companion object {
        /** Device RAM in MB from `/proc/meminfo` (falls back to the ActivityManager hint). */
        fun totalRamMb(): Int {
            val memTotalKb = runCatching {
                java.io.File("/proc/meminfo").useLines { lines ->
                    lines.first { it.startsWith("MemTotal:") }
                        .split(Regex("\\s+"))
                        .getOrNull(1)?.toLongOrNull() ?: 0L
                }
            }.getOrNull() ?: 0L
            return if (memTotalKb > 0) (memTotalKb / 1024).toInt() else 3072
        }

        /**
         * Recommended `-Xmx` for the device. Android + the GPU driver need ~1.2–1.6 GB, so we
         * reserve a healthy chunk, then round down to a 256 MB step and clamp to [512, 6144].
         */
        fun recommendedRamMb(totalMb: Int = totalRamMb()): Int {
            val reserved = when {
                totalMb >= 12_288 -> 4_096
                totalMb >= 8_192 -> 3_072
                totalMb >= 6_144 -> 2_048
                totalMb >= 4_096 -> 1_536
                else -> 1_024
            }
            val raw = totalMb - reserved
            val stepped = (raw / 256) * 256
            return stepped.coerceIn(512, 6_144)
        }

        /** Lower bound for the slider (the game will not boot reliably below this). */
        fun minRamMb(totalMb: Int = totalRamMb()): Int = if (totalMb >= 4_096) 512 else 384

        /** Upper bound for the slider — never allow allocating the whole device. */
        fun maxRamMb(totalMb: Int = totalRamMb()): Int = (totalMb - 1_024).coerceIn(1_024, 8_192)

        /** 1 GB steps are plenty for a slider; finer control comes from the numeric field. */
        fun ramSteps(totalMb: Int = totalRamMb()): Int {
            val span = maxRamMb(totalMb) - minRamMb(totalMb)
            return (span / 256).coerceAtLeast(1)
        }

        // SharedPreferences keys (kept together so migrations stay obvious).
        const val KEY_RENDERER = "renderer"
        const val KEY_SCALE = "scale"
        const val KEY_VSYNC = "vsync"
        const val KEY_MAX_FPS = "max_fps"
        const val KEY_ZINK_DRIVER = "zink_driver"
        const val KEY_ADRENO_TURBO = "adreno_turbo"
        const val KEY_BIG_CORES = "big_cores"
        const val KEY_DUMP_SHADERS = "dump_shaders"
        const val KEY_RAM = "ram"
        const val KEY_JVM_ARGS = "jvm_args"
        const val KEY_AUTO_RAM = "auto_ram"
        const val KEY_ZGC = "zgc"
        const val KEY_VERSION = "version"
        const val KEY_PROFILE = "profile"
        const val KEY_LANGUAGE = "language"
        const val KEY_KEEP_SCREEN = "keep_screen"
        const val KEY_CONSOLE_OVERLAY = "console_overlay"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_TELEMETRY = "telemetry"
        const val KEY_GAME_DIR = "game_dir"
        const val KEY_KEYBOARD = "keyboard_layout"
        const val KEY_GAMEPAD = "gamepad"
        const val KEY_DEADZONE = "deadzone"
        const val KEY_CURSOR_SPEED = "cursor_speed"
        const val KEY_CAPTURE_MOUSE = "capture_mouse"
        const val KEY_INVERT_Y = "invert_y"
        const val KEY_MOUSE_SENS = "mouse_sens"
        const val KEY_RMB_HOLD = "rmb_hold"
    }

    fun read(): LauncherSettings {
        val total = totalRamMb()
        return LauncherSettings(
            graphics = GraphicsOptions(
                renderer = Renderer.fromId(prefs.getString(KEY_RENDERER, Renderer.GL4ES.id)),
                scaleFactor = prefs.getFloat(KEY_SCALE, 1.0f).coerceIn(0.5f, 2.0f),
                vsync = prefs.getBoolean(KEY_VSYNC, true),
                maxFrameRate = prefs.getInt(KEY_MAX_FPS, 60).coerceIn(30, 240),
                zinkPreferSystemDriver = prefs.getBoolean(KEY_ZINK_DRIVER, false),
                adrenoTurbo = prefs.getBoolean(KEY_ADRENO_TURBO, true),
                bigCoreAffinity = prefs.getBoolean(KEY_BIG_CORES, true),
                dumpShaders = prefs.getBoolean(KEY_DUMP_SHADERS, false),
            ),
            performance = PerformanceOptions(
                allocatedRamMb = prefs.getInt(KEY_RAM, recommendedRamMb(total))
                    .coerceIn(minRamMb(total), maxRamMb(total)),
                jvmArgs = prefs.getString(KEY_JVM_ARGS, PerformanceOptions.DEFAULT_JVM_ARGS)!!,
                autoRamOnStart = prefs.getBoolean(KEY_AUTO_RAM, true),
                useZgcForJava21 = prefs.getBoolean(KEY_ZGC, false),
            ),
            selectedVersionId = prefs.getString(KEY_VERSION, "")!!,
            selectedProfileId = prefs.getString(KEY_PROFILE, "")!!,
            language = prefs.getString(KEY_LANGUAGE, "system")!!,
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN, true),
            showConsoleOverlay = prefs.getBoolean(KEY_CONSOLE_OVERLAY, true),
            autoUpdateLauncher = prefs.getBoolean(KEY_AUTO_UPDATE, true),
            telemetryOptIn = prefs.getBoolean(KEY_TELEMETRY, false),
            gameDirectory = prefs.getString(KEY_GAME_DIR, "")!!,
        )
    }

    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val updated = transform(_settings.value)
        write(updated)
        _settings.value = updated
    }

    fun inputSettings(): InputSettings = InputSettings(
        keyboardLayout = prefs.getString(KEY_KEYBOARD, "QWERTY")!!,
        gamepadEnabled = prefs.getBoolean(KEY_GAMEPAD, true),
        gamepadDeadzone = prefs.getFloat(KEY_DEADZONE, 0.12f),
        gamepadCursorSpeed = prefs.getFloat(KEY_CURSOR_SPEED, 1.0f),
        mouseCaptureOnStart = prefs.getBoolean(KEY_CAPTURE_MOUSE, true),
        invertMouseY = prefs.getBoolean(KEY_INVERT_Y, false),
        mouseSensitivity = prefs.getFloat(KEY_MOUSE_SENS, 1.0f),
        rightClickHoldMode = prefs.getBoolean(KEY_RMB_HOLD, false),
    )

    fun saveInputSettings(input: InputSettings) {
        prefs.edit()
            .putString(KEY_KEYBOARD, input.keyboardLayout)
            .putBoolean(KEY_GAMEPAD, input.gamepadEnabled)
            .putFloat(KEY_DEADZONE, input.gamepadDeadzone)
            .putFloat(KEY_CURSOR_SPEED, input.gamepadCursorSpeed)
            .putBoolean(KEY_CAPTURE_MOUSE, input.mouseCaptureOnStart)
            .putBoolean(KEY_INVERT_Y, input.invertMouseY)
            .putFloat(KEY_MOUSE_SENS, input.mouseSensitivity)
            .putBoolean(KEY_RMB_HOLD, input.rightClickHoldMode)
            .apply()
    }

    private fun write(s: LauncherSettings) {
        prefs.edit()
            .putString(KEY_RENDERER, s.graphics.renderer.id)
            .putFloat(KEY_SCALE, s.graphics.scaleFactor)
            .putBoolean(KEY_VSYNC, s.graphics.vsync)
            .putInt(KEY_MAX_FPS, s.graphics.maxFrameRate)
            .putBoolean(KEY_ZINK_DRIVER, s.graphics.zinkPreferSystemDriver)
            .putBoolean(KEY_ADRENO_TURBO, s.graphics.adrenoTurbo)
            .putBoolean(KEY_BIG_CORES, s.graphics.bigCoreAffinity)
            .putBoolean(KEY_DUMP_SHADERS, s.graphics.dumpShaders)
            .putInt(KEY_RAM, s.performance.allocatedRamMb)
            .putString(KEY_JVM_ARGS, s.performance.jvmArgs)
            .putBoolean(KEY_AUTO_RAM, s.performance.autoRamOnStart)
            .putBoolean(KEY_ZGC, s.performance.useZgcForJava21)
            .putString(KEY_VERSION, s.selectedVersionId)
            .putString(KEY_PROFILE, s.selectedProfileId)
            .putString(KEY_LANGUAGE, s.language)
            .putBoolean(KEY_KEEP_SCREEN, s.keepScreenOn)
            .putBoolean(KEY_CONSOLE_OVERLAY, s.showConsoleOverlay)
            .putBoolean(KEY_AUTO_UPDATE, s.autoUpdateLauncher)
            .putBoolean(KEY_TELEMETRY, s.telemetryOptIn)
            .putString(KEY_GAME_DIR, s.gameDirectory)
            .apply()
    }
}
