package dev.ibb.launcher

import dev.ibb.launcher.data.model.Account
import dev.ibb.launcher.data.model.AccountType
import dev.ibb.launcher.data.model.MinecraftProfile
import dev.ibb.launcher.data.model.MsaDeviceCode
import dev.ibb.launcher.data.model.MsaTokenResponse
import dev.ibb.launcher.data.model.XboxAuthResponse
import dev.ibb.launcher.data.model.XstsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * NOTE: these tests need the kotlinx.serialization compiler plugin (they are skipped by the
 * offline compile harness, which cannot run the plugin against stub libraries) but run normally
 * under `./gradlew test`.
 *
 * Microsoft / Xbox responses are heavily nested and change wording often (the XSTS error codes in
 * particular). These tests replay realistic payloads so a refactor of the DTOs cannot silently
 * break sign-in — and they run offline, without touching Microsoft's servers.
 */
class AuthParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // ------------------------------------------------------------------ device code

    @Test
    fun `device code response is parsed`() {
        val payload = """
            {
              "user_code": "ABCD1234",
              "device_code": "long-opaque-device-code",
              "verification_uri": "https://microsoft.com/link",
              "expires_in": 900,
              "interval": 5,
              "message": "To sign in, use a web browser to open https://microsoft.com/link and enter ABCD1234."
            }
        """.trimIndent()
        val code = json.decodeFromString(MsaDeviceCode.serializer(), payload)
        assertEquals("ABCD1234", code.userCode)
        assertEquals(900L, code.expiresIn)
        assertEquals(5L, code.interval)
        assertEquals("https://microsoft.com/link", code.verificationUri)
    }

    @Test
    fun `device code survives missing optional fields`() {
        val payload = """{"user_code":"X","device_code":"Y","verification_uri":"https://microsoft.com/link"}"""
        val code = json.decodeFromString(MsaDeviceCode.serializer(), payload)
        assertTrue(code.expiresIn > 0)
        assertTrue(code.interval >= 1)
    }

    // ------------------------------------------------------------------ MSA tokens

    @Test
    fun `token response keeps the refresh token and expiry`() {
        val payload = """
            {"token_type":"Bearer","scope":"XboxLive.signin","expires_in":3600,
             "access_token":"eyJhbGciOi...","refresh_token":"M.R3_BAY...","user_id":"abc"}
        """.trimIndent()
        val token = json.decodeFromString(MsaTokenResponse.serializer(), payload)
        assertEquals(3600L, token.expiresIn)
        assertEquals("M.R3_BAY...", token.refreshToken)
        assertTrue(token.accessToken.isNotBlank())
    }

    // ------------------------------------------------------------------ Xbox / XSTS

    @Test
    fun `xbox live token response exposes token and user hash`() {
        val payload = """
            {"Token":"eyJ0eXAi...","DisplayClaims":{"xui":[{"uhs":"1234567890"}]}}
        """.trimIndent()
        val xbl = json.decodeFromString(XboxAuthResponse.serializer(), payload)
        assertEquals("1234567890", xbl.displayClaims.xui.first().uhs)
        assertTrue(xbl.token.isNotBlank())
    }

    @Test
    fun `xsts claims expose the user hash list`() {
        val payload = """
            {"IssueInstant":"2024-01-01T00:00:00Z","NotAfter":"2024-01-02T00:00:00Z",
             "Token":"xsts","DisplayClaims":{"xui":[{"uhs":"1234567890"}]}}
        """.trimIndent()
        val xsts = json.decodeFromString(XstsResponse.serializer(), payload)
        assertEquals("1234567890", xsts.displayClaims.xui.first().uhs)
    }

    @Test
    fun `xsts error payload is json not a crash`() {
        // Microsoft returns 401 with this body for accounts without an Xbox profile.
        val payload = """{"Identity":"0","XErr":2148916233,"Message":"","Redirect":"https://start.ui.xboxlive.com/AddChildToFamily"}"""
        val parsed = json.parseToJsonElement(payload)
        assertTrue(parsed.toString().contains("2148916233"))
    }

    // ------------------------------------------------------------------ account model

    @Test
    fun `offline accounts derive uuid v3-style from the name`() {
        val first = Account(username = "Steve", type = AccountType.OFFLINE)
        val second = Account(username = "Steve", type = AccountType.OFFLINE)
        val other = Account(username = "Alex", type = AccountType.OFFLINE)
        assertEquals(32, first.uuid.length)
        assertFalse(first.uuid.contains('-'))
        assertEquals(first.uuid, second.uuid)
        assertTrue(first.uuid != other.uuid)
    }

    @Test
    fun `online account expiry is respected`() {
        val expired = Account(
            username = "Steve",
            type = AccountType.MICROSOFT,
            accessToken = "token",
            expiresAt = System.currentTimeMillis() / 1000 - 60,
        )
        assertTrue(expired.isExpired)
        assertFalse(expired.isUsable)

        val fresh = expired.copy(expiresAt = System.currentTimeMillis() / 1000 + 3600)
        assertFalse(fresh.isExpired)
        assertTrue(fresh.isUsable)
    }

    @Test
    fun `offline accounts never expire and are always usable`() {
        val offline = Account(username = "Alex", type = AccountType.OFFLINE, expiresAt = 1)
        assertFalse(offline.isExpired)
        assertTrue(offline.isUsable)
    }

    @Test
    fun `profile id can be normalised back to a uuid`() {
        val profile = MinecraftProfile(id = "069a79f444e94726a5befca90e38aaf5", name = "Steve")
        assertEquals(
            UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
            UUID.fromString(profile.id.replaceFirst(Regex("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})"), "$1-$2-$3-$4-$5")),
        )
    }
}
