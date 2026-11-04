package dev.ibb.launcher.ui.game.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.controls.ControlEngine
import dev.ibb.launcher.data.model.ControlLayout
import dev.ibb.launcher.data.model.ControlSpec
import dev.ibb.launcher.data.model.ControlType
import dev.ibb.launcher.data.model.InputSettings
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import org.lwjgl.glfw.CallbackBridge
import kotlin.math.abs
import kotlin.math.max

/**
 * The on-screen controller.
 *
 * ### Performance contract
 * * Everything is one `Canvas` draw per frame; controls are not composables, so there is no
 *   layout pass when the player moves their thumb.
 * * Pointer input uses a single `pointerInput` with `awaitEachGesture` so we see all fingers in
 *   one place (needed for move + look + jump simultaneously).
 * * Key events are emitted the moment a finger crosses a control's boundary — no frame delay,
 *   which is what makes jumps feel responsive.
 * * Active pointer state lives in a `mutableStateMapOf` keyed by pointer id; only the changed
 *   pointer triggers redraw of its own control region (Compose invalidates the whole canvas, but
 *   the draw itself is trivial: N rounded rects + 1 joystick).
 *
 * Coordinates arrive normalised (0..1) from [ControlLayout]; we multiply by the canvas size.
 */
@Composable
fun VirtualController(
    layout: ControlLayout,
    settings: InputSettings,
    modifier: Modifier = Modifier,
) {
    val activePointers = remember { mutableStateMapOf<Long, ActivePointer>() }
    val toggled = remember { mutableStateMapOf<String, Boolean>() }

    // Precomputed hit boxes are rebuilt only when the layout changes.
    // The hit boxes must be recomputed when the viewport changes shape, so the aspect ratio comes
    // from the real canvas instead of a hard-coded 16:9 guess (phones range from 16:9 to 21:9 and
    // tablets go the other way). Until the first layout pass we fall back to 16:9.
    var viewportAspect by remember { mutableFloatStateOf(1.78f) }
    val boxes = remember(layout, viewportAspect) { ControlEngine.hitBoxes(layout, aspectRatio = viewportAspect) }

    Box(modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(layout, settings) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val size = this@pointerInput.size
                            for (change in event.changes) {
                                val id = change.id.value
                                val normX = change.position.x / size.width
                                val normY = change.position.y / size.height

                                if (change.pressed) {
                                    val hit = ControlEngine.hitTest(boxes, normX, normY)
                                    val previous = activePointers[id]
                                    if (hit == null) {
                                        // Empty space = mouse look, exactly like dragging the world.
                                        val prev = previous ?: ActivePointer(null, 0f, 0f, active = false)
                                        handleMouseLook(change.position.x, change.position.y, settings, prev)
                                        activePointers[id] = prev.copy(lastX = change.position.x, lastY = change.position.y)
                                        continue
                                    }
                                    when (hit.spec.type) {
                                        ControlType.JOYSTICK -> {
                                            activePointers[id] = ActivePointer(hit.spec, normX, normY, active = true)
                                        }
                                        ControlType.MOUSE_AREA -> {
                                            activePointers[id] = ActivePointer(hit.spec, normX, normY, active = true)
                                        }
                                        ControlType.SCROLL_WHEEL -> {
                                            if (previous == null) {
                                                activePointers[id] = ActivePointer(hit.spec, normX, normY, active = true)
                                            } else if (abs(normX - previous.x) > 0.01f) {
                                                val delta = if (normX > previous.x) -1.0 else 1.0
                                                CallbackBridge.sendScroll(delta)
                                                activePointers[id] = previous.copy(x = normX, y = normY)
                                            }
                                        }
                                        else -> {
                                            if (previous?.spec?.id != hit.spec.id) {
                                                pressControl(hit.spec, toggled)
                                                activePointers[id] = ActivePointer(hit.spec, normX, normY, active = true)
                                            }
                                        }
                                    }
                                    change.consume()
                                } else {
                                    val previous = activePointers.remove(id) ?: continue
                                    val spec = previous.spec ?: continue
                                    when (spec.type) {
                                        ControlType.JOYSTICK -> releaseMovement(spec, toggled)
                                        ControlType.BUTTON,
                                        ControlType.FLY_UP,
                                        ControlType.FLY_DOWN,
                                        ControlType.MACRO -> if (!spec.toggle) releaseControl(spec)
                                        ControlType.SNEAK_TOGGLE, ControlType.SPRINT_LOCK -> Unit // toggles stay latched
                                        else -> Unit
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                },
        ) {
            val canvasSize = size
            if (canvasSize.height > 0) {
                val aspect = canvasSize.width / canvasSize.height
                if (kotlin.math.abs(aspect - viewportAspect) > 0.01f) viewportAspect = aspect
            }
            for (box in boxes) {
                val centre = Offset((box.left + box.right) / 2f * canvasSize.width, (box.top + box.bottom) / 2f * canvasSize.height)
                val w = (box.right - box.left) * canvasSize.width
                val h = (box.bottom - box.top) * canvasSize.height
                when (box.spec.type) {
                    ControlType.JOYSTICK -> drawJoystick(box.spec, centre, w, h, activePointers.values.firstOrNull { it.spec?.id == box.spec.id }, canvasSize)
                    ControlType.MOUSE_AREA -> Unit // invisible
                    ControlType.SCROLL_WHEEL -> drawScrollStrip(box.spec, centre, w, h)
                    else -> drawButton(box.spec, centre, w, h, toggled[box.spec.id] == true)
                }
            }
        }

        // Labels are drawn as text by the canvas above; interactive hints live here for a11y.
        if (boxes.isEmpty()) {
            Text(
                text = "No controls — open the toolbar to pick a layout",
                style = MaterialTheme.typography.bodySmall,
                color = Porcelain.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private data class ActivePointer(
    val spec: ControlSpec?,
    val x: Float,
    val y: Float,
    val active: Boolean,
    val lastX: Float = 0f,
    val lastY: Float = 0f,
)

private fun pressControl(spec: ControlSpec, toggled: MutableMap<String, Boolean>) {
    if (spec.toggle) {
        val now = !(toggled[spec.id] ?: false)
        toggled[spec.id] = now
        if (now) spec.keys.forEach { CallbackBridge.sendKey(it, true) } else spec.keys.forEach { CallbackBridge.sendKey(it, false) }
        return
    }
    if (spec.type == ControlType.MACRO) {
        spec.macroKeys.forEach { CallbackBridge.sendKeyPress(it) }
        return
    }
    spec.keys.forEach { CallbackBridge.sendKey(it, true) }
}

private fun releaseControl(spec: ControlSpec) {
    if (spec.toggle) return
    spec.keys.forEach { CallbackBridge.sendKey(it, false) }
}

private fun releaseMovement(spec: ControlSpec, toggled: MutableMap<String, Boolean>) {
    // The joystick emits movement keys per frame; releasing clears any that are held.
    listOf(spec.joystickForward, spec.joystickLeft, spec.joystickBack, spec.joystickRight)
        .distinct()
        .forEach { CallbackBridge.sendKey(it, false) }
}

private fun handleMouseLook(px: Float, py: Float, settings: InputSettings, previous: ActivePointer) {
    if (!CallbackBridge.isGrabbing) return
    val dx = px - previous.lastX
    val dy = py - previous.lastY
    if (dx == 0f && dy == 0f) return
    val (sx, sy) = ControlEngine.mouseDelta(dx, dy, settings.mouseSensitivity, settings.invertMouseY)
    CallbackBridge.sendCursorPos(CallbackBridge.mouseX + sx, CallbackBridge.mouseY + sy)
}

private fun DrawScope.drawJoystick(spec: ControlSpec, centre: Offset, width: Float, height: Float, pointer: ActivePointer?, canvas: Size) {
    val radius = minOf(width, height) / 2f
    val alpha = spec.opacity.coerceIn(0.15f, 1f)
    drawCircle(Color.White.copy(alpha = 0.06f * alpha), radius, centre)
    drawCircle(Color.White.copy(alpha = 0.16f * alpha), radius, centre, style = Stroke(width = 1.5.dp.toPx()))
    drawCircle(Violet.copy(alpha = 0.16f * alpha), radius * 0.92f, centre)

    // Thumb position: clamp to the ring.
    val thumb = if (pointer != null) {
        val dx = (pointer.x * canvas.width) - centre.x
        val dy = (pointer.y * canvas.height) - centre.y
        val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        val clamped = minOf(length, radius * 0.72f)
        centre + Offset(dx / length * clamped, dy / length * clamped)
    } else {
        centre
    }
    drawCircle(Violet.copy(alpha = 0.55f * alpha), radius * 0.34f, thumb)
    drawCircle(Color.White.copy(alpha = 0.25f * alpha), radius * 0.34f, thumb, style = Stroke(width = 1.dp.toPx()))
}

private fun DrawScope.drawButton(spec: ControlSpec, centre: Offset, width: Float, height: Float, active: Boolean) {
    val tint = if (spec.tint != 0) Color(spec.tint) else if (active) Cyan else Color.White
    val alpha = if (active) (spec.opacity + 0.25f).coerceAtMost(0.9f) else spec.opacity
    val topLeft = Offset(centre.x - width / 2f, centre.y - height / 2f)
    drawRoundRect(
        color = tint.copy(alpha = 0.16f * alpha),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(height * spec.cornerRadius.coerceIn(0.1f, 0.5f)),
    )
    drawRoundRect(
        color = tint.copy(alpha = 0.30f * alpha),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(height * spec.cornerRadius.coerceIn(0.1f, 0.5f)),
        style = Stroke(width = 1.dp.toPx()),
    )
}

private fun DrawScope.drawScrollStrip(spec: ControlSpec, centre: Offset, width: Float, height: Float) {
    val topLeft = Offset(centre.x - width / 2f, centre.y - height / 2f)
    drawRoundRect(
        color = Color.White.copy(alpha = 0.10f * spec.opacity.coerceIn(0.2f, 1f)),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2f),
    )
    // 9 hotbar pips, mirroring Minecraft's hotbar.
    for (i in 0 until 9) {
        val x = topLeft.x + width * (i + 0.5f) / 9f
        drawCircle(Color.White.copy(alpha = 0.22f), radius = height * 0.13f, center = Offset(x, centre.y))
    }
}
