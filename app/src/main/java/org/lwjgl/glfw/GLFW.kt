package org.lwjgl.glfw

/**
 * GLFW 3 shim **contract** used by Minecraft 1.13+ on Android.
 *
 * ==============================================================================================
 * Important: this file is *not* the implementation used by the game
 * ==============================================================================================
 * Minecraft runs against `glfw-stub.jar` (the `org.lwjgl.glfw` package shipped by the bridge
 * project), which is placed **first** on the classpath at launch — see `GameLauncher`. That jar
 * owns the real `GLFW` class, including the static fields the native layer binds to:
 *
 *  * `keyDownBuffer`  — `Ljava/nio/ByteBuffer;`
 *  * `mouseDownBuffer`— `Ljava/nio/ByteBuffer;`
 *  * `glfwSetWindowAttrib(JII)V`, `internalWindowSizeChanged(J)V`,
 *    `internalChangeMonitorSize(II)V`
 *
 * This class only declares the constants and the tiny surface our own (launcher-side) code touches,
 * so the launcher compiles without depending on the jar. If you add fields here, mirror them in
 * `glfw-stub.jar` before the native layer will find them.
 */
object GLFW {

    const val GLFW_TRUE = 1
    const val GLFW_FALSE = 0

    const val GLFW_PRESS = 1
    const val GLFW_RELEASE = 0
    const val GLFW_REPEAT = 2

    const val GLFW_MOUSE_BUTTON_LEFT = 0
    const val GLFW_MOUSE_BUTTON_RIGHT = 1
    const val GLFW_MOUSE_BUTTON_MIDDLE = 2

    const val GLFW_KEY_ESCAPE = 256
    const val GLFW_KEY_ENTER = 257
    const val GLFW_KEY_TAB = 258
    const val GLFW_KEY_BACKSPACE = 259
    const val GLFW_KEY_SPACE = 32
    const val GLFW_KEY_LEFT_SHIFT = 340
    const val GLFW_KEY_LEFT_CONTROL = 341
    const val GLFW_KEY_F11 = 300

    const val GLFW_CURSOR = 0x00033001
    const val GLFW_CURSOR_NORMAL = 0x00034001
    const val GLFW_CURSOR_DISABLED = 0x00034003

    // LWJGL scancodes for the keys the on-screen controller sends. Using the scancode path avoids
    // the GLFW key-code lookup table and works for every layout.
    const val SCANCODE_ESCAPE = 1
    const val SCANCODE_W = 17
    const val SCANCODE_A = 30
    const val SCANCODE_S = 31
    const val SCANCODE_D = 32
    const val SCANCODE_SPACE = 57
    const val SCANCODE_LEFT_SHIFT = 42
    const val SCANCODE_TAB = 15
    const val SCANCODE_E = 18
    const val SCANCODE_Q = 16
    const val SCANCODE_F1 = 59
    const val SCANCODE_F5 = 63
}
