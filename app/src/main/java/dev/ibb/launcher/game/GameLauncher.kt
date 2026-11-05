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
        val surface: Surface? = null,
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
        val nativeLibraryDir: File,
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
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        val classpath = buildClasspath(flattened, instanceDir, natives, javaMajor)

        return Prepared(flattened, instanceDir, javaMajor, runtimeHome, nativeLibraryDir, natives, classpath)
    }

    /**
     * Builds the game classpath.
     *
     * Order is load-bearing:
     *  1. **authlib-injector** (optional) — must be able to replace the auth classes.
     *  2. **glfw-stub.jar** — supplies `org.lwjgl.glfw.*` against the Android EGL bridge. It has
     *     to shadow the desktop `lwjgl-glfw.jar`, so the real jar is filtered out below.
     *  3. every remaining version library (LWJGL 3 is skipped: the stub also carries the GL
     *     bindings Minecraft actually calls, and the desktop ones reference unavailable symbols).
     *  4. the client jar last, so mod loaders can shadow vanilla classes.
     */
    private fun buildClasspath(detail: VersionDetail, instanceDir: File, natives: File, javaMajor: Int): List<String> {
        val entries = mutableListOf<String>()

        yggdrasilStub()?.let { entries += it.absolutePath }
        glfwStub()?.let { entries += it.absolutePath }

        val env = VersionResolver.Env(osName = "linux", osArch = if (is64Bit()) "aarch64" else "arm")
        for (library in VersionResolver.dedupeLibraries(detail.libraries)) {
            if (!VersionResolver.isAndroidCompatible(library, env)) continue
            // The Android LWJGL is supplied by the stub + the packaged .so files.
            if (library.name.startsWith("org.lwjgl:")) continue
            val file = installer.libraryFile(library.name)
            if (file.exists()) entries += file.absolutePath
        }

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
            // LWJGL/JNA load `liblwjgl*.so`, `libopenal.so`, `libjnidispatch.so` from the APK's
            // own lib directory, which is where the native layer lives. The per-version natives
            // folder is kept in front for mods that ship their own .so files.
            nativesDirectory = prepared.nativesDir.absolutePath + ":" + prepared.nativeLibraryDir.absolutePath,
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

    /**
     * The renderer shared object, exactly as `JREUtils.loadGraphicsLibrary()` expects it.
     *
     * The native layer `dlopen()`s this by bare name (it is already in the APK's lib dir, which is
     * on the loader path), so do not prefix it with a directory.
     */
    private fun rendererLibrary(renderer: Renderer): String = renderer.nativeLibrary

    /**
     * Native bring-up + in-process JVM launch.
     *
     * The order below is the one the native layer requires; changing it produces failures that
     * look unrelated (a JVM that starts but cannot find `libjvm.so`, or a black surface because the
     * window was attached after EGL was initialised):
     *
     *  1. `LD_LIBRARY_PATH` → the runtime's `lib/`, `lib/server`, `lib/jli` and the APK's native dir.
     *  2. `dlopen` the JVM's own libraries so `JLI_Launch` does not have to resolve them itself.
     *  3. renderer (`libgl4es_114.so` / `libOSMesa.so`) — must precede EGL init.
     *  4. `setupBridgeWindow(surface)` → hands EGL the SurfaceView's ANativeWindow.
     *  5. `setupExitMethod(context)` + `initializeHooks()` → exit interception, chmod/linker hooks.
     *  6. `startJVM(argv)` on a dedicated thread; blocks until the game exits.
     */
    private fun execute(request: Request, prepared: Prepared, argv: List<String>): Int {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val renderer = request.settings.graphics.renderer

        ExecutionLayer.setLibraryPath(prepared.runtimeHome, nativeDir)
        ExecutionLayer.preloadJvm(prepared.runtimeHome)

        // Environment consumed by the GLFW stub, the EGL bridge and Mesa.
        applyNativeEnvironment(request, prepared, renderer)

        ExecutionLayer.loadRenderer(nativeDir, rendererLibrary(renderer))
        ExecutionLayer.attachSurface(request.surface)
        ExecutionLayer.setupExitHandler(context)
        ExecutionLayer.initializeHooks()
        ExecutionLayer.chdir(prepared.versionDir.absolutePath)

        // Route the game's stdout/stderr into the in-app console. Must run before JLI_Launch
        // redirects the JVM's stdio.
        GameLogSink.attach(File(prepared.versionDir, "logs/latest.log"))

        var exitCode = -1
        val thread = Thread({
            exitCode = try {
                com.oracle.dalvik.VMLauncher.startJVM(argv.toTypedArray())
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

    /**
     * Sets the environment the native bridge reads. These names are read by
     * `libpojavexec.so` / Mesa / the GLFW stub and are **not** invented here — they are the
     * contract published by the bridge project.
     */
    private fun applyNativeEnvironment(request: Request, prepared: Prepared, renderer: Renderer) {
        val graphics = request.settings.graphics

        setEnv("POJAV_RENDERER", renderer.id)
        setEnv("POJAV_NATIVES_LIBRARY", prepared.nativeLibraryDir.absolutePath)
        setEnv("LIBGL_NAME", renderer.nativeLibrary)
        setEnv("LIBGL_ES", if (renderer == Renderer.GLES2) "2" else "3")
        setEnv("MESA_GLSL_CACHE_DIR", File(context.cacheDir, "mesa").apply { mkdirs() }.absolutePath)
        setEnv("force_glsl_extensions_warn", "true")
        setEnv("allow_higher_compat_version", "true")
        setEnv("allow_glsl_extension_directive_midshader", "true")
        setEnv("FORCE_VSYNC", if (graphics.vsync) "1" else "0")
        setEnv("PATH", "${prepared.runtimeHome.absolutePath}/bin:" + (System.getenv("PATH") ?: "/system/bin"))

        if (graphics.bigCoreAffinity) setEnv("POJAV_BIG_CORE_AFFINITY", "1")

        if (renderer == Renderer.ZINK_VULKAN) {
            setEnv("MESA_LOADER_DRIVER_OVERRIDE", "zink")
            setEnv("MESA_GL_VERSION_OVERRIDE", "4.6")
            setEnv("GALLIUM_DRIVER", "zink")
            setEnv("MESA_NO_ERROR", "1")
            if (graphics.zinkPreferSystemDriver) setEnv("ZINK_USE_SYSTEM_DRIVER", "1")
        }
        if (graphics.adrenoTurbo) setEnv("POJAV_LOAD_TURNIP", "1")
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
