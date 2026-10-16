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
     * onto another phone, a reset Keystore) or the value is damaged. It reads as no key, and the
     * screen asks for it again; saving a new one replaces it.
     */
    UNREADABLE,
}

/**
 * The user's API keys, each sealed by [cipher] (AES-GCM, with the key in the Android Keystore) and
 * kept in its own DataStore file, apart from the settings (architecture, *Settings and secrets*).
 * A key is handed out only as a [Secret], whose `toString()` is redacted, and nothing here logs.
 *
 * An entry that cannot be opened is [SecretState.UNREADABLE] and [get] returns null for it, as for
 * no key; it is kept, not deleted, so a Keystore that fails once does not cost the user the key, and
 * it goes when the user saves a new key or removes it. Saving replaces the Keystore key only when
 * that loses nothing: the key store says it is invalidated for good, or no stored key can open
 * under it ([SecretCipher.seal]). A store file DataStore cannot parse is
 * replaced by an empty one (the corruption handler where the store is created), so its keys read as
 * absent.
 *
 * The cipher's work, which goes to the Keystore over binder calls, runs on [dispatcher], never on
 * the caller's (the screen's main thread). Saving or removing a key that fails, in the key store or
 * in DataStore, returns false rather than throwing.
 */
class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val data: Flow<Preferences> =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /**
     * Counts saves, so [states] is read again after one even when nothing was written: a save that
     * failed after replacing the Keystore key has left the stored keys unreadable.
     */
    private val saves = MutableStateFlow(0)

    /** Each key's state, as the settings screen shows it. */
    val states: Flow<Map<SecretId, SecretState>> =
        combine(data, saves) { prefs, _ -> prefs }
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
     * (the key store failed) or written (DataStore failed); what was stored before is then kept.
     */
    suspend fun set(
        id: SecretId,
        secret: Secret,
    ): Boolean =
        try {
            val sealed =
                withContext(dispatcher) {
                    val prefs = data.first()
                    val plain = secret.reveal().encodeToByteArray()
                    try {
                        cipher.seal(plain, associated(id)) { nothingOpens(prefs) }
                    } finally {
                        plain.fill(0)
                    }
                }
            sealed != null && write { it[keyOf(id)] = Base64.getEncoder().encodeToString(sealed) }
        } finally {
            saves.update { it + 1 }
        }

    /** Removes the key [id]. False when DataStore could not write. */
    suspend fun clear(id: SecretId): Boolean = write { it.remove(keyOf(id)) }

    @Suppress("SwallowedException") // A failed write is reported as false; its message holds no key.
    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean =
        try {
            dataStore.edit(change)
            true
        } catch (e: IOException) {
            false
        }

    /**
     * Whether no stored key can open ([SecretCipher.mightOpen]), so replacing the Keystore key would
     * lose none. The key being saved counts too, since a save that fails after the replacement keeps
     * it. A key the key store merely failed to open counts as one that can: with a key store that
     * fails every time, the user removes the unreadable keys and then saves.
     */
    @Suppress("SwallowedException") // Not Base64: the entry is damaged, so it cannot open.
    private fun nothingOpens(prefs: Preferences): Boolean =
        SecretId.entries.none { other ->
            val sealed =
                prefs[keyOf(other)]?.let {
                    try {
                        Base64.getDecoder().decode(it)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
            sealed != null && cipher.mightOpen(sealed, associated(other))
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
