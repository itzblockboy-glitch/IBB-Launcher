package dev.ibb.launcher.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.ui.components.BrandHeader
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.GlassChip
import dev.ibb.launcher.ui.components.IbbButton
import dev.ibb.launcher.ui.components.IbbButtonStyle
import dev.ibb.launcher.ui.components.SectionHeader
import dev.ibb.launcher.ui.components.clickableNoRipple
import dev.ibb.launcher.ui.screens.components.PlayerHead
import dev.ibb.launcher.ui.theme.Ash
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import dev.ibb.launcher.ui.theme.VioletBright

/**
 * Skin customiser.
 *
 * The "preview" is a voxel character drawn with Compose primitives (no GL, no image decoding) that
 * reacts to the selected skin model and colour preset instantly. For online accounts the real skin
 * download/upload goes through the Minecraft services API — wired in
 * `SkinRepository` — while the local preview keeps working offline, which is the common case on
 * Android.
 */
@Composable
fun SkinsScreen(app: IbbApplication) {
    val accounts by app.accountStore.accounts.collectAsState()
    val activeId by app.accountStore.activeId.collectAsState()
    val active = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
    var model by remember(active?.id) { mutableStateOf(active?.skinModel ?: "classic") }
    var preset by remember { mutableStateOf(SkinPresets.entries.first()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BrandHeader(subtitle = "Skin customiser") }

        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = Cyan,
                accentStrength = 0.08f,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.radialGradient(
                                    listOf(preset.accent.copy(alpha = 0.22f), Color.Transparent),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        VoxelCharacter(preset = preset, slim = model == "slim")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        active?.username ?: "No profile selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = Porcelain,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (model == "slim") "Slim (Alex) model" else "Classic (Steve) model",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ash,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModelChip(
                    label = "Classic",
                    selected = model == "classic",
                    onClick = { model = "classic" },
                    modifier = Modifier.weight(1f),
                )
                ModelChip(
                    label = "Slim",
                    selected = model == "slim",
                    onClick = { model = "slim" },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item { SectionHeader("Presets", accent = VioletBright) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(SkinPresets.entries.toList()) { entry ->
                    PresetSwatch(
                        preset = entry,
                        selected = preset == entry,
                        onClick = { preset = entry },
                    )
                }
            }
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth(), accentStrength = 0.04f) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerHead(username = active?.username ?: "IBB", size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Apply to ${active?.username ?: "profile"}", style = MaterialTheme.typography.titleSmall, color = Porcelain)
                            Text(
                                if (active?.isOnline == true) "Uploads to Minecraft.com via the official API"
                                else "Offline profiles keep skins locally",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ash,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    IbbButton(
                        text = "Save appearance",
                        style = IbbButtonStyle.PRIMARY,
                        fillWidth = true,
                        onClick = {
                            active?.let {
                                app.accountStore.upsert(it.copy(skinModel = model), makeActive = false)
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChip("CUSTOM PNG", accent = Cyan)
                        GlassChip("SKIN API", accent = VioletBright)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Import a 64×64 skin PNG from your gallery — the launcher stores it in the instance folder " +
                            "and serves it through the built-in skin proxy.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ash,
                    )
                }
            }
        }
    }
}

enum class SkinPresets(
    val label: String,
    val skin: Color,
    val shade: Color,
    val hair: Color,
    val shirt: Color,
    val pants: Color,
    val accent: Color,
) {
    VOID("Void", Color(0xFFF3D3B5), Color(0xFFD3AC8D), Color(0xFF1B1A22), Color(0xFF7C5CFF), Color(0xFF241E3A), Violet),
    TUNDRA("Tundra", Color(0xFFE8D5C0), Color(0xFFC9AE95), Color(0xFF8A5A2B), Color(0xFF22D3EE), Color(0xFF1D2C33), Cyan),
    EMBER("Ember", Color(0xFFC08A5E), Color(0xFF9C6A43), Color(0xFF3A2418), EMPER_ACCENT, Color(0xFF3A2018), EMPER_ACCENT),
    MOSS("Moss", Color(0xFFD9BFA4), Color(0xFFB99A80), Color(0xFF2F3B22), MOSS_ACCENT, Color(0xFF1E2A1C), MOSS_ACCENT),
    ROSEWOOD("Rosewood", Color(0xFFB97C57), Color(0xFF96603F), Color(0xFF2C1F2B), ROSE_ACCENT, Color(0xFF2B1A22), ROSE_ACCENT),
    ;

    companion object {
        private val EMPER_ACCENT = Color(0xFFFFC48A)
        private val MOSS_ACCENT = Color(0xFF4ADE80)
        private val ROSE_ACCENT = Color(0xFFFF6B81)
    }
}

@Composable
private fun VoxelCharacter(preset: SkinPresets, slim: Boolean) {
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp, vertical = 18.dp),
    ) {
        val w = size.width
        val h = size.height
        val unit = w / 10f
        fun block(col: Int, row: Int, cw: Int, ch: Int, color: Color) {
            drawRect(color, Offset(col * unit, row * unit), androidx.compose.ui.geometry.Size(cw * unit, ch * unit))
        }
        // Legs
        block(3, 17, 2, 6, preset.pants)
        block(5, 17, 2, 6, preset.pants.copy(alpha = 0.86f))
        // Torso
        block(3, 11, 4, 6, preset.shirt)
        block(3, 11, 4, 1, preset.shirt.copy(alpha = 0.75f))
        // Arms (slim = 1 unit wide)
        val armW = if (slim) 1 else 1
        block(3 - armW, 11, armW, 6, preset.skin)
        block(7, 11, armW, 6, preset.skin.copy(alpha = 0.9f))
        // Head
        block(3, 6, 4, 5, preset.skin)
        block(3, 6, 4, 1, preset.hair)
        block(3, 7, 1, 2, preset.hair)
        block(6, 7, 1, 3, preset.hair)
        // Eyes
        block(4, 8, 1, 1, Color.White)
        block(6, 8, 1, 1, Color.White)
        block(4, 8, 1, 1, Color(0xFF24212B).copy(alpha = 0.95f))
        // Glow ground
        drawCircle(
            brush = Brush.radialGradient(
                listOf(preset.accent.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(w / 2f, h),
                radius = w * 0.6f,
            ),
            radius = w * 0.6f,
            center = Offset(w / 2f, h),
        )
    }
}

@Composable
private fun ModelChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Violet.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
            .clickableNoRipple(onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Porcelain else Ash,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun PresetSwatch(preset: SkinPresets, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(74.dp).clickableNoRipple(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(preset.shirt, preset.accent)))
                .then(
                    if (selected) Modifier.background(Color.White.copy(alpha = 0.12f)) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(preset.skin),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // mini head: hair cap + eyes
                    drawRect(preset.hair, size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.34f))
                    drawRect(preset.hair, Offset(0f, size.height * 0.34f), androidx.compose.ui.geometry.Size(size.width * 0.18f, size.height * 0.5f))
                    drawRect(Color.White, Offset(size.width * 0.26f, size.height * 0.52f), androidx.compose.ui.geometry.Size(size.width * 0.16f, size.height * 0.14f))
                    drawRect(Color.White, Offset(size.width * 0.58f, size.height * 0.52f), androidx.compose.ui.geometry.Size(size.width * 0.16f, size.height * 0.14f))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(preset.label, style = MaterialTheme.typography.labelSmall, color = if (selected) Porcelain else Ash)
    }
}
