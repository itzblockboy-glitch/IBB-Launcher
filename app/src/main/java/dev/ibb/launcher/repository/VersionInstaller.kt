package dev.ibb.launcher.repository

import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.model.VersionDetail
import dev.ibb.launcher.data.model.VersionSummary
import dev.ibb.launcher.data.network.MojangApi
import dev.ibb.launcher.download.ParallelDownloader
import dev.ibb.launcher.game.VersionResolver
import dev.ibb.launcher.mods.LoaderRepository
import dev.ibb.launcher.mods.ModLoaderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Installs a game version end-to-end:
 *
 * ```
 * manifest → version json → libraries (+natives) → client jar → asset index → assets
 *          → optional loader profile → per-instance launcher_profiles.json
 * ```
 *
 * Everything is idempotent: an interrupted install resumes where it stopped because the
 * downloader verifies checksums and skips complete files. Re-running for an installed version
 * is a no-op (fast path used by the "Installed" badge).
 */
class VersionInstaller(
    private val api: MojangApi,
    private val loaders: LoaderRepository,
    private val gameRoot: File,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {

    private val librariesDir = File(gameRoot, "libraries").apply { mkdirs() }
    private val assetsDir = File(gameRoot, "assets").apply { mkdirs() }
    private val versionsDir = File(gameRoot, "versions").apply { mkdirs() }
    private val indexDir = File(assetsDir, "indexes").apply { mkdirs() }
    private val objectsDir = File(assetsDir, "objects").apply { mkdirs() }

    data class Stage(val name: String, val fraction: Float, val detail: String = "")

    sealed class Result {
        data class Installed(val versionId: String, val detail: VersionDetail) : Result()
        data class Failed(val message: String, val cause: Throwable? = null) : Result()
    }

    private val _stage = MutableStateFlow(Stage("Idle", 0f))
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val _downloadProgress = MutableStateFlow(ParallelDownloader.Progress())
    val downloadProgress: StateFlow<ParallelDownloader.Progress> = _downloadProgress.asStateFlow()

    /**
     * @param loaderKind optional loader to install on top of the vanilla version.
     * @param loaderVersion required when [loaderKind] is not VANILLA.
     */
    suspend fun install(
        summary: VersionSummary,
        loaderKind: ModLoaderKind = ModLoaderKind.VANILLA,
        loaderVersion: String = "",
        useMirror: Boolean = false,
    ): Result = try {
        withContext(Dispatchers.IO) {
            _stage.value = Stage("Reading metadata", 0.02f, summary.id)
            val vanilla = api.versionDetail(summary, useMirror)
            val versionId = if (loaderKind == ModLoaderKind.VANILLA) vanilla.id
            else loaders.profileId(loaderKind, vanilla.id, loaderVersion)

            // 1. Libraries — resolve rules for Android, then download artifacts.
            _stage.value = Stage("Planning libraries", 0.05f)
            val env = VersionResolver.Env(osName = "linux", osArch = "aarch64")
            val libraries = VersionResolver.dedupeLibraries(
                vanilla.libraries.filter { VersionResolver.isAndroidCompatible(it, env) },
            ).also { list -> IbbLog.i(TAG, "Resolved ${list.size} libraries") }

            val libraryTasks = buildList {
                for (library in libraries) {
                    library.downloads?.artifact?.takeIf { it.url.isNotBlank() }?.let { entry ->
                        add(ParallelDownloader.Task(url = entry.url, target = libraryFile(library.name), sha1 = entry.sha1, size = entry.size))
                    }
                }
            }

            // 2. Client jar + log4j config.
            val clientJar = File(versionsDir, "${vanilla.id}/${vanilla.id}.jar").apply { parentFile?.mkdirs() }
            val clientEntry = vanilla.downloads["client"]
            val clientTask = clientEntry?.let {
                ParallelDownloader.Task(it.url, clientJar, it.sha1, it.size, label = "${vanilla.id}.jar")
            }

            val loggingTask = vanilla.logging?.client?.file?.let { file ->
                val target = File(gameRoot, "log4j-rce-patch.xml")
                ParallelDownloader.Task(file.url, target, file.sha1, file.size, label = "log4j config")
            }

            _stage.value = Stage("Downloading libraries", 0.15f)
            val downloader = ParallelDownloader(maxParallel = 4)
            
            coroutineScope {
                val mirrorJob = launch {
                    downloader.progress.collect { p -> _downloadProgress.value = p }
                }
                try {
                    downloader.downloadAll(libraryTasks.filterNotNull())
                    listOfNotNull(clientTask, loggingTask).forEach { task ->
                        downloader.fetch(task) {}
                    }
                } finally {
                    mirrorJob.cancel()
                }
            }

            // 3. Asset index + objects.
            _stage.value = Stage("Fetching asset index", 0.55f)
            val assetIndex = vanilla.assetIndex ?: throw IOException("Version has no asset index")
            val indexFile = File(indexDir, "${assetIndex.id}.json")
            if (!indexFile.exists()) {
                if (!assetIndex.url.isBlank()) {
                    ParallelDownloader.downloadSingle(assetIndex.url, indexFile, assetIndex.sha1)
                }
            }
            val objects = api.assetIndex(assetIndex, useMirror)
            _stage.value = Stage("Downloading assets", 0.6f, "${objects.size} objects")
            val assetTasks = objects.map { (_, obj) ->
                ParallelDownloader.Task(obj.downloadUrl, File(objectsDir, obj.relativePath), obj.hash, obj.size)
            }
            val assetDownloader = ParallelDownloader(maxParallel = 6)
            coroutineScope {
                val mirrorJob = launch {
                    assetDownloader.progress.collect { p ->
                        _downloadProgress.value = p
                        _stage.value = Stage("Downloading assets", 0.6f + 0.35f * p.fraction, p.currentLabel)
                    }
                }
                try {
                    assetDownloader.downloadAll(assetTasks)
                } finally {
                    mirrorJob.cancel()
                }
            }

            // 4. Optional loader profile.
            _stage.value = Stage("Preparing mod loader", 0.97f)
            val finalDetail: VersionDetail = if (loaderKind == ModLoaderKind.VANILLA) {
                vanilla
            } else {
                val profile = loaders.buildProfile(loaderKind, vanilla.id, loaderVersion, vanilla)
                writeVersionJson(profile)
                // Loaders bring their own libraries (ASM, mixin, …) — fetch them too.
                val loaderTasks = profile.libraries
                    .filter { VersionResolver.isAndroidCompatible(it, env) }
                    .mapNotNull { lib ->
                        lib.downloads?.artifact?.takeIf { it.url.isNotBlank() }?.let { entry ->
                            ParallelDownloader.Task(entry.url, libraryFile(lib.name), entry.sha1, entry.size)
                        }
                    }
                ParallelDownloader(maxParallel = 4).downloadAll(loaderTasks)
                profile
            }

            // 5. Per-instance launcher_profiles.json so modded versions appear in-game menus.
            writeLauncherProfiles(versionId)

            _stage.value = Stage("Done", 1f, versionId)
            IbbLog.i(TAG, "Installed $versionId")
            Result.Installed(versionId, finalDetail)
        }
    } catch (t: Throwable) {
        IbbLog.e(TAG, "Install failed for ${summary.id}", t)
        _stage.value = Stage("Failed", 0f, t.message.orEmpty())
        Result.Failed(t.message ?: "Install failed", t)
    }

    /**
     * Fast existence check matching what [install] writes. Loader profiles never carry their own
     * jar, so they are "installed" once their JSON exists and the parent's client jar is present.
     */
    fun isInstalled(versionId: String): Boolean {
        val json = versionJsonFile(versionId)
        if (!json.exists()) return false
        val parent = readVersionJson(versionId)?.inheritsFrom
        return if (parent.isNullOrBlank()) {
            File(versionsDir, "$versionId/$versionId.jar").exists()
        } else {
            File(versionsDir, "$parent/$parent.jar").exists() && versionJsonFile(parent).exists()
        }
    }

    fun versionJsonFile(versionId: String) = File(versionsDir, "$versionId/$versionId.json")

    fun readVersionJson(versionId: String): VersionDetail? = runCatching {
        json.decodeFromString(VersionDetail.serializer(), versionJsonFile(versionId).readText())
    }.getOrNull()

    /** Maven coordinate → `libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar`. */
    fun libraryFile(coordinate: String): File {
        val parts = coordinate.substringBefore('@').split(':')
        require(parts.size >= 3) { "Bad maven coordinate: $coordinate" }
        val (group, artifact, version) = Triple(parts[0], parts[1], parts[2])
        val classifier = parts.getOrNull(3)
        val extension = coordinate.substringAfter('@', "jar")
        val fileName = buildString {
            append(artifact).append('-').append(version)
            if (classifier != null) append('-').append(classifier)
            append('.').append(extension)
        }
        return File(librariesDir, "${group.replace('.', '/')}/$artifact/$version/$fileName")
    }

    private fun writeVersionJson(detail: VersionDetail) {
        val dir = File(versionsDir, detail.id).apply { mkdirs() }
        File(dir, "${detail.id}.json").writeText(json.encodeToString(VersionDetail.serializer(), detail))
    }

    private fun writeLauncherProfiles(versionId: String) {
        val file = File(gameRoot, "launcher_profiles.json")
        val content = """
            {
              "profiles": {
                "$versionId": {
                  "name": "$versionId",
                  "type": "custom",
                  "lastVersionId": "$versionId"
                }
              },
              "selectedProfile": "$versionId",
              "clientToken": "ibb-launcher",
              "launcherVersion": { "name": "IBB", "format": 21 }
            }
        """.trimIndent()
        file.writeText(content)
    }

    /** Full install summary shown in the version card. */
    fun installedSummary(versionId: String): InstalledSummary? {
        val detail = readVersionJson(versionId) ?: return null
        val size = File(versionsDir, versionId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return InstalledSummary(versionId, detail.type, size, detail.ibbLoader)
    }

    data class InstalledSummary(
        val versionId: String,
        val type: String,
        val sizeBytes: Long,
        val loader: String?,
    )

    /**
     * Re-verifies a single critical file (used by "Repair installation" in Settings).
     */
    fun verify(versionId: String): List<String> {
        val problems = mutableListOf<String>()
        val detail = readVersionJson(versionId) ?: return listOf("version json missing")
        val jarId = detail.inheritsFrom ?: versionId
        if (detail.inheritsFrom == null && !File(versionsDir, "$jarId/$jarId.jar").exists()) {
            problems += "client jar missing"
        }
        detail.assetIndex?.let { index ->
            if (!File(indexDir, "${index.id}.json").exists()) problems += "asset index missing"
        }
        return problems
    }

    /** Assets object count, for the storage card. */
    fun assetsSize(): Long = objectsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Libraries size, for the storage card. */
    fun librariesSize(): Long = librariesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val TAG = "VersionInstaller"
    }
}
