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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.data.model.VersionSummary
import dev.ibb.launcher.mods.ModLoaderKind
import dev.ibb.launcher.repository.VersionInstaller
import dev.ibb.launcher.ui.components.BrandHeader
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.GlassChip
import dev.ibb.launcher.ui.components.GlassProgressBar
import dev.ibb.launcher.ui.components.IbbButton
import dev.ibb.launcher.ui.components.IbbButtonStyle
import dev.ibb.launcher.ui.components.clickableNoRipple
import dev.ibb.launcher.ui.game.GameSession
import dev.ibb.launcher.ui.theme.Amber
import dev.ibb.launcher.ui.theme.Ash
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Mint
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import dev.ibb.launcher.ui.theme.VioletBright
import kotlinx.coroutines.launch

/**
 * Version manager.
 *
 * Shows the full Mojang catalogue with loader filters, a search field, and inline install
 * progress. Installation is cancellable and resumable; the card mirrors the installer's real
 * stage (`VersionInstaller.stage`) so users see "Downloading assets · 2 351 objects" instead of a
 * fake spinner.
 */
@Composable
fun VersionsScreen(
    app: IbbApplication,
    onLaunch: (GameSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by app.settingsStore.settings.collectAsState()
    val accounts by app.accountStore.accounts.collectAsState()
    val activeId by app.accountStore.activeId.collectAsState()

    var versions by remember { mutableStateOf<List<VersionSummary>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(VersionFilter.RELEASE) }
    var loader by remember { mutableStateOf(ModLoaderKind.VANILLA) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var installStage by remember { mutableStateOf<VersionInstaller.Stage?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { app.mojangApi.manifest() }
            .onSuccess { versions = it.versions }
            .onFailure { error = it.message ?: "Could not load the version list" }
        loading = false
    }

    val filtered = remember(versions, query, filter) {
        versions.filter { version ->
            val matchesFilter = when (filter) {
                VersionFilter.RELEASE -> version.isRelease
                VersionFilter.SNAPSHOT -> version.isSnapshot
                VersionFilter.OLD -> version.isOldBetaAlpha
                VersionFilter.ALL -> true
            }
            matchesFilter && (query.isBlank() || version.id.contains(query, ignoreCase = true))
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        BrandHeader(subtitle = "Version manager")

        Spacer(Modifier.height(14.dp))
        SearchField(query = query, onQueryChange = { query = it })
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(VersionFilter.entries.toList()) { entry ->
                FilterChip(
                    label = entry.label,
                    selected = filter == entry,
                    onClick = { filter = entry },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ModLoaderKind.entries.toList()) { kind ->
                FilterChip(
                    label = kind.displayName,
                    selected = loader == kind,
                    accent = if (kind == ModLoaderKind.VANILLA) Cyan else VioletBright,
                    onClick = { loader = kind },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading -> LoadingState()
            error != null -> ErrorState(message = error!!) { scope.launch { app.mojangApi.manifest(forceRefresh = true) } }
            filtered.isEmpty() -> EmptyState(text = "No versions match \"$query\"")
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                items(filtered, key = { it.id }) { version ->
                    VersionCard(
                        version = version,
                        installed = app.versionInstaller.isInstalled(version.id),
                        selected = settings.selectedVersionId == version.id,
                        installing = installing == version.id,
                        stage = if (installing == version.id) installStage else null,
                        loader = loader,
                        onInstall = {
                            scope.launch {
                                installing = version.id
                                installError = null
                                val job = launch {
                                    app.versionInstaller.stage.collect { installStage = it }
                                }
                                when (val result = app.versionInstaller.install(version, loaderKind = loader)) {
                                    is VersionInstaller.Result.Installed -> {
                                        app.settingsStore.update { it.copy(selectedVersionId = result.versionId) }
                                    }
                                    is VersionInstaller.Result.Failed -> installError = result.message
                                }
                                job.cancel()
                                installing = null
                            }
                        },
                        onPlay = {
                            val account = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
                            if (account != null) {
                                app.settingsStore.update { it.copy(selectedVersionId = version.id) }
                                onLaunch(
                                    GameSession(
                                        account = account,
                                        versionId = version.id,
                                        settings = settings,
                                        summary = version,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
        installError?.let {
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth(), accent = Color(0xFFFF5C7A)) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF8FA3))
            }
        }
    }
}

enum class VersionFilter(val label: String) {
    RELEASE("Releases"),
    SNAPSHOT("Snapshots"),
    OLD("Alpha / Beta"),
    ALL("All"),
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f)),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search versions", color = Ash, style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Ash, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Violet.copy(alpha = 0.6f),
                unfocusedIndicatorColor = Color.White.copy(alpha = 0.10f),
                focusedTextColor = Porcelain,
                unfocusedTextColor = Porcelain,
                cursorColor = Cyan,
            ),
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, accent: Color = Violet) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f))
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Porcelain else Ash,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun VersionCard(
    version: VersionSummary,
    installed: Boolean,
    selected: Boolean,
    installing: Boolean,
    stage: VersionInstaller.Stage?,
    loader: ModLoaderKind,
    onInstall: () -> Unit,
    onPlay: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = if (selected) Cyan else Violet,
        accentStrength = if (selected) 0.10f else 0.05f,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            version.id,
                            style = MaterialTheme.typography.titleMedium,
                            color = Porcelain,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (loader != ModLoaderKind.VANILLA) {
                            Spacer(Modifier.width(8.dp))
                            GlassChip(loader.displayName, accent = VioletBright)
                        } else if (version.isSnapshot) {
                            Spacer(Modifier.width(8.dp))
                            GlassChip("SNAPSHOT", accent = Amber)
                        }
                    }
                    Text(
                        version.released.take(10) + " · " + version.type.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = Ash,
                    )
                }
                if (installed) {
                    GlassChip("INSTALLED", accent = Mint)
                }
            }

            AnimatedVisibility(visible = installing) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stage?.detail?.takeIf { it.isNotBlank() } ?: stage?.name ?: "Preparing…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ash,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    GlassProgressBar(progress = stage?.fraction ?: 0f)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed) {
                    IbbButton(
                        text = "Play",
                        icon = Icons.Rounded.PlayArrow,
                        style = IbbButtonStyle.PRIMARY,
                        onClick = onPlay,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    IbbButton(
                        text = if (installing) "Installing…" else "Install",
                        icon = Icons.Rounded.Download,
                        style = IbbButtonStyle.PRIMARY,
                        loading = installing,
                        enabled = !installing,
                        onClick = onInstall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.CircularProgressIndicator(color = Violet, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(12.dp))
        Text("Fetching the version list…", style = MaterialTheme.typography.bodySmall, color = Ash)
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), accent = Color(0xFFFF5C7A)) {
        Column {
            Text("Could not load versions", style = MaterialTheme.typography.titleSmall, color = Porcelain)
            Text(message, style = MaterialTheme.typography.bodySmall, color = Ash)
            Spacer(Modifier.height(10.dp))
            IbbButton("Retry", onRetry, style = IbbButtonStyle.SECONDARY)
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ash)
    }
}
