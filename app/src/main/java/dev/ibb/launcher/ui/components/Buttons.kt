package dev.ibb.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import dev.ibb.launcher.ui.theme.VioletBright

/**
 * Buttons.
 *
 * The primary action ("Play") is the app's visual anchor: a violet→cyan gradient with a moving
 * sheen, a soft outer glow and a spring press. Everything else is quieter so there is never doubt
 * about what the main action is.
 */

enum class IbbButtonStyle { PRIMARY, SECONDARY, GHOST, DANGER }

@Composable
fun IbbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: IbbButtonStyle = IbbButtonStyle.SECONDARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp),
    fillWidth: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 1000f),
        label = "buttonScale",
    )
    val alpha by animateFloatAsState(if (enabled) 1f else 0.42f, label = "buttonAlpha")

    val container: Modifier = when (style) {
        IbbButtonStyle.PRIMARY -> Modifier.gradientFill(listOf(Violet, VioletBright, Cyan), shape)
        IbbButtonStyle.SECONDARY -> Modifier.glassSurface(shape, accentStrength = 0.04f)
        IbbButtonStyle.GHOST -> Modifier
        IbbButtonStyle.DANGER -> Modifier
            .clip(shape)
            .background(Color(0xFF3A1520).copy(alpha = 0.9f))
            .border(1.dp, Color(0xFFFF5C7A).copy(alpha = 0.45f), shape)
    }

    val contentColor = when (style) {
        IbbButtonStyle.PRIMARY -> Color(0xFF0B0716)
        IbbButtonStyle.DANGER -> Color(0xFFFF8FA3)
        IbbButtonStyle.GHOST -> Porcelain.copy(alpha = 0.86f)
        IbbButtonStyle.SECONDARY -> Porcelain
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .then(container)
            .pressable(
                onClick = { if (enabled && !loading) onClick() },
                interactionSource = interaction,
                enabled = enabled && !loading,
                pressedScale = 1f,
            )
            .defaultMinSize(minHeight = 46.dp)
            .padding(horizontal = if (style == IbbButtonStyle.GHOST) 10.dp else 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (style == IbbButtonStyle.PRIMARY) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The hero call-to-action. Deliberately larger and animated: it is the one button users hunt for.
 */
@Composable
fun PlayButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    subtitle: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 850f),
        label = "playScale",
    )
    val glowStrength by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.25f,
        animationSpec = tween(400),
        label = "playGlow",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(64.dp)
            .drawWithCache {
                val radius = 22.dp.toPx()
                val glowBrush = Brush.radialGradient(
                    colors = listOf(Violet.copy(alpha = 0.45f * glowStrength), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.width * 0.85f,
                )
                onDrawWithContent {
                    drawRect(glowBrush)
                    drawContent()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(20.dp))
                .gradientFill(
                    colors = if (enabled) listOf(Violet, VioletBright, Cyan) else listOf(Color(0xFF2A2A35), Color(0xFF33333F)),
                    shape = RoundedCornerShape(20.dp),
                )
                .pressable(onClick = onClick, interactionSource = interaction, enabled = enabled && !loading, pressedScale = 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF0B0716),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = Color(0xFF0B0716),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = text,
                    color = Color(0xFF0B0716),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = Color(0xFF0B0716).copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** Gradient fill with a diagonal sheen — the signature "IBB" surface. */
fun Modifier.gradientFill(colors: List<Color>, shape: Shape): Modifier = this
    .clip(shape)
    .drawWithCache {
        val base = Brush.linearGradient(
            colors = colors,
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        )
        val sheen = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.28f), Color.Transparent, Color.Transparent),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height * 0.8f),
        )
        onDrawBehind {
            drawRect(base)
            drawRect(sheen)
        }
    }

/** Compact icon-only glass button used in toolbars. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Porcelain,
    size: Dp = 42.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .glassSurface(CircleShape, accentStrength = 0.05f)
            .pressable(onClick = onClick, enabled = enabled, pressedScale = 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(size * 0.44f))
    }
}

/** Small labelled status dot ("Installed", "Needs download"). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, color.copy(alpha = 0.5f), CircleShape),
    )
}
