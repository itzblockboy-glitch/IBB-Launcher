package dev.ibb.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.ui.theme.GlassBorder
import dev.ibb.launcher.ui.theme.GlassFill
import dev.ibb.launcher.ui.theme.GlassHighlight
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet

/**
 * ============================================================================
 * The glassmorphism primitives.
 * ============================================================================
 *
 * ### How "frosted glass" is achieved without `backdrop-filter`
 * Android has no CSS-style backdrop blur that samples arbitrary content behind a view. The
 * options and the trade-off we chose:
 *
 * | Technique | Cost | Verdict |
 * |---|---|---|
 * | `Modifier.blur()` / `RenderEffect` on each panel | a full offscreen layer per panel, re-blurred on every backdrop animation frame | drops frames on mid-range GPUs |
 * | `RenderEffect` on a scrim + punch-out | 2 extra layers, tricky with rounded corners | crisp but fragile |
 * | **Layered translucent fill + gradient sheen + hairline specular border + inner highlight** | pure draw calls, zero extra layers | the *perceived* glass look, ~free |
 *
 * We use the third everywhere, on top of an animated dark backdrop (`IbbBackdrop`), which is what
 * actually sells the effect: the eye reads a moving, blurred-light environment through the panel.
 * [GlassCard] also exposes [blurRadius] for the one or two hero surfaces where a real blur is
 * worth the cost (it is applied via `graphicsLayer` + `RenderEffect`, guarded to API 31+).
 */

private val DefaultShape = RoundedCornerShape(24.dp)
private val PillShape = RoundedCornerShape(50)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = DefaultShape,
    fillAlpha: Float = 1f,
    accent: Color = Violet,
    accentStrength: Float = 0.06f,
    borderAlpha: Float = 1f,
    glow: Boolean = false,
    blurRadius: Float = 0f,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, if (onClick != null) 0.975f else 1f)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .glassSurface(shape, fillAlpha, accent, accentStrength, borderAlpha, glow, blurRadius)
            .then(
                when {
                    onClick != null || onLongClick != null -> Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick?.let { { it() } },
                    )
                    else -> Modifier
                },
            )
            .padding(contentPadding),
        content = content,
    )
}

/** Core glass paint, cached per size so gradients/paths are not rebuilt every frame. */
fun Modifier.glassSurface(
    shape: Shape,
    fillAlpha: Float = 1f,
    accent: Color = Violet,
    accentStrength: Float = 0.06f,
    borderAlpha: Float = 1f,
    glow: Boolean = false,
    blurRadius: Float = 0f,
): Modifier {
    // Real blur is opt-in and intentionally capped: a blurred layer at 60/120 fps is the single
    // most expensive thing a launcher UI can do, so only hero surfaces request it.
    val blurred = if (blurRadius > 0f) {
        this.blur(blurRadius.dp, edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
    } else {
        this
    }
    return blurred.then(
        Modifier.drawWithCache {
            val fill = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.11f * fillAlpha),
                    GlassFill.copy(alpha = GlassFill.alpha * fillAlpha),
                    Color.White.copy(alpha = 0.035f * fillAlpha),
                ),
            )
            val accentWash = Brush.linearGradient(
                colors = listOf(
                    accent.copy(alpha = accentStrength * fillAlpha),
                    Color.Transparent,
                    accent.copy(alpha = accentStrength * 0.35f * fillAlpha),
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
            val edge = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.34f * borderAlpha),
                    GlassBorder.copy(alpha = GlassBorder.alpha * borderAlpha),
                    Color.White.copy(alpha = 0.05f * borderAlpha),
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
            val highlight = Brush.verticalGradient(
                colors = listOf(GlassHighlight, Color.Transparent),
                endY = size.height * 0.45f,
            )
            val outline = shape.createOutline(size, layoutDirection, this)
            val stroke = Stroke(width = 1.dp.toPx())
            onDrawWithContent {
                drawRect(fill)
                drawRect(accentWash)
                drawRect(highlight)
                if (glow) {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                            center = Offset(size.width * 0.12f, 0f),
                            radius = size.width * 0.9f,
                        ),
                    )
                }
                drawOutline(outline, edge, style = stroke)
                drawContent()
            }
        },
    )
}

/**
 * Press feedback used across the app: springs down to [pressedScale]. Springs (not tweens) feel
 * physical and never "stick" when taps arrive faster than the animation.
 */
@Composable
fun rememberPressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.97f): Float {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "pressScale",
    )
    return scale
}

@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    pressedScale: Float = 0.97f,
    enabled: Boolean = true,
): Modifier {
    val scale = rememberPressScale(interactionSource, if (enabled) pressedScale else 1f)
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/** Hairline separator that fades at both ends. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = 0.10f)) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent))),
    )
}

/**
 * Pill badge, e.g. "Fabric 0.16.9" or "Java 21".
 *
 * When [onClick] is supplied the chip becomes a filter control (used by the version catalogue and
 * the file explorer) and gets the same press-scale feedback as buttons, so it never looks
 * interactive without responding.
 */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Violet,
    leading: (@Composable RowScope.() -> Unit)? = null,
    selected: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val fill = if (selected) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f)
    val stroke = if (selected) accent.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.10f)
    val label = if (selected) accent else Porcelain.copy(alpha = 0.62f)
    val scale by animateFloatAsState(if (selected) 1f else 0.98f, label = "chipScale")
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(PillShape)
            .background(fill)
            .border(1.dp, stroke, PillShape)
            .then(if (onClick != null) Modifier.clickableNoRipple(onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.invoke(this)
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = label)
    }
}

/** `clickable` without the ripple — the glass aesthetic uses scale/alpha feedback instead. */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
    )
}
