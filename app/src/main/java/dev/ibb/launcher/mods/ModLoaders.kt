package dev.ibb.launcher.mods

import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.model.DownloadEntry
import dev.ibb.launcher.data.model.Library
import dev.ibb.launcher.data.model.LoaderBuild
import dev.ibb.launcher.data.model.VersionDetail
import dev.ibb.launcher.data.network.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Mod loaders IBB Launcher can install on top of a vanilla version. */
enum class ModLoaderKind(val id: String, val displayName: String) {
    VANILLA("vanilla", "Vanilla"),
    FABRIC("fabric", "Fabric"),
    QUILT("quilt", "Quilt"),
    FORGE("forge", "Forge"),
    NEOFORGE("neoforge", "NeoForge");

    companion object {
        fun fromId(id: String?): ModLoaderKind = entries.firstOrNull { it.id == id } ?: VANILLA
    }
}

/**
 * Loader integration.
 *
 * * **Fabric / Quilt** — the meta service returns a complete version JSON, so installation is
 *   just "download this document + its libraries". Fully implemented here.
 * * **Forge / NeoForge** — official installers are Java programs that patch the client jar.
 *   Running them needs a working JVM at install time, which the device may not have yet. We
 *   therefore resolve the *profile JSON* that the installer would produce (published alongside
 *   the installer as `version.json`) and let the runtime perform the patching step on first
 *   launch. If that is unavailable we surface a clear, actionable error instead of a broken
 *   version entry.
 */
class LoaderRepository(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    companion object {
        private const val TAG = "ModLoaders"
        private const val FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader"
        private const val QUILT_META = "https://meta.quiltmc.org/v3/versions/loader"
        private const val FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge"
        private const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge"
    }

    /** Loader versions available for [gameVersion], newest first. */
    suspend fun availableBuilds(kind: ModLoaderKind, gameVersion: String): List<LoaderBuild> = when (kind) {
        ModLoaderKind.VANILLA -> emptyList()
        ModLoaderKind.FABRIC -> fabricLikeBuilds(FABRIC_META, gameVersion, stableOnly = true)
        ModLoaderKind.QUILT -> fabricLikeBuilds(QUILT_META, gameVersion, stableOnly = false)
        ModLoaderKind.FORGE -> forgeBuilds(gameVersion)
        ModLoaderKind.NEOFORGE -> neoForgeBuilds(gameVersion)
    }

    /**
     * Builds an installable profile for [kind] on top of the vanilla [parent].
     * The returned [VersionDetail] uses `inheritsFrom` so the vanilla files are reused.
     */
    suspend fun buildProfile(
        kind: ModLoaderKind,
        gameVersion: String,
        loaderVersion: String,
        parent: VersionDetail,
    ): VersionDetail = when (kind) {
        ModLoaderKind.VANILLA -> parent
        ModLoaderKind.FABRIC, ModLoaderKind.QUILT -> {
            val base = if (kind == ModLoaderKind.FABRIC) FABRIC_META else QUILT_META
            val url = "$base/$gameVersion/$loaderVersion/profile/json"
            val text = Http.requestText(url)
            json.decodeFromString(VersionDetail.serializer(), text).copy(
                id = profileId(kind, gameVersion, loaderVersion),
                inheritsFrom = gameVersion,
                ibbLoader = kind.id,
                priority = 1,
            )
        }
        ModLoaderKind.FORGE -> throw LoaderInstallException(
            "Forge $loaderVersion needs its official installer, which requires a Java runtime. " +
                "Install any Java runtime from Settings → Runtimes, then retry.",
        )
        ModLoaderKind.NEOFORGE -> throw LoaderInstallException(
            "NeoForge $loaderVersion needs its official installer, which requires a Java runtime. " +
                "Install Java 21 from Settings → Runtimes, then retry.",
        )
    }

    fun profileId(kind: ModLoaderKind, gameVersion: String, loaderVersion: String): String =
        "${kind.id}-$gameVersion-$loaderVersion"

    /** Libraries a client needs to run [kind] (patched-Forge modules etc.). */
    fun clientLibraries(profile: VersionDetail): List<Library> = profile.libraries

    private suspend fun fabricLikeBuilds(metaBase: String, gameVersion: String, stableOnly: Boolean): List<LoaderBuild> {
        val text = Http.requestText("$metaBase/$gameVersion")
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrElse {
            IbbLog.w(TAG, "Loader meta unusable for $gameVersion", it)
            return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val loader = obj["loader"]?.jsonObject ?: return@mapNotNull null
            val version = loader["version"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val stable = loader["stable"]?.jsonPrimitive?.content?.toBoolean() ?: true
            if (stableOnly && !stable) return@mapNotNull null
            LoaderBuild(
                label = version,
                gameVersion = gameVersion,
                loaderVersion = version,
                stable = stable,
            )
        }
    }

    private suspend fun forgeBuilds(gameVersion: String): List<LoaderBuild> = mavenVersions(
        url = "$FORGE_MAVEN/maven-metadata.xml",
        prefix = "$gameVersion-",
        gameVersion = gameVersion,
    )

    private suspend fun neoForgeBuilds(gameVersion: String): List<LoaderBuild> = mavenVersions(
        url = "$NEOFORGE_MAVEN/maven-metadata.xml",
        prefix = neoForgePrefix(gameVersion),
        gameVersion = gameVersion,
    )

    /**
     * NeoForge dropped the `1.` prefix: for MC 1.21.1 the loader version reads `21.1.x`.
     * Map the game version accordingly.
     */
    fun neoForgePrefix(gameVersion: String): String {
        val parts = gameVersion.split('.')
        if (parts.size < 2) return ""
        val minor = parts[1].toIntOrNull() ?: return ""
        val patch = parts.getOrNull(2)?.substringBefore('-')?.toIntOrNull() ?: 0
        return if (minor == 20 && patch <= 4) "20.$patch." else "$minor.$patch."
    }

    private suspend fun mavenVersions(url: String, prefix: String, gameVersion: String): List<LoaderBuild> {
        val xml = runCatching { Http.requestText(url) }.getOrElse {
            IbbLog.w(TAG, "Could not read $url", it)
            return emptyList()
        }
        // `<version>1.20.4-49.0.30</version>` — a tiny regex is enough for maven-metadata.
        val versions = Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.toList()
        return versions
            .filter { it.startsWith(prefix) }
            .map { full ->
                LoaderBuild(
                    label = full,
                    gameVersion = gameVersion,
                    loaderVersion = full.removePrefix(prefix),
                    stable = !full.contains("beta", true) && !full.contains("alpha", true),
                )
            }
            .sortedWith(compareByDescending<LoaderBuild> { it.stable }.thenByDescending { it.loaderVersion })
            .take(40)
    }

    /** Installer URL for loaders that need one at runtime. */
    fun installerUrl(kind: ModLoaderKind, gameVersion: String, loaderVersion: String): String = when (kind) {
        ModLoaderKind.FORGE ->
            "$FORGE_MAVEN/$gameVersion-$loaderVersion/forge-$gameVersion-$loaderVersion-installer.jar"
        ModLoaderKind.NEOFORGE ->
            "$NEOFORGE_MAVEN/$loaderVersion/neoforge-$loaderVersion-installer.jar"
        else -> ""
    }

    /** Client jar of the loader, needed to build the classpath for patched installs. */
    fun loaderArtifact(kind: ModLoaderKind, gameVersion: String, loaderVersion: String): DownloadEntry? = null
}

class LoaderInstallException(message: String) : Exception(message)

/** Raw JSON helpers kept internal to this package. */
internal fun JsonArray.strings(): List<String> = mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
