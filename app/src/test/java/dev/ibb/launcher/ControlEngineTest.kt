package dev.ibb.launcher

import dev.ibb.launcher.controls.ControlEngine
import dev.ibb.launcher.controls.DefaultLayouts
import dev.ibb.launcher.data.model.ControlLayout
import dev.ibb.launcher.data.model.ControlSpec
import dev.ibb.launcher.data.model.ControlType
import dev.ibb.launcher.data.model.ControlVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Touch controls are pure geometry: normalised coordinates in, GLFW key sets / mouse deltas out.
 * Keeping this testable on the JVM is what makes the 20-preset controller trustworthy —
 * one bad hit box and the player cannot walk.
 */
class ControlEngineTest {

    private fun button(id: String = "b", x: Float = 0.5f, y: Float = 0.5f, w: Float = 0.1f) = ControlSpec(
        id = id,
        type = ControlType.BUTTON,
        x = x,
        y = y,
        width = w,
        height = w,
        keys = listOf(32),
    )


    // ------------------------------------------------------------------ playability

    /**
     * A layout the player cannot mine with is not playable.
     *
     * Minecraft reads attack/use through `glfwGetMouseButton`, never through a key binding, so
     * every shipped layout must carry a left- and a right-button control. This is the regression
     * test for the build that could walk but not break a block.
     */
    @Test
    fun `every default layout can attack and use`() {
        val layouts = listOf(
            "classic" to DefaultLayouts.classic(),
            "leftHanded" to DefaultLayouts.leftHanded(),
            "compact" to DefaultLayouts.compact(),
        )
        for ((name, layout) in layouts) {
            val buttons = layout.controls.mapNotNull { it.mouseButton }.toSet()
            assertTrue("$name has no left-click (attack) control", 0 in buttons)
            assertTrue("$name has no right-click (use) control", 1 in buttons)
        }
    }

    /** Mouse-button controls must repeat, otherwise a held finger mines exactly one block. */
    @Test
    fun `mouse button controls repeat while held`() {
        val attack = DefaultLayouts.classic().controls.first { it.mouseButton == 0 }
        assertTrue("attack does not repeat", attack.holdIntervalMs > 0)
    }

    /**
     * The whole button, not just its centre, must be on screen — a mirrored layout that pushes
     * half of "Attack" past the edge leaves the player unable to mine.
     */
    @Test
    fun `attack and use fit inside the viewport in every mirrored layout`() {
        val layouts = listOf(DefaultLayouts.classic(), DefaultLayouts.leftHanded(), DefaultLayouts.compact())
        for (layout in layouts) {
            for (spec in layout.controls.filter { it.mouseButton != null }) {
                val left = spec.x - spec.width / 2f
                val right = spec.x + spec.width / 2f
                assertTrue("${layout.name}: ${spec.label} starts off screen ($left)", left >= 0f)
                assertTrue("${layout.name}: ${spec.label} runs off screen ($right)", right <= 1f)
            }
        }
    }

    // ------------------------------------------------------------------ hit testing

    @Test
    fun `hit box is centred on the control`() {
        val layout = ControlLayout(id = "t", name = "t", controls = listOf(button(x = 0.5f, y = 0.5f, w = 0.2f)))
        val box = ControlEngine.hitBoxes(layout).single()
        // `width` is the full diameter: half of it is the radius.
        assertEquals(0.4f, box.left, 1e-4f)
        assertEquals(0.6f, box.right, 1e-4f)
        assertTrue(box.contains(0.5f, 0.5f))
        assertTrue(box.contains(0.59f, 0.5f))
        assertFalse(box.contains(0.61f, 0.5f))
        assertFalse(box.contains(0.9f, 0.9f))
    }

    @Test
    fun `topmost control wins when they overlap`() {
        val bottom = button(id = "bottom", w = 0.4f)
        val top = button(id = "top", w = 0.2f)
        val boxes = ControlEngine.hitBoxes(ControlLayout(id = "t", name = "t", controls = listOf(bottom, top)))
        assertEquals("top", ControlEngine.hitTest(boxes, 0.5f, 0.5f)?.spec?.id)
    }

    @Test
    fun `nothing is hit outside every box`() {
        val boxes = ControlEngine.hitBoxes(ControlLayout(id = "t", name = "t", controls = listOf(button())))
        assertNull(ControlEngine.hitTest(boxes, 0.05f, 0.05f))
    }

    @Test
    fun `menu-only controls never receive touches`() {
        val hidden = button().copy(visibility = ControlVisibility.MENU_ONLY)
        val boxes = ControlEngine.hitBoxes(ControlLayout(id = "t", name = "t", controls = listOf(hidden)))
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun `aspect ratio keeps controls physically square on wide screens`() {
        val square = ControlEngine.hitBoxes(ControlLayout(id = "t", name = "t", controls = listOf(button(w = 0.2f)))).single()
        val wide = ControlEngine.hitBoxes(
            ControlLayout(id = "t", name = "t", controls = listOf(button(w = 0.2f))),
            aspectRatio = 2f,
        ).single()
        // The short edge (height) is the reference, so on a 2:1 screen a circle spans twice the
        // vertical fraction while its horizontal fraction stays put.
        assertEquals(square.right - square.left, wide.right - wide.left, 1e-4f)
        assertEquals(2f * (square.bottom - square.top), wide.bottom - wide.top, 1e-4f)
    }

    // ------------------------------------------------------------------ joystick maths

    @Test
    fun `dead zone clamps the joystick to zero`() {
        val box = ControlEngine.hitBoxes(
            ControlLayout(
                id = "t", name = "t",
                controls = listOf(ControlSpec(type = ControlType.JOYSTICK, x = 0.5f, y = 0.5f, width = 0.4f, height = 0.4f)),
            ),
        ).single()
        assertEquals(0f to 0f, ControlEngine.joystickVector(box, 0.5f, 0.5f))
        // 5% off-centre is still inside the 12% dead zone
        assertEquals(0f to 0f, ControlEngine.joystickVector(box, 0.51f, 0.5f))
    }

    @Test
    fun `full deflection saturates at one`() {
        val box = ControlEngine.hitBoxes(
            ControlLayout(
                id = "t", name = "t",
                controls = listOf(ControlSpec(type = ControlType.JOYSTICK, x = 0.5f, y = 0.5f, width = 0.4f, height = 0.4f)),
            ),
        ).single()
        // The box spans 0.3..0.7 horizontally (width 0.4 → radius 0.2), so pushing the thumb to
        // the rim is exactly full deflection.
        val (x, y) = ControlEngine.joystickVector(box, 0.5f, 0.5f - 0.2f)
        assertEquals(0f, x, 1e-3f)
        assertEquals(-1f, y, 1e-3f)
        // …and anything past the rim clamps instead of running away (screen coords: y grows down).
        val (clampedX, clampedY) = ControlEngine.joystickVector(box, 0.5f, 0.9f)
        assertEquals(0f, clampedX, 1e-3f)
        assertEquals(1f, clampedY, 1e-3f)
        // Halfway out is strictly between the dead zone and full deflection (analog movement).
        val halfway = ControlEngine.joystickVector(box, 0.5f + 0.08f, 0.5f).first
        assertTrue("halfway deflection was $halfway", halfway in 0.05f..0.95f)
    }

    @Test
    fun `diagonal deflection stays inside the unit circle`() {
        val box = ControlEngine.hitBoxes(
            ControlLayout(
                id = "t", name = "t",
                controls = listOf(ControlSpec(type = ControlType.JOYSTICK, x = 0.5f, y = 0.5f, width = 0.4f, height = 0.4f)),
            ),
        ).single()
        val (x, y) = ControlEngine.joystickVector(box, 0.9f, 0.9f)
        assertTrue(abs(x) <= 1f && abs(y) <= 1f)
        assertTrue(kotlin.math.sqrt(x * x + y * y) <= 1.0001f)
    }

    @Test
    fun `movement keys map to wasd through thresholds`() {
        val spec = ControlSpec(type = ControlType.JOYSTICK)
        val box = ControlEngine.HitBox(spec, 0f, 0f, 0.4f, 0.4f)
        assertEquals(setOf(spec.joystickForward), ControlEngine.movementKeys(box, 0f to -1f))
        assertEquals(setOf(spec.joystickBack), ControlEngine.movementKeys(box, 0f to 1f))
        assertEquals(setOf(spec.joystickLeft), ControlEngine.movementKeys(box, -1f to 0f))
        assertEquals(setOf(spec.joystickRight), ControlEngine.movementKeys(box, 1f to 0f))
        assertTrue(ControlEngine.movementKeys(box, 0f to 0f).isEmpty())
        assertTrue(ControlEngine.movementKeys(box, 0.1f to -0.1f).isEmpty())
    }

    // ------------------------------------------------------------------ mouse + toggles

    @Test
    fun `mouse delta is scaled and inverted on demand`() {
        assertEquals(10f to -20f, ControlEngine.mouseDelta(10f, 20f, 1f, invertY = false))
        assertEquals(10f to 20f, ControlEngine.mouseDelta(10f, 20f, 1f, invertY = true))
        assertEquals(5f to -5f, ControlEngine.mouseDelta(10f, 10f, 0.5f, invertY = false))
    }

    @Test
    fun `sensitivity is clamped to a sane range`() {
        val (x, _) = ControlEngine.mouseDelta(1f, 0f, 1000f, false)
        assertEquals(5f, x, 1e-4f)
        val (slow, _) = ControlEngine.mouseDelta(1f, 0f, 0.0001f, false)
        assertEquals(0.1f, slow, 1e-4f)
    }

    @Test
    fun `toggle keys are only held while active`() {
        val sneak = ControlSpec(type = ControlType.SNEAK_TOGGLE, keys = listOf(42), toggle = true)
        assertEquals(listOf(42), ControlEngine.toggleKeys(sneak, active = true))
        assertTrue(ControlEngine.toggleKeys(sneak, active = false).isEmpty())
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun `a healthy preset validates without complaints`() {
        assertTrue(ControlEngine.validate(DefaultLayouts.classic()).isEmpty())
    }

    @Test
    fun `out of bounds and zero sized controls are reported`() {
        val broken = ControlLayout(
            id = "broken", name = "broken",
            controls = listOf(button(x = 1.4f), button(w = 0f)),
        )
        val problems = ControlEngine.validate(broken)
        assertEquals(2, problems.size)
    }

    @Test
    fun `all built-in presets are valid and uniquely identified`() {
        val presets = DefaultLayouts.all()
        assertTrue(presets.size >= 4)
        presets.forEach { layout ->
            assertTrue("${layout.id} invalid: ${ControlEngine.validate(layout)}", ControlEngine.validate(layout).isEmpty())
        }
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
    }

    @Test
    fun `unknown layout id falls back to the classic preset`() {
        assertEquals(DefaultLayouts.classic().id, DefaultLayouts.byId("does-not-exist").id)
    }

    @Test
    fun `left handed preset mirrors the classic d-pad`() {
        val classic = DefaultLayouts.classic().controls.first { it.type == ControlType.JOYSTICK }
        val mirrored = DefaultLayouts.leftHanded().controls.first { it.type == ControlType.JOYSTICK }
        assertEquals(1f - classic.x, mirrored.x, 1e-4f)
    }
}
