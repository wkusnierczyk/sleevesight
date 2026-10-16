package app.sleevesight.app.secrets

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.app.SoftwareKeySource
import app.sleevesight.app.TestStores
import app.sleevesight.app.softwareKey
import app.sleevesight.app.testCipher
import app.sleevesight.core.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey

class SecretStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }
    private val key: SecretKey = softwareKey()

    @After
    fun close() = stores.close()

    private fun store(cipherKey: SecretKey = key) = SecretStore(stores.open("secrets"), testCipher(cipherKey))

    private fun SecretStore.state(id: SecretId) = runBlocking { states.first().getValue(id) }

    @Test
    fun aKeyRoundTripsAcrossARestart() =
        runBlocking {
            store().set(SecretId.LASTFM, Secret(LASTFM_KEY))
            stores.close()

            val store = store()
            assertEquals(LASTFM_KEY, store.get(SecretId.LASTFM)?.reveal())
            assertEquals(SecretState.SAVED, store.state(SecretId.LASTFM))
            assertEquals(SecretState.ABSENT, store.state(SecretId.EXA))
            assertNull(store.get(SecretId.EXA))
        }

    @Test
    fun theFileHoldsNoPartOfTheKey() =
        runBlocking {
            store().set(SecretId.ANTHROPIC, Secret(ANTHROPIC_KEY))
            stores.close()
            val bytes = stores.file("secrets").readBytes().decodeToString()
            assertFalse(bytes.contains(ANTHROPIC_KEY))
            assertFalse(bytes.contains(ANTHROPIC_KEY.takeLast(12)))
        }

    @Test
    fun aKeyIsReplacedAndRemoved() =
        runBlocking {
            val store = store()
            store.set(SecretId.EXA, Secret("first"))
            store.set(SecretId.EXA, Secret("second"))
            assertEquals("second", store.get(SecretId.EXA)?.reveal())

            store.clear(SecretId.EXA)
            assertNull(store.get(SecretId.EXA))
            assertEquals(SecretState.ABSENT, store.state(SecretId.EXA))
        }

    @Test
    fun aKeySealedWithALostKeystoreKeyIsUnreadableUntilEnteredAgain() =
        runBlocking {
            store().set(SecretId.ANTHROPIC, Secret(ANTHROPIC_KEY))
            stores.close()

            // As after the Keystore key is lost: a new one cannot open what the old one sealed.
            val store = store(cipherKey = softwareKey())
            assertNull(store.get(SecretId.ANTHROPIC))
            assertEquals(SecretState.UNREADABLE, store.state(SecretId.ANTHROPIC))

            store.set(SecretId.ANTHROPIC, Secret("sk-new"))
            assertEquals("sk-new", store.get(SecretId.ANTHROPIC)?.reveal())
            assertEquals(SecretState.SAVED, store.state(SecretId.ANTHROPIC))
        }

    @Test
    fun anUnreadableKeyIsKeptUntilTheUserActs() =
        runBlocking {
            store().set(SecretId.LASTFM, Secret(LASTFM_KEY))
            stores.close()
            store(cipherKey = softwareKey()).get(SecretId.LASTFM)
            stores.close()

            // A Keystore that failed once does not cost the key: with the right key it opens again.
            assertEquals(LASTFM_KEY, store().get(SecretId.LASTFM)?.reveal())
        }

    @Test
    fun aDamagedEntryIsUnreadable() =
        runBlocking {
            val dataStore = stores.open("secrets")
            val store = SecretStore(dataStore, testCipher(key))
            store.set(SecretId.LASTFM, Secret(LASTFM_KEY))
            dataStore.edit { prefs ->
                val name = stringPreferencesKey("secret.source.lastfm")
                val stored = checkNotNull(prefs[name])
                prefs[name] = stored.dropLast(4) + if (stored.endsWith("AAAA")) "BBBB" else "AAAA"
                prefs[stringPreferencesKey("secret.source.exa")] = "not base64 at all!"
            }
            assertEquals(SecretState.UNREADABLE, store.state(SecretId.LASTFM))
            assertEquals(SecretState.UNREADABLE, store.state(SecretId.EXA))
            assertNull(store.get(SecretId.LASTFM))
            assertNull(store.get(SecretId.EXA))
        }

    @Test
    fun aSealedValueMovedToAnotherEntryDoesNotOpenThere() =
        runBlocking {
            val dataStore = stores.open("secrets")
            val store = SecretStore(dataStore, testCipher(key))
            store.set(SecretId.ANTHROPIC, Secret(ANTHROPIC_KEY))
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("secret.source.exa")] =
                    checkNotNull(prefs[stringPreferencesKey("secret.ai.anthropic")])
            }
            assertNull(store.get(SecretId.EXA))
            assertEquals(SecretState.UNREADABLE, store.state(SecretId.EXA))
        }

    @Test
    fun aDamagedFileReadsAsNoKeys() =
        runBlocking {
            stores.file("secrets").writeBytes(byteArrayOf(0x0a, 0x7f, 0x01, 0x02, 0x03))
            val store = store()
            assertEquals(SecretId.entries.associateWith { SecretState.ABSENT }, store.states.first())
            store.set(SecretId.EXA, Secret("exa"))
            assertEquals("exa", store.get(SecretId.EXA)?.reveal())
        }

    @Test
    fun theCipherRefusesAnotherFormatAndAnotherEntry() {
        val cipher = testCipher(key)
        val sealed = checkNotNull(cipher.seal("value".encodeToByteArray(), "a".encodeToByteArray(), mayCreate = false))
        assertArrayEquals("value".encodeToByteArray(), cipher.open(sealed, "a".encodeToByteArray()))
        assertNull(cipher.open(sealed, "b".encodeToByteArray()))
        assertNull(cipher.open(byteArrayOf(2) + sealed.copyOfRange(1, sealed.size), "a".encodeToByteArray()))
        assertNull(cipher.open(sealed.copyOf(10), "a".encodeToByteArray()))
    }

    /** A key source whose key store fails with [failure] for the first [times] calls, then works. */
    private fun failing(
        failure: Exception,
        times: Int = Int.MAX_VALUE,
    ) = object : SoftwareKeySource(key) {
        var calls = 0

        override fun find(): SecretKey? = if (++calls <= times) throw failure else super.find()
    }

    private fun aFailedSaveDeletesNothing(failure: Exception) =
        runBlocking {
            val dataStore = stores.open("secrets")
            SecretStore(dataStore, testCipher(key)).set(SecretId.LASTFM, Secret(LASTFM_KEY))

            // The key store fails for both of the save's attempts, then recovers.
            val keys = failing(failure, times = 2)
            val store = SecretStore(dataStore, SecretCipher(keys))
            assertFalse(store.set(SecretId.ANTHROPIC, Secret(ANTHROPIC_KEY)))
            assertEquals(0, keys.deletions)
            assertEquals(SecretState.SAVED, store.state(SecretId.LASTFM))
            assertEquals(LASTFM_KEY, store.get(SecretId.LASTFM)?.reveal())
            assertEquals(SecretState.ABSENT, store.state(SecretId.ANTHROPIC))
        }

    @Test
    fun aFailedSaveOnInvalidKeyDeletesNothing() = aFailedSaveDeletesNothing(InvalidKeyException("busy"))

    @Test
    fun aFailedSaveOnUnrecoverableKeyDeletesNothing() =
        aFailedSaveDeletesNothing(UnrecoverableKeyException("Failed to load key from keystore"))

    @Test
    fun aFailedSaveOnAPermanentlyInvalidatedKeyDeletesNothing() =
        aFailedSaveDeletesNothing(KeyPermanentlyInvalidatedException())

    @Test
    fun aFailedSaveOnAProviderErrorDeletesNothing() = aFailedSaveDeletesNothing(ProviderException("key store down"))

    @Test
    fun aSaveSucceedsWhenTheRetryWorks() =
        runBlocking {
            val keys = failing(InvalidKeyException("stale key object"), times = 1)
            val store = SecretStore(stores.open("secrets"), SecretCipher(keys))
            assertTrue(store.set(SecretId.EXA, Secret("exa")))
            assertEquals(0, keys.deletions)
            assertEquals("exa", store.get(SecretId.EXA)?.reveal())
        }

    @Test
    fun aKeyStoreThatKeepsFailingIsResetByTheUser() =
        runBlocking {
            val dataStore = stores.open("secrets")
            SecretStore(dataStore, testCipher(key)).set(SecretId.LASTFM, Secret(LASTFM_KEY))

            // The stored key fails every time until it is deleted, as a corrupt key blob would.
            val keys =
                object : SoftwareKeySource(key) {
                    override fun find(): SecretKey? =
                        if (deletions == 0) throw InvalidKeyException("invalid key blob") else super.find()
                }
            val store = SecretStore(dataStore, SecretCipher(keys))
            assertFalse(store.set(SecretId.EXA, Secret("exa")))
            assertEquals(SecretState.UNREADABLE, store.state(SecretId.LASTFM))
            assertEquals(0, keys.deletions)

            assertTrue(store.reset())
            assertEquals(1, keys.deletions)
            assertEquals(SecretId.entries.associateWith { SecretState.ABSENT }, store.states.first())

            assertTrue(store.set(SecretId.EXA, Secret("exa")))
            assertEquals("exa", store.get(SecretId.EXA)?.reveal())
        }

    @Test
    fun aResetKeystoreGetsANewKeyAndTheOldKeysReadUnreadable() =
        runBlocking {
            val dataStore = stores.open("secrets")
            SecretStore(dataStore, testCipher(key)).set(SecretId.LASTFM, Secret(LASTFM_KEY))

            // From API 31, the Keystore has no key under the alias any more: a save makes one.
            val keys = SoftwareKeySource(current = null)
            val store = SecretStore(dataStore, SecretCipher(keys), legacyKeystore = false)
            assertTrue(store.set(SecretId.ANTHROPIC, Secret(ANTHROPIC_KEY)))
            assertEquals(1, keys.creations)
            assertEquals(ANTHROPIC_KEY, store.get(SecretId.ANTHROPIC)?.reveal())
            assertEquals(SecretState.UNREADABLE, store.state(SecretId.LASTFM))
            assertEquals(0, keys.deletions)
        }

    @Test
    fun aReadWithNoKeyCreatesNone() =
        runBlocking {
            val dataStore = stores.open("secrets")
            SecretStore(dataStore, testCipher(key)).set(SecretId.LASTFM, Secret(LASTFM_KEY))

            val keys = SoftwareKeySource(current = null)
            val store = SecretStore(dataStore, SecretCipher(keys), legacyKeystore = false)
            assertEquals(SecretState.UNREADABLE, store.state(SecretId.LASTFM))
            assertNull(store.get(SecretId.LASTFM))
            assertEquals(0, keys.creations)
        }

    @Test
    fun beforeApi31ASaveWithKeysStoredAndNoKeyFoundCreatesNothing() =
        runBlocking {
            val dataStore = stores.open("secrets")
            SecretStore(dataStore, testCipher(key)).set(SecretId.LASTFM, Secret(LASTFM_KEY))

            // Before API 31 a failed lookup reads as no key, and creating one would delete the
            // existing key: with a key stored, the save fails instead.
            val keys = SoftwareKeySource(current = null)
            val store = SecretStore(dataStore, SecretCipher(keys), legacyKeystore = true)
            assertFalse(store.set(SecretId.EXA, Secret("exa")))
            assertEquals(0, keys.creations)
            assertEquals(0, keys.deletions)
            assertEquals(SecretState.ABSENT, store.state(SecretId.EXA))
            // The stored key is untouched and opens once the key store finds its key again.
            assertEquals(LASTFM_KEY, SecretStore(dataStore, testCipher(key)).get(SecretId.LASTFM)?.reveal())
        }

    @Test
    fun beforeApi31ASaveWithNoKeysStoredCreatesTheKey() =
        runBlocking {
            val keys = SoftwareKeySource(current = null)
            val store = SecretStore(stores.open("secrets"), SecretCipher(keys), legacyKeystore = true)
            assertTrue(store.set(SecretId.EXA, Secret("exa")))
            assertEquals(1, keys.creations)
            assertEquals("exa", store.get(SecretId.EXA)?.reveal())
        }

    @Test
    fun beforeApi31AFailedReadOfTheFileCreatesNoKey() =
        runBlocking {
            val dataStore = stores.open("secrets")
            val unreadable =
                object : DataStore<Preferences> by dataStore {
                    override val data = flow<Preferences> { throw IOException("cannot read") }
                }
            val keys = SoftwareKeySource(current = null)
            val store = SecretStore(unreadable, SecretCipher(keys), legacyKeystore = true)
            assertFalse(store.set(SecretId.EXA, Secret("exa")))
            assertEquals(0, keys.creations)
        }

    @Test
    fun concurrentSavesBothPersist() =
        runBlocking {
            val store = store()
            val saves =
                SecretId.entries.map { id ->
                    async(Dispatchers.IO) { store.set(id, Secret("value of $id")) }
                }
            assertTrue(saves.awaitAll().all { it })
            for (id in SecretId.entries) assertEquals("value of $id", store.get(id)?.reveal())
        }

    @Test
    fun aFailedSaveLeavesTheOtherKeysShownSaved() =
        runBlocking {
            val dataStore = stores.open("secrets")
            SecretStore(dataStore, testCipher(key)).set(SecretId.LASTFM, Secret(LASTFM_KEY))
            val keys =
                object : SoftwareKeySource(key) {
                    @Volatile var down = false

                    override fun find(): SecretKey? = if (down) throw InvalidKeyException("busy") else super.find()
                }
            val store = SecretStore(dataStore, SecretCipher(keys))
            val seen = mutableListOf<SecretState>()
            val watcher = launch(Dispatchers.IO) { store.states.collect { seen += it.getValue(SecretId.LASTFM) } }
            withTimeout(5_000) { while (seen.isEmpty()) delay(10) }

            keys.down = true
            assertFalse(store.set(SecretId.EXA, Secret("exa")))
            keys.down = false
            delay(200)
            watcher.cancel()
            assertEquals(listOf(SecretState.SAVED), seen.distinct())
        }

    @Test
    fun aKeyStoreThatCannotLoadReadsAsUnreadable() =
        runBlocking {
            val dataStore = stores.open("secrets")
            SecretStore(dataStore, testCipher(key)).set(SecretId.EXA, Secret("exa"))
            val unloadable =
                object : SoftwareKeySource(key) {
                    override fun find(): SecretKey? = throw IOException("cannot load")
                }
            val store = SecretStore(dataStore, SecretCipher(unloadable))
            assertEquals(SecretState.UNREADABLE, store.state(SecretId.EXA))
            assertNull(store.get(SecretId.EXA))
        }

    @Test
    fun aStoreThatCannotWriteReportsTheFailure() =
        runBlocking {
            val dataStore = stores.open("secrets")
            SecretStore(dataStore, testCipher(key)).set(SecretId.EXA, Secret("exa"))
            val failing =
                object : DataStore<Preferences> by dataStore {
                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                        throw IOException("disk full")
                }
            val store = SecretStore(failing, testCipher(key))
            assertFalse(store.set(SecretId.EXA, Secret("other")))
            assertFalse(store.clear(SecretId.EXA))
            assertEquals("exa", store.get(SecretId.EXA)?.reveal())
        }

    private companion object {
        // Not real keys.
        const val ANTHROPIC_KEY = "sk-ant-test-0123456789abcdefghijklmnop"
        const val LASTFM_KEY = "0123456789abcdef0123456789abcdef"
    }
}
