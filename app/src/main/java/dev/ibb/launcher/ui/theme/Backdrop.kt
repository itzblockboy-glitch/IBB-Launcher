package dev.ibb.launcher.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The atmospheric layer every screen sits on.
 *
 * Performance notes (this runs behind *everything*, so it must be cheap):
 *  * Three soft "aurora" blobs are drawn with radial gradients — no blur filters, no image
 *    shaders, no render-script. Cost is a handful of draw calls per frame.
 *  * The vignette is a static vertical gradient.
 *  * The optional grid is drawn only when [showGrid] is true (home screen) and is a single
 *    `Path`, not per-line composables.
 *  * Animation is a slow (24 s) drift driven by one infinite transition; values are read inside
 *    the draw lambda so it never triggers recomposition — only re-draw, which the GPU handles.
 */
@Composable
fun IbbBackdrop(
    modifier: Modifier = Modifier,
    showGrid: Boolean = true,
    animated: Boolean = true,
    accent: Color = Violet,
    secondaryAccent: Color = Cyan,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    // A single slow drift value drives all three blobs. When animations are disabled (battery
    // saver / reduce-motion) we hold the phase at zero and skip the infinite transition entirely.
    val drift: Float = if (animated) {
        val transition = rememberInfiniteTransition(label = "backdrop")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 6.2831855f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 24_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "drift",
        )
        value
    } else {
        0f
    }

    Box(modifier = modifier.fillMaxSize().background(Brush.verticalGradient(BackdropGradient))) {
        Canvas(Modifier.fillMaxSize()) {
            drawAurora(drift, accent, secondaryAccent)
            if (showGrid) drawVoxelGrid(density.density)
            drawVignette()
        }
        content()
    }
}

private fun DrawScope.drawAurora(phase: Float, accent: Color, secondaryAccent: Color) {
    val w = size.width
    val h = size.height
    val radius = (maxOf(w, h) * 0.62f)

    // Blob 1 — violet, top-left.
    val x1 = w * (0.18f + 0.05f * cos(phase))
    val y1 = h * (0.14f + 0.04f * sin(phase))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(x1, y1),
            radius = radius,
        ),
        radius = radius,
        center = Offset(x1, y1),
    )

    // Blob 2 — cyan, bottom-right.
    val x2 = w * (0.86f + 0.04f * sin(phase * 0.8f))
    val y2 = h * (0.82f + 0.05f * cos(phase * 0.7f))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(secondaryAccent.copy(alpha = 0.22f), Color.Transparent),
            center = Offset(x2, y2),
            radius = radius * 0.85f,
        ),
        radius = radius * 0.85f,
        center = Offset(x2, y2),
    )

    // Blob 3 — warm center-bottom haze, grounds the palette (ties into the avatar's skin tone).
    val x3 = w * 0.5f
    val y3 = h * (1.02f + 0.02f * sin(phase * 0.5f))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Amber.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(x3, y3),
            radius = radius * 0.7f,
        ),
        radius = radius * 0.7f,
        center = Offset(x3, y3),
    )
}

/** Faint isometric grid — a nod to the voxel source material, drawn as one stroked path. */
private fun DrawScope.drawVoxelGrid(density: Float) {
    val cell = 44.dp.toPx()
    if (cell <= 0f) return
    val path = androidx.compose.ui.graphics.Path()
    val stroke = 1.dp.toPx() * 0.6f
    var x = 0f
    while (x < size.width) {
        path.moveTo(x, 0f)
        path.lineTo(x, size.height)
        x += cell
    }
    var y = 0f
    while (y < size.height) {
        path.moveTo(0f, y)
        path.lineTo(size.width, y)
        y += cell
    }
    drawPath(path, Color.White.copy(alpha = 0.022f), style = Stroke(width = stroke))
    // One brighter diagonal to hint depth.
    drawLine(
        color = Color.White.copy(alpha = 0.03f),
        start = Offset(size.width, 0f),
        end = Offset(0f, size.height),
        strokeWidth = stroke,
    )
}

private fun DrawScope.drawVignette() {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
            center = Offset(size.width / 2f, size.height * 0.42f),
            radius = maxOf(size.width, size.height) * 0.78f,
        ),
    )
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
            startY = 0f,
            endY = size.height * 0.18f,
        ),
    )
}
