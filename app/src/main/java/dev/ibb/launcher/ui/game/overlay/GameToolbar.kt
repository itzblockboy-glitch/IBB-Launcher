package dev.ibb.launcher.ui.game.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.controls.DefaultLayouts
import dev.ibb.launcher.data.model.ControlLayout
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.GlassChip
import dev.ibb.launcher.ui.components.GlassDivider
import dev.ibb.launcher.ui.components.GlassIconButton
import dev.ibb.launcher.ui.components.IbbButton
import dev.ibb.launcher.ui.components.IbbButtonStyle
import dev.ibb.launcher.ui.components.clickableNoRipple
import dev.ibb.launcher.ui.components.SectionHeader
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet

/**
 * In-game overlay: controller layout switcher + quick actions.
 *
 * Rendered above the SurfaceView in its own window layer, so opening it never pauses the game
 * (unlike a Dialog, which dims and would capture the JVM's surface).
 */
@Composable
fun GameToolbar(
    onDismiss: () -> Unit,
    layout: ControlLayout,
    onLayoutChange: (ControlLayout) -> Unit,
    onExitGame: () -> Unit,
) {
    var selected by remember(layout) { mutableStateOf(layout.id) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickableNoRipple(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.86f)
                .clickableNoRipple { },
            accentStrength = 0.08f,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Session",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Porcelain,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    GlassIconButton(Icons.Rounded.Close, "Close", onDismiss, size = 36.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Switch control layout or leave the game.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Porcelain.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(14.dp))
                GlassDivider()
                Spacer(Modifier.height(10.dp))

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    SectionHeader("Control layout", accent = Violet)
                    DefaultLayouts.all().forEach { preset ->
                        LayoutRow(
                            name = preset.name,
                            controls = preset.controls.size,
                            selected = selected == preset.id,
                            onClick = {
                                selected = preset.id
                                onLayoutChange(preset)
                            },
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    SectionHeader("Quick actions", accent = Cyan)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IbbButton(
                            text = "Save layout",
                            icon = Icons.Rounded.Save,
                            style = IbbButtonStyle.SECONDARY,
                            onClick = { onLayoutChange(layout.copy(updatedAt = System.currentTimeMillis())) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    IbbButton(
                        text = "Leave game",
                        icon = Icons.Rounded.ExitToApp,
                        style = IbbButtonStyle.DANGER,
                        fillWidth = true,
                        onClick = onExitGame,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The world is saved before the JVM exits.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Porcelain.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutRow(name: String, controls: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Violet.copy(alpha = 0.18f) else dev.ibb.launcher.ui.theme.Charcoal.copy(alpha = 0.55f))
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) Cyan else Color.White.copy(alpha = 0.25f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = Porcelain)
            Text("$controls controls", style = MaterialTheme.typography.labelSmall, color = Porcelain.copy(alpha = 0.45f))
        }
        if (selected) GlassChip("ACTIVE", accent = Cyan)
    }
}
