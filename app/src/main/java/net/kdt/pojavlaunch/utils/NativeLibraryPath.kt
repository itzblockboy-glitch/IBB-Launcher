package net.kdt.pojavlaunch.utils

import dev.ibb.launcher.core.IbbLog
import java.io.File

/**
 * Makes `libpojavexec.so` reachable from **inside the game JVM**.
 *
 * ==============================================================================================
 * Why this is not `System.loadLibrary`
 * ==============================================================================================
 * The game's JVM is created by `libpojavexec.so` itself (`JLI_Launch`), so it has none of ART's
 * native-library plumbing: no `java.library.path` lookup by name, no APK `lib/` extraction. When
 * `org.lwjgl.glfw.GLFW` (from `glfw-stub.jar`, which is first on the classpath) runs its static
 * initializer it calls `System.loadLibrary("pojavexec")` or `System.load("<dir>/libpojavexec.so")`
 * for the *same* shared object that is already mapped into the process.
 *
 * That is the whole trick behind this layer: a second `dlopen` of an already-loaded library returns
 * the existing handle, so `JNI_OnLoad` runs again, this time with the game JVM as the argument, and
 * takes the `else if (dalvikJavaVMPtr != vm)` branch in `input_bridge_v3.c`. That branch is what
 * binds `org/lwjgl/glfw/GLFW` (window attribute helpers, `keyDownBuffer`, monitor resizing) and
 * installs the LWJGL `dlopen` hook plus the AWT stubs.
 *
 * ==============================================================================================
 * What the game JVM needs from the environment
 * ==============================================================================================
 *  * `POJAV_ENVIRON` — hex pointer to the shared `pojav_environ` struct. Inherited automatically
 *    only if *this* process has it exported before the JVM starts, which `setenv` in the launcher
 *    guarantees; if it were missing, the game-side `env_init` would allocate a **second** struct and
 *    the two layers would silently disagree about the surface and the input buffers.
 *  * `POJAV_NATIVEDIR` — the APK `lib/<abi>` directory, used by Java to build the absolute path.
 *  * `LD_LIBRARY_PATH` — so `dlopen("libpojavexec.so")` also resolves by bare name.
 */
object NativeLibraryPath {

    private const val TAG = "NativeLibPath"

    /** Name the stub loads; also the name the launcher loads first. */
    const val EXEC_LIB = "pojavexec"

    /** Directory of the running process's native libraries (`.../lib/<abi>`). */
    @Volatile
    var nativeDir: File? = null
        private set

    /**
     * Publishes the library directory and exports it for the game JVM.
     *
     * Call **before** `JREUtils.preloadJvm`/`VMLauncher.startJVM`, with the same directory that was
     * passed to [JREUtils.setLdLibraryPath].
     */
    fun prepare(nativeLibraryDir: File?, currentLdPath: String) {
        nativeDir = nativeLibraryDir
        val dir = nativeLibraryDir?.absolutePath ?: return

        setEnv("POJAV_NATIVEDIR", dir)
        // The game JVM resolves the renderer library and libpojavexec.so by bare name.
        val ld = if (currentLdPath.isBlank()) dir else "$dir:$currentLdPath"
        setEnv("LD_LIBRARY_PATH", ld)

        val exec = File(dir, "lib$EXEC_LIB.so")
        IbbLog.i(
            TAG,
            if (exec.exists()) "Native dir → $dir (lib$EXEC_LIB.so present)"
            else "Native dir → $dir (lib$EXEC_LIB.so MISSING — the game JVM cannot bind input)",
        )
    }

    /**
     * Absolute path of `libpojavexec.so`, for `-Djava.library.path` and diagnostics. Null when the
     * directory is unknown.
     */
    fun execLibraryPath(): String? = nativeDir?.let { File(it, "lib$EXEC_LIB.so").absolutePath }

    private fun setEnv(key: String, value: String) {
        runCatching {
            val os = Class.forName("android.system.Os")
            os.getMethod(
                "setenv",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(null, key, value, true)
        }.onFailure { IbbLog.w(TAG, "Could not set $key", it) }
    }
}
