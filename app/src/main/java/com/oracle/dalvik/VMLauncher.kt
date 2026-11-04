package com.oracle.dalvik

import dev.ibb.launcher.core.IbbLog
import net.kdt.pojavlaunch.utils.JREUtils

/**
 * In-process JVM launcher.
 *
 * ==============================================================================================
 * Why this class lives in `com.oracle.dalvik`
 * ==============================================================================================
 * `libpojavexec.so` exports `Java_com_oracle_dalvik_VMLauncher_launchJVM` (see upstream
 * `jni/jre_launcher.c`). JNI binds native methods by the *literal* mangled class name, so this
 * class cannot be moved or renamed without also rebuilding the `.so`. The package is intentionally
 * "foreign" for that reason — it is the ABI, not a design choice.
 *
 * ==============================================================================================
 * Contract
 * ==============================================================================================
 * The call **blocks the calling thread until Minecraft exits** and returns the process exit code
 * (0 = clean quit, anything else = crash or kill). We therefore run it on a dedicated
 * `IBB-GameThread`. Once `JLI_Launch` returns, the JVM cannot be restarted in the same process, so
 * the UI offers a *relaunch* (fresh session) rather than a retry.
 *
 * `argv[0]` is the program name (`java`), exactly as a shell would pass it.
 */
object VMLauncher {

    private const val TAG = "VMLauncher"

    /**
     * Loads the native bridge lazily so importing this object costs nothing.
     *
     * @return true when `libpojavexec.so` is present for this ABI.
     */
    @JvmStatic
    fun isAvailable(): Boolean = JREUtils.isAvailable() || JREUtils.loadNativeLibraries()

    /**
     * Starts the JVM and blocks until it exits.
     *
     * @param argv full command line; `argv[0]` is the program name.
     * @return the process exit code.
     * @throws IllegalStateException when the native execution layer is not bundled — callers turn
     *         this into a readable launch failure instead of an `UnsatisfiedLinkError`.
     */
    @JvmStatic
    fun launchJVM(argv: Array<String>): Int {
        if (!isAvailable()) {
            throw IllegalStateException(
                "The native execution layer (libpojavexec.so) is missing for this device's ABI. " +
                    "See docs/NATIVE_LAYER.md — the JNI bridge must be built for arm64-v8a, " +
                    "armeabi-v7a and x86_64 and placed in app/src/main/jniLibs/<abi>/.",
            )
        }
        Log.d(TAG) { "launchJVM(${argv.size} args) on ${Thread.currentThread().name}" }
        return launchJVMNative(argv)
    }

    /** Implemented by `libpojavexec.so`. Not called directly — see [launchJVM]. */
    @JvmStatic
    private external fun launchJVMNative(argv: Array<String>): Int

    private object Log {
        fun d(tag: String, message: () -> String) = IbbLog.jvm("[${tag}] ${message()}")
    }
}
