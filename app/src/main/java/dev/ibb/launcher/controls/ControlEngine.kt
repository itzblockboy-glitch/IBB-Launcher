package dev.ibb.launcher.controls

import dev.ibb.launcher.data.model.ControlLayout
import dev.ibb.launcher.data.model.ControlSpec
import dev.ibb.launcher.data.model.ControlType
import dev.ibb.launcher.data.model.ControlVisibility

/**
 * Touch → input translation for the in-game overlay.
 *
 * The overlay itself is a Compose canvas (`ui/game/VirtualController.kt`); all the math lives
 * here so it can be unit-tested without an emulator. Coordinates are **normalised** (0..1 of the
 * game surface) which makes the same layout usable on a 6" phone and an 11" tablet.
 *
 * Key design choices for latency:
 *  * no allocation in [hitTest]/[joystickVector] hot paths (primitives + precomputed rects),
 *  * key events are emitted immediately on touch-down instead of on a frame boundary,
 *  * the joystick uses a dead-zone with hysteresis so a resting thumb cannot cause drift.
 */
object ControlEngine {

    /** Precomputed hit rectangle for a control, in normalised coordinates. */
    data class HitBox(val spec: ControlSpec, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }

    /** Builds hit boxes for one layout. Call once per layout change, not per frame. */
    fun hitBoxes(layout: ControlLayout, aspectRatio: Float = 1.0f): List<HitBox> = layout.controls
        .filter { it.visibility != ControlVisibility.MENU_ONLY }
        .map { spec ->
            // `width` is the *full* extent as a fraction of the *shorter* edge, so a circle keeps
            // its physical size on any screen: on a wide screen the vertical fraction grows by the
            // aspect ratio (the short edge is the height) and stays untouched horizontally.
            val aspect = aspectRatio.coerceIn(0.25f, 4f)
            val halfW = spec.width / 2f
            val halfH = if (aspect >= 1f) spec.height * aspect / 2f else spec.height / aspect / 2f
            HitBox(spec, spec.x - halfW, spec.y - halfH, spec.x + halfW, spec.y + halfH)
        }

    /** Topmost control under the finger (later entries win, matching draw order). */
    fun hitTest(boxes: List<HitBox>, x: Float, y: Float): HitBox? = boxes.lastOrNull { it.contains(x, y) }

    /**
     * Joystick output in [-1, 1] on both axes.
     * @param deadZone fraction of the radius that is ignored (default 12%).
     */
    fun joystickVector(
        box: HitBox,
        pointerX: Float,
        pointerY: Float,
        deadZone: Float = 0.12f,
    ): Pair<Float, Float> {
        val centreX = (box.left + box.right) / 2f
        val centreY = (box.top + box.bottom) / 2f
        // The hit box is not necessarily square (a joystick can be an ellipse), so normalise each
        // axis by its own radius; otherwise the vertical throw is scaled by the aspect ratio and a
        // fully-deflected stick only reports a fraction of its value.
        val radiusX = maxOf((box.right - box.left) / 2f, 1e-4f)
        val radiusY = maxOf((box.bottom - box.top) / 2f, 1e-4f)
        val dx = (pointerX - centreX) / radiusX
        val dy = (pointerY - centreY) / radiusY
        val magnitude = kotlin.math.sqrt(dx * dx + dy * dy)
        if (magnitude < deadZone) return 0f to 0f
        // Unit direction of the thumb, then the analogue magnitude after the dead zone.
        // NOTE: the direction must be normalised exactly once — dividing by `magnitude` a second
        // time while scaling halves the output for a full push (a bug that reads like "W is slow").
        val unitX = dx / magnitude
        val unitY = dy / magnitude
        // Re-scale past the dead zone so full deflection is reachable with a short thumb travel.
        val scaled = ((magnitude - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        return unitX * scaled to unitY * scaled
    }

    /** Maps a joystick vector onto the four movement keys (WASD by default). */
    fun movementKeys(box: HitBox, vector: Pair<Float, Float>, threshold: Float = 0.35f): Set<Int> {
        val (x, y) = vector
        val keys = mutableSetOf<Int>()
        if (y < -threshold) keys += box.spec.joystickForward
        if (y > threshold) keys += box.spec.joystickBack
        if (x < -threshold) keys += box.spec.joystickLeft
        if (x > threshold) keys += box.spec.joystickRight
        return keys
    }

    /**
     * Converts a pointer drag inside a mouse area into relative cursor motion, already scaled for
     * the current UI scale factor (so 1 cm of finger travel = the same on-screen distance).
     */
    fun mouseDelta(dxPixels: Float, dyPixels: Float, sensitivity: Float, invertY: Boolean): Pair<Float, Float> {
        val scale = sensitivity.coerceIn(0.1f, 5f)
        val dy = if (invertY) dyPixels else -dyPixels
        return dxPixels * scale to dy * scale
    }

    /** Keys held for an active toggle control (sneak/sprint lock). */
    fun toggleKeys(spec: ControlSpec, active: Boolean): List<Int> = if (active) spec.keys else emptyList()

    /**
     * Validates a layout being imported from another device — returns a human-readable list of
     * problems instead of letting a malformed JSON crash the game overlay.
     */
    fun validate(layout: ControlLayout): List<String> {
        val problems = mutableListOf<String>()
        if (layout.controls.isEmpty()) problems += "Layout has no controls"
        if (layout.controls.size > 64) problems += "Too many controls (max 64)"
        layout.controls.forEachIndexed { index, spec ->
            if (spec.x !in 0f..1f || spec.y !in 0f..1f) {
                problems += "Control ${index + 1} is outside the screen"
            }
            if (spec.width <= 0f || spec.height <= 0f) {
                problems += "Control ${index + 1} has no size"
            }
            if (spec.type == ControlType.JOYSTICK && spec.keys.isEmpty() && spec.joystickForward == 0) {
                problems += "Joystick ${index + 1} has no movement keys"
            }
        }
        return problems
    }

    /** Simple overlap check used by the editor to avoid stacked invisible buttons. */
    fun overlappingControls(layout: ControlLayout, maxOverlap: Float = 0.6f): List<Pair<ControlSpec, ControlSpec>> {
        val result = mutableListOf<Pair<ControlSpec, ControlSpec>>()
        val controls = layout.controls
        for (i in controls.indices) {
            for (j in i + 1 until controls.size) {
                val a = controls[i]
                val b = controls[j]
                val overlapX = (a.width + b.width) / 2 - kotlin.math.abs(a.x - b.x)
                val overlapY = (a.height + b.height) / 2 - kotlin.math.abs(a.y - b.y)
                if (overlapX > 0 && overlapY > 0) {
                    val area = (overlapX * overlapY) / (a.width * a.height)
                    if (area > maxOverlap) result += a to b
                }
            }
        }
        return result
    }
}
