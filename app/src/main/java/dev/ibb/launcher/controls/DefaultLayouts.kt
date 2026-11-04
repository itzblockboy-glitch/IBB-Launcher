package dev.ibb.launcher.controls

import dev.ibb.launcher.data.model.ControlLayout
import dev.ibb.launcher.data.model.ControlSpec
import dev.ibb.launcher.data.model.ControlType
import dev.ibb.launcher.data.model.ControlVisibility

/**
 * Factory presets for the virtual controller.
 *
 * Key codes are GLFW/LWJGL scancode-independent *key* codes (the same numbers Java's
 * `Keyboard.KEY_W` uses), because the native input bridge converts them directly.
 * Layout is normalised: x/y in 0..1, sizes relative to the shorter edge.
 */
object DefaultLayouts {

    // LWJGL key codes
    private const val KEY_W = 17
    private const val KEY_A = 30
    private const val KEY_S = 31
    private const val KEY_D = 32
    private const val KEY_SPACE = 57
    private const val KEY_LSHIFT = 42
    private const val KEY_ESCAPE = 1
    private const val KEY_E = 18
    private const val KEY_Q = 16
    private const val KEY_TAB = 15
    private const val KEY_1 = 2

    /** Landscape "classic" layout — thumbstick left, action cluster right. */
    fun classic(): ControlLayout = ControlLayout(
        id = "default-classic",
        name = "Classic (recommended)",
        isDefault = true,
        controls = listOf(
            ControlSpec(
                type = ControlType.JOYSTICK, label = "Move", x = 0.13f, y = 0.68f,
                width = 0.26f, height = 0.30f, opacity = 0.42f,
                joystickForward = KEY_W, joystickLeft = KEY_A, joystickBack = KEY_S, joystickRight = KEY_D,
                keys = listOf(KEY_W, KEY_A, KEY_S, KEY_D),
            ),
            ControlSpec(
                type = ControlType.MOUSE_AREA, label = "Look", x = 0.62f, y = 0.50f,
                width = 0.55f, height = 0.75f, opacity = 0.0f,
                visibility = ControlVisibility.IN_GAME_ONLY,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Jump", x = 0.885f, y = 0.74f,
                width = 0.10f, height = 0.12f, keys = listOf(KEY_SPACE), opacity = 0.5f,
            ),
            ControlSpec(
                type = ControlType.SNEAK_TOGGLE, label = "Sneak", x = 0.775f, y = 0.80f,
                width = 0.09f, height = 0.11f, keys = listOf(KEY_LSHIFT), opacity = 0.5f, toggle = true,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Inventory", x = 0.70f, y = 0.765f,
                width = 0.08f, height = 0.10f, keys = listOf(KEY_E), opacity = 0.5f,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Drop", x = 0.795f, y = 0.635f,
                width = 0.075f, height = 0.095f, keys = listOf(KEY_Q), opacity = 0.45f,
            ),
            ControlSpec(
                type = ControlType.SCROLL_WHEEL, label = "Hotbar", x = 0.30f, y = 0.90f,
                width = 0.25f, height = 0.09f, opacity = 0.25f,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Menu", x = 0.055f, y = 0.10f,
                width = 0.065f, height = 0.085f, keys = listOf(KEY_ESCAPE), opacity = 0.5f,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Chat", x = 0.135f, y = 0.10f,
                width = 0.065f, height = 0.085f, keys = listOf(KEY_TAB), opacity = 0.5f,
            ),
        ),
    )

    /** Left-handed mirror. */
    fun leftHanded(): ControlLayout {
        val mirrored = classic().controls.map { spec ->
            spec.copy(
                id = java.util.UUID.randomUUID().toString(),
                x = when (spec.type) {
                    ControlType.JOYSTICK -> 0.87f
                    ControlType.MOUSE_AREA -> 0.38f
                    ControlType.SCROLL_WHEEL -> 0.70f
                    else -> 1f - spec.x
                },
            )
        }
        return ControlLayout(id = "default-left", name = "Left-handed", controls = mirrored)
    }

    /** Minimal layout for players using a Bluetooth gamepad. */
    fun gamepad(): ControlLayout = ControlLayout(
        id = "default-gamepad",
        name = "Gamepad (minimal overlay)",
        controls = listOf(
            ControlSpec(
                type = ControlType.BUTTON, label = "Menu", x = 0.06f, y = 0.12f,
                width = 0.07f, height = 0.09f, keys = listOf(KEY_ESCAPE), opacity = 0.35f,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Inventory", x = 0.15f, y = 0.12f,
                width = 0.07f, height = 0.09f, keys = listOf(KEY_E), opacity = 0.35f,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Sprint", x = 0.24f, y = 0.12f,
                width = 0.07f, height = 0.09f, keys = listOf(KEY_LSHIFT), opacity = 0.35f, toggle = true,
            ),
        ),
    )

    /** Compact layout for small screens / one-handed play. */
    fun compact(): ControlLayout = ControlLayout(
        id = "default-compact",
        name = "Compact",
        controls = listOf(
            ControlSpec(
                type = ControlType.JOYSTICK, label = "Move", x = 0.16f, y = 0.72f,
                width = 0.28f, height = 0.34f, opacity = 0.4f,
                joystickForward = KEY_W, joystickLeft = KEY_A, joystickBack = KEY_S, joystickRight = KEY_D,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Jump", x = 0.80f, y = 0.80f,
                width = 0.13f, height = 0.16f, keys = listOf(KEY_SPACE), opacity = 0.45f,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Sneak", x = 0.90f, y = 0.62f,
                width = 0.10f, height = 0.13f, keys = listOf(KEY_LSHIFT), opacity = 0.45f, toggle = true,
            ),
            ControlSpec(
                type = ControlType.BUTTON, label = "Inv", x = 0.66f, y = 0.86f,
                width = 0.10f, height = 0.13f, keys = listOf(KEY_E), opacity = 0.45f,
            ),
            ControlSpec(
                type = ControlType.MOUSE_AREA, label = "Look", x = 0.60f, y = 0.45f,
                width = 0.5f, height = 0.6f, opacity = 0f, visibility = ControlVisibility.IN_GAME_ONLY,
            ),
        ),
    )

    fun all(): List<ControlLayout> = listOf(classic(), compact(), leftHanded(), gamepad())

    fun byId(id: String): ControlLayout = all().firstOrNull { it.id == id } ?: classic()
}
