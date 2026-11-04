package dev.ibb.launcher.data.model

/**
 * Renderer back-ends exposed by the native execution layer.
 * [nativeLibrary] must match the module name the bring-up code dlopen()s.
 */
enum class Renderer(
    val id: String,
    val displayName: String,
    val nativeLibrary: String,
    val notes: String,
) {
    ANGLE_GLES(
        id = "angle",
        displayName = "ANGLE → OpenGL ES 3",
        nativeLibrary = "libEGL_angle.so",
        notes = "Most compatible. Translates desktop GL 3.3 to GLES 3.x through ANGLE.",
    ),
    ZINK_VULKAN(
        id = "zink",
        displayName = "Zink → Vulkan",
        nativeLibrary = "libOSMesa_zink.so",
        notes = "Fastest on Adreno/Mali with modern drivers; falls back to ANGLE when unavailable.",
    ),
    VIRGL(
        id = "virgl",
        displayName = "VirGL (experimental)",
        nativeLibrary = "libvirglrenderer.so",
        notes = "Experimental path for devices with a host-side renderer. Expect glitches.",
    ),
    GL4ES(
        id = "gl4es",
        displayName = "GL4ES → GLES 2",
        nativeLibrary = "libgl4es.so",
        notes = "Legacy GL 1.x/2.x translator. Use for very old devices or 1.8-era modpacks.",
    );

    companion object {
        fun fromId(id: String?): Renderer = entries.firstOrNull { it.id == id } ?: ANGLE_GLES
    }
}

/** Runtime tuning knobs mirrored into the native layer (`llvmpipe`/zink env variables). */
data class GraphicsOptions(
    val renderer: Renderer = Renderer.ANGLE_GLES,
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
