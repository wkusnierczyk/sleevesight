package app.sleevesight.app.secrets

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.core.Secret
import app.sleevesight.core.keys.KeyName
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Base64
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The keys the user enters: the AI service's and the keyed sources'. */
enum class SecretId(
    internal val storageName: String,
    /** The name a setup code gives the key (#268). */
    val keyName: KeyName,
) {
    /** Anthropic's API key, for the AI service. */
    ANTHROPIC("ai.anthropic", KeyName.ANTHROPIC),

    /** OpenAI's API key, for the AI service. */
    OPENAI("ai.openai", KeyName.OPENAI),

    /** Google's Gemini API key, for the AI service. */
    GOOGLE("ai.google", KeyName.GOOGLE),

    /** The user's Last.fm API key (design, *Source adapters*). */
    LASTFM("source.lastfm", KeyName.LASTFM),

    /** The web search provider's key: Exa, the first (D11). */
    EXA("source.exa", KeyName.EXA),
    ;

    companion object {
        /** The key a setup code names [name]. */
        fun of(name: KeyName): SecretId = entries.first { it.keyName == name }

        /** The key [provider] needs, or null for one with no client yet, or none needed (Ollama). */
        fun forProvider(provider: ProviderId): SecretId? =
            when (provider) {
                ProviderId.ANTHROPIC -> ANTHROPIC
                ProviderId.OPENAI -> OPENAI
                ProviderId.GOOGLE -> GOOGLE
                ProviderId.OLLAMA -> null
            }

        /** The key the source [switch] needs, or null for one that needs none; web search's are its engines'. */
        fun forSource(switch: SourceSwitch): SecretId? =
            when (switch) {
                SourceSwitch.LASTFM -> LASTFM
                SourceSwitch.MUSICBRAINZ,
                SourceSwitch.WIKIPEDIA,
                SourceSwitch.DISCOGS,
                SourceSwitch.WEB,
                SourceSwitch.MODEL,
                -> null
            }

        /** The key the web search engine [engine] needs. */
        fun forEngine(engine: WebEngine): SecretId =
            when (engine) {
                WebEngine.EXA -> EXA
            }
    }
}

/** How a save ([SecretStore.set]) ended. */
enum class SaveResult {
    SAVED,

    /**
     * The key store could not seal the key, or may not make a key ([SecretStore]'s class KDoc):
     * if that keeps happening, *Purge keys* is the way out.
     */
    NOT_SEALED,

    /**
     * The file could not be written, or, before API 31, read to tell whether a key may be made: a
     * reset would not help; trying again may.
     */
    NOT_WRITTEN,
}

/**
 * How *Purge keys* ([SecretStore.reset]) ended: whether every key's entry was [removed] from the
 * file, whether the Keystore key that sealed them was deleted ([sealDeleted]), and each key's state
 * read from the file after both steps ([left]; null when the file could not be read).
 */
class ResetResult(
    val removed: Boolean,
    val sealDeleted: Boolean,
    val left: Map<SecretId, SecretState>?,
) {
    /** Both steps succeeded. */
    val done: Boolean get() = removed && sealDeleted
}

/** What the settings screen may know of a stored key: never its value. */
enum class SecretState {
    ABSENT,
    SAVED,

    /**
     * A value is stored but cannot be opened: the Keystore key that sealed it is gone (a restore
     * onto another phone, a reset Keystore), the value is damaged, or the key store fails. It reads
     * as no key. Saving it again works when the Keystore key is gone from API 31 (a new one is made);
     * before API 31 with any key stored, this one included, or when the key store keeps failing, the
     * user's *Purge keys* ([SecretStore.reset]) is the way out.
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
 * Reading never creates the Keystore key; a save creates it only when the key store has none and
 * that cannot cost a stored key: from API 31 (keystore2, which reports a failed lookup as an
 * error), or when no key is stored. Before API 31 the key store reports a failed lookup as no key,
 * and creating one deletes whatever is under the alias, so with keys stored such a save fails and
 * *Purge keys* is the way out. [legacyKeystore] says which applies.
 *
 * The cipher's work, which goes to the Keystore over binder calls, runs on [dispatcher], never on
 * the caller's (the screen's main thread). Saves, removals and resets run one at a time. One that
 * fails, in the key store or in DataStore, says so in what it returns rather than throwing.
 */
class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val legacyKeystore: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeFirstSave: suspend (SecretId) -> Boolean = { true },
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

    /**
     * Emits once, and again whenever a stored key changes (saved, replaced, removed, reset), for the
     * pipeline on the phone, which is built with the keys and reads them with [get] when this
     * emits (#51). It says nothing of the values: a key replaced by another emits, though its state
     * stays [SecretState.SAVED]. Reading a key or the states does not emit. While the file cannot
     * be read it emits nothing, and the read is retried, after [FIRST_RETRY] and then twice as long
     * each time up to [MAX_RETRY], counted from the last read that succeeded, so a pipeline keeps
     * the keys it was built with, and the changes after the file reads again still arrive. Until
     * its first read succeeds it emits nothing at all, so no pipeline is built before it.
     */
    val changes: Flow<Unit> =
        combine(
            flow {
                // Failures since the last read that succeeded: the backoff starts again after one.
                var failures = 0L
                emitAll(
                    dataStore.data
                        .onEach { failures = 0 }
                        .retryWhen {
                            cause,
                            _,
                            ->
                            (cause is IOException).also { io -> if (io) delay(retryDelay(failures++)) }
                        },
                )
            },
            resets,
        ) { prefs, count -> SecretId.entries.map { prefs[keyOf(it)] } to count }
            .distinctUntilChanged()
            .map { }

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
     * Whether an entry is stored for the key [id], whether or not it can be opened (#540): what
     * tells a key removed from one [get] could not read for now. True too when the file cannot be
     * read, since nothing then says the entry is gone; false only when the file reads without it.
     */
    @Suppress("SwallowedException") // A file that cannot be read says nothing of the entry; it is kept.
    suspend fun isStored(id: SecretId): Boolean =
        try {
            dataStore.data.first()[keyOf(id)] != null
        } catch (e: IOException) {
            true
        }

    /**
     * Saves [secret] as the key [id], replacing any before it: [SaveResult.NOT_SEALED] when it could
     * not be sealed (the key store failed, or may not make a key), [SaveResult.NOT_WRITTEN] when it
     * could not be written (DataStore failed), or, before API 31, when no key was found and the file
     * could not be read to tell whether one may be made; what was stored before is then kept, and
     * nothing is deleted.
     *
     * Where no entry is stored for [id] (or the file cannot be read to tell), [beforeFirstSave] runs
     * first (#667): in the app, it saves the services that need the key off, so a key saved never
     * finds its service still stored on from before it was removed, should the keys' gate
     * ([KeyGate]) have failed to write that service off. When it fails, nothing is saved, and the
     * result is [SaveResult.NOT_WRITTEN]: storage failed, and trying again may work.
     */
    suspend fun set(
        id: SecretId,
        secret: Secret,
    ): SaveResult =
        writes.withLock {
            val first =
                try {
                    dataStore.data.first()[keyOf(id)] == null
                } catch (_: IOException) {
                    true // Not known: as if none were stored, so its services are saved off first.
                }
            if (first && !beforeFirstSave(id)) return@withLock SaveResult.NOT_WRITTEN
            // Before API 31, whether any entry is stored: null when the file cannot be read.
            val stored = if (legacyKeystore) anyStored() else false
            val sealed =
                withContext(dispatcher) {
                    val mayCreate = stored == false
                    val plain = secret.reveal().encodeToByteArray()
                    try {
                        cipher.seal(plain, associated(id), mayCreate)
                    } finally {
                        plain.fill(0)
                    }
                }
            when {
                // No key found, and none may be made because the file could not be read: storage.
                sealed == null && stored == null -> SaveResult.NOT_WRITTEN
                sealed == null -> SaveResult.NOT_SEALED
                write { it[keyOf(id)] = Base64.getEncoder().encodeToString(sealed) } -> SaveResult.SAVED
                else -> SaveResult.NOT_WRITTEN
            }
        }

    /** Removes the key [id]. False when DataStore could not write. */
    suspend fun clear(id: SecretId): Boolean = writes.withLock { write { it.remove(keyOf(id)) } }

    /**
     * *Purge keys*: removes every saved key and deletes the Keystore
     * key, so the next save makes a new one. The way out when the key store cannot read the saved
     * keys or seal new ones. Says how each step went, and what is stored after them ([ResetResult]);
     * when one step fails, the other is still done.
     *
     * The Keystore key is deleted even when removing the entries failed (the file could not be
     * written). The user asked for every key to go, and without the Keystore key the entries left
     * can never be opened again, so no key the user removed is used, or can be read, after the
     * reset: that matters more than the entries, which read as [SecretState.UNREADABLE] and go with
     * the next reset that can write the file. Keeping the Keystore key would leave every key
     * working after a removal the user was told had not finished. Until a reset removes them, a save
     * makes a new Keystore key from API 31 (the entries left stay unreadable); before it, with
     * entries stored, a save fails ([SaveResult.NOT_SEALED]) and *Purge keys* is offered again.
     */
    suspend fun reset(): ResetResult =
        writes.withLock {
            try {
                val removed = write { prefs -> SecretId.entries.forEach { prefs.remove(keyOf(it)) } }
                val deleted = withContext(dispatcher) { cipher.deleteKey() }
                ResetResult(removed, deleted, statesNow())
            } finally {
                resets.update { it + 1 }
            }
        }

    /** Each key's state, read from the file itself; null when the read fails. */
    @Suppress("SwallowedException") // A failed read means "unknown"; that is all the caller needs.
    private suspend fun statesNow(): Map<SecretId, SecretState>? =
        try {
            val prefs = dataStore.data.first()
            withContext(dispatcher) { SecretId.entries.associateWith { stateOf(prefs, it) } }
        } catch (e: IOException) {
            null
        }

    /**
     * Whether any key is stored, read from the file itself; null when the read fails, which never
     * allows creating a key that could cost one.
     */
    @Suppress("SwallowedException") // A failed read means "unknown"; that is all the caller needs.
    private suspend fun anyStored(): Boolean? =
        try {
            val prefs = dataStore.data.first()
            SecretId.entries.any { prefs[keyOf(it)] != null }
        } catch (e: IOException) {
            null
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

    companion object {
        /** The first retry of a failed read for [changes]. */
        val FIRST_RETRY: Duration = 250.milliseconds

        /** The longest wait between retries of a read for [changes] that keeps failing. */
        val MAX_RETRY: Duration = 30.seconds

        private const val DOUBLINGS = 7

        private fun retryDelay(attempt: Long): Duration =
            minOf(FIRST_RETRY * 2.0.pow(attempt.coerceAtMost(DOUBLINGS.toLong()).toInt()), MAX_RETRY)

        private fun keyOf(id: SecretId): Preferences.Key<String> = stringPreferencesKey("secret.${id.storageName}")

        /** Binds a sealed value to its entry: one copied to another entry does not open. */
        private fun associated(id: SecretId): ByteArray = "sleevesight:${id.storageName}".encodeToByteArray()
    }
}
