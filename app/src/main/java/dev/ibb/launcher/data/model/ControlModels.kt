package dev.ibb.launcher.data.model

import kotlinx.serialization.Serializable

/**
 * On-screen control model for the virtual controller editor.
 *
 * Coordinates are **normalised** (0..1 of the game viewport) so a layout authored on a phone
 * renders identically on a tablet. [ControlSpec.macroKeys] stores LWJGL key codes; the native
 * input bridge converts them into GLFW events.
 */
@Serializable
data class ControlLayout(
    val id: String,
    val name: String,
    val controls: List<ControlSpec> = emptyList(),
    val mouseSensitivity: Float = 1.0f,
    val isDefault: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ControlSpec(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: ControlType,
    val label: String = "",
    /** Centre of the control, normalised to the viewport. */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /**
     * Diameter as a fraction of the shorter viewport edge.
     *
     * A value of 0.16 means "as wide as 16% of the screen's short side" — the same physical size
     * on a phone and on a tablet. The on-screen renderer and [dev.ibb.launcher.controls.ControlEngine]
     * both treat this as the *full* extent (half of it is the radius), which is why hit boxes and
     * drawn buttons always line up.
     */
    val width: Float = 0.16f,
    val height: Float = 0.16f,
    val opacity: Float = 0.55f,
    /** ARGB; 0 means "use theme glass". */
    val tint: Int = 0,
    val cornerRadius: Float = 0.5f,
    /** Key codes bound to press (and to release). */
    val keys: List<Int> = emptyList(),
    /** Extra keys fired sequentially — used for macros and chat shortcuts. */
    val macroKeys: List<Int> = emptyList(),
    val toggle: Boolean = false,
    val swipeable: Boolean = false,
    /**
     * Mouse button driven by this control, if any (0 = left/attack, 1 = right/use).
     *
     * Minecraft has no key binding for attacking or using an item — it reads `glfwGetMouseButton`
     * — so a control that only sends a key code can never mine a block or place one. When this is
     * set the control emits a real GLFW mouse-button event instead, and [holdIntervalMs] > 0 makes
     * it repeat while the finger stays down (hold-to-mine).
     */
    val mouseButton: Int? = null,
    val holdIntervalMs: Long = 0,
    /** For joysticks: which axes they emulate. */
    val joystickForward: Int = 87,   // W
    val joystickLeft: Int = 65,      // A
    val joystickBack: Int = 83,       // S
    val joystickRight: Int = 68,     // D
    val visibility: ControlVisibility = ControlVisibility.ALWAYS,
)

@Serializable
enum class ControlType {
    BUTTON, JOYSTICK, MOUSE_AREA, SCROLL_WHEEL, MACRO, SNEAK_TOGGLE, FLY_UP, FLY_DOWN, SPRINT_LOCK,

    /**
     * A button that drives a real mouse button (see [ControlSpec.mouseButton]).
     *
     * Attack/use cannot be expressed as key codes, so these are distinct from [BUTTON] and are
     * dispatched through `CallbackBridge.sendMouseButton`.
     */
    MOUSE_BUTTON,
}

@Serializable
enum class ControlVisibility { ALWAYS, IN_GAME_ONLY, MENU_ONLY }

/** Device-level input configuration (external keyboards/gamepads). */
data class InputSettings(
    val keyboardLayout: String = "QWERTY",
    val gamepadEnabled: Boolean = true,
    val gamepadDeadzone: Float = 0.12f,
    val gamepadCursorSpeed: Float = 1.0f,
    val mouseCaptureOnStart: Boolean = true,
    val invertMouseY: Boolean = false,
    val mouseSensitivity: Float = 1.0f,
    val rightClickHoldMode: Boolean = false,
)

/** Items that can be dropped into the instance directory. */
enum class ModCategory(val folder: String, val label: String, val extensions: List<String>) {
    MOD("mods", "Mods", listOf("jar")),
    RESOURCE_PACK("resourcepacks", "Resource packs", listOf("zip")),
    SHADER_PACK("shaderpacks", "Shaders", listOf("zip")),
    DATAPACK("datapacks", "Datapacks", listOf("zip")),
    WORLD("saves", "Worlds", listOf("zip", "mcworld")),
    CONFIG("config", "Configs", listOf("json", "toml", "cfg", "txt"));

    companion object {
        fun forExtension(ext: String): ModCategory? =
            entries.firstOrNull { ext.lowercase() in it.extensions }
    }
}

data class ModFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val category: ModCategory,
    val modifiedAt: Long,
)
