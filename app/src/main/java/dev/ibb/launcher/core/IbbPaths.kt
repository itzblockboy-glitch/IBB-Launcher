package dev.ibb.launcher.core

import android.content.Context
import java.io.File

/**
 * Every path the launcher writes to, in one place.
 *
 * Layout (all under the app's private external files dir so no storage permission is needed):
 * ```
 * <root>/                     app-specific external storage (survives app updates)
 * ├── instances/<version>/    per-version .minecraft: mods/, saves/, config/, options.txt
 * ├── assets/                 shared asset objects + indexes (deduplicated across instances)
 * ├── libraries/              shared maven tree (deduplicated across instances)
 * ├── versions/<id>/          version json + client jar
 * ├── runtimes/               OpenJDK runtimes (private internal storage: contains exec bits)
 * ├── logs/                   crash reports + exported console dumps
 * └── controls/               custom virtual-controller layouts
 * ```
 *
 * Why this split: assets/libraries dominate the disk footprint and are identical across
 * versions, while saving/worlds must be per-instance so two versions cannot corrupt each
 * other. Runtimes live in *internal* storage because some devices mount the external
 * filesystem `noexec`, which breaks `bin/java`.
 */
object IbbPaths {

    @Volatile private var rootOverride: File? = null

    fun gameRoot(context: Context): File =
        rootOverride ?: File(context.getExternalFilesDir(null) ?: context.filesDir, "minecraft").apply { mkdirs() }

    fun runtimeRoot(context: Context): File = File(context.filesDir, "runtimes").apply { mkdirs() }

    fun instancesRoot(context: Context): File = File(gameRoot(context), "instances").apply { mkdirs() }

    fun instanceDir(context: Context, versionId: String): File =
        File(instancesRoot(context), sanitize(versionId)).apply { mkdirs() }

    fun assetsDir(context: Context): File = File(gameRoot(context), "assets").apply { mkdirs() }

    fun librariesDir(context: Context): File = File(gameRoot(context), "libraries").apply { mkdirs() }

    fun versionsDir(context: Context): File = File(gameRoot(context), "versions").apply { mkdirs() }

    fun logsDir(context: Context): File = File(gameRoot(context), "logs").apply { mkdirs() }

    fun controlsDir(context: Context): File = File(gameRoot(context), "controls").apply { mkdirs() }

    fun cacheDir(context: Context): File = File(context.cacheDir, "ibb").apply { mkdirs() }

    fun nativesDir(context: Context, versionId: String): File =
        File(context.filesDir, "natives/${sanitize(versionId)}").apply { mkdirs() }

    /** "1.20.4 - Fabric" style ids become safe folder names. */
    fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Used by the "custom .minecraft folder" setting (SAF-free path pickers only). */
    fun setRootOverride(path: String?) {
        rootOverride = path?.takeIf { it.isNotBlank() }?.let { File(it) }
    }

    fun rootOverrideOrNull(): File? = rootOverride

    /** Total bytes used by the whole game root — shown in Settings → Storage. */
    fun usage(root: File): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value >= 100) "%.0f %s".format(value, units[unit])
        else "%.1f %s".format(value, units[unit])
    }
}
