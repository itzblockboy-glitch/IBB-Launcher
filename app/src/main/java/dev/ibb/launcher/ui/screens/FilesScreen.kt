package dev.ibb.launcher.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.core.IbbPaths
import dev.ibb.launcher.data.model.ModCategory
import dev.ibb.launcher.data.model.ModFile
import dev.ibb.launcher.mods.ModRepository
import dev.ibb.launcher.ui.components.BrandHeader
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.GlassChip
import dev.ibb.launcher.ui.components.GlassIconButton
import dev.ibb.launcher.ui.components.IbbButton
import dev.ibb.launcher.ui.components.IbbButtonStyle
import dev.ibb.launcher.ui.components.SectionHeader
import dev.ibb.launcher.ui.components.clickableNoRipple
import dev.ibb.launcher.ui.theme.Amber
import dev.ibb.launcher.ui.theme.Coral
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Mint
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Built-in file explorer for the *instance* folder — mods, resource packs, shader packs, datapacks
 * and worlds.
 *
 * Why not just open the system file manager: the game root lives in app-specific external storage
 * (`Android/data/<pkg>/files/minecraft`), which most OEM file managers refuse to browse on
 * Android 11+. Importing through the SAF picker is also the only way to accept files from
 * cloud apps without holding `MANAGE_EXTERNAL_STORAGE` — a permission Play policy rejects for
 * launchers.
 *
 * Import pipeline: SAF URI → copy into the app cache → [ModRepository.import] which sniffs the
 * archive (`fabric.mod.json`, `pack.mcmeta`, `shaders/`) and files it in the right folder.
 */
@Composable
fun FilesScreen(
    app: IbbApplication,
    versionId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(versionId) {
        ModRepository(IbbPaths.instanceDir(context, versionId))
    }

    var category by remember { mutableStateOf(ModCategory.MOD) }
    var entries by remember { mutableStateOf<List<ModFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(category, reloadToken) {
        entries = runCatching { repository.list(category) }
            .onFailure { IbbLog.w(TAG, "Could not list ${category.folder}", it) }
            .getOrDefault(emptyList())
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            var imported = 0
            uris.forEach { uri ->
                val local = copyToCache(context, uri) ?: return@forEach
                val result = repository.import(local, category = null)
                if (result != null) imported++ else local.delete()
            }
            busy = false
            status = if (imported == 0) "Nothing was imported" else "Imported $imported file(s)"
            reloadToken++
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { BrandHeader(subtitle = "Instance files") }
        item {
            Text(
                text = versionId,
                style = MaterialTheme.typography.titleMedium,
                color = Porcelain,
            )
        }
        item {
            Text(
                text = "Drop in mods, packs and worlds. The type is detected from the archive " +
                    "contents, so a renamed file still lands in the right folder.",
                style = MaterialTheme.typography.bodySmall,
                color = Porcelain.copy(alpha = 0.6f),
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ModCategory.entries) { item ->
                    GlassChip(
                        text = item.label,
                        accent = if (item == category) Violet else Cyan,
                        selected = item == category,
                        onClick = { category = item },
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IbbButton(
                    text = "Add files",
                    icon = Icons.Rounded.Add,
                    onClick = { picker.launch(category.mimes()) },
                    enabled = !busy,
                    loading = busy,
                    style = IbbButtonStyle.PRIMARY,
                )
                GlassIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    onClick = { reloadToken++ },
                    size = 40.dp,
                )
            }
        }

        status?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = Mint,
                )
            }
        }

        if (entries.isEmpty()) {
            item {
                GlassCard(contentPadding = PaddingValues(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = Cyan.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (busy) "Working…" else "No ${category.label.lowercase()} yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Porcelain.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        items(entries, key = { it.path }) { file ->
            FileRow(
                file = file,
                onToggle = {
                    repository.toggle(file.path)
                    status = if (file.name.endsWith(".disabled")) "Enabled ${file.name}" else "Disabled ${file.name}"
                    reloadToken++
                },
                onDelete = {
                    repository.delete(file.path)
                    status = "Deleted ${file.name}"
                    reloadToken++
                },
            )
        }

        item {
            SectionHeader("Where these live", accent = Amber)
            GlassCard(contentPadding = PaddingValues(14.dp)) {
                Column {
                    Text(
                        text = repository.categoryDir(category).absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = Porcelain.copy(alpha = 0.55f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Files disabled here are renamed with a .disabled suffix — " +
                            "the game ignores them but nothing is deleted.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Porcelain.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: ModFile,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val disabled = file.name.endsWith(".disabled")
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (disabled) Porcelain.copy(alpha = 0.45f) else Porcelain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = IbbPaths.formatBytes(file.sizeBytes) + if (disabled) " · disabled" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Porcelain.copy(alpha = 0.45f),
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .clickableNoRipple(onToggle)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (disabled) "Enable" else "Disable",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (disabled) Mint else Cyan,
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "Delete ${file.name}",
                tint = Coral,
                onClick = onDelete,
                size = 34.dp,
            )
        }
    }
}

/** Copies a SAF document into the app cache so it can be moved atomically. */
private suspend fun copyToCache(context: android.content.Context, uri: Uri): File? = withContext(Dispatchers.IO) {
    runCatching {
        val name = context.contentResolver.displayName(uri) ?: "import-${System.currentTimeMillis()}.jar"
        val target = File(IbbPaths.cacheDir(context), name)
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching null
        target
    }.onFailure { IbbLog.w(TAG, "Could not read $uri", it) }.getOrNull()
}

private fun android.content.ContentResolver.displayName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()


// MIME filters per category; a wildcard type is the only safe filter for .jar-shaped archives.
private fun ModCategory.mimes(): Array<String> = when (this) {
    ModCategory.MOD -> arrayOf("application/java-archive", "application/x-java-archive", "*/*")
    ModCategory.RESOURCE_PACK, ModCategory.SHADER_PACK, ModCategory.DATAPACK ->
        arrayOf("application/zip", "application/x-zip", "*/*")
    ModCategory.WORLD -> arrayOf("application/zip", "application/octet-stream", "*/*")
    ModCategory.CONFIG -> arrayOf("application/json", "text/plain", "*/*")
}

private const val TAG = "FilesScreen"
