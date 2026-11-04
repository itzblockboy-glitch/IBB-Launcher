package org.lwjgl.glfw

/**
 * GLFW 3 shim used by Minecraft 1.13+ when running on Android.
 *
 * In a Pojav-class launcher the real `lwjgl-glfw` module is replaced by a "glfwstub" that
 * implements the handful of GLFW calls Minecraft makes (window creation, polling, callbacks,
 * clipboard) on top of a single implicit EGL window owned by the Android SurfaceView. Minecraft's
 * code paths do not change at all.
 *
 * The stub ships in `assets/lwjgl3/glfw-stub.jar` and is prepended to the classpath at launch
 * (see `GameLauncher`). This Kotlin file documents the contract and provides the constants that
 * our own (non-game) code uses; it is **replaced** by the jar at runtime, which is why it only
 * declares what we need.
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

    @JvmStatic external fun glfwInit(): Boolean
    @JvmStatic external fun glfwTerminate()
    @JvmStatic external fun glfwPollEvents()
    @JvmStatic external fun glfwSwapBuffers(window: Long)
    @JvmStatic external fun glfwSetInputMode(window: Long, mode: Int, value: Int)
    @JvmStatic external fun glfwGetKey(window: Long, key: Int): Int
    @JvmStatic external fun glfwGetWindowSize(window: Long, width: IntArray?, height: IntArray?)
    @JvmStatic external fun glfwSetWindowTitle(window: Long, title: CharSequence?)
}
