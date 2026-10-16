package app.sleevesight.app.secrets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Base64

/** The keys the user enters: the AI service's and the keyed sources'. */
enum class SecretId(
    internal val storageName: String,
) {
    /** Anthropic's API key, for the AI service. */
    ANTHROPIC("ai.anthropic"),

    /** The user's Last.fm API key (design, *Source adapters*). */
    LASTFM("source.lastfm"),

    /** The web search provider's key: Exa, the first (D11). */
    EXA("source.exa"),
    ;

    companion object {
        /** The key [provider] needs, or null for one with no client yet, or none needed (Ollama). */
        fun forProvider(provider: ProviderId): SecretId? =
            when (provider) {
                ProviderId.ANTHROPIC -> ANTHROPIC
                ProviderId.OPENAI, ProviderId.GOOGLE, ProviderId.OLLAMA -> null
            }
    }
}

/** What the settings screen may know of a stored key: never its value. */
enum class SecretState {
    ABSENT,
    SAVED,

    /**
     * A value is stored but cannot be opened: the Keystore key that sealed it is gone (a restore
     * onto another phone, a reset Keystore), the value is damaged, or the key store fails. It reads
     * as no key. Saving it again works when the Keystore key is gone (a new one is made); when the
     * key store keeps failing, the user's *Reset saved keys* ([SecretStore.reset]) is the way out.
     */
    UNREADABLE,
}

/**
 * The user's API keys, each sealed by [cipher] (AES-GCM, with the key in the Android Keystore) and
 * kept in its own DataStore file, apart from the settings (architecture, *Settings and secrets*).
 * A key is handed out only as a [Secret], whose `toString()` is redacted, and nothing here logs.
 *
 * Nothing here deletes a key on its own, the Keystore key included: an entry that cannot be opened is
 * [SecretState.UNREADABLE], [get] returns null for it, and it stays until the user saves a new key,
 * removes it, or resets every saved key ([reset]). A store file DataStore cannot parse is replaced by
 * an empty one (the corruption handler where the store is created), so its keys read as absent.
 *
 * The cipher's work, which goes to the Keystore over binder calls, runs on [dispatcher], never on
 * the caller's (the screen's main thread). Saves, removals and resets run one at a time. One that
 * fails, in the key store or in DataStore, returns false rather than throwing.
 */
class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val data: Flow<Preferences> =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    private val writes = Mutex()

    /**
     * Counts resets, so [states] is read again after one even if removing the entries failed: with
     * the Keystore key deleted, what is left no longer opens.
     */
    private val resets = MutableStateFlow(0)

    /** Each key's state, as the settings screen shows it. */
    val states: Flow<Map<SecretId, SecretState>> =
        combine(data, resets) { prefs, _ -> prefs }
            .map { prefs -> SecretId.entries.associateWith { id -> stateOf(prefs, id) } }
            .flowOn(dispatcher)
            .distinctUntilChanged()

    /** The key [id], or null when none is saved or it cannot be read. */
    suspend fun get(id: SecretId): Secret? {
        val stored = data.first()[keyOf(id)] ?: return null
        return withContext(dispatcher) {
            open(id, stored)?.let { plain ->
                try {
                    Secret(plain.decodeToString())
                } finally {
                    plain.fill(0)
                }
            }
        }
    }

    /**
     * Saves [secret] as the key [id], replacing any before it. False when it could not be sealed
     * (the key store failed) or written (DataStore failed); what was stored before is then kept, and
     * nothing is deleted.
     */
    suspend fun set(
        id: SecretId,
        secret: Secret,
    ): Boolean =
        writes.withLock {
            val sealed =
                withContext(dispatcher) {
                    val plain = secret.reveal().encodeToByteArray()
                    try {
                        cipher.seal(plain, associated(id))
                    } finally {
                        plain.fill(0)
                    }
                }
            sealed != null && write { it[keyOf(id)] = Base64.getEncoder().encodeToString(sealed) }
        }

    /** Removes the key [id]. False when DataStore could not write. */
    suspend fun clear(id: SecretId): Boolean = writes.withLock { write { it.remove(keyOf(id)) } }

    /**
     * *Reset saved keys*: removes every saved key and deletes the Keystore key, so the next save makes
     * a new one. The way out when the key store cannot read the saved keys or seal new ones. False
     * when either step failed; the other is still done.
     */
    suspend fun reset(): Boolean =
        writes.withLock {
            try {
                val removed = write { prefs -> SecretId.entries.forEach { prefs.remove(keyOf(it)) } }
                val deleted = withContext(dispatcher) { cipher.deleteKey() }
                removed && deleted
            } finally {
                resets.update { it + 1 }
            }
        }

    @Suppress("SwallowedException") // A failed write is reported as false; its message holds no key.
    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean =
        try {
            dataStore.edit(change)
            true
        } catch (e: IOException) {
            false
        }

    private fun stateOf(
        prefs: Preferences,
        id: SecretId,
    ): SecretState {
        val stored = prefs[keyOf(id)] ?: return SecretState.ABSENT
        val plain = open(id, stored)
        plain?.fill(0)
        return if (plain != null) SecretState.SAVED else SecretState.UNREADABLE
    }

    @Suppress("SwallowedException") // Not Base64: the entry is damaged, which is all the caller needs.
    private fun open(
        id: SecretId,
        stored: String,
    ): ByteArray? {
        val sealed =
            try {
                Base64.getDecoder().decode(stored)
            } catch (e: IllegalArgumentException) {
                return null
            }
        return cipher.open(sealed, associated(id))
    }

    private companion object {
        fun keyOf(id: SecretId): Preferences.Key<String> = stringPreferencesKey("secret.${id.storageName}")

        /** Binds a sealed value to its entry: one copied to another entry does not open. */
        fun associated(id: SecretId): ByteArray = "sleevesight:${id.storageName}".encodeToByteArray()
    }
}
