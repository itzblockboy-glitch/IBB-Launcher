package dev.ibb.launcher.data.network

import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.model.AssetIndex
import dev.ibb.launcher.data.model.VersionDetail
import dev.ibb.launcher.data.model.VersionManifest
import dev.ibb.launcher.data.model.VersionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mojang metadata + resources endpoints.
 *
 * Everything is fetched lazily with an in-memory cache: the manifest is the entry point for
 * every install and is revalidated (not re-downloaded) at most once per session.
 */
class MojangApi(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    companion object {
        const val BASE_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val BASE_RESOURCES = "https://resources.download.minecraft.net"
        const val BASE_LIBRARIES = "https://libraries.minecraft.net"
        const val BASE_ASSETS = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        /** Optional China mirror for users behind a slow CDN. */
        const val BASE_BMCLAPI = "https://bmclapi2.bangbang93.com"
        private const val TAG = "MojangApi"
    }

    @Volatile private var cachedManifest: VersionManifest? = null
    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, VersionDetail>()

    /** Full version list (newest first), cached in memory. */
    suspend fun manifest(forceRefresh: Boolean = false, useMirror: Boolean = false): VersionManifest {
        cachedManifest?.takeIf { !forceRefresh }?.let { return it }
        val url = if (useMirror) "$BASE_BMCLAPI/mc/game/version_manifest_v2.json" else BASE_MANIFEST
        IbbLog.d(TAG, "Fetching version manifest")
        val parsed = json.decodeFromString(VersionManifest.serializer(), Http.requestText(url))
        cachedManifest = parsed
        return parsed
    }

    suspend fun latestRelease(): VersionSummary? = manifest().versions.firstOrNull {
        it.id == manifest().latest.release
    } ?: manifest().versions.firstOrNull { it.isRelease }

    /** The per-version document that contains libraries, assets and the main class. */
    suspend fun versionDetail(summary: VersionSummary, useMirror: Boolean = false): VersionDetail {
        detailCache[summary.id]?.let { return it }
        val url = if (useMirror) summary.url.replace("https://piston-meta.mojang.com", BASE_BMCLAPI) else summary.url
        val parsed = json.decodeFromString(VersionDetail.serializer(), Http.requestText(url))
        detailCache[summary.id] = parsed
        return parsed
    }

    /**
     * The asset index (`assets/indexes/<id>.json`) has one entry per object with the
     * shape `{"objects": {"icons/icon_16x16.png": {"hash": "…", "size": 123}}}`.
     * We only need the hash/size pairs, so parse them into a flat map instead of a full model.
     */
    suspend fun assetIndex(index: AssetIndex, useMirror: Boolean = false): Map<String, AssetObject> {
        val url = if (useMirror) index.url.replace("https://piston-meta.mojang.com", BASE_BMCLAPI) else index.url
        val root = json.parseToJsonElement(Http.requestText(url)).jsonObject
        val objects = root["objects"]?.jsonObject ?: return emptyMap()
        val result = HashMap<String, AssetObject>(objects.size)
        for ((path, element) in objects) {
            val obj = element.jsonObject
            val hash = obj["hash"]?.jsonPrimitive?.content ?: continue
            val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            result[path] = AssetObject(hash = hash, size = size)
        }
        return result
    }

    /** Server list ping-ish: is Mojang reachable right now? Used by the pre-flight check. */
    suspend fun isReachable(): Boolean = runCatching {
        manifest(forceRefresh = true)
        true
    }.getOrElse {
        IbbLog.w(TAG, "Mojang metadata unreachable", it)
        false
    }

    data class AssetObject(val hash: String, val size: Long) {
        /** Assets are content-addressed: `objects/<first 2 chars>/<hash>`. */
        val relativePath: String get() = "${hash.take(2)}/$hash"
        val downloadUrl: String get() = "$BASE_RESOURCES/$relativePath"
    }
}

/**
 * Utility for reading arbitrary JSON trees (used by the Forge/NeoForge installers whose formats
 * vary between versions). Kept here so the rest of the code base never touches raw JSON.
 */
internal fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

internal fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement>? =
    runCatching { this[key]?.jsonArray?.toList() }.getOrNull()
