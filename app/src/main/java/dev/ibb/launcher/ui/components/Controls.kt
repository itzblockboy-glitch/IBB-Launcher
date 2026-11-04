package dev.ibb.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.ui.theme.Ash
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import kotlin.math.roundToInt

/**
 * Form controls.
 *
 * All of them are custom-drawn instead of Material defaults so the glass language survives into
 * settings screens (Material sliders/switches would look pasted-on). Every control is a single
 * `Canvas` + `pointerInput`, which keeps them cheap: no ripples, no elevation shadows.
 */

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    accent: Color = Violet,
    valueLabel: (Float) -> String = { "%.0f".format(it) },
) {
    val haptics = LocalHapticFeedback.current
    val range = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    var dragFraction by remember { mutableFloatStateOf(((value - valueRange.start) / range).coerceIn(0f, 1f)) }
    val animatedFraction by animateFloatAsState(dragFraction, spring(stiffness = 1200f), label = "sliderFill")

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(valueRange, steps, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        dragFraction = snap(f, steps, range)
                        onValueChange(valueRange.start + dragFraction * range)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
                .pointerInput(valueRange, steps, enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures { change, _ ->
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        val snapped = snap(f, steps, range)
                        if (snapped != dragFraction) {
                            dragFraction = snapped
                            onValueChange(valueRange.start + snapped * range)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                val trackHeight = 6.dp.toPx()
                val centerY = size.height / 2f
                val radius = size.height / 2f

                // Track
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
                )
                // Active fill
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(accent, Cyan)),
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width * animatedFraction, trackHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
                )
                // Thumb with glow
                val thumbX = (size.width * animatedFraction).coerceIn(radius, size.width - radius)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(thumbX, centerY),
                        radius = radius * 2.2f,
                    ),
                    radius = radius * 2.2f,
                    center = Offset(thumbX, centerY),
                )
                drawCircle(Color(0xFF14121F), radius = radius * 0.74f, center = Offset(thumbX, centerY))
                drawCircle(accent, radius = radius * 0.50f, center = Offset(thumbX, centerY))
                drawCircle(Color.White.copy(alpha = 0.85f), radius = radius * 0.16f, center = Offset(thumbX, centerY))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(valueLabel(valueRange.start), style = MaterialTheme.typography.labelSmall, color = Ash)
            Text(valueLabel(valueRange.endInclusive), style = MaterialTheme.typography.labelSmall, color = Ash)
        }
    }
}

/** Snaps a 0..1 fraction to `steps` discrete notches (0 = continuous). */
private fun snap(fraction: Float, steps: Int, range: Float): Float {
    if (steps <= 0) return fraction
    val notches = steps + 1
    val step = 1f / (notches - 1)
    return (fraction / step).roundToInt().coerceIn(0, notches - 1) * step
}

@Composable
fun GlassSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Violet,
    onCheckedChange: (Boolean) -> Unit,
) {
    val offset by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "switch")
    Box(
        modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    if (checked) listOf(accent, Cyan) else listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.05f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = if (checked) 0.28f else 0.12f), CircleShape)
            .pointerInput(enabled, checked) {
                if (!enabled) return@pointerInput
                detectTapGestures { onCheckedChange(!checked) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 48.dp, height = 28.dp)) {
            val knobRadius = size.height * 0.36f
            val travel = size.width - knobRadius * 2 - 4.dp.toPx() * 2
            val cx = 4.dp.toPx() + knobRadius + travel * offset
            drawCircle(Color.Black.copy(alpha = 0.25f), knobRadius + 1f, Offset(cx, size.height / 2f + 1f))
            drawCircle(if (checked) Color.White else Color(0xFFB9B4C9), knobRadius, Offset(cx, size.height / 2f))
        }
    }
}

/** Settings row: label + optional description + trailing control. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    titleColor: Color = Porcelain,
    verticalPadding: androidx.compose.ui.unit.Dp = 10.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor, fontWeight = FontWeight.Medium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = Ash)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Section heading with a soft accent rule. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, accent: Color = Violet, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(accent, Cyan))),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = Porcelain.copy(alpha = 0.92f),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

/** Linear progress bar with gradient fill + soft glow. */
@Composable
fun GlassProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    accent: Color = Violet,
    height: androidx.compose.ui.unit.Dp = 8.dp,
    indeterminate: Boolean = false,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), spring(stiffness = 300f), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .drawWithCache {
                val brush = Brush.horizontalGradient(listOf(accent, Cyan))
                onDrawBehind {
                    if (!indeterminate) {
                        drawRoundRect(
                            brush = brush,
                            size = androidx.compose.ui.geometry.Size(size.width * animated, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                        )
                    }
                }
            },
    ) {
        if (indeterminate) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(height),
                color = accent,
                trackColor = Color.Transparent,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}
