package dev.ibb.launcher.data.store

import dev.ibb.launcher.data.model.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Account persistence + active-account selection.
 *
 * The JSON blob is sealed with [SecureStore] (AES-GCM, hardware-backed key) so a rooted device
 * or a leaked backup cannot lift refresh tokens. Only one decryption happens at startup; after
 * that everything is in memory and exposed as a [StateFlow] for Compose.
 */
class AccountStore(
    private val secure: SecureStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val active: Account? get() = _accounts.value.firstOrNull { it.id == _activeId.value }
        ?: _accounts.value.firstOrNull()

    fun load() {
        val raw = secure.get(KEY_ACCOUNTS) ?: return
        val decoded = runCatching {
            json.decodeFromString(AccountList.serializer(), raw)
        }.getOrNull() ?: return
        _accounts.value = decoded.accounts
        _activeId.value = decoded.activeId ?: decoded.accounts.firstOrNull()?.id
    }

    fun upsert(account: Account, makeActive: Boolean = true) {
        val existing = _accounts.value
        val index = existing.indexOfFirst { it.id == account.id }
        val updated = if (index >= 0) {
            existing.toMutableList().also { it[index] = account }
        } else {
            existing + account
        }
        _accounts.value = updated
        if (makeActive) _activeId.value = account.id
        persist()
    }

    fun select(id: String) {
        if (_accounts.value.any { it.id == id }) {
            _activeId.value = id
            _accounts.value = _accounts.value.map {
                if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it
            }
            persist()
        }
    }

    fun remove(id: String) {
        _accounts.value = _accounts.value.filterNot { it.id == id }
        if (_activeId.value == id) _activeId.value = _accounts.value.firstOrNull()?.id
        persist()
    }

    fun signOutAll() {
        _accounts.value = emptyList()
        _activeId.value = null
        secure.remove(KEY_ACCOUNTS)
    }

    /** Replaces tokens after a silent refresh without disturbing list order. */
    fun updateTokens(accountId: String, accessToken: String, refreshToken: String, expiresAt: Long) {
        _accounts.value = _accounts.value.map { account ->
            if (account.id == accountId) {
                account.copy(accessToken = accessToken, refreshToken = refreshToken, expiresAt = expiresAt)
            } else {
                account
            }
        }
        persist()
    }

    private fun persist() {
        val payload = AccountList(_accounts.value, _activeId.value)
        secure.put(KEY_ACCOUNTS, json.encodeToString(AccountList.serializer(), payload))
    }

    @kotlinx.serialization.Serializable
    private data class AccountList(
        val accounts: List<Account> = emptyList(),
        val activeId: String? = null,
    )

    private companion object {
        const val KEY_ACCOUNTS = "accounts_v1"
    }
}
