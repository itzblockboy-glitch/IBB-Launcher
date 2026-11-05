package net.kdt.pojavlaunch

/**
 * Probe used by `libpojavexec.so` to decide whether the device supports
 * `dalvik.annotation.optimization.CriticalNative`.
 *
 * `input_bridge_v3.c` does the following during `JNI_OnLoad`:
 *  1. looks up `net/kdt/pojavlaunch/CriticalNativeTest`,
 *  2. `RegisterNatives` the `testCriticalNative(II)V` method with a probe that inspects the raw
 *     argument registers,
 *  3. calls [invokeTest].
 *
 * If CriticalNative is supported the platform strips `JNIEnv`/`jclass`, the probe sees the expected
 * register layout, and the native layer registers the *critical* input functions — roughly 4×
 * faster input dispatch. Otherwise the non-critical variants are registered.
 *
 * The class name and the method names/signatures are ABI: keep them exactly as they are.
 */
object CriticalNativeTest {

    /**
     * Bound natively at load time by `libpojavexec.so`. Never called from Kotlin — if the native
     * library is absent this would throw `UnsatisfiedLinkError`, which is why the only caller is
     * [invokeTest] *after* the native registration.
     */
    @JvmStatic
    external fun testCriticalNative(arg0: Int, arg1: Int)

    /** Called by `JNI_OnLoad` after it has registered [testCriticalNative]. */
    @JvmStatic
    fun invokeTest() {
        runCatching { testCriticalNative(0, 0) }
    }
}
