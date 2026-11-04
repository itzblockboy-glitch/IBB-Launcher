package dev.ibb.launcher.game

import android.content.Context
import android.view.Surface
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.core.IbbPaths
import dev.ibb.launcher.data.model.Account
import dev.ibb.launcher.data.model.LauncherSettings
import dev.ibb.launcher.data.model.Renderer
import dev.ibb.launcher.data.model.VersionDetail
import dev.ibb.launcher.data.model.VersionSummary
import dev.ibb.launcher.game.nativebridge.ExecutionLayer
import dev.ibb.launcher.repository.VersionInstaller
import dev.ibb.launcher.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Orchestrates a game launch.
 *
 * Responsibilities, in order:
 * 1. **Pre-flight** — every failure mode we can detect *before* spawning the JVM is checked here
 *    (missing version, missing runtime, missing account, missing assets). Each produces an
 *    actionable message instead of a Java stack trace in the console.
 * 2. **Command line** — delegate to [LaunchArgsBuilder] (pure, unit-tested).
 * 3. **Native bring-up** — [ExecutionLayer]: LD_LIBRARY_PATH → preload JVM → renderer → surface.
 * 4. **Execute** — `com.oracle.dalvik.VMLauncher.launchJVM(argv)` runs in-process (the JNI
 *    symbol is `Java_com_oracle_dalvik_VMLauncher_launchJVM`) and blocks until exit.
 * 5. **Recovery** — non-zero exit returns a [LaunchResult.Failed] carrying the console tail, so
 *    the UI can offer "Share log" / "Repair" / "Switch renderer".
 *
 * The class is deliberately stateless apart from the observable [state]; one instance per process
 * (a second launch while one is running is rejected).
 */
class GameLauncher(
    private val context: Context,
    private val installer: VersionInstaller,
    private val runtimes: RuntimeManager,
) {

    sealed class LaunchState {
        data object Idle : LaunchState()
        data class Preparing(val step: String) : LaunchState()
        data object Running : LaunchState()
        data class Exited(val exitCode: Int, val logTail: String) : LaunchState()
        data class Failed(val reason: String, val hint: String = "") : LaunchState()
    }

    data class Request(
        val account: Account,
        val versionId: String,
        val settings: LauncherSettings,
        val surface: Surface,
        val surfaceWidth: Int,
        val surfaceHeight: Int,
        val versionSummary: VersionSummary? = null,
        val quickPlayWorld: String? = null,
    )

    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> = _state.asStateFlow()

    @Volatile private var running = false

    val isRunning: Boolean get() = running

    suspend fun launch(request: Request): LaunchState = withContext(Dispatchers.IO) {
        if (running) {
            return@withContext LaunchState.Failed("A game session is already running")
        }
        try {
            _state.value = LaunchState.Preparing("Validating installation")
            val prepared = preflight(request)

            _state.value = LaunchState.Preparing("Starting Java ${prepared.javaMajor}")
            val argv = buildCommand(request, prepared)
            IbbLog.i(TAG, "Launching ${request.versionId} with Java ${prepared.javaMajor}")
            IbbLog.jvm(argv.joinToString(" "))

            _state.value = LaunchState.Preparing("Booting JVM")
            running = true
            val exit = execute(request, prepared, argv)
            running = false

            val tail = consoleTail()
            val state = if (exit == 0) {
                IbbLog.i(TAG, "Game exited normally")
                LaunchState.Exited(0, tail)
            } else {
                IbbLog.e(TAG, "Game exited with code $exit")
                LaunchState.Exited(exit, tail)
            }
            _state.value = state
            state
        } catch (t: Throwable) {
            running = false
            IbbLog.e(TAG, "Launch failed", t)
            val failed = LaunchState.Failed(
                reason = t.message ?: "The game could not start",
                hint = recoveryHint(t),
            )
            _state.value = failed
            failed
        }
    }

    // ------------------------------------------------------------------------------------
    // Pre-flight
    // ------------------------------------------------------------------------------------

    private data class Prepared(
        val detail: VersionDetail,
        val versionDir: File,
        val javaMajor: Int,
        val runtimeHome: File,
        val nativesDir: File,
        val classpath: List<String>,
    )

    private fun preflight(request: Request): Prepared {
        if (!ExecutionLayer.isAvailable) {
            throw IOException(
                "The native execution layer is missing from this build. " +
                    "Rebuild with the pojavexec libraries (see docs/BUILDING.md).",
            )
        }
        if (!installer.isInstalled(request.versionId)) {
            throw IOException("Version ${request.versionId} is not installed")
        }
        val detail = installer.readVersionJson(request.versionId)
            ?: throw IOException("Version metadata is corrupt — repair the installation")
        val parent = detail.inheritsFrom?.let { installer.readVersionJson(it) }
        val flattened = VersionResolver.flatten(detail, parent)

        val javaMajor = VersionResolver.requiredJavaMajor(
            request.versionId,
            flattened.javaVersion?.majorVersion,
        )
        if (!runtimes.isInstalled(javaMajor)) {
            throw IOException(
                "Java $javaMajor is required but not installed. " +
                    "Open Settings → Runtimes to download it (${runTimesSizeHint(javaMajor)}).",
            )
        }
        val runtimeHome = runtimes.runtimeHome(javaMajor)

        val instanceDir = IbbPaths.instanceDir(context, request.versionId)
        seedInstanceDefaults(instanceDir)

        val natives = IbbPaths.nativesDir(context, request.versionId)
        val classpath = buildClasspath(flattened, instanceDir, natives, javaMajor)

        return Prepared(flattened, instanceDir, javaMajor, runtimeHome, natives, classpath)
    }

    private fun buildClasspath(detail: VersionDetail, instanceDir: File, natives: File, javaMajor: Int): List<String> {
        val entries = mutableListOf<String>()
        // The glfw/EGL shim must win over any desktop LWJGL jar pulled in transitively.
        // Add stubs first: they must shadow the desktop LWJGL classes shipped by mod loaders.
        val authStub = yggdrasilStub()
        if (authStub != null) entries += authStub.absolutePath
        val glfwJar = glfwStub()
        if (glfwJar != null) entries += glfwJar.absolutePath

        val env = VersionResolver.Env(osName = "linux", osArch = if (is64Bit()) "aarch64" else "arm")
        for (library in VersionResolver.dedupeLibraries(detail.libraries)) {
            if (!VersionResolver.isAndroidCompatible(library, env)) continue
            val file = installer.libraryFile(library.name)
            if (file.exists()) entries += file.absolutePath
        }
        // The client jar always comes last so mod loaders can shadow vanilla classes.
        val jarId = detail.inheritsFrom ?: detail.id
        val clientJar = File(IbbPaths.versionsDir(context), "$jarId/$jarId.jar")
        if (clientJar.exists()) entries += clientJar.absolutePath
        return entries
    }

    private fun is64Bit(): Boolean =
        android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    private fun glfwStub(): File? = runCatching { ComponentManager.glfwStub(context) }.getOrNull()

    private fun yggdrasilStub(): File? = runCatching { ComponentManager.authlibInjector(context) }.getOrNull()

    /**
     * First run of an instance: create the folder skeleton and copy the launcher's tuned
     * `options.txt` so the game starts in a sensible fullscreen/windowed state.
     */
    private fun seedInstanceDefaults(instanceDir: File) {
        listOf("mods", "resourcepacks", "shaderpacks", "saves", "config", "logs", "screenshots")
            .forEach { folder -> File(instanceDir, folder).mkdirs() }
        val options = File(instanceDir, "options.txt")
        if (!options.exists()) {
            // The bundled options.txt is only a nicety: a missing asset must never block a launch.
            runCatching {
                val assets = context.assets
                assets.open("defaults/options.txt").use { input ->
                    options.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { IbbLog.w(TAG, "Could not seed options.txt", it) }
        }
    }

    private fun runTimesSizeHint(major: Int): String = when (major) {
        8 -> "≈65 MB"
        17 -> "≈85 MB"
        else -> "≈95 MB"
    }

    // ------------------------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------------------------

    private fun buildCommand(request: Request, prepared: Prepared): List<String> {
        val summary = request.versionSummary ?: VersionSummary(
            id = request.versionId,
            type = prepared.detail.type,
            url = "",
        )
        val scale = request.settings.graphics.scaleFactor.coerceIn(0.5f, 2f)
        val virtualWidth = (request.surfaceWidth / scale).toInt().coerceAtLeast(320)
        val virtualHeight = (request.surfaceHeight / scale).toInt().coerceAtLeast(240)

        val env = LaunchArgsBuilder.Environment(
            gameDirectory = prepared.versionDir.absolutePath,
            assetsDirectory = IbbPaths.assetsDir(context).absolutePath,
            assetIndexId = prepared.detail.assetIndex?.id ?: prepared.detail.assets,
            versionId = request.versionId,
            versionType = prepared.detail.type,
            nativesDirectory = prepared.nativesDir.absolutePath,
            librariesDirectory = IbbPaths.librariesDir(context).absolutePath,
            runtimeHome = prepared.runtimeHome.absolutePath,
            windowWidth = virtualWidth,
            windowHeight = virtualHeight,
            glfwStubWidth = virtualWidth,
            glfwStubHeight = virtualHeight,
            allocatedRamMb = request.settings.performance.allocatedRamMb,
            extraJvmArgs = parseJvmArgs(request.settings),
            classpath = prepared.classpath,
            mainClass = prepared.detail.mainClass,
            resolutionRequested = false,
            openglLibrary = rendererLibrary(request.settings.graphics.renderer),
            cpuCount = Runtime.getRuntime().availableProcessors(),
        )
        return LaunchArgsBuilder.buildFullCommand(
            account = request.account,
            version = prepared.detail,
            summary = summary,
            env = env,
            quickPlayWorld = request.quickPlayWorld,
        ).let { LaunchArgsBuilder.findUnresolvedPlaceholders(it).takeIf { bad -> bad.isNotEmpty() }?.let { bad ->
            IbbLog.w(TAG, "Unresolved launch placeholders: $bad")
        }; it }
    }

    private fun parseJvmArgs(settings: LauncherSettings): List<String> =
        settings.performance.jvmArgs.split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun rendererLibrary(renderer: Renderer): String? = when (renderer) {
        Renderer.ANGLE_GLES -> "libEGL_angle.so"
        Renderer.ZINK_VULKAN -> "libOSMesa_zink.so"
        Renderer.VIRGL -> "libvirglrenderer.so"
        Renderer.GL4ES -> "libgl4es.so"
    }

    /**
     * Native bring-up + in-process JVM launch.
     *
     * Runs on a dedicated thread with a generous stack (the JVM launcher recurses through JLI and
     * LWJGL init) and sets the process-wide environment before `JLI_Launch` reads it.
     */
    private fun execute(request: Request, prepared: Prepared, argv: List<String>): Int {
        ExecutionLayer.setLibraryPath(prepared.runtimeHome)
        ExecutionLayer.preloadJvm(prepared.runtimeHome)
        ExecutionLayer.loadRenderer(File(context.applicationInfo.nativeLibraryDir), rendererLibrary(request.settings.graphics.renderer))
        ExecutionLayer.attachSurface(request.surface)
        ExecutionLayer.initializeHooks()
        ExecutionLayer.setupExitHandler(context)
        ExecutionLayer.chdir(prepared.versionDir.absolutePath)

        // Env vars consumed by the GLFW/EGL shim and by Mesa when zink is selected.
        setEnv("POJAV_NATIVES_LIBRARY", prepared.nativesDir.absolutePath)
        setEnv("POJAV_RENDERER", request.settings.graphics.renderer.id)
        setEnv("MESA_NO_ERROR", "1")
        if (request.settings.graphics.renderer == Renderer.ZINK_VULKAN) {
            setEnv("MESA_GL_VERSION_OVERRIDE", "4.6")
            setEnv("GALLIUM_DRIVER", "zink")
            setEnv("ZINK_DESCRIPTORS", "lazy")
            if (request.settings.graphics.zinkPreferSystemDriver) setEnv("ZINK_USE_SYSTEM_DRIVER", "1")
        }
        setEnv("LIBGL_ES", "3")

        var exitCode = -1
        val thread = Thread({
            exitCode = try {
                com.oracle.dalvik.VMLauncher.launchJVM(argv.toTypedArray())
            } catch (t: Throwable) {
                IbbLog.e(TAG, "JVM launch threw", t)
                -1
            }
        }, "IBB-GameThread").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = false
            start()
        }
        thread.join()
        ExecutionLayer.detachSurface()
        return exitCode
    }

    private fun setEnv(key: String, value: String) {
        runCatching {
            // android.system.Os is API 21+ and the only supported way to mutate environ.
            val os = Class.forName("android.system.Os")
            val setenv = os.getMethod("setenv", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            setenv.invoke(null, key, value, true)
        }.onFailure { IbbLog.w(TAG, "Could not set $key", it) }
    }

    private fun consoleTail(lines: Int = 60): String =
        IbbLog.history.value.takeLast(lines).joinToString("\n") { it.format() }

    /** Maps common JVM failures to user actions. */
    private fun recoveryHint(t: Throwable): String {
        val message = (t.message ?: "").lowercase()
        val tail = consoleTail(15).lowercase()
        return when {
            "outofmemory" in tail || "could not reserve enough space" in tail ->
                "Lower the allocated RAM in Settings → Performance, or close other apps."
            "java.lang.unsupportedclassversionerror" in tail ->
                "This version needs a newer Java runtime. Install Java 21 in Settings → Runtimes."
            "glfw" in tail || "egl" in tail ->
                "The renderer failed to start. Try switching to ANGLE in Settings → Graphics."
            "nosuchmethod" in tail || "noclassdeffound" in tail ->
                "Libraries are incomplete. Use Repair installation in Settings → Maintenance."
            "checksum" in message -> "A download was corrupted — reinstall the version."
            message.contains("not installed") -> t.message.orEmpty()
            else -> "Open the Console tab for the full log, then share it if the issue persists."
        }
    }

    private companion object {
        const val TAG = "GameLauncher"
    }
}
