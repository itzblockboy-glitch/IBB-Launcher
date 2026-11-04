package dev.ibb.launcher.data.auth

import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.model.Account
import dev.ibb.launcher.data.model.AccountType
import dev.ibb.launcher.data.model.EntitlementsResponse
import dev.ibb.launcher.data.model.MinecraftProfile
import dev.ibb.launcher.data.model.MinecraftTokenResponse
import dev.ibb.launcher.data.model.MsaDeviceCode
import dev.ibb.launcher.data.model.MsaTokenResponse
import dev.ibb.launcher.data.model.XboxAuthResponse
import dev.ibb.launcher.data.model.XstsResponse
import dev.ibb.launcher.data.network.Http
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Microsoft / Xbox Live / Minecraft-services authentication.
 *
 * Flow (all endpoints are public client credentials used by every third-party launcher):
 * ```
 *  device code ─► MSA token ─► XBL user token ─► XSTS token ─► Minecraft access token ─► profile
 * ```
 * We use the **device code** grant: it gives the user a short code to enter on
 * microsoft.com/link, which needs no embedded WebView, keeps the OAuth secret out of the app, and
 * survives 2FA / password-less accounts. The old embedded-flow is intentionally not supported.
 *
 * Tokens are returned as [Tokens] and persisted by [dev.ibb.launcher.data.store.AccountStore]
 * inside an [androidx.security.crypto.EncryptedSharedPreferences]-equivalent store, never in
 * plaintext prefs.
 */
class MicrosoftAuth(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true },
) {

    /** OAuth endpoints. `consumers` = personal accounts, `live` = the MSA token service. */
    private object Endpoints {
        const val DEVICE_CODE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        const val TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        const val XBL_AUTH = "https://user.auth.xboxlive.com/user/authenticate"
        const val XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize"
        const val MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox"
        const val MC_ENTITLEMENTS = "https://api.minecraftservices.com/entitlements/mcstore"
        const val MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile"

        /** Public client id used by launchers (open source, no secret). Override in settings. */
        const val CLIENT_ID = "00000000402b5328"
        const val SCOPE = "XboxLive.signin offline_access"
    }

    sealed class Result {
        data class Success(val account: Account, val profile: MinecraftProfile) : Result()
        data class Failure(val reason: String, val cause: Throwable? = null) : Result()
        /** User must open [MsaDeviceCode.verificationUri] and enter [MsaDeviceCode.userCode]. */
        data class AwaitingUser(val code: MsaDeviceCode) : Result()
        data object Cancelled : Result()
        data object NoMinecraftOwnership : Result()
        data object NoProfileName : Result()
    }

    class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Starts the device-code flow and blocks (suspending) until the user finishes or the code
     * expires. [onCode] is invoked exactly once, as soon as the code is available, so the UI can
     * show it while we poll.
     */
    suspend fun signIn(
        clientId: String = Endpoints.CLIENT_ID,
        onCode: (MsaDeviceCode) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        val deviceCode = try {
            requestDeviceCode(clientId)
        } catch (t: Throwable) {
            IbbLog.e(TAG, "Device code request failed", t)
            return Result.Failure("Could not reach Microsoft sign-in", t)
        }
        onCode(deviceCode)

        val deadline = System.currentTimeMillis() + deviceCode.expiresIn.coerceAtLeast(60) * 1000
        var interval = deviceCode.interval.coerceAtLeast(2)
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return Result.Cancelled
            delay(interval * 1000)
            val poll = try {
                pollToken(clientId, deviceCode.deviceCode)
            } catch (t: Throwable) {
                return Result.Failure("Sign-in polling failed", t)
            }
            when (poll) {
                PollResult.Pending -> Unit // keep polling at the current interval
                is PollResult.PendingWithDelay -> interval = poll.retryAfter.coerceAtLeast(2L)
                is PollResult.Failed -> return Result.Failure(poll.reason, poll.cause)
                is PollResult.Tokens -> return finishSignIn(poll.msa)
            }
        }
        return Result.Failure("The sign-in code expired — please try again")
    }

    /** Uses a stored refresh token; runs silently on app start to keep sessions fresh. */
    suspend fun refresh(refreshToken: String, clientId: String = Endpoints.CLIENT_ID): Result = try {
        val msa = refreshMsaToken(refreshToken, clientId)
        finishSignIn(msa)
    } catch (t: Throwable) {
        IbbLog.w(TAG, "Silent refresh failed", t)
        Result.Failure("Session expired — sign in again", t)
    }

    // ---------------------------------------------------------------------------------------
    // Steps
    // ---------------------------------------------------------------------------------------

    private suspend fun requestDeviceCode(clientId: String): MsaDeviceCode {
        val body = "client_id=$clientId&scope=${Endpoints.SCOPE.replace(" ", "%20")}"
        val text = Http.requestText(
            Endpoints.DEVICE_CODE,
            method = "POST",
            body = body,
            contentType = "application/x-www-form-urlencoded",
        )
        return json.decodeFromString(MsaDeviceCode.serializer(), text)
    }

    private sealed class PollResult {
        // Note: `Pending` carries no data; `PendingWithDelay` is returned for OAuth `slow_down`.
        data object Pending : PollResult()
        data class PendingWithDelay(val retryAfter: Long) : PollResult()
        data class Tokens(val msa: MsaTokenResponse) : PollResult()
        data class Failed(val reason: String, val cause: Throwable? = null) : PollResult()
    }

    private suspend fun pollToken(clientId: String, deviceCode: String): PollResult {
        val body = "client_id=$clientId&grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=$deviceCode"
        return try {
            val text = Http.requestText(
                Endpoints.TOKEN,
                method = "POST",
                body = body,
                contentType = "application/x-www-form-urlencoded",
            )
            PollResult.Tokens(json.decodeFromString(MsaTokenResponse.serializer(), text))
        } catch (e: Http.HttpException) {
            when {
                e.body.contains("authorization_pending") -> PollResult.Pending
                e.body.contains("slow_down") -> PollResult.PendingWithDelay(8)
                e.body.contains("authorization_declined") -> PollResult.Failed("Sign-in was declined on the Microsoft page")
                e.body.contains("expired_token") -> PollResult.Failed("The sign-in code expired")
                else -> PollResult.Failed("Microsoft returned an error (HTTP ${e.code})", e)
            }
        }
    }

    private suspend fun refreshMsaToken(refreshToken: String, clientId: String): MsaTokenResponse {
        val body = "client_id=$clientId&grant_type=refresh_token&refresh_token=$refreshToken&scope=${Endpoints.SCOPE.replace(" ", "%20")}"
        val text = Http.requestText(
            Endpoints.TOKEN,
            method = "POST",
            body = body,
            contentType = "application/x-www-form-urlencoded",
        )
        val msa = json.decodeFromString(MsaTokenResponse.serializer(), text)
        // Microsoft rotates refresh tokens; keep the old one only if the server didn't send a new one.
        return if (msa.refreshToken.isBlank()) msa.copy(refreshToken = refreshToken) else msa
    }

    private suspend fun finishSignIn(msa: MsaTokenResponse): Result = try {
        val xbl = authenticateXboxLive(msa.accessToken)
        val xsts = authorizeXsts(xbl.token)
        val mcToken = loginWithXbox(xsts)
        ensureOwnership(mcToken.accessToken)
        val profile = fetchProfile(mcToken.accessToken)
        if (profile.name.isBlank()) return Result.NoProfileName
        val account = Account(
            type = AccountType.MICROSOFT,
            username = profile.name,
            uuid = profile.id,
            xuid = xsts.displayClaims.xui.firstOrNull()?.uhs.orEmpty(),
            skinModel = profile.skins.firstOrNull { it.state == "ACTIVE" }?.variant?.lowercase() ?: "classic",
            accessToken = mcToken.accessToken,
            refreshToken = msa.refreshToken,
            expiresAt = Instant.now().epochSecond + mcToken.expiresIn.coerceAtLeast(3600),
            lastUsedAt = System.currentTimeMillis(),
        )
        IbbLog.i(TAG, "Signed in as ${account.username}")
        Result.Success(account, profile)
    } catch (t: Throwable) {
        IbbLog.e(TAG, "Minecraft sign-in failed", t)
        Result.Failure(t.message ?: "Sign-in failed", t)
    }

    private suspend fun authenticateXboxLive(msaAccessToken: String): XboxAuthResponse {
        val payload = """
            {
              "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": "d=$msaAccessToken"
              },
              "RelyingParty": "http://auth.xboxlive.com",
              "TokenType": "JWT"
            }
        """.trimIndent()
        val text = Http.requestText(
            Endpoints.XBL_AUTH,
            method = "POST",
            body = payload,
            headers = mapOf("Accept" to "application/json", "x-xbl-contract-version" to "1"),
        )
        return json.decodeFromString(XboxAuthResponse.serializer(), text)
    }

    private suspend fun authorizeXsts(xblToken: String): XstsResponse {
        val payload = """
            {
              "Properties": { "SandboxId": "RETAIL", "UserTokens": ["$xblToken"] },
              "RelyingParty": "rp://api.minecraftservices.com/",
              "TokenType": "JWT"
            }
        """.trimIndent()
        return try {
            val text = Http.requestText(
                Endpoints.XSTS_AUTH,
                method = "POST",
                body = payload,
                headers = mapOf("Accept" to "application/json", "x-xbl-contract-version" to "1"),
            )
            json.decodeFromString(XstsResponse.serializer(), text)
        } catch (e: Http.HttpException) {
            // XSTS returns a structured error for child accounts / no-Xbox-profile cases.
            throw AuthException(describeXstsError(e.body), e)
        }
    }

    private fun describeXstsError(body: String): String = runCatching {
        // The XSTS error payload is small and stable; a regex avoids depending on JSON tree APIs.
        val xerr = Regex("\"XErr\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
        when (xerr) {
            2148916233L -> "This Microsoft account has no Xbox profile. Create one at xbox.com, then retry."
            2148916235L -> "Xbox Live is not available in this account's country."
            2148916236L, 2148916237L -> "This account needs adult verification on xbox.com."
            2148916238L -> "This is a child account — an adult must add it to a Microsoft family."
            else -> "Xbox Live authorisation failed (XErr=$xerr)."
        }
    }.getOrElse { "Xbox Live authorisation failed." }

    private suspend fun loginWithXbox(xsts: XstsResponse): MinecraftTokenResponse {
        val uhs = xsts.displayClaims.xui.firstOrNull()?.uhs
            ?: throw AuthException("Xbox did not return a user hash")
        val payload = """{"identityToken":"XBL3.0 x=$uhs;${xsts.token}"}"""
        val text = Http.requestText(
            Endpoints.MC_LOGIN,
            method = "POST",
            body = payload,
            headers = mapOf("Accept" to "application/json"),
        )
        return json.decodeFromString(MinecraftTokenResponse.serializer(), text)
    }

    private suspend fun ensureOwnership(accessToken: String) {
        val text = Http.requestText(
            Endpoints.MC_ENTITLEMENTS,
            headers = mapOf("Authorization" to "Bearer $accessToken"),
        )
        val entitlements = runCatching {
            json.decodeFromString(EntitlementsResponse.serializer(), text)
        }.getOrNull() ?: return
        val owns = entitlements.items.any { it.name == "product_minecraft" || it.name == "game_minecraft" }
        if (!owns) throw AuthException("This account does not own Minecraft: Java Edition")
    }

    suspend fun fetchProfile(accessToken: String): MinecraftProfile {
        val text = Http.requestText(
            Endpoints.MC_PROFILE,
            headers = mapOf("Authorization" to "Bearer $accessToken"),
        )
        return json.decodeFromString(MinecraftProfile.serializer(), text)
    }

    private companion object {
        const val TAG = "MicrosoftAuth"
    }
}
