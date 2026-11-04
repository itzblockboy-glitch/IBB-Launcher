package dev.ibb.launcher.ui.screens.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.ui.theme.Amber
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Violet
import kotlin.math.abs

/**
 * A deterministic, offline "player head".
 *
 * Fetching a real skin head would require a network round-trip *and* a skin-API dependency for
 * every account row — too expensive for a list. Instead we derive a voxel-style face from the
 * username hash: the colours are picked from the brand palette, the eyes/bangs pattern varies,
 * and the same name always yields the same head (so it is recognisable across screens).
 *
 * The result is drawn with ~20 rectangles, no bitmaps, no allocation at draw time.
 */
@Composable
fun PlayerHead(
    username: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
) {
    val palette = remember(username) { paletteFor(username) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(Brush.linearGradient(listOf(palette.skin, palette.skinShade))),
    ) {
        Canvas(Modifier.size(size)) {
            drawHead(palette, glow)
        }
    }
}

private data class HeadPalette(
    val skin: Color,
    val skinShade: Color,
    val hair: Color,
    val hairHighlight: Color,
    val eye: Color,
    val accent: Color,
)

private fun paletteFor(username: String): HeadPalette {
    val hash = abs(username.lowercase().hashCode())
    val skins = listOf(
        Color(0xFFF3D3B5) to Color(0xFFD8AE8B),
        Color(0xFFE8BE9C) to Color(0xFFC99C77),
        Color(0xFFC99472) to Color(0xFFA9764F),
        Color(0xFF8D5B3D) to Color(0xFF6E4227),
    )
    val hairs = listOf(
        Color(0xFF1B1A22) to Color(0xFF2E2C38),
        Color(0xFF2C1F2B) to Color(0xFF43303F),
        Color(0xFF3A2418) to Color(0xFF55371F),
        Color(0xFF6E3B1E) to Color(0xFF8B4E28),
    )
    val (skin, shade) = skins[hash % skins.size]
    val (hair, hairHi) = hairs[(hash / 7) % hairs.size]
    val accents = listOf(Violet, Cyan, Amber)
    return HeadPalette(
        skin = skin,
        skinShade = shade,
        hair = hair,
        hairHighlight = hairHi,
        eye = Color(0xFF24212B),
        accent = accents[(hash / 13) % accents.size],
    )
}

private fun DrawScope.drawHead(palette: HeadPalette, glow: Boolean) {
    val w = size.width
    val h = size.height
    fun rect(x: Float, y: Float, rw: Float, rh: Float, color: Color) {
        drawRect(color, Offset(w * x, h * y), androidx.compose.ui.geometry.Size(w * rw, h * rh))
    }

    // Hair top / bangs (two rows so it reads as voxel layers).
    rect(0f, 0f, 1f, 0.30f, palette.hair)
    rect(0f, 0.30f, 1f, 0.13f, palette.hair)
    rect(0.06f, 0.30f, 0.18f, 0.16f, palette.hair)
    rect(0.44f, 0.43f, 0.14f, 0.12f, palette.hair)
    rect(0.74f, 0.30f, 0.20f, 0.18f, palette.hair)
    // Highlight strip
    rect(0.08f, 0.06f, 0.46f, 0.06f, palette.hairHighlight)

    // Face
    rect(0.06f, 0.43f, 0.88f, 0.44f, palette.skin)
    rect(0.06f, 0.78f, 0.88f, 0.10f, palette.skinShade)

    // Eyes (characteristic voxel 2x1 blocks)
    rect(0.22f, 0.58f, 0.16f, 0.10f, Color.White)
    rect(0.62f, 0.58f, 0.16f, 0.10f, Color.White)
    rect(0.28f, 0.60f, 0.09f, 0.07f, palette.eye)
    rect(0.66f, 0.60f, 0.09f, 0.07f, palette.eye)
    // Mouth
    rect(0.44f, 0.76f, 0.14f, 0.05f, palette.skinShade.copy(alpha = 0.9f))

    if (glow) {
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, palette.accent.copy(alpha = 0.10f)),
                start = Offset(0f, size.height),
                end = Offset(size.width, 0f),
            ),
        )
    }
}
