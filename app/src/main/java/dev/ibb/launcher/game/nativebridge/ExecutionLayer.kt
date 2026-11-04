package dev.ibb.launcher.game.nativebridge

import android.view.Surface
import dev.ibb.launcher.core.IbbLog
import net.kdt.pojavlaunch.utils.JREUtils
import java.io.File

/**
 * Facade over the native execution layer (PojavLauncher-derived `pojavexec`).
 *
 * The actual JNI declarations live in [net.kdt.pojavlaunch.utils.JREUtils] because the prebuilt
 * shared libraries export symbols with that exact prefix. Everything else in IBB Launcher talks
 * to the native layer through this class, so the contract is documented in one place.
 *
 * ### Bring-up sequence (order is load-bearing)
 * 1. `System.loadLibrary("exithook")` → installs the exit/abort interceptors.
 * 2. `System.loadLibrary("pojavexec")` → dlopen bridge, EGL bridge, input bridge, GOT hooks.
 * 3. `setLdLibraryPath(runtime/lib)` → lets `libjli.so` find `libjvm.so` and friends.
 * 4. `dlopen(libjli) → dlopen(libjvm) → dlopen(libawt_*)` → seed the JVM's native deps.
 * 5. `setupBridgeWindow(surface)` → hand EGL the SurfaceView's ANativeWindow.
 * 6. `initializeHooks()` → GLFW GLX dispatch, AWT stubs, Android resolver patches.
 * 7. `com.oracle.dalvik.VMLauncher.launchJVM(argv)` → runs `JLI_Launch` **in-process**; it blocks until
 *    Minecraft exits and returns its exit code.
 */
object ExecutionLayer {

    private const val TAG = "ExecutionLayer"

    val isAvailable: Boolean get() = JREUtils.loadNativeLibraries()

    /** Libraries that must be resident before `JLI_Launch` walks the runtime directory. */
    private val mandatoryJvmLibs = listOf(
        "libjli.so",
        "libjvm.so",
        "libverify.so",
        "libjava.so",
        "libnet.so",
        "libnio.so",
        "libzip.so",
    )

    private val optionalJvmLibs = listOf(
        "libawt.so",
        "libawt_headless.so",
        "libfreetype.so",
        "libfontmanager.so",
        "libjimage.so",
    )

    fun setLibraryPath(runtimeHome: File) {
        val libPath = listOf(
            File(runtimeHome, "lib").absolutePath,
            File(runtimeHome, "lib/server").absolutePath,
            File(runtimeHome, "lib/client").absolutePath,
            File(runtimeHome, "lib/jli").absolutePath,
        ).filter { File(it).exists() }.joinToString(":")
        JREUtils.setLdLibraryPath(libPath)
        IbbLog.jvm("LD_LIBRARY_PATH=$libPath")
    }

    /** Loads the JVM's shared objects so `JLI_Launch` can resolve them. */
    fun preloadJvm(runtimeHome: File) {
        var missingMandatory = 0
        for (lib in mandatoryJvmLibs) {
            if (!dlopenAnywhere(runtimeHome, lib)) {
                missingMandatory++
                IbbLog.e(TAG, "Could not load $lib from ${runtimeHome.absolutePath}")
            }
        }
        for (lib in optionalJvmLibs) {
            dlopenAnywhere(runtimeHome, lib)
        }
        if (missingMandatory > 0) {
            throw IllegalStateException(
                "This Java runtime is incomplete ($missingMandatory core libraries missing). " +
                    "Delete it in Settings → Runtimes and install it again.",
            )
        }
    }

    private fun dlopenAnywhere(runtimeHome: File, name: String): Boolean {
        if (JREUtils.dlopenSafe(name)) return true
        val candidates = listOf(
            File(runtimeHome, "lib/$name"),
            File(runtimeHome, "lib/server/$name"),
            File(runtimeHome, "lib/jli/$name"),
        )
        return candidates.any { candidate -> candidate.exists() && JREUtils.dlopenSafe(candidate.absolutePath) }
    }

    /** Preferred browser of the OpenGL translation library (ANGLE / zink / gl4es). */
    fun loadRenderer(nativeLibraryDir: File, rendererLibrary: String?): Boolean {
        if (rendererLibrary.isNullOrBlank()) return true // system GLES passthrough
        val candidates = listOf(
            File(nativeLibraryDir, rendererLibrary),
            File(nativeLibraryDir, rendererLibrary.removePrefix("lib")),
        )
        val loaded = candidates.any { it.exists() && JREUtils.dlopenSafe(it.absolutePath) } ||
            JREUtils.dlopenSafe(rendererLibrary)
        if (!loaded) IbbLog.w(TAG, "Renderer $rendererLibrary not found; falling back to system GLES")
        return loaded
    }

    fun attachSurface(surface: Surface?) {
        JREUtils.setupBridgeWindow(surface)
        IbbLog.jvm("Attached render surface ${surface?.hashCode() ?: -1}")
    }

    fun detachSurface() {
        JREUtils.releaseWindowSafe()
    }

    fun initializeHooks() {
        JREUtils.initializeHooks()
        IbbLog.jvm("JVM hooks installed")
    }

    fun setupExitHandler(context: android.content.Context) {
        runCatching { JREUtils.setupExitMethod(context.applicationContext) }
            .onFailure { IbbLog.w(TAG, "Exit handler unavailable", it) }
    }

    fun chdir(path: String) {
        JREUtils.chdirSafe(path)
    }
}
