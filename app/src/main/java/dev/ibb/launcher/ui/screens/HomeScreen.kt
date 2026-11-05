package dev.ibb.launcher.ui.screens

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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.data.model.Account
import dev.ibb.launcher.runtime.RuntimeManager
import dev.ibb.launcher.ui.components.BrandHeader
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.IbbButton
import dev.ibb.launcher.ui.components.IbbButtonStyle
import dev.ibb.launcher.ui.components.PlayButton
import dev.ibb.launcher.ui.components.SectionHeader
import dev.ibb.launcher.ui.game.GameSession
import dev.ibb.launcher.ui.screens.components.PlayerHead
import dev.ibb.launcher.ui.theme.Amber
import dev.ibb.launcher.ui.theme.Ash
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Mint
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import kotlinx.coroutines.launch

/**
 * Home — the only screen a player has to understand.
 *
 * Layout, top to bottom:
 *  1. who is playing (account chip),
 *  2. the one big red button: **Play**,
 *  3. what that button is going to do (version, Java, RAM) with a one-tap way to change it.
 *
 * Everything else (versions, mods, settings) is reachable from the bottom bar. Readiness is
 * derived, never asked: the button says *exactly* what will happen when tapped, and performs the
 * missing step itself (download Java, install the version) instead of sending the user hunting.
 */
@Composable
fun HomeScreen(
    app: IbbApplication,
    onLaunch: (GameSession) -> Unit,
    onOpenVersions: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenSkins: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val settings by app.settingsStore.settings.collectAsState()
    val accounts by app.accountStore.accounts.collectAsState()
    val activeId by app.accountStore.activeId.collectAsState()
    val activeAccount: Account? = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()

    // Installation state is read from disk each time home is shown, so a freshly installed version
    // is reflected without restarting the launcher.
    var refresh by remember { mutableStateOf(0) }
    val selectedVersion = settings.selectedVersionId.ifBlank { DEFAULT_VERSION }
    val installed = remember(selectedVersion, refresh) {
        runCatching { app.versionInstaller.isInstalled(selectedVersion) }.getOrDefault(false)
    }
    val javaMajor = remember(selectedVersion) {
        runCatching { dev.ibb.launcher.game.VersionResolver.requiredJavaMajor(selectedVersion, null) }
            .getOrDefault(17)
    }
    val runtimeReady = remember(javaMajor, refresh) {
        runCatching { app.runtimeManager.isInstalled(javaMajor) }.getOrDefault(false)
    }

    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Pick the newest release the first time the launcher opens.
        if (settings.selectedVersionId.isBlank()) {
            runCatching { app.mojangApi.manifest() }.onSuccess { manifest ->
                app.settingsStore.update { s -> s.copy(selectedVersionId = manifest.latest.release) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BrandHeader(subtitle = "Ready when you are") }

        // ------------------------------------------------------------------ account
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = Violet,
                accentStrength = 0.07f,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerHead(username = activeAccount?.username ?: "Steve", size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            activeAccount?.username ?: "No account yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = Porcelain,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                activeAccount == null -> "Tap to sign in or play offline"
                                activeAccount.isOnline -> "Microsoft account · ready"
                                else -> "Offline profile · single player"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Ash,
                            maxLines = 1,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            if (activeAccount == null) "Add" else "Switch",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (activeAccount == null) Amber else Cyan,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------ play
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = Cyan,
                accentStrength = 0.09f,
                contentPadding = PaddingValues(16.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "SELECTED VERSION",
                                style = MaterialTheme.typography.labelSmall,
                                color = Cyan,
                            )
                            Text(
                                selectedVersion,
                                style = MaterialTheme.typography.displaySmall,
                                color = Porcelain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Change", style = MaterialTheme.typography.labelMedium, color = Porcelain)
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = Porcelain.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    ReadinessRow(label = "Game files", ready = installed, detail = if (installed) "Installed" else "Not installed yet")
                    Spacer(Modifier.height(8.dp))
                    ReadinessRow(label = "Java $javaMajor", ready = runtimeReady, detail = if (runtimeReady) "Ready" else "Will download (~85 MB)")
                    Spacer(Modifier.height(16.dp))

                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress ?: 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Cyan,
                            trackColor = Color.White.copy(alpha = 0.10f),
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    PlayButton(
                        text = when {
                            busy -> busyLabel
                            activeAccount == null -> "Add an account to play"
                            !installed -> "Install & play"
                            !runtimeReady -> "Set up Java & play"
                            else -> "Play"
                        },
                        subtitle = if (busy) null else when {
                            activeAccount == null -> "Sign in with Microsoft or create an offline profile"
                            !installed -> "One tap installs the game files for this version"
                            !runtimeReady -> "One-time Java download for this version"
                            else -> "$selectedVersion · ${settings.performance.allocatedRamMb} MB RAM"
                        },
                        enabled = !busy,
                        loading = busy,
                        onClick = {
                            if (busy) return@PlayButton
                            error = null
                            if (activeAccount == null) {
                                onOpenAccounts()
                                return@PlayButton
                            }
                            scope.launch {
                                busy = true
                                try {
                                    if (!runtimeReady) {
                                        busyLabel = "Downloading Java $javaMajor"
                                        progress = 0f
                                        val job = launch {
                                            app.runtimeManager.state.collect { st ->
                                                if (st is RuntimeManager.InstallState.Downloading) progress = st.progress
                                            }
                                        }
                                        val state = app.runtimeManager.install(javaMajor)
                                        job.cancel()
                                        progress = null
                                        if (state is RuntimeManager.InstallState.Failed) {
                                            error = state.message
                                            return@launch
                                        }
                                    }
                                    if (!installed) {
                                        busyLabel = "Installing $selectedVersion"
                                        val summary = runCatching {
                                            app.mojangApi.manifest().versions.firstOrNull { it.id == selectedVersion }
                                        }.getOrNull()
                                        if (summary == null) {
                                            error = "Could not reach Mojang to download $selectedVersion"
                                            return@launch
                                        }
                                        val result = app.versionInstaller.install(summary)
                                        if (result is dev.ibb.launcher.repository.VersionInstaller.Result.Failed) {
                                            error = result.message
                                            return@launch
                                        }
                                    }
                                    refresh++
                                    onLaunch(
                                        GameSession(
                                            account = activeAccount,
                                            versionId = selectedVersion,
                                            settings = app.settingsStore.settings.value,
                                        ),
                                    )
                                } catch (t: Throwable) {
                                    error = t.message ?: "Something went wrong"
                                } finally {
                                    busy = false
                                    busyLabel = ""
                                }
                            }
                        },
                    )

                    error?.let { message ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = dev.ibb.launcher.ui.theme.Danger,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------ shortcuts
        item {
            SectionHeader("Manage", accent = Violet)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutTile(
                    label = "Versions",
                    hint = "Install, switch, mods",
                    onClick = onOpenVersions,
                    modifier = Modifier.weight(1f),
                )
                ShortcutTile(
                    label = "Files",
                    hint = "Mods, worlds, packs",
                    onClick = onOpenFiles,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutTile(
                    label = "Skins",
                    hint = "Your look",
                    onClick = onOpenSkins,
                    modifier = Modifier.weight(1f),
                )
                ShortcutTile(
                    label = "Accounts",
                    hint = "Sign in, profiles",
                    onClick = onOpenAccounts,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Spacer(Modifier.height(2.dp))
            Text(
                "IBB Launcher runs Minecraft Java Edition on Android using a bundled Java runtime " +
                    "and an OpenGL bridge. First launch downloads the game, so use Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = Ash.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun ReadinessRow(label: String, ready: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (ready) Mint.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (ready) Icons.Rounded.Check else Icons.Rounded.Download,
                contentDescription = null,
                tint = if (ready) Mint else Ash,
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Porcelain.copy(alpha = 0.9f))
        Spacer(Modifier.weight(1f))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (ready) Mint.copy(alpha = 0.9f) else Ash,
        )
    }
}

@Composable
private fun ShortcutTile(
    label: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier,
        accentStrength = 0.05f,
        contentPadding = PaddingValues(14.dp),
        onClick = onClick,
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Porcelain, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Ash, maxLines = 1)
        }
    }
}

/** Version shown before the user has picked one (Mojang's latest release overrides it on first run). */
private const val DEFAULT_VERSION = "1.21.4"
