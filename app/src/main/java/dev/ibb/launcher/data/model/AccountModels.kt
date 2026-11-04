package dev.ibb.launcher.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A launcher account. Microsoft accounts store the full session (tokens live in the encrypted
 * store, never in plain prefs); offline profiles only carry a name + generated UUID.
 */
@Serializable
data class Account(
    val id: String = UUID.randomUUID().toString(),
    val type: AccountType = AccountType.OFFLINE,
    /** Minecraft profile name shown in-game. */
    val username: String,
    /** Undashed UUID used by `--uuid`. */
    val uuid: String = offlineUuid(username),
    /** XUID for online accounts (used by the authlib-injector style launch args). */
    val xuid: String = "",
    /** Skin model, "classic" or "slim". */
    val skinModel: String = "classic",
    @SerialName("addedAt") val addedAt: Long = System.currentTimeMillis(),
    @SerialName("lastUsedAt") val lastUsedAt: Long = 0,
    val accessToken: String = "",
    val refreshToken: String = "",
    /** Unix seconds; 0 for offline accounts. */
    val expiresAt: Long = 0,
) {
    val isOnline: Boolean get() = type == AccountType.MICROSOFT
    val isExpired: Boolean get() = isOnline && expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt
    val isUsable: Boolean get() = type == AccountType.OFFLINE || (accessToken.isNotBlank() && !isExpired)

    companion object {
        /**
         * Offline UUIDs must be stable across launches — servers with `online-mode=false`
         * key player data (and some plugins key permissions) on it. We derive it from the
         * name exactly like the vanilla server does: `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`.
         */
        fun offlineUuid(username: String): String {
            val bytes = "OfflinePlayer:$username".toByteArray(Charsets.UTF_8)
            return UUID.nameUUIDFromBytes(bytes).toString().replace("-", "")
        }
    }
}

@Serializable
enum class AccountType { MICROSOFT, OFFLINE }

/** Shape of `https://api.minecraftservices.com/minecraft/profile`. */
@Serializable
data class MinecraftProfile(
    val id: String = "",
    val name: String = "",
    val skins: List<Skin> = emptyList(),
    val capes: List<Cape> = emptyList(),
) {
    @Serializable
    data class Skin(val id: String = "", val state: String = "", val url: String = "", val variant: String = "")
    @Serializable
    data class Cape(val id: String = "", val state: String = "", val url: String = "", val alias: String = "")
}

/** OAuth token payloads (MSA + Xbox Live + Minecraft services). */
@Serializable
data class MsaTokenResponse(
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val scope: String = "",
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
)

@Serializable
data class XboxAuthResponse(
    @SerialName("IssueInstant") val issueInstant: String = "",
    @SerialName("NotAfter") val notAfter: String = "",
    @SerialName("Token") val token: String = "",
    @SerialName("DisplayClaims") val displayClaims: XboxClaims = XboxClaims(),
) {
    @Serializable
    data class XboxClaims(val xui: List<XboxUserHash> = emptyList())
    @Serializable
    data class XboxUserHash(val uhs: String = "")
}

@Serializable
data class XstsResponse(
    @SerialName("Token") val token: String = "",
    @SerialName("DisplayClaims") val displayClaims: XstsClaims = XstsClaims(),
) {
    @Serializable
    data class XstsClaims(val xui: List<XstsUserHash> = emptyList())
    @Serializable
    data class XstsUserHash(val uhs: String = "")
}

@Serializable
data class MsaDeviceCode(
    @SerialName("user_code") val userCode: String = "",
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val interval: Long = 5,
    val message: String = "",
)

@Serializable
data class MinecraftTokenResponse(
    val username: String = "",
    val roles: List<String> = emptyList(),
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
data class EntitlementsResponse(val items: List<Entitlement> = emptyList()) {
    @Serializable
    data class Entitlement(val name: String = "", val signature: String = "")
}
