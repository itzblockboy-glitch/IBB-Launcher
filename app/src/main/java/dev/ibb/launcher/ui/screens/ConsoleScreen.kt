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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.core.IbbPaths
import dev.ibb.launcher.ui.components.BrandHeader
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.SectionHeader
import dev.ibb.launcher.ui.components.GlassIconButton
import dev.ibb.launcher.ui.components.clickableNoRipple
import dev.ibb.launcher.ui.theme.Amber
import dev.ibb.launcher.ui.theme.Ash
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Mint
import dev.ibb.launcher.ui.theme.Porcelain
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The live console.
 *
 * Feeds from [IbbLog] (a replaying `SharedFlow`), so opening it mid-session still shows earlier
 * output. Auto-scroll is suspended the moment the user scrolls up (otherwise reading a crash
 * trace while the game is booting is impossible), and resumes at the bottom.
 *
 * Rendering is a `LazyColumn` of pre-formatted strings: no per-line parsing, no highlighting
 * engine. Levels are colour-coded only, which keeps a 4000-line buffer scrolling at 120 fps.
 */
@Composable
fun ConsoleScreen(app: IbbApplication) {
    val entries by IbbLog.history.collectAsState()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var pinnedToBottom by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<IbbLog.Entry>>(emptyList()) }

    val visible = if (paused) frozen else entries

    LaunchedEffect(paused) {
        if (paused) frozen = entries
    }
    LaunchedEffect(visible.size, pinnedToBottom) {
        if (pinnedToBottom && visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || listState.firstVisibleItemIndex >= total - 3
        }.collect { atBottom -> pinnedToBottom = atBottom }
    }

    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        BrandHeader(
            subtitle = "Live console · ${visible.size} lines",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassIconButton(
                        icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = if (paused) "Resume" else "Pause",
                        onClick = { paused = !paused },
                        size = 36.dp,
                    )
                    GlassIconButton(
                        icon = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy log",
                        onClick = { clipboard.setText(AnnotatedString(IbbLog.dump())) },
                        size = 36.dp,
                    )
                    GlassIconButton(
                        icon = Icons.Rounded.DeleteSweep,
                        contentDescription = "Clear",
                        onClick = { IbbLog.clear() },
                        size = 36.dp,
                    )
                }
            },
        )

        Spacer(Modifier.height(10.dp))
        SectionHeader("Runtime output", accent = Mint)
        Box(Modifier.weight(1f)) {
            if (visible.isEmpty()) {
                GlassCard(Modifier.fillMaxWidth(), accentStrength = 0.03f) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Console is idle", style = MaterialTheme.typography.titleSmall, color = Porcelain)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Install or launch a version and the runtime output streams here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ash,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(visible) { entry ->
                        ConsoleLine(entry = entry, time = formatter.format(Date(entry.timestamp)))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .clickableNoRipple { pinnedToBottom = !pinnedToBottom }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (pinnedToBottom) "Following output" else "Scroll paused — tap to follow",
                style = MaterialTheme.typography.labelSmall,
                color = if (pinnedToBottom) Mint else Amber,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Export",
                style = MaterialTheme.typography.labelSmall,
                color = Cyan,
                modifier = Modifier.clickableNoRipple {
                    runCatching {
                        val file = File(IbbPaths.logsDir(app), "console-${System.currentTimeMillis()}.log")
                        file.writeText(IbbLog.dump())
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Download, null, tint = Cyan, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ConsoleLine(entry: IbbLog.Entry, time: String) {
    val color = when (entry.level) {
        IbbLog.Level.ERROR, IbbLog.Level.CRASH -> Color(0xFFFF7D93)
        IbbLog.Level.WARN -> dev.ibb.launcher.ui.theme.Warning
        IbbLog.Level.INFO -> Mint
        IbbLog.Level.GAME -> Porcelain.copy(alpha = 0.88f)
        IbbLog.Level.JVM -> Cyan.copy(alpha = 0.9f)
        else -> Ash
    }
    Row(Modifier.fillMaxWidth()) {
        Text(
            time,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = dev.ibb.launcher.ui.theme.ConsoleFamily, fontSize = 10.sp),
            color = Ash.copy(alpha = 0.55f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            entry.level.short,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = dev.ibb.launcher.ui.theme.ConsoleFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold),
            color = color.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = dev.ibb.launcher.ui.theme.ConsoleFamily, fontSize = 11.sp, lineHeight = 15.sp),
            color = color,
        )
    }
}
