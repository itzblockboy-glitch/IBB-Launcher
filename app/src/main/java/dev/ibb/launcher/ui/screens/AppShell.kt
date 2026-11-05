package dev.ibb.launcher.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.ui.components.glassSurface
import dev.ibb.launcher.ui.components.pressable
import dev.ibb.launcher.ui.game.GameSession
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.IbbBackdrop
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import dev.ibb.launcher.ui.theme.VioletBright
import java.io.File

/**
 * Navigation shell.
 *
 * A custom bottom bar + [AnimatedContent] instead of Navigation-Compose: the app has exactly five
 * top-level destinations plus modal sheets, and this avoids a `NavHost` back-stack churn on every
 * tab press (which is visible as jank on low-end devices). Tab content is keyed so state survives
 * switching, and the transition is a 260 ms fade/16 dp slide — short enough to feel instant.
 */
enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    VERSIONS("Versions", Icons.Rounded.Inventory2),
    FILES("Files", Icons.Rounded.FolderOpen),
    ACCOUNTS("Account", Icons.Rounded.AccountCircle),
    SETTINGS("Settings", Icons.Rounded.Tune),
}

@Composable
fun AppShell(
    app: IbbApplication,
    onLaunch: (GameSession) -> Unit,
    pendingCrashReport: File?,
    onCrashReportDismissed: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }

    // A crash from the previous run is surfaced once, then dismissed for good.
    if (pendingCrashReport != null) {
        CrashReportSheet(reportFile = pendingCrashReport, onDismiss = onCrashReportDismissed)
    }

    IbbBackdrop(showGrid = true, accent = Violet, secondaryAccent = Cyan) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        ) {
            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 24 })
                        .togetherWith(fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { -it / 24 })
                },
                label = "tabContent",
                modifier = Modifier.weight(1f),
            ) { target ->
                when (target) {
                    Destination.HOME -> HomeScreen(
                        app = app,
                        onLaunch = onLaunch,
                        onOpenVersions = { destination = Destination.VERSIONS },
                        onOpenFiles = { destination = Destination.FILES },
                        onOpenSkins = { destination = Destination.ACCOUNTS },
                        onOpenAccounts = { destination = Destination.ACCOUNTS },
                    )
                    Destination.VERSIONS -> VersionsScreen(app = app, onLaunch = onLaunch)
                    Destination.FILES -> FilesScreen(
                        app = app,
                        versionId = app.settingsStore.settings.value.selectedVersionId.ifBlank { "1.21.4" },
                    )
                    Destination.ACCOUNTS -> AccountsScreen(app = app)
                    Destination.SETTINGS -> SettingsScreen(app = app)
                }
            }
            IbbBottomBar(
                selected = destination,
                onSelect = { destination = it },
                bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            )
        }
    }
}

@Composable
private fun IbbBottomBar(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    // Five destinations is the most that keeps every label on one line at 320 dp.
    val items = Destination.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .padding(bottom = bottomInset)
            .height(64.dp)
            .clip(RoundedCornerShape(22.dp))
            .glassSurface(RoundedCornerShape(22.dp), accentStrength = 0.05f)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Seven top-level destinations: labels stay single-line and the icon shrinks a little on
        // narrow screens instead of wrapping (a wrapped label doubles the bar height).
        items.forEach { item ->
            BottomBarItem(
                destination = item,
                selected = selected == item,
                onClick = { onSelect(item) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BottomBarItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateFloatAsState(if (selected) 1f else 0f, spring(stiffness = 800f), label = "tabTint")
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (selected) Modifier.background(Violet.copy(alpha = 0.16f)).border(1.dp, Violet.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                else Modifier,
            )
            .pressable(onClick = onClick, pressedScale = 0.93f)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.label,
            tint = if (selected) VioletBright else Porcelain.copy(alpha = 0.55f),
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 10.sp,
            ),
            color = if (selected) Porcelain else Porcelain.copy(alpha = 0.5f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Post-mortem sheet shown when the previous run left a crash report behind.
 *
 * It never blocks the launcher: the user can dismiss, read, or share the report. This is the
 * "graceful fallback" path required when a version fails to boot — the console tab holds the
 * full log, and this card explains what happened without a scary dialog.
 */
@Composable
private fun CrashReportSheet(reportFile: File, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preview = androidx.compose.runtime.remember(reportFile) {
        runCatching { reportFile.readLines().takeLast(24).joinToString("\n") }
            .getOrElse { "Report unreadable: ${it.message}" }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = androidx.compose.ui.graphics.Color(0xFF16141F),
        title = { Text("Last session crashed", color = Porcelain) },
        text = {
            Column {
                Text(
                    "A crash report was saved locally. Nothing is uploaded automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Porcelain.copy(alpha = 0.65f),
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                        .padding(10.dp),
                ) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = dev.ibb.launcher.ui.theme.ConsoleFamily),
                        color = Porcelain.copy(alpha = 0.75f),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                // Sharing can fail (no handler, provider misconfigured); the sheet still closes
                // and the report stays on disk, so nothing is lost.
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", reportFile,
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share crash report"))
                }.onFailure { IbbLog.w("AppShell", "Crash report share unavailable", it) }
                onDismiss()
            }) { Text("Share", color = Cyan) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Dismiss", color = Porcelain.copy(alpha = 0.6f)) }
        },
    )
}
