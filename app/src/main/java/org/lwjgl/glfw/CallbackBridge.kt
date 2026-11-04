package org.lwjgl.glfw

import dev.ibb.launcher.core.IbbLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Input/telemetry bridge between Minecraft's GLFW callbacks and the Android SurfaceView.
 *
 * Mirrors `org.lwjgl.glfw.CallbackBridge` from PojavLauncher. The name and the static field
 * layout are part of the contract with `libpojavexec.so` (`input_bridge_v3.c` reads
 * `sendCursorX`/`sendCursorY` and calls `nativeSetInputReady`), so **do not rename** anything
 * here without recompiling the native side.
 *
 * Rendering never blocks on input: touch events are queued as keycodes/cursor deltas and drained
 * on the GLFW event-pump thread (`glfwPollEvents`), which keeps frame pacing stable.
 */
object CallbackBridge {

    const val EVENT_TYPE_CHAR = 0
    const val EVENT_TYPE_CHAR_MODS = 1
    const val EVENT_TYPE_CURSOR_ENTER = 2
    const val EVENT_TYPE_CURSOR_POS = 3
    const val EVENT_TYPE_MOUSE_BUTTON = 5
    const val EVENT_TYPE_SCROLL = 6
    const val EVENT_TYPE_KEY = 9

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

    /** Pending key presses (LWJGL keycodes). Guarded by [pendingLock]. */
    private val pendingKeys = ArrayDeque<Int>()
    private val pendingButtons = ArrayDeque<Int>()
    private val pendingScroll = ArrayDeque<Double>()
    private val pendingChars = ArrayDeque<Int>()
    private val pendingLock = Any()

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
        // Modifiers ride along in the low bits like GLFW does for char events.
        if (pressed) synchronized(pendingLock) { pendingKeys.addLast(keyCode) }
        nativeKeyEvent(keyCode, if (pressed) 1 else 0, 0, 0)
    }

    fun sendKeyPress(keyCode: Int, mods: Int = 0) {
        nativeKeyEvent(keyCode, 1, 0, mods)
        nativeKeyEvent(keyCode, 0, 0, mods)
    }

    fun sendMouseButton(button: Int, pressed: Boolean) {
        if (pressed) synchronized(pendingLock) { pendingButtons.addLast(button) }
        nativeMouseButton(button, if (pressed) 1 else 0)
    }

    /** Raw cursor movement in virtual pixels (already scaled from device pixels). */
    fun sendCursorPos(x: Double, y: Double) {
        lastMouseX = mouseX
        lastMouseY = mouseY
        mouseX = x
        mouseY = y
        nativeCursorPos(x, y)
    }

    fun sendScroll(delta: Double) {
        synchronized(pendingLock) { pendingScroll.addLast(delta) }
        nativeScroll(delta)
    }

    fun sendChar(char: Int) = nativeChar(char)

    fun setGrabbing(grabbing: Boolean) {
        isGrabbing = grabbing
        nativeSetGrabbing(grabbing)
    }

    fun setInputReady(ready: Boolean) {
        isInputReady = ready
        runCatching { nativeSetInputReady(ready) }
    }

    fun updateWindowSize(physicalW: Int, physicalH: Int, virtualW: Int, virtualH: Int) {
        physicalWidth = physicalW
        physicalHeight = physicalH
        windowWidth = virtualW.coerceAtLeast(1)
        windowHeight = virtualH.coerceAtLeast(1)
        nativeSetWindowSize(virtualW, virtualH)
        IbbLog.v("CallbackBridge", "window $virtualW x $virtualH (surface $physicalW x $physicalH)")
    }

    /** Called by GLFW's `glfwSwapBuffers` shim every frame — used by the FPS overlay. */
    fun onFrameRendered() {
        frameCounter.incrementAndGet()
    }

    fun renderedFrames(): Long = frameCounter.get()

    // ------------------------------------------------------------------------------------
    // Native fallbacks — no-ops when libpojavexec is absent so the launcher stays usable.
    // ------------------------------------------------------------------------------------

    private fun nativeKeyEvent(key: Int, action: Int, mods: Int, scancode: Int) {
        runCatching { nativeSendKeyEvent(key, action, mods, scancode) }
    }

    @JvmStatic
external fun nativeSendKeyEvent(key: Int, action: Int, mods: Int, scancode: Int)
    @JvmStatic
external fun nativeMouseButton(button: Int, action: Int)
    @JvmStatic
external fun nativeCursorPos(x: Double, y: Double)
    @JvmStatic
external fun nativeScroll(delta: Double)
    @JvmStatic
external fun nativeChar(codepoint: Int)
    @JvmStatic
external fun nativeSetGrabbing(grabbing: Boolean)
    @JvmStatic
external fun nativeSetInputReady(ready: Boolean): Boolean
    @JvmStatic
external fun nativeSetWindowSize(width: Int, height: Int)
    @JvmStatic
external fun nativeNotifyLauncher(type: Int)

    const val NOTIFY_EXIT = 0
    const val NOTIFY_CRASH = 1
    const val NOTIFY_LOADING = 2
}
