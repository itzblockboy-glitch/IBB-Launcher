package com.oracle.dalvik

import dev.ibb.launcher.core.IbbLog
import net.kdt.pojavlaunch.utils.JREUtils

/**
 * In-process JVM launcher.
 *
 * ==============================================================================================
 * Why this class lives in `com.oracle.dalvik`
 * ==============================================================================================
 * `libpojavexec.so` exports `Java_com_oracle_dalvik_VMLauncher_launchJVM` (see
 * `src/main/jni/jre_launcher.c`). JNI binds native methods by the *literal* mangled class name, so
 * moving this class, renaming it, or renaming [launchJVM] silently breaks the binding and the
 * launch dies with `UnsatisfiedLinkError` the moment the game thread starts.
 *
 * ==============================================================================================
 * Contract
 * ==============================================================================================
 * The call **blocks the calling thread until Minecraft exits** and returns the process exit code
 * (0 = clean quit, anything else = crash or kill). We therefore run it on a dedicated
 * `IBB-GameThread`. Once `JLI_Launch` returns, the JVM cannot be restarted in the same process, so
 * the UI offers a *relaunch* (fresh process) rather than a retry.
 *
 * `argv[0]` is the program name (`java`), exactly as a shell would pass it.
 */
object VMLauncher {

    private const val TAG = "VMLauncher"

    /** True when `libpojavexec.so` is present for this ABI and bound successfully. */
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
    fun startJVM(argv: Array<String>): Int {
        if (!isAvailable()) {
            throw IllegalStateException(
                "The native execution layer (libpojavexec.so) is missing for this device's ABI. " +
                    "Reinstall the launcher, or rebuild with the NDK (see docs/NATIVE_LAYER.md).",
            )
        }
        IbbLog.jvm("[VM] launchJVM(${argv.size} args) on ${Thread.currentThread().name}")
        return launchJVM(argv)
    }

    /**
     * Implemented by `libpojavexec.so` as `Java_com_oracle_dalvik_VMLauncher_launchJVM`.
     *
     * Not called directly — use [startJVM], which checks availability first so a missing library
     * turns into a readable message instead of a raw link error.
     */
    @JvmStatic
    external fun launchJVM(argv: Array<String>): Int
}
