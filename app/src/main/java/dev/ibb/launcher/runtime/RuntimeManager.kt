package dev.ibb.launcher.runtime

import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.model.RuntimeRequirement
import dev.ibb.launcher.download.ParallelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Downloads and provisions ARM-compatible OpenJDK runtimes.
 *
 * Why ship a custom JRE instead of using the device's? Android has no `java` binary, and
 * Minecraft needs a full desktop JVM with `libjli`, AWT headless stubs and JIT. The builds used
 * here are the PojavLauncher/Amethyst-published OpenJDK ports (`angelauramc-openjdk-build`),
 * which are jdk8u / 17 / 21 for `arm64-v8a`, `armeabi-v7a`, `x86_64`.
 *
 * Layout produced by [install]:
 * ```
 * files/runtimes/<component>/bin/java
 * files/runtimes/<component>/lib/{server,client}/...
 * files/runtimes/<component>/.ibb-runtime.json   <- marker with version + arch
 * ```
 * Extraction is streamed straight out of the `.tar.xz` (tukaani-xz) so peak memory stays flat
 * even for 90 MB archives; corrupt/partial trees are deleted and re-fetched automatically.
 */
class RuntimeManager(
    private val rootDir: File,
    private val arch: AndroidArch,
) {

    /** CPU ABI of the host device, with the download token used by the JRE releases. */
    enum class AndroidArch(val abiName: String, val downloadToken: String, val is64Bit: Boolean) {
        ARM64("arm64-v8a", "arm64", true),
        ARM32("armeabi-v7a", "arm", false),
        X86_64("x86_64", "x86_64", true),
        X86("x86", "x86", false);

        companion object {
            fun fromAbis(abis: List<String>): AndroidArch =
                abis.firstNotNullOfOrNull { abi ->
                    entries.firstOrNull { it.abiName.equals(abi, ignoreCase = true) }
                } ?: ARM64
        }
    }

    data class InstalledRuntime(
        val component: String,
        val majorVersion: Int,
        val path: String,
        val sizeBytes: Long,
    )

    sealed class InstallState {
        data object Idle : InstallState()
        data class Downloading(val progress: Float) : InstallState()
        data object Extracting : InstallState()
        data class Ready(val runtime: InstalledRuntime) : InstallState()
        data class Failed(val message: String, val cause: Throwable? = null) : InstallState()
    }

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val runtimesDir = File(rootDir, "runtimes").apply { mkdirs() }

    fun componentName(major: Int): String = "jre-$major-${arch.downloadToken}"

    fun runtimeHome(major: Int): File = File(runtimesDir, componentName(major))

    fun isInstalled(major: Int): Boolean = File(runtimeHome(major), "bin/java").exists() ||
        File(runtimeHome(major), "bin/java.orig").exists()

    /** Every runtime currently provisioned, newest java first. */
    fun installed(): List<InstalledRuntime> = runtimesDir.listFiles().orEmpty()
        .filter { it.isDirectory && it.marker().exists() }
        .mapNotNull { dir ->
            val marker = dir.marker().readText().trim().split('\n').associate { line ->
                val (k, v) = line.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                k to v
            }
            InstalledRuntime(
                component = dir.name,
                majorVersion = marker["major"]?.toIntOrNull() ?: return@mapNotNull null,
                path = dir.absolutePath,
                sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            )
        }
        .sortedByDescending { it.majorVersion }

    /** Resolves what the given game version needs and whether it is present. */
    fun requirement(major: Int, component: String): RuntimeRequirement = RuntimeRequirement(
        majorVersion = major,
        component = component,
        isInstalled = isInstalled(major),
        installedPath = if (isInstalled(major)) runtimeHome(major).absolutePath else "",
    )

    /** Download + extract the runtime for [major]. Safe to call again after a failure. */
    suspend fun install(major: Int): InstallState {
        val target = runtimeHome(major)
        if (isInstalled(major)) {
            val ready = InstallState.Ready(readMarker(target, major))
            _state.value = ready
            return ready
        }
        val url = downloadUrl(major) ?: return InstallState.Failed(
            "Java $major is not available for ${arch.abiName}",
        ).also { _state.value = it }

        return try {
            target.deleteRecursively()
            target.mkdirs()
            val archive = File(runtimesDir, "$major.tar.xz")
            if (archive.exists()) archive.delete()

            _state.value = InstallState.Downloading(0f)
            val downloader = ParallelDownloader()
            // Mirror the downloader's coarse progress into our own state while it runs.
            coroutineScope {
                val progressJob = launch {
                    downloader.progress.collect { p -> _state.value = InstallState.Downloading(p.fraction) }
                }
                try {
                    downloader.downloadAll(listOf(ParallelDownloader.Task(url, archive, label = "openjdk-$major")))
                } finally {
                    progressJob.cancel()
                }
            }

            _state.value = InstallState.Extracting
            withContext(Dispatchers.IO) { extractTarXz(archive, target) }
            archive.delete()

            // Some builds wrap the launcher binary as `bin/java.orig` behind a shell script.
            if (!hasJavaBinary(target)) flattenForTest(target)
            if (!hasJavaBinary(target)) {
                throw IOException("Archive did not contain bin/java")
            }
            writeMarker(target, major)
            val ready = InstallState.Ready(readMarker(target, major))
            _state.value = ready
            IbbLog.i(TAG, "Java $major installed at ${target.absolutePath}")
            ready
        } catch (t: Throwable) {
            IbbLog.e(TAG, "Failed to install Java $major", t)
            target.deleteRecursively()
            val failed = InstallState.Failed("Java $major could not be installed: ${t.message}", t)
            _state.value = failed
            failed
        }
    }

    fun uninstall(major: Int): Boolean = runtimeHome(major).deleteRecursively()

    /** Release URLs published by the PojavLauncher team's OpenJDK build project. */
    fun downloadUrl(major: Int): String? {
        // x86 (32-bit) Java 21+ builds do not exist upstream.
        if (arch == AndroidArch.X86 && major >= 21) return null
        return "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/" +
            "download_jre$major/jre$major-android-${arch.downloadToken}.tar.xz"
    }

    /**
     * Streams a `.tar.xz` into [target]. Tar is parsed by hand (250 KB of code saved vs. Apache
     * Commons Compress) — the format is simple: 512-byte headers with `ustar` magic.
     */
    internal fun extractTarXz(archive: File, target: File) {
        archive.inputStream().buffered(1 shl 16).use { raw ->
            XZInputStream(raw).use { xz -> untar(xz, target) }
        }
    }

    private fun untar(input: InputStream, target: File) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            val read = input.readFully(header)
            if (read < 512 || header.all { it == 0.toByte() }) break

            val name = longName ?: readString(header, 0, 100)
            longName = null
            val prefix = if (isUstar(header)) readString(header, 345, 155) else ""
            val size = readOctal(header, 124, 12)
            val type = header[156].toInt().toChar()

            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val outFile = File(target, fullName).canonicalFile
            // Zip-Slip guard: never write outside the target directory.
            if (!outFile.path.startsWith(target.canonicalFile.path + File.separator)) {
                throw IOException("Blocked unsafe archive entry: $fullName")
            }

            when (type) {
                'L' -> { // GNU long name
                    val data = ByteArray(size.toInt())
                    input.readFully(data)
                    longName = data.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
                    skipPadding(input, size)
                }
                '5' -> {
                    outFile.mkdirs()
                    skipPadding(input, size)
                }
                '0', '\u0000' -> {
                    outFile.parentFile?.mkdirs()
                    java.io.FileOutputStream(outFile).use { out -> copyExactly(input, out, size) }
                    skipPadding(input, size)
                    // Preserve the executable bit for bin/* entries.
                    if (fullName.startsWith("bin/") || fullName.contains("/lib/")) {
                        outFile.setExecutable(true, false)
                    }
                }
                '2' -> { // symlink
                    val data = ByteArray(size.toInt())
                    input.readFully(data)
                    val linkTarget = data.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
                    createSymlink(outFile, linkTarget)
                    skipPadding(input, size)
                }
                else -> skipBytes(input, size + padding(size))
            }
        }
    }

    private fun createSymlink(link: File, target: String) {
        link.parentFile?.mkdirs()
        val resolved = if (target.startsWith("/")) File(target)
        else File(link.parentFile, target).canonicalFile
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), resolved.toPath())
            return
        } catch (e: IOException) {
            IbbLog.w(TAG, "Symlink ${link.name} -> $target failed, copying instead", e)
        } catch (e: UnsupportedOperationException) {
            IbbLog.w(TAG, "Symlinks unsupported on this filesystem", e)
        }
        // Android's app-private storage normally allows symlinks, but FAT-formatted external
        // storage does not. Copying the target keeps the runtime usable either way — every symlink
        // in an OpenJDK tarball points at a real file inside the same archive.
        runCatching {
            if (resolved.isFile && resolved.canonicalPath.startsWith(this.rootDir.canonicalPath)) {
                resolved.copyTo(link, overwrite = true)
                link.setExecutable(resolved.canExecute(), false)
            }
        }.onFailure { IbbLog.w(TAG, "Could not materialise ${link.name} -> $target", it) }
    }

    private fun isUstar(header: ByteArray): Boolean =
        String(header, 257, 5, Charsets.US_ASCII) == "ustar"

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: (offset + length)
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        // GNU base-256 encoding for files >8GB — not needed here, but handle the leading flag.
        if (header[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in offset + 1 until offset + length) {
                value = (value shl 8) or (header[i].toLong() and 0xFF)
            }
            return value
        }
        val text = String(header, offset, length, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        return text.toLongOrNull(8) ?: 0L
    }

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) return total
            total += read
        }
        return total
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(1 shl 16)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Unexpected end of archive")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun padding(size: Long): Long = (512 - (size % 512)) % 512

    private fun skipPadding(input: InputStream, size: Long) = skipBytes(input, padding(size))

    private fun skipBytes(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun File.marker() = File(this, MARKER)

    private fun writeMarker(dir: File, major: Int) {
        dir.marker().writeText("major=$major\narch=${arch.abiName}\ninstalledAt=${System.currentTimeMillis()}\n")
    }

    private fun readMarker(dir: File, major: Int): InstalledRuntime = InstalledRuntime(
        component = dir.name,
        majorVersion = major,
        path = dir.absolutePath,
        sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
    )

    private companion object {
        const val TAG = "RuntimeManager"
        const val MARKER = ".ibb-runtime"
    }

    /** True when [dir] (or a nested layout already flattened) holds the launcher binary. */
    internal fun hasJavaBinary(dir: File): Boolean =
        File(dir, "bin/java").exists() || File(dir, "bin/java.orig").exists()

    /**
     * Moves the contents of a single nested directory up one level.
     *
     * Archive layouts differ between JDK builds: some extract `bin/`, `lib/`, `conf/` at the root,
     * others wrap them in one directory. Both end up usable; the second is flattened here so the
     * rest of the launcher can assume [runtimeHome] holds `bin/java` directly.
     */
    /**
     * Some archives nest everything under a single top-level directory (`jre17/`, `jdk-17.0.9/`).
     * If exactly one such directory holds the launcher binary, treat it as the runtime root.
     */
    internal fun flattenForTest(root: File) {
        val candidates = root.listFiles()
            ?.filter { it.isDirectory }
            ?.filter { hasJavaBinary(it) }
            .orEmpty()
        if (candidates.size != 1) return
        IbbLog.i(TAG, "Runtime is nested in ${candidates.single().name}; flattening")
        flattenIntoRoot(candidates.single(), root)
    }

    internal fun flattenIntoRoot(nested: File, root: File) {
        for (child in nested.listFiles().orEmpty()) {
            val destination = File(root, child.name)
            if (destination.exists()) destination.deleteRecursively()
            if (!child.renameTo(destination)) {
                child.copyRecursively(destination, overwrite = true)
                child.deleteRecursively()
            }
        }
        nested.deleteRecursively()
    }

}
