package dev.ibb.launcher.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * IBB Launcher palette.
 *
 * The brand is built from the avatar's near-black hair, the porcelain skin tone and a violet
 * accent that reads as "glass lit from behind". Every colour here is alpha-tuned for the dark
 * theme first; light surfaces are derived, not hand-picked, so contrast stays consistent.
 */

// --- Core surfaces -------------------------------------------------------------------------
val Void = Color(0xFF05060B)
val VoidElevated = Color(0xFF0A0C14)
val IndigoDeep = Color(0xFF15103A)
val IndigoMid = Color(0xFF1E1850)
val Slate = Color(0xFF22242F)

// --- Brand accents -------------------------------------------------------------------------
val Violet = Color(0xFF7C5CFF)
val VioletBright = Color(0xFF9B85FF)
val VioletDim = Color(0xFF4A38B8)
val Cyan = Color(0xFF22D3EE)
val CyanDim = Color(0xFF0E7490)
val Amber = Color(0xFFFFC48A)
val Coral = Color(0xFFFF6B81)
val Mint = Color(0xFF4ADE80)

// --- Content -------------------------------------------------------------------------------
val Porcelain = Color(0xFFF4F1FA)
val Mist = Color(0xFFB8B4C8)
val Ash = Color(0xFF7B7790)
val Charcoal = Color(0xFF2A2B38)

// --- Glass -----------------------------------------------------------------------------------
val GlassFill = Color(0x14FFFFFF)
val GlassFillStrong = Color(0x22FFFFFF)
val GlassBorder = Color(0x26FFFFFF)
val GlassBorderBright = Color(0x40FFFFFF)
val GlassHighlight = Color(0x0DFFFFFF)

// --- Semantic --------------------------------------------------------------------------------
val Success = Color(0xFF4ADE80)
val Warning = Color(0xFFFFB86B)
val Danger = Color(0xFFFF5C7A)
val Info = Cyan

/** Gradient stops reused by the backdrop and the quick-play button. */
val BackdropGradient = listOf(
    Color(0xFF0B0A17),
    Color(0xFF120E2B),
    Color(0xFF07070E),
)
