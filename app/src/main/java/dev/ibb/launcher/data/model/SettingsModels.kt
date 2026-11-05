package dev.ibb.launcher.data.model

/**
 * Renderer back-ends exposed by the native execution layer.
 *
 * The id/name pair must match what `libpojavexec.so` expects: the native layer reads
 * `POJAV_RENDERER` (set by `GameLauncher`) and `JREUtils.loadGraphicsLibrary()` maps it to the
 * shared object it `dlopen()`s. The three supported values are the ones PojavLauncher ships
 * prebuilt libraries for — anything else silently falls back to gl4es.
 */
enum class Renderer(
    val id: String,
    val displayName: String,
    val nativeLibrary: String,
    val notes: String,
) {
    GL4ES(
        id = "opengles3",
        displayName = "OpenGL ES 3 (gl4es)",
        nativeLibrary = "libgl4es_114.so",
        notes = "Default and most compatible. Translates desktop OpenGL to GLES 3; works on every GPU.",
    ),
    ZINK_VULKAN(
        id = "vulkan_zink",
        displayName = "Vulkan (Zink)",
        nativeLibrary = "libOSMesa.so",
        notes = "Often faster on Adreno/Mali, but needs a Vulkan 1.1 driver. Falls back to gl4es.",
    ),
    GLES2(
        id = "opengles2",
        displayName = "OpenGL ES 2 (older GPUs)",
        nativeLibrary = "libgl4es_114.so",
        notes = "For devices that only expose GLES 2.0. Slower; some shader packs will not load.",
    ),
    ;

    companion object {
        /**
         * Resolves a stored preference. Older builds saved shorter aliases (`zink`, `angle`,
         * `gl4es`) and installs may carry them across an upgrade, so they are mapped rather than
         * silently reset to the default.
         */
        fun fromId(id: String?): Renderer = when (id) {
            null, "" -> GL4ES
            "zink", "vulkan_zink" -> ZINK_VULKAN
            "angle", "opengles3" -> GL4ES
            "gl4es", "opengles2" -> entries.firstOrNull { it.id == id } ?: GL4ES
            else -> entries.firstOrNull { it.id == id } ?: GL4ES
        }
    }
}

/** Runtime tuning knobs mirrored into the native layer (`llvmpipe`/zink env variables). */
data class GraphicsOptions(
    val renderer: Renderer = Renderer.GL4ES,
    val scaleFactor: Float = 1.0f,
    val vsync: Boolean = true,
    val maxFrameRate: Int = 60,
    val zinkPreferSystemDriver: Boolean = false,
    val adrenoTurbo: Boolean = true,
    val bigCoreAffinity: Boolean = true,
    val dumpShaders: Boolean = false,
)

/** JVM tuning, derived from the device's RAM at first launch and user-editable afterwards. */
data class PerformanceOptions(
    val allocatedRamMb: Int = 1024,
    val jvmArgs: String = DEFAULT_JVM_ARGS,
    val autoRamOnStart: Boolean = true,
    val useZgcForJava21: Boolean = false,
) {
    companion object {
        /** Safe starting set: the flags Pojav-class launchers rely on for Android. */
        const val DEFAULT_JVM_ARGS =
            "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 " +
                "-XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=16M " +
                "-XX:-UseAdaptiveSizePolicy -XX:-OmitStackTraceInFastThrow " +
                "-Dfml.ignoreInvalidMinecraftCertificates=true -Dfml.ignorePatchDiscrepancies=true"
    }
}

/** The full persisted launcher configuration. */
data class LauncherSettings(
    val graphics: GraphicsOptions = GraphicsOptions(),
    val performance: PerformanceOptions = PerformanceOptions(),
    val selectedVersionId: String = "",
    val selectedProfileId: String = "",
    val language: String = "system",
    val keepScreenOn: Boolean = true,
    val showConsoleOverlay: Boolean = true,
    val autoUpdateLauncher: Boolean = true,
    val telemetryOptIn: Boolean = false,
    val gameDirectory: String = "",
)

/** Result of resolving which Java runtime a given game version needs. */
data class RuntimeRequirement(
    val majorVersion: Int,
    val component: String,
    val isInstalled: Boolean = false,
    val installedPath: String = "",
)
