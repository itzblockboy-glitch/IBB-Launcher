package dev.ibb.launcher.mods

import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.model.ModCategory
import dev.ibb.launcher.data.model.ModFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Mod / resource-pack / shader management for one instance directory.
 *
 * Design notes
 * ------------
 * * Files live in the vanilla folder names (`mods/`, `resourcepacks/`, ...) so nothing has to be
 *   patched in the game to find them.
 * * Type detection reads the archive itself (`fabric.mod.json`, `pack.mcmeta`, `shaders/`) instead
 *   of trusting the extension — users rename files constantly, and loading a resource pack as a
 *   mod is one of the most common "why does my game crash" causes.
 * * Every operation is a plain file move on the same volume, so importing is instant even for a
 *   500 MB shaderpack (no copy through the JVM heap).
 */
class ModRepository(private val instanceDir: File) {

    fun categoryDir(category: ModCategory): File = File(instanceDir, category.folder).apply { mkdirs() }

    /** Lists installed content, newest first. */
    suspend fun list(category: ModCategory): List<ModFile> = withContext(Dispatchers.IO) {
        val dir = categoryDir(category)
        dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { file ->
                ModFile(
                    name = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    category = category,
                    modifiedAt = file.lastModified(),
                )
            }
            .sortedByDescending { it.modifiedAt }
    }

    /**
     * Imports a file into the right category folder.
     * @param source the picked file (often in the SAF cache directory)
     * @param category target category, or null to auto-detect from the archive contents
     */
    suspend fun import(source: File, category: ModCategory? = null): ModFile? = withContext(Dispatchers.IO) {
        val resolved = category ?: detectCategory(source) ?: ModCategory.MOD
        val targetDir = categoryDir(resolved)
        val target = uniqueTarget(targetDir, source.name)
        try {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = false)
                source.delete()
            }
            IbbLog.i(TAG, "Imported ${target.name} → ${resolved.folder}/")
            ModFile(target.name, target.absolutePath, target.length(), resolved, target.lastModified())
        } catch (t: IOException) {
            IbbLog.e(TAG, "Import failed for ${source.name}", t)
            null
        }
    }

    fun delete(path: String): Boolean = runCatching {
        val file = File(path).canonicalFile
        // Refuse to delete anything outside the instance directory.
        if (!file.path.startsWith(instanceDir.canonicalPath)) return false
        file.delete()
    }.getOrDefault(false)

    fun toggle(path: String): Boolean = runCatching {
        val file = File(path)
        val disabled = File(file.parentFile, "${file.name}.disabled")
        if (file.exists()) file.renameTo(disabled) else disabled.renameTo(file)
    }.getOrDefault(false)

    /** Opens an archive once and looks for the markers that identify each content type. */
    fun detectCategory(file: File): ModCategory? = runCatching {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            when {
                names.any { it == "fabric.mod.json" || it == "quilt.mod.json" || it == "META-INF/mods.toml" || it == "mcmod.info" } -> ModCategory.MOD
                names.any { it.startsWith("shaders/") || it.startsWith("shaderpacks/") } -> ModCategory.SHADER_PACK
                names.any { it == "pack.mcmeta" } -> ModCategory.RESOURCE_PACK
                names.any { it == "level.dat" } -> ModCategory.WORLD
                else -> null
            }
        }
    }.getOrElse {
        IbbLog.w(TAG, "Could not inspect ${file.name}", it)
        ModCategory.forExtension(file.extension)
    }

    /** Never silently overwrite an existing file — mods are often re-installed deliberately. */
    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, base + " (" + index + ")" + if (ext.isNotEmpty()) ".$ext" else "")
            index++
        }
        return candidate
    }

    /** Total size of all content folders — shown in Settings → Storage. */
    fun totalSize(): Long = ModCategory.entries.sumOf { category ->
        categoryDir(category).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private companion object {
        const val TAG = "ModRepository"
    }
}
