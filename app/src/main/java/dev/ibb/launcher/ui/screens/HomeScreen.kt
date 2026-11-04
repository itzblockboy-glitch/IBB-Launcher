package dev.ibb.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.core.IbbPaths
import dev.ibb.launcher.data.model.Account
import dev.ibb.launcher.data.model.VersionSummary
import dev.ibb.launcher.data.store.SettingsStore
import dev.ibb.launcher.game.VersionResolver
import dev.ibb.launcher.ui.components.BrandHeader
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.GlassChip
import dev.ibb.launcher.ui.components.GlassIconButton
import dev.ibb.launcher.ui.components.IbbButton
import dev.ibb.launcher.ui.components.IbbButtonStyle
import dev.ibb.launcher.ui.components.PlayButton
import dev.ibb.launcher.ui.components.SectionHeader
import dev.ibb.launcher.ui.components.StatusDot
import dev.ibb.launcher.ui.game.GameSession
import dev.ibb.launcher.ui.screens.components.PlayerHead
import dev.ibb.launcher.ui.theme.Ash
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Mint
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import dev.ibb.launcher.ui.theme.VioletBright
import dev.ibb.launcher.ui.theme.VioletBright
import kotlinx.coroutines.launch

/**
 * Home dashboard.
 *
 * Layout priority (top to bottom): who am I → what will I play → how is my device doing.
 * The Play button is deliberately in the thumb zone and is the only gradient primary surface on
 * the screen, so muscle memory finds it instantly.
 */
@Composable
fun HomeScreen(
    app: IbbApplication,
    onLaunch: (GameSession) -> Unit,
    onOpenVersions: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenSkins: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val settings by app.settingsStore.settings.collectAsState()
    val accounts by app.accountStore.accounts.collectAsState()
    val activeId by app.accountStore.activeId.collectAsState()
    val activeAccount: Account? = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()

    var manifestVersions by remember { mutableStateOf<List<VersionSummary>>(emptyList()) }
    var latestRelease by remember { mutableStateOf("") }
    var runtimeBusy by remember { mutableStateOf(false) }
    var launchError by remember { mutableStateOf<String?>(null) }

    val totalRam = remember { SettingsStore.totalRamMb() }
    val selectedVersion = settings.selectedVersionId.ifBlank { latestRelease }
    val installed = remember(selectedVersion) {
        selectedVersion.isNotBlank() && app.versionInstaller.isInstalled(selectedVersion)
    }
    val javaMajor = remember(selectedVersion) {
        VersionResolver.requiredJavaMajor(selectedVersion, null)
    }
    val runtimeReady = remember(javaMajor) { app.runtimeManager.isInstalled(javaMajor) }

    LaunchedEffect(Unit) {
        runCatching { app.mojangApi.manifest() }
            .onSuccess {
                manifestVersions = it.versions.filter { v -> v.isRelease }.take(8)
                latestRelease = it.latest.release
            }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BrandHeader(subtitle = "Welcome back${activeAccount?.let { ", ${it.username}" } ?: ""}") }

        // --- Account + quick switch -----------------------------------------------------
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = Violet,
                accentStrength = 0.08f,
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerHead(username = activeAccount?.username ?: "Steve", size = 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            activeAccount?.username ?: "No account",
                            style = MaterialTheme.typography.titleMedium,
                            color = Porcelain,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                activeAccount == null -> "Add a Microsoft or offline profile"
                                activeAccount.isOnline -> "Microsoft account"
                                else -> "Offline profile"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Ash,
                        )
                    }
                    GlassChip(
                        text = if (activeAccount?.isOnline == true) "ONLINE" else "LOCAL",
                        accent = if (activeAccount?.isOnline == true) Mint else dev.ibb.launcher.ui.theme.Coral,
                    )
                }
            }
        }

        // --- Quick play ------------------------------------------------------------------
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = Cyan,
                accentStrength = 0.07f,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Ready to play",
                                style = MaterialTheme.typography.labelMedium,
                                color = Cyan,
                            )
                            Text(
                                selectedVersion.ifBlank { "No version selected" },
                                style = MaterialTheme.typography.displaySmall,
                                color = Porcelain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        GlassIconButton(
                            icon = Icons.Rounded.ChevronRight,
                            contentDescription = "Choose version",
                            onClick = onOpenVersions,
                            size = 38.dp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            label = if (installed) "Installed" else "Not installed",
                            good = installed,
                        )
                        StatusPill(label = "Java $javaMajor", good = runtimeReady)
                        StatusPill(
                            label = "${settings.performance.allocatedRamMb} MB",
                            good = true,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    PlayButton(
                        text = when {
                            launchError != null -> "Retry"
                            !installed -> "Install & play"
                            !runtimeReady -> "Download Java $javaMajor"
                            else -> "Play"
                        },
                        subtitle = when {
                            installed && runtimeReady -> "Tap to launch ${selectedVersion}"
                            !installed -> "First launch downloads the game files"
                            else -> "One-time runtime download"
                        },
                        enabled = activeAccount != null && selectedVersion.isNotBlank(),
                        loading = runtimeBusy,
                        onClick = {
                            val account = activeAccount ?: return@PlayButton
                            scope.launch {
                                launchError = null
                                if (!runtimeReady) {
                                    runtimeBusy = true
                                    val state = app.runtimeManager.install(javaMajor)
                                    runtimeBusy = false
                                    if (state is dev.ibb.launcher.runtime.RuntimeManager.InstallState.Failed) {
                                        launchError = state.message
                                        return@launch
                                    }
                                }
                                if (!installed) {
                                    onOpenVersions()
                                    return@launch
                                }
                                onLaunch(
                                    GameSession(
                                        account = account,
                                        versionId = selectedVersion,
                                        settings = settings,
                                    ),
                                )
                            }
                        },
                    )
                    launchError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF8FA3))
                    }
                }
            }
        }

        // --- Quick actions ---------------------------------------------------------------
        item {
            SectionHeader("Quick actions", accent = Violet)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(
                    icon = Icons.Rounded.Inventory2,
                    label = "Versions",
                    hint = "Install & switch",
                    onClick = onOpenVersions,
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    icon = Icons.Rounded.FolderOpen,
                    label = "Files",
                    hint = "Mods & packs",
                    onClick = onOpenFiles,
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    icon = Icons.Rounded.Palette,
                    label = "Skin",
                    hint = "Customise",
                    onClick = onOpenSkins,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // --- Device capability ------------------------------------------------------------
        item {
            GlassCard(modifier = Modifier.fillMaxWidth(), accentStrength = 0.04f) {
                Column {
                    SectionHeader("This device", accent = Cyan)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InfoTile(
                            icon = Icons.Rounded.Memory,
                            label = "RAM",
                            value = "${totalRam / 1024} GB",
                            hint = "alloc ${settings.performance.allocatedRamMb} MB",
                            modifier = Modifier.weight(1f),
                        )
                        InfoTile(
                            icon = Icons.Rounded.Speed,
                            label = "Renderer",
                            value = settings.graphics.renderer.displayName.substringBefore(" →").substringBefore(" ("),
                            hint = "${settings.graphics.maxFrameRate} FPS cap",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.CloudDownload, null, tint = Cyan.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                        val used = remember { IbbPaths.usage(IbbPaths.gameRoot(app)) }
                        Text(
                            "Game files · ${IbbPaths.formatBytes(used)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ash,
                        )
                    }
                }
            }
        }

        // --- Latest releases --------------------------------------------------------------
        if (manifestVersions.isNotEmpty()) {
            item { SectionHeader("Latest releases", accent = Violet) }
            items(manifestVersions) { version ->
                ReleaseRow(
                    version = version,
                    installed = app.versionInstaller.isInstalled(version.id),
                    selected = version.id == selectedVersion,
                    onSelect = {
                        app.settingsStore.update { it.copy(selectedVersionId = version.id) }
                    },
                    onOpenVersions = onOpenVersions,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, good: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = if (good) Mint else Color(0xFFFFB86B), size = 6.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Porcelain.copy(alpha = 0.78f))
    }
}

@Composable
private fun InfoTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .padding(12.dp),
    ) {
        Icon(icon, null, tint = VioletBright.copy(alpha = 0.9f), modifier = Modifier.size(17.dp))
        Spacer(Modifier.height(7.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ash)
        Text(value, style = MaterialTheme.typography.titleMedium, color = Porcelain, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hint, style = MaterialTheme.typography.labelSmall, color = Ash.copy(alpha = 0.8f))
    }
}

@Composable
private fun ReleaseRow(
    version: VersionSummary,
    installed: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpenVersions: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) Brush.horizontalGradient(listOf(Violet.copy(alpha = 0.22f), Cyan.copy(alpha = 0.08f)))
                else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.03f))),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(version.id, style = MaterialTheme.typography.titleMedium, color = Porcelain, fontWeight = FontWeight.Medium)
            Text(
                version.released.take(10),
                style = MaterialTheme.typography.labelSmall,
                color = Ash,
            )
        }
        if (installed) {
            GlassChip("INSTALLED", accent = Mint)
            Spacer(Modifier.width(8.dp))
        }
        IbbButton(
            text = when {
                selected && installed -> "Play"
                selected -> "Manage"
                else -> "Use"
            },
            style = IbbButtonStyle.GHOST,
            onClick = {
                // "Use" selects; a second tap on the already-selected row goes to the manager,
                // which is where install/repair lives. Never launch from here — the dashboard
                // card owns the big play action and its preflight checks.
                if (!selected) onSelect() else onOpenVersions()
            },
        )
    }
}

/** Compact glass tile used by the dashboard quick actions. */
@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
        accentStrength = 0.05f,
        onClick = onClick,
    ) {
        Column {
            Icon(icon, null, tint = VioletBright, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Porcelain, maxLines = 1)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = Ash, maxLines = 1)
        }
    }
}
