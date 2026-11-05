package org.lwjgl.glfw

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Input/telemetry bridge between Minecraft's GLFW callbacks and the Android SurfaceView.
 *
 * ==============================================================================================
 * ABI contract — do not rename anything here
 * ==============================================================================================
 * `libpojavexec.so` resolves these by literal name from `JNI_OnLoad` (`input_bridge_v3.c`) and via
 * `RegisterNatives`, so both the members and their signatures are frozen:
 *
 *  * static methods `accessAndroidClipboard(ILjava/lang/String;)Ljava/lang/String;`,
 *    `onGrabStateChanged(Z)V`, `onDirectInputEnable()V`
 *  * native methods `nativeSendChar`, `nativeSendCharMods`, `nativeSendKey`, `nativeSendCursorPos`,
 *    `nativeSendMouseButton`, `nativeSendScroll`, `nativeSendScreenSize` (and the
 *    `nativeSetUseInputStackQueue` toggle)
 *
 * The remaining methods (`nativeClipboard`, `nativeSetGrabbing`, `nativeSetInputReady`, …) are
 * exported directly from the C source and bound by their mangled names.
 *
 * ==============================================================================================
 * `@CriticalNative` and why the device build differs
 * ==============================================================================================
 * `glfw-stub.jar` calls the send* methods with the platform `@CriticalNative` annotation, which
 * **strips the JNIEnv and jclass arguments**. C cannot detect that at compile time, so the native
 * side probes for support at runtime (`dvm_testCriticalNative`) and re-registers either the
 * critical or the non-critical variants. Our declarations therefore stay annotation-free: the
 * registration supplies the correct ABI for whatever the device supports.
 *
 * Rendering never blocks on input: touch events are queued as keycodes/cursor deltas and drained
 * on the GLFW event-pump thread (`glfwPollEvents`), which keeps frame pacing stable.
 */
object CallbackBridge {

    private const val TAG = "CallbackBridge"

    // Event types read by the GLFW stub's event pump.
    const val EVENT_TYPE_CHAR = 1000
    const val EVENT_TYPE_CHAR_MODS = 1001
    const val EVENT_TYPE_CURSOR_ENTER = 1002
    const val EVENT_TYPE_KEY = 1005
    const val EVENT_TYPE_MOUSE_BUTTON = 1006
    const val EVENT_TYPE_SCROLL = 1007

    const val CLIPBOARD_COPY = 2000
    const val CLIPBOARD_PASTE = 2001

    private const val ANDROID_TYPE_GRAB_STATE = 0

    /** Accelerator (mouse look) cursor position, in virtual window pixels. */
    @JvmField var mouseX = 0.0
    @JvmField var mouseY = 0.0
    @JvmField var lastMouseX = 0.0
    @JvmField var lastMouseY = 0.0

    /** Virtual window size in "GUI pixels" — Minecraft's own coordinates. */
    @JvmField @Volatile var windowWidth = 1280
    @JvmField @Volatile var windowHeight = 720

    /** Physical surface size in device pixels. */
    @JvmField @Volatile var physicalWidth = 1280
    @JvmField @Volatile var physicalHeight = 720

    @JvmField @Volatile var isGrabbing = false
    @JvmField @Volatile var isInputReady = false
    @JvmField @Volatile var isFocused = true

    /** Set by the launcher overlay so the native layer knows a controller is on screen. */
    @JvmField @Volatile var isDirectInputEnabled = false

    private val exitRequested = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)

    /** Called by the launcher when the Compose overlay wants to close the game. */
    fun requestExit() {
        exitRequested.set(true)
        nativeNotifyLauncher(NOTIFY_EXIT)
    }

    fun isExitRequested(): Boolean = exitRequested.get()

    // ------------------------------------------------------------------------------------
    // Producer side (Android UI thread / Compose pointer input)
    // ------------------------------------------------------------------------------------

    fun sendKey(keyCode: Int, pressed: Boolean) {
        nativeSendKey(keyCode, 0, if (pressed) 1 else 0, 0)
    }

    fun sendKeyPress(keyCode: Int, mods: Int = 0) {
        nativeSendKey(keyCode, 0, 1, mods)
        nativeSendKey(keyCode, 0, 0, mods)
    }

    fun sendMouseButton(button: Int, pressed: Boolean) {
        nativeSendMouseButton(button, if (pressed) 1 else 0, 0)
    }

    /** Raw cursor movement in virtual pixels (already scaled from device pixels). */
    fun sendCursorPos(x: Double, y: Double) {
        lastMouseX = mouseX
        lastMouseY = mouseY
        mouseX = x
        mouseY = y
        nativeSendCursorPos(x.toFloat(), y.toFloat())
    }

    fun sendScroll(delta: Double) = nativeSendScroll(0.0, delta)

    // ------------------------------------------------------------------------------------
    // Hold-to-repeat (mining / placing)
    // ------------------------------------------------------------------------------------

    /**
     * Re-clicks [button] every [intervalMs] while [isHeld] returns true.
     *
     * Vanilla samples the mouse button once per tick: a down/up pair destroys or places exactly one
     * block. On desktop the button simply stays down; a touchscreen has no such state, so the press
     * is repeated on a timer. Only one repeater runs per button.
     */
    fun repeatMouseClick(button: Int, intervalMs: Long, isHeld: () -> Boolean) {
        stopRepeat(button, clickBeforeStop = false)
        val worker = Thread({
            while (isHeld()) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!isHeld()) break
                nativeSendMouseButton(button, 0, 0)
                nativeSendMouseButton(button, 1, 0)
            }
        }, "IBB-HoldRepeat-$button").apply { isDaemon = true }
        repeaters[button] = worker
        worker.start()
    }

    /** Stops the repeater for [button] and releases it. Safe to call when none is running. */
    fun stopRepeat(button: Int?, clickBeforeStop: Boolean = false) {
        button ?: return
        repeaters.remove(button)?.interrupt()
        if (clickBeforeStop) nativeSendMouseButton(button, 0, 0)
    }

    /** Stops every repeater; called when the session ends or the overlay is torn down. */
    fun stopAllRepeats() {
        repeaters.keys.toList().forEach { stopRepeat(it, clickBeforeStop = false) }
    }

    private val repeaters = java.util.concurrent.ConcurrentHashMap<Int, Thread>()

    fun sendChar(char: Int) {
        nativeSendChar(char.toChar())
    }

    fun setGrabbing(grabbing: Boolean) {
        isGrabbing = grabbing
        nativeSetGrabbing(grabbing)
    }

    fun setInputReady(ready: Boolean) {
        isInputReady = ready
        runCatching { nativeSetInputReady(ready) }
    }

    /**
     * Pushes a new virtual window size to the GLFW stub and the native layer. Called from the
     * surface host whenever the viewport or the resolution scale changes.
     */
    fun updateWindowSize(physicalW: Int, physicalH: Int, virtualW: Int, virtualH: Int) {
        physicalWidth = physicalW
        physicalHeight = physicalH
        windowWidth = virtualW.coerceAtLeast(1)
        windowHeight = virtualH.coerceAtLeast(1)
        nativeSendScreenSize(windowWidth, windowHeight)
        Log.v(TAG, "window $virtualW x $virtualH (surface $physicalW x $physicalH)")
    }

    /** Called by GLFW's `glfwSwapBuffers` shim every frame — used by the FPS overlay. */
    fun onFrameRendered() {
        frameCounter.incrementAndGet()
    }

    fun renderedFrames(): Long = frameCounter.get()

    // ------------------------------------------------------------------------------------
    // Callbacks invoked *by* the native layer (referenced from input_bridge_v3.c)
    // ------------------------------------------------------------------------------------

    /**
     * Clipboard access requested by the game (`CLIPBOARD_COPY` / `CLIPBOARD_PASTE`).
     * Returns the text to paste, or null. Wired to the Android clipboard by the game activity.
     */
    @JvmStatic
    fun accessAndroidClipboard(action: Int, copySrc: String?): String? =
        clipboardHandler?.invoke(action, copySrc)

    /** The game started or stopped capturing the mouse for mouse-look. */
    @JvmStatic
    fun onGrabStateChanged(grabState: Boolean) {
        isGrabbing = grabState
    }

    /** The stub enables direct (non-queued) input dispatch. */
    @JvmStatic
    fun onDirectInputEnable() {
        isDirectInputEnabled = true
    }

    /** Installed by `GameActivity` so the bridge stays free of Android UI dependencies. */
    @Volatile
    var clipboardHandler: ((action: Int, copySrc: String?) -> String?)? = null

    // ------------------------------------------------------------------------------------
    // Native entry points exported by libpojavexec.so
    // ------------------------------------------------------------------------------------

    @JvmStatic external fun nativeSendChar(codepoint: Char): Boolean
    @JvmStatic external fun nativeSendCharMods(codepoint: Char, mods: Int): Boolean
    @JvmStatic external fun nativeSendKey(key: Int, scancode: Int, action: Int, mods: Int)
    @JvmStatic external fun nativeSendCursorPos(x: Float, y: Float)
    @JvmStatic external fun nativeSendMouseButton(button: Int, action: Int, mods: Int)
    @JvmStatic external fun nativeSendScroll(xoffset: Double, yoffset: Double)
    @JvmStatic external fun nativeSendScreenSize(width: Int, height: Int)
    @JvmStatic external fun nativeSetUseInputStackQueue(useInputStackQueue: Boolean)

    @JvmStatic external fun nativeSetInputReady(ready: Boolean): Boolean
    @JvmStatic external fun nativeSetGrabbing(grabbing: Boolean)
    @JvmStatic external fun nativeClipboard(action: Int, copySrc: ByteArray?): String?
    @JvmStatic external fun nativeCreateGamepadButtonBuffer(): ByteBuffer?
    @JvmStatic external fun nativeCreateGamepadAxisBuffer(): ByteBuffer?

    /** Implemented by the launcher, not the bridge: used for the exit/notification channel. */
    @JvmStatic
    fun nativeNotifyLauncher(type: Int) {
        when (type) {
            NOTIFY_EXIT -> exitRequested.set(true)
            NOTIFY_CRASH -> Log.e(TAG, "Native layer reported a crash")
            NOTIFY_LOADING -> Log.i(TAG, "Native layer finished loading")
            else -> Log.w(TAG, "Unknown native notification $type")
        }
    }

    const val NOTIFY_EXIT = 0
    const val NOTIFY_CRASH = 1
    const val NOTIFY_LOADING = 2
}
