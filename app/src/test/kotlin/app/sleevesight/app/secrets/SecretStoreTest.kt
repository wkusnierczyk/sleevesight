package app.sleevesight.app.secrets

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.app.TestStores
import app.sleevesight.app.softwareKey
import app.sleevesight.app.testCipher
import app.sleevesight.core.Secret
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
        val sealed = cipher.seal("value".encodeToByteArray(), "a".encodeToByteArray())
        assertArrayEquals("value".encodeToByteArray(), cipher.open(sealed, "a".encodeToByteArray()))
        assertNull(cipher.open(sealed, "b".encodeToByteArray()))
        assertNull(cipher.open(byteArrayOf(2) + sealed.copyOfRange(1, sealed.size), "a".encodeToByteArray()))
        assertNull(cipher.open(sealed.copyOf(10), "a".encodeToByteArray()))
    }

    private companion object {
        // Not real keys.
        const val ANTHROPIC_KEY = "sk-ant-test-0123456789abcdefghijklmnop"
        const val LASTFM_KEY = "0123456789abcdef0123456789abcdef"
    }
}
