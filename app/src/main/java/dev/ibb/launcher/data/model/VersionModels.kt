package dev.ibb.launcher.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Models for Mojang's `version_manifest_v2.json`.
 * Reference: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
 */
@Serializable
data class VersionManifest(
    val latest: LatestVersions = LatestVersions(),
    val versions: List<VersionSummary> = emptyList(),
)

@Serializable
data class LatestVersions(
    val release: String = "",
    val snapshot: String = "",
)

@Serializable
data class VersionSummary(
    val id: String,
    val type: String,
    val url: String,
    @SerialName("time") val published: String = "",
    @SerialName("releaseTime") val released: String = "",
    val sha1: String = "",
    val complianceLevel: Int = 0,
) {
    val isRelease: Boolean get() = type == "release"
    val isSnapshot: Boolean get() = type == "snapshot"
    val isOldBetaAlpha: Boolean get() = type.startsWith("old_")

    /** 1.21.4 -> (1, 21) so the UI can group and sort by major version. */
    fun majorSeries(): Int {
        val digits = id.removePrefix("1.").takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}

/** Top-level `[version].json` document (one per game version). */
@Serializable
data class VersionDetail(
    val id: String,
    val type: String = "release",
    @SerialName("mainClass") val mainClass: String,
    val inheritsFrom: String? = null,
    val assets: String = "legacy",
    @SerialName("assetIndex") val assetIndex: AssetIndex? = null,
    val downloads: Map<String, DownloadEntry> = emptyMap(),
    val libraries: List<Library> = emptyList(),
    @SerialName("javaVersion") val javaVersion: JavaVersion? = null,
    val logging: LoggingConfig? = null,
    /** Only present on mod-loader profiles we generate ourselves. */
    val ibbLoader: String? = null,
    val priority: Int = 0,
)

@Serializable
data class AssetIndex(
    val id: String,
    val sha1: String = "",
    val size: Long = 0,
    val totalSize: Long = 0,
    val url: String,
)

@Serializable
data class DownloadEntry(
    val sha1: String = "",
    val size: Long = 0,
    val url: String = "",
)

@Serializable
data class JavaVersion(
    val component: String = "jre-legacy",
    @SerialName("majorVersion") val majorVersion: Int = 8,
)

@Serializable
data class LoggingConfig(
    val client: LoggingClient? = null,
) {
    @Serializable
    data class LoggingClient(val argument: String = "", val file: LoggingFile? = null)
    @Serializable
    data class LoggingFile(val id: String = "", val sha1: String = "", val size: Long = 0, val url: String = "")
}

@Serializable
data class Library(
    val name: String,
    val downloads: LibraryDownloads? = null,
    val natives: Map<String, String>? = null,
    /** Only present on older (pre-1.13) versions. */
    val extract: Extract? = null,
    val rules: List<Rule> = emptyList(),
) {
    /** Maven coordinate: `org.lwjgl:lwjgl:3.3.3` -> ("org.lwjgl", "lwjgl", "3.3.3"). */
    fun coordinate(): Triple<String, String, String>? {
        val parts = name.split('@').first().split(':')
        if (parts.size < 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    val isNativeClassifier: Boolean
        get() = name.substringAfterLast(':').contains("natives")
}

@Serializable
data class LibraryDownloads(
    val artifact: DownloadEntry? = null,
    val classifiers: Map<String, DownloadEntry> = emptyMap(),
)

@Serializable
data class Extract(val exclude: List<String> = emptyList())

@Serializable
data class Rule(
    val action: String,
    val os: OsRule? = null,
    val features: Map<String, Boolean>? = null,
) {
    @Serializable
    data class OsRule(val name: String? = null, val version: String? = null, val arch: String? = null)
}

/** Response of `/version/{id}/json` style metadata for the mod loaders we support. */
@Serializable
data class LoaderBuild(
    val label: String,
    val gameVersion: String,
    val loaderVersion: String,
    val stable: Boolean = true,
    val downloadUrl: String = "",
)
