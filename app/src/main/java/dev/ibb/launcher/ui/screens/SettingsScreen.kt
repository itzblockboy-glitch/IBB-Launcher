package dev.ibb.launcher.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.BuildConfig
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.core.IbbPaths
import dev.ibb.launcher.data.model.Renderer
import dev.ibb.launcher.data.store.SettingsStore
import dev.ibb.launcher.game.ComponentManager
import kotlinx.coroutines.launch
import dev.ibb.launcher.runtime.RuntimeManager
import dev.ibb.launcher.ui.components.BrandFooter
import dev.ibb.launcher.ui.components.BrandHeader
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.GlassChip
import dev.ibb.launcher.ui.components.GlassDivider
import dev.ibb.launcher.ui.components.GlassSlider
import dev.ibb.launcher.ui.components.GlassSwitch
import dev.ibb.launcher.ui.components.IbbButton
import dev.ibb.launcher.ui.components.IbbButtonStyle
import dev.ibb.launcher.ui.components.SectionHeader
import dev.ibb.launcher.ui.components.SettingRow
import dev.ibb.launcher.ui.components.clickableNoRipple
import dev.ibb.launcher.ui.theme.Amber
import dev.ibb.launcher.ui.theme.Ash
import dev.ibb.launcher.ui.theme.Coral
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Mint
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import dev.ibb.launcher.ui.theme.VioletBright
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings.
 *
 * Organised as collapsible glass sections so the page stays scannable: only the first section is
 * expanded by default. Every change is written immediately (no "Save" button) — users expect
 * launcher settings to persist, and an explicit save button invites data loss on process death.
 */
@Composable
fun SettingsScreen(app: IbbApplication) {
    val scope = rememberCoroutineScope()
    val settings by app.settingsStore.settings.collectAsState()
    val totalRam = remember { SettingsStore.totalRamMb() }

    var showGraphics by remember { mutableStateOf(true) }
    var showPerformance by remember { mutableStateOf(false) }
    var showRuntime by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var showBehaviour by remember { mutableStateOf(false) }
    var componentBusy by remember { mutableStateOf<String?>(null) }
    var componentMessage by remember { mutableStateOf<String?>(null) }
    var showStorage by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var runtimeBusy by remember { mutableStateOf<Int?>(null) }
    var runtimeMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BrandHeader(subtitle = "Settings") }
        item { SectionHeader("Tune the experience", accent = Cyan) }

        // --- Graphics -----------------------------------------------------------------
        item {
            SettingsSection(
                title = "Graphics",
                subtitle = settings.graphics.renderer.displayName,
                icon = Icons.Rounded.Palette,
                expanded = showGraphics,
                onToggle = { showGraphics = !showGraphics },
            ) {
                Renderer.entries.forEach { renderer ->
                    RendererRow(
                        renderer = renderer,
                        selected = settings.graphics.renderer == renderer,
                        onClick = {
                            app.settingsStore.update { it.copy(graphics = it.graphics.copy(renderer = renderer)) }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("UI scale", style = MaterialTheme.typography.labelMedium, color = Ash)
                GlassSlider(
                    value = settings.graphics.scaleFactor,
                    onValueChange = { value ->
                        app.settingsStore.update { it.copy(graphics = it.graphics.copy(scaleFactor = value)) }
                    },
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    valueLabel = { "%.2f×".format(it) },
                )
                SettingRow(
                    title = "Vertical sync",
                    description = "Prevents tearing; adds one frame of latency",
                    trailing = {
                        GlassSwitch(settings.graphics.vsync, onCheckedChange = {
                            app.settingsStore.update { it.copy(graphics = it.graphics.copy(vsync = it)) }
                        })
                    },
                )
                SettingRow(
                    title = "Frame limit",
                    description = "${settings.graphics.maxFrameRate} FPS",
                    trailing = {
                        GlassChip("${settings.graphics.maxFrameRate}", accent = Cyan)
                    },
                )
                GlassSlider(
                    value = settings.graphics.maxFrameRate.toFloat(),
                    onValueChange = { value ->
                        app.settingsStore.update { it.copy(graphics = it.graphics.copy(maxFrameRate = value.roundToInt())) }
                    },
                    valueRange = 30f..144f,
                    steps = 6,
                    valueLabel = { "%.0f FPS".format(it) },
                )
                SettingRow(
                    title = "Adreno turbo",
                    description = "Hints the driver for sustained clocks on Snapdragon GPUs",
                    trailing = {
                        GlassSwitch(settings.graphics.adrenoTurbo, onCheckedChange = {
                            app.settingsStore.update { it.copy(graphics = it.graphics.copy(adrenoTurbo = it)) }
                        })
                    },
                )
                SettingRow(
                    title = "Big core affinity",
                    description = "Keeps the render thread on performance cores",
                    trailing = {
                        GlassSwitch(settings.graphics.bigCoreAffinity, onCheckedChange = {
                            app.settingsStore.update { it.copy(graphics = it.graphics.copy(bigCoreAffinity = it)) }
                        })
                    },
                )
            }
        }

        // --- Performance --------------------------------------------------------------
        item {
            SettingsSection(
                title = "Performance",
                subtitle = "${settings.performance.allocatedRamMb} MB allocated of ${totalRam / 1024} GB",
                icon = Icons.Rounded.Memory,
                expanded = showPerformance,
                onToggle = { showPerformance = !showPerformance },
            ) {
                Text(
                    "Recommended for this device: ${SettingsStore.recommendedRamMb(totalRam)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                )
                GlassSlider(
                    value = settings.performance.allocatedRamMb.toFloat(),
                    onValueChange = { value ->
                        app.settingsStore.update { it.copy(performance = it.performance.copy(allocatedRamMb = value.roundToInt())) }
                    },
                    valueRange = SettingsStore.minRamMb(totalRam).toFloat()..SettingsStore.maxRamMb(totalRam).toFloat(),
                    steps = (SettingsStore.ramSteps(totalRam) - 1).coerceAtLeast(1),
                    valueLabel = { "${it.roundToInt()} MB" },
                )
                IbbButton(
                    text = "Use recommended",
                    style = IbbButtonStyle.GHOST,
                    onClick = {
                        app.settingsStore.update {
                            it.copy(performance = it.performance.copy(allocatedRamMb = SettingsStore.recommendedRamMb(totalRam)))
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                SettingRow(
                    title = "Auto-tune on launch",
                    description = "Adjusts -Xmx down if the last session ran out of memory",
                    trailing = {
                        GlassSwitch(settings.performance.autoRamOnStart, onCheckedChange = {
                            app.settingsStore.update { it.copy(performance = it.performance.copy(autoRamOnStart = it)) }
                        })
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text("JVM arguments", style = MaterialTheme.typography.labelMedium, color = Ash)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(12.dp),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = settings.performance.jvmArgs,
                        onValueChange = { args ->
                            app.settingsStore.update { it.copy(performance = it.performance.copy(jvmArgs = args)) }
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = Porcelain,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Cyan),
                    )
                }
            }
        }

        // --- Java runtimes -------------------------------------------------------------
        item {
            val installed = remember(runtimeMessage) { app.runtimeManager.installed() }
            SettingsSection(
                title = "Java runtimes",
                subtitle = if (installed.isEmpty()) "Not installed" else installed.joinToString { "Java ${it.majorVersion}" },
                icon = Icons.Rounded.Build,
                expanded = showRuntime,
                onToggle = { showRuntime = !showRuntime },
            ) {
                listOf(8, 17, 21).forEach { major ->
                    val present = app.runtimeManager.isInstalled(major)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Java $major", style = MaterialTheme.typography.titleSmall, color = Porcelain)
                            Text(
                                if (present) "Installed" else app.runtimeManager.downloadUrl(major)?.let { "Available" } ?: "Unsupported on this CPU",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (present) Mint else Ash,
                            )
                        }
                        if (present) {
                            IbbButton("Remove", {
                                app.runtimeManager.uninstall(major)
                                runtimeMessage = "Java $major removed"
                            }, style = IbbButtonStyle.GHOST)
                        } else {
                            IbbButton(
                                text = if (runtimeBusy == major) "Downloading" else "Install",
                                loading = runtimeBusy == major,
                                enabled = runtimeBusy == null && app.runtimeManager.downloadUrl(major) != null,
                                style = IbbButtonStyle.SECONDARY,
                                onClick = {
                                    scope.launch {
                                        runtimeBusy = major
                                        val state = app.runtimeManager.install(major)
                                        runtimeBusy = null
                                        runtimeMessage = when (state) {
                                            is RuntimeManager.InstallState.Ready -> "Java $major installed"
                                            is RuntimeManager.InstallState.Failed -> state.message
                                            else -> null
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                runtimeMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Cyan)
                }
            }
        }

        // --- Controls ------------------------------------------------------------------
        item {
            SettingsSection(
                title = "Controls",
                subtitle = app.controlRepository.selected.name,
                icon = Icons.Rounded.VideogameAsset,
                expanded = showControls,
                onToggle = { showControls = !showControls },
            ) {
                SettingRow(
                    title = "Mouse sensitivity",
                    trailing = { GlassChip("%.2f×".format(app.settingsStore.inputSettings().mouseSensitivity), accent = Cyan) },
                )
                GlassSlider(
                    value = app.settingsStore.inputSettings().mouseSensitivity,
                    onValueChange = { value ->
                        app.settingsStore.saveInputSettings(app.settingsStore.inputSettings().copy(mouseSensitivity = value))
                    },
                    valueRange = 0.2f..3f,
                    valueLabel = { "%.2f×".format(it) },
                )
                SettingRow(
                    title = "Invert vertical look",
                    trailing = {
                        GlassSwitch(app.settingsStore.inputSettings().invertMouseY, onCheckedChange = { checked ->
                            app.settingsStore.saveInputSettings(app.settingsStore.inputSettings().copy(invertMouseY = checked))
                        })
                    },
                )
                SettingRow(
                    title = "Gamepad support",
                    description = "USB / Bluetooth controllers map to keyboard & mouse",
                    trailing = {
                        GlassSwitch(app.settingsStore.inputSettings().gamepadEnabled, onCheckedChange = { checked ->
                            app.settingsStore.saveInputSettings(app.settingsStore.inputSettings().copy(gamepadEnabled = checked))
                        })
                    },
                )
                SettingRow(
                    title = "Controller layout",
                    description = "Edit in-game from the session toolbar",
                    trailing = { GlassChip(app.controlRepository.selected.name.take(14), accent = VioletBright) },
                )
            }
        }

        // --- Play behaviour ------------------------------------------------------------
        item {
            SettingsSection(
                title = "Play behaviour",
                subtitle = "Screen, console and updates",
                icon = Icons.Rounded.VideogameAsset,
                expanded = showBehaviour,
                onToggle = { showBehaviour = !showBehaviour },
            ) {
                SettingRow(
                    title = "Keep screen on while playing",
                    description = "Prevents the system from dimming mid-session",
                    trailing = {
                        GlassSwitch(settings.keepScreenOn, onCheckedChange = { checked ->
                            app.settingsStore.update { it.copy(keepScreenOn = checked) }
                        })
                    },
                )
                SettingRow(
                    title = "Console overlay in game",
                    description = "Show the log drawer button in the session toolbar",
                    trailing = {
                        GlassSwitch(settings.showConsoleOverlay, onCheckedChange = { checked ->
                            app.settingsStore.update { it.copy(showConsoleOverlay = checked) }
                        })
                    },
                )
                SettingRow(
                    title = "Check for launcher updates",
                    description = "Uses one small request per launch",
                    trailing = {
                        GlassSwitch(settings.autoUpdateLauncher, onCheckedChange = { checked ->
                            app.settingsStore.update { it.copy(autoUpdateLauncher = checked) }
                        })
                    },
                )
                SettingRow(
                    title = "Share anonymous diagnostics",
                    description = "Off by default. Crash reports never leave the device unless you share them.",
                    trailing = {
                        GlassSwitch(settings.telemetryOptIn, onCheckedChange = { checked ->
                            app.settingsStore.update { it.copy(telemetryOptIn = checked) }
                        })
                    },
                )
            }
        }

        // --- Storage -------------------------------------------------------------------
        item {
            SettingsSection(
                title = "Storage & maintenance",
                subtitle = "Game files and logs",
                icon = Icons.Rounded.Storage,
                expanded = showStorage,
                onToggle = { showStorage = !showStorage },
            ) {
                val gameRoot = IbbPaths.gameRoot(app)
                val usage = remember(showStorage) { IbbPaths.usage(gameRoot) }
                Text("Game directory", style = MaterialTheme.typography.labelMedium, color = Ash)
                Text(gameRoot.absolutePath, style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = Porcelain.copy(alpha = 0.75f))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("Assets ${IbbPaths.formatBytes(app.versionInstaller.assetsSize())}", accent = Cyan)
                    GlassChip("Libraries ${IbbPaths.formatBytes(app.versionInstaller.librariesSize())}", accent = VioletBright)
                }
                Spacer(Modifier.height(10.dp))
                val context = androidx.compose.ui.platform.LocalContext.current
                Spacer(Modifier.height(12.dp))
                GlassDivider()
                Spacer(Modifier.height(10.dp))
                Text("Optional components", style = MaterialTheme.typography.labelMedium, color = Ash)
                Spacer(Modifier.height(6.dp))
                ComponentManager.describe(app).forEach { component ->
                    SettingRow(
                        title = component.title,
                        description = component.description,
                        trailing = {
                            if (component.installed) {
                                GlassChip(text = "INSTALLED", accent = Mint)
                            } else if (component.url != null) {
                                GlassChip(
                                    text = if (componentBusy == component.id) "FETCHING…" else "FETCH",
                                    accent = Coral,
                                    onClick = fetch@{
                                        if (componentBusy != null) return@fetch
                                        componentBusy = component.id
                                        scope.launch {
                                            val result = ComponentManager.install(component)
                                            componentBusy = null
                                            componentMessage = result.fold(
                                                onSuccess = { "${component.title} installed" },
                                                onFailure = { it.message ?: "Download failed" },
                                            )
                                        }
                                    },
                                )
                            } else {
                                GlassChip(text = "MISSING", accent = Coral)
                            }
                        },
                    )
                }
                componentMessage?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(message, style = MaterialTheme.typography.labelSmall, color = Ash)
                }
                Spacer(Modifier.height(10.dp))
                IbbButton(
                    text = "Export logs",
                    style = IbbButtonStyle.GHOST,
                    onClick = {
                        // Writing the dump is not useful on its own: hand the file to a share
                        // target so it can actually reach a support thread.
                        runCatching {
                            val file = java.io.File(IbbPaths.logsDir(app), "console-${System.currentTimeMillis()}.log")
                            file.writeText(IbbLog.dump())
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                context.packageName + ".fileprovider",
                                file,
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share log"))
                        }.onFailure { IbbLog.w("Settings", "Log export failed", it) }
                    },
                )
            }
        }

        // --- About ---------------------------------------------------------------------
        item {
            SettingsSection(
                title = "About",
                subtitle = "IBB Launcher ${BuildConfig.VERSION_NAME}",
                icon = Icons.Rounded.Info,
                expanded = showAbout,
                onToggle = { showAbout = !showAbout },
            ) {
                Text(
                    "An independent Minecraft: Java Edition launcher for Android. " +
                        "Execution layer derived from PojavLauncher (GPLv3); Java runtimes are provided by the " +
                        "PojavLauncher/Amethyst OpenJDK build project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("v${BuildConfig.VERSION_NAME}", accent = Cyan)
                    GlassChip("API ${android.os.Build.VERSION.SDK_INT}", accent = VioletBright)
                    GlassChip(if (BuildConfig.DEBUG) "DEBUG" else "RELEASE", accent = Amber)
                }
                Spacer(Modifier.height(12.dp))
                GlassDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ash.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(10.dp))
                var showLicenses by remember { mutableStateOf(false) }
                IbbButton(
                    text = if (showLicenses) "Hide licenses" else "Licenses & credits",
                    style = IbbButtonStyle.GHOST,
                    onClick = { showLicenses = !showLicenses },
                )
                AnimatedVisibility(visible = showLicenses) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = rememberLicenses(app),
                            style = MaterialTheme.typography.labelSmall,
                            color = Ash.copy(alpha = 0.85f),
                        )
                    }
                }
                BrandFooter()
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentStrength = 0.04f,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickableNoRipple(onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Violet.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = VioletBright, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Porcelain, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Ash, maxLines = 1)
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = Ash,
                    modifier = Modifier.size(20.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun RendererRow(renderer: Renderer, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Violet.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.035f))
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) Cyan else Color.White.copy(alpha = 0.22f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(renderer.displayName, style = MaterialTheme.typography.titleSmall, color = Porcelain)
            Text(renderer.notes, style = MaterialTheme.typography.labelSmall, color = Ash)
        }
    }
}

/**
 * Reads the bundled NOTICE from assets.
 *
 * Asset reads are cheap but not free, so the text is memoised per composition scope with
 * [remember]; if the asset is missing (custom build) we degrade to a one-line explanation rather
 * than crashing the About panel.
 */
@Composable
private fun rememberLicenses(app: IbbApplication): String = remember(app) {
    runCatching {
        app.assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() }
    }.getOrElse { "License notice unavailable in this build." }
}
