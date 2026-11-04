package dev.ibb.launcher.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ibb.launcher.R
import dev.ibb.launcher.ui.theme.Amber
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.GlassFillStrong
import dev.ibb.launcher.ui.theme.IndigoDeep
import dev.ibb.launcher.ui.theme.Violet
import dev.ibb.launcher.ui.theme.VioletBright

/**
 * Brand marks.
 *
 * The supplied avatar is used everywhere (header, splash, about, empty states) instead of a
 * separate logotype, which keeps the launcher instantly recognisable. The wordmark is set in
 * Outfit with a gradient text fill and a subtle tracking bump.
 */

/** The avatar in a glass tile. [glow] adds the "lit from behind" halo used on the home header. */
@Composable
fun BrandMark(
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    animated: Boolean = false,
) {
    val pulse by if (animated) {
        rememberInfiniteTransition(label = "brandPulse").animateFloat(
            initialValue = 0.82f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                if (glow) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Violet.copy(alpha = 0.34f * pulse), Cyan.copy(alpha = 0.10f * pulse), Color.Transparent),
                            center = center,
                            radius = this.size.minDimension * 0.95f,
                        ),
                        radius = this.size.minDimension * 0.95f,
                    )
                }
            },
    ) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.28f))
                .background(
                    Brush.linearGradient(listOf(GlassFillStrong, IndigoDeep.copy(alpha = 0.35f))),
                )
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(size * 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ibb_logo),
                contentDescription = "IBB Launcher",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 0.84f),
            )
        }
    }
}

/** "IBB Launcher" wordmark with a violet→cyan gradient. */
@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 20.sp,
    showTagline: Boolean = false,
) {
    Column(modifier) {
        Text(
            text = "IBB",
            style = androidx.compose.ui.text.TextStyle(
                brush = Brush.linearGradient(listOf(Porcelain, VioletBright, Cyan)),
                fontFamily = dev.ibb.launcher.ui.theme.DisplayFamily,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
                letterSpacing = 1.2.sp,
            ),
        )
        if (showTagline) {
            Text(
                text = "LAUNCHER",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.4.sp),
                color = Porcelain.copy(alpha = 0.45f),
            )
        }
    }
}

/** Header row used by every top-level screen. */
@Composable
fun BrandHeader(
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(size = 42.dp, glow = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "IBB",
                    style = androidx.compose.ui.text.TextStyle(
                        brush = Brush.linearGradient(listOf(Porcelain, VioletBright)),
                        fontFamily = dev.ibb.launcher.ui.theme.DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                        letterSpacing = 1.sp,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "LAUNCHER",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.4.sp),
                    color = Porcelain.copy(alpha = 0.42f),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Porcelain.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
        trailing?.invoke()
    }
}

/** Subtle "made with" style footer used in About. */
@Composable
fun BrandFooter(modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(size = 22.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "IBB Launcher · built for Android",
            style = MaterialTheme.typography.labelSmall,
            color = Porcelain.copy(alpha = 0.35f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Amber.copy(alpha = 0.8f)),
        )
    }
}
