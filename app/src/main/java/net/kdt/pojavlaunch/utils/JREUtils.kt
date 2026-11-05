package net.kdt.pojavlaunch.utils

import android.content.Context
import android.view.Surface
import dev.ibb.launcher.core.IbbLog

/**
 * JNI surface of the PojavLauncher/AngelAuraMC native bridge.
 *
 * ==============================================================================================
 * Why these names are frozen
 * ==============================================================================================
 * The shared objects we ship (`libpojavexec.so`, `libpojavexec_awt.so`, `libexithook.so`) export
 * C symbols generated with `JNIEXPORT` from the *Java* class
 * `net.kdt.pojavlaunch.utils.JREUtils`. JNI resolves `native` methods by the mangled class name
 * (`Java_net_kdt_pojavlaunch_utils_JREUtils_chdir`), so:
 *
 *  * the Kotlin package **must** stay `net.kdt.pojavlaunch.utils` (a "cleaner" `dev.ibb…` package
 *    would silently fail to bind — and a failed bind is reported as `UnsatisfiedLinkError` from
 *    whatever thread first calls it, usually deep inside the game);
 *  * every method below must keep its exact name, arity and parameter types;
 *  * ProGuard keeps the whole class (see `proguard-rules.pro`).
 *
 * ==============================================================================================
 * Lifecycle
 * ==============================================================================================
 * 1. [loadNativeLibraries] — `exithook` installs the process-exit watchdog, `pojavexec` is the JVM
 *    launcher + EGL bridge, `pojavexec_awt` is only needed by old Forge (LWJGL2) screens.
 * 2. [setLdLibraryPath] — makes the runtime's `lib/` (`libjvm.so`) visible to `dlopen`.
 * 3. [chdir] — the JVM resolves relative paths (and `-Djava.home`) from the process CWD, which on
 *    Android is `/` and read-only; without this the runtime fails to find its own modules.
 * 4. [initializeHooks] + [setupExitMethod] — patch the two JVM behaviours Android cannot provide
 *    (signal handling and `System.exit`).
 * 5. [setupBridgeWindow] — hands the `Surface` to the EGL bridge; [releaseBridgeWindow] detaches
 *    it when the activity goes away.
 *
 * Every direct call is wrapped by a `*Safe` variant. Missing libraries are a *supported* state:
 * the launcher then runs in "diagnostics" mode (the console explains that the native layer was
 * not bundled) instead of crashing on start-up.
 */
object JREUtils {

    private const val TAG = "JREUtils"

    @Volatile
    private var loaded = false

    @Volatile
    private var loadError: String? = null

    /**
     * Loads the bridge libraries. Idempotent and safe to call from any thread.
     *
     * @return true when `pojavexec` (the only library that is strictly required to boot a JVM) is
     *         available.
     */
    @Synchronized
    fun loadNativeLibraries(): Boolean {
        if (loaded) return true
        loadError = null
        try {
            // The exit hook must come first: it overrides the JVM's exit handler, and a JVM that
            // starts before it is installed can call exit() with no way for us to intercept it.
            System.loadLibrary("exithook")
            System.loadLibrary("pojavexec")
            loaded = true
        } catch (t: Throwable) {
            loadError = t.message ?: t::class.java.simpleName
            IbbLog.e(TAG, "Native bridge unavailable: $loadError", t)
            return false
        }
        // AWT screens are optional: old Forge installers and LWJGL2 versions need them, modern
        // versions never load the class.
        runCatching { System.loadLibrary("pojavexec_awt") }
            .onFailure { IbbLog.w(TAG, "pojavexec_awt not bundled (AWT screens disabled)", it) }
        return true
    }

    /** True once [loadNativeLibraries] succeeded. */
    fun isAvailable(): Boolean = loaded

    /** Human-readable reason the bridge is unavailable, or null. */
    fun unavailableReason(): String? = loadError

    // ---------------------------------------------------------------------------------------------
    // Native entry points — DO NOT RENAME (see the class KDoc).
    // ---------------------------------------------------------------------------------------------

    /** `chdir(2)`. Returns 0 on success. */
    @JvmStatic external fun chdir(path: String): Int

    /** `dlopen(3)` with `RTLD_LAZY|RTLD_GLOBAL`. Returns true on success. */
    @JvmStatic external fun dlopen(path: String): Boolean

    /** Sets `LD_LIBRARY_PATH` for subsequently loaded libraries (needed by LWJGL's loader). */
    @JvmStatic external fun setLdLibraryPath(ldLibraryPath: String)

    /** Installs the EGL/GLES context that renders into [surface]. */
    @JvmStatic external fun setupBridgeWindow(surface: Surface?)

    /** Tears the bridge window down; the JVM keeps running. */
    @JvmStatic external fun releaseBridgeWindow()

    /** Installs the JVM hooks (signal handlers, `/proc/self/maps` shims, stdio redirection). */
    @JvmStatic external fun initializeHooks()

    /** Replaces `System.exit`/`Runtime.halt` so the launcher can restart the session in-process. */
    @JvmStatic external fun setupExitMethod(context: Context?)

    /** Pumps one frame of an AWT-based screen; returns `[width, height]` or null when idle. */
    @JvmStatic external fun renderAWTScreenFrame(): IntArray?

    /** Runs a helper binary from the runtime (used to unpack archives / run `unpack200`). */
    @JvmStatic external fun executeBinary(argv: Array<String>): Int

    // ---------------------------------------------------------------------------------------------
    // Safe wrappers — every caller in the launcher uses these.
    // ---------------------------------------------------------------------------------------------

    fun chdirSafe(path: String): Boolean =
        if (loaded) runCatching { chdir(path) == 0 }.getOrElse { logFailure("chdir", it); false } else false

    fun dlopenSafe(path: String): Boolean =
        if (loaded) runCatching { dlopen(path) }.getOrElse { logFailure("dlopen", it); false } else false

    fun setLdLibraryPathSafe(path: String) {
        if (loaded) runCatching { setLdLibraryPath(path) }.onFailure { logFailure("setLdLibraryPath", it) }
    }

    fun initializeHooksSafe() {
        if (loaded) runCatching { initializeHooks() }.onFailure { logFailure("initializeHooks", it) }
    }

    fun setupExitMethodSafe(context: Context?) {
        if (loaded) runCatching { setupExitMethod(context) }.onFailure { logFailure("setupExitMethod", it) }
    }

    fun setupBridgeWindowSafe(surface: Surface?) {
        if (loaded) runCatching { setupBridgeWindow(surface) }.onFailure { logFailure("setupBridgeWindow", it) }
    }

    fun releaseWindowSafe() {
        if (loaded) runCatching { releaseBridgeWindow() }.onFailure { logFailure("releaseBridgeWindow", it) }
    }

    fun renderFrameSafe(): IntArray? =
        if (loaded) runCatching { renderAWTScreenFrame() }.getOrElse { null } else null

    private fun logFailure(call: String, throwable: Throwable) {
        // A missing symbol here is almost always a mismatched .so — say so instead of printing a
        // bare UnsatisfiedLinkError that looks like a launcher bug.
        IbbLog.e(
            TAG,
            "Native call $call failed (native bridge mismatch?). Re-bundle jniLibs for this ABI.",
            throwable,
        )
    }

    /**
     * Convenience used by [dev.ibb.launcher.game.nativebridge.ExecutionLayer]: prepares the
     * process for a JVM launch. Returns false (and logs why) when the bridge is missing.
     */
    fun prepareForLaunch(runtimeHome: String, gameDirectory: String, nativeLibraryDir: String, context: Context?): Boolean {
        if (!loadNativeLibraries()) return false
        setLdLibraryPathSafe("$runtimeHome/lib:$runtimeHome/lib/server:$nativeLibraryDir")
        if (!chdirSafe(gameDirectory)) {
            IbbLog.w(TAG, "chdir to $gameDirectory failed; the JVM may not find its own files")
        }
        setupExitMethodSafe(context)
        initializeHooksSafe()
        return true
    }
}
