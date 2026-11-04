package dev.ibb.launcher.data.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.ibb.launcher.core.IbbLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption backed by the Android Keystore.
 *
 * Used to seal Microsoft refresh tokens, access tokens and the offline profile database.
 * Why not `EncryptedSharedPreferences`? That library is deprecated and drags in Tink (~1.2 MB);
 * a hardware-backed key plus GCM gives the same guarantee for our small payloads with far less
 * code. Ciphertext format: `base64(iv || ciphertext)`.
 *
 * Key material never leaves the TEE/StrongBox. If the keystore entry is invalidated (device
 * restore, lock-screen reset) [decrypt] returns null and callers re-prompt for sign-in rather
 * than crashing.
 */
class SecureStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(key: String, value: String) {
        val sealed = encrypt(value) ?: return
        prefs.edit().putString(key, sealed).apply()
    }

    fun get(key: String): String? {
        val sealed = prefs.getString(key, null) ?: return null
        return decrypt(sealed)
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun secretKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateKey()
    }.onFailure { IbbLog.e(TAG, "Keystore unavailable", it) }.getOrNull()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-authentication requirement: the launcher must refresh tokens while the
                // screen is off (game running). The key is still non-exportable and hardware-backed.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        val key = generator.generateKey()
        IbbLog.d(TAG, "Generated launcher data key")
        return key
    }

    private fun encrypt(plain: String): String? = runCatching {
        val key = secretKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }.onFailure { IbbLog.e(TAG, "Encrypt failed", it) }.getOrNull()

    private fun decrypt(encoded: String): String? = runCatching {
        val key = secretKey() ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size <= IV_LENGTH) return null
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(ciphertext).decodeToString()
    }.onFailure { IbbLog.w(TAG, "Decrypt failed (key invalidated?)", it) }.getOrNull()

    private companion object {
        const val TAG = "SecureStore"
        const val PREFS = "ibb_secure"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ibb_launcher_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
