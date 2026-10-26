package app.sleevesight.app.secrets

import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.SoftwareKeySource
import app.sleevesight.app.TestStores
import app.sleevesight.app.softwareKey
import app.sleevesight.core.Secret
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.InvalidKeyException
import javax.crypto.SecretKey

/**
 * [SecretStore.isStored] (#540): whether an entry is stored for a key, readable or not, so that a
 * read that fails for now is not taken for a key removed. Only a file read without the entry says
 * it is gone.
 */
class SecretPresenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }
    private val key: SecretKey = softwareKey()

    @After
    fun close() = stores.close()

    private fun cipher() = SecretCipher(SoftwareKeySource(key))

    @Test
    fun aSavedKeyIsStoredAndOneNeverSavedOrRemovedIsNot() =
        runBlocking {
            val store = SecretStore(stores.open("secrets"), cipher(), legacyKeystore = false)
            assertFalse(store.isStored(SecretId.ANTHROPIC))
            store.set(SecretId.ANTHROPIC, Secret("sk-ant"))
            assertTrue(store.isStored(SecretId.ANTHROPIC))
            store.clear(SecretId.ANTHROPIC)
            assertFalse(store.isStored(SecretId.ANTHROPIC))
        }

    @Test
    fun aFileThatCannotBeReadSaysTheKeyIsStoredThoughItsReadFindsNone() =
        runBlocking {
            val flaky = FlakyDataStore(stores.open("secrets"))
            val store = SecretStore(flaky, cipher(), legacyKeystore = false)
            store.set(SecretId.ANTHROPIC, Secret("sk-ant"))

            // Both reads fail: the key reads as none, and its state as absent.
            flaky.failures.set(2)
            assertNull(store.get(SecretId.ANTHROPIC))
            assertTrue("nothing says it is gone", store.isStored(SecretId.ANTHROPIC))
            flaky.failures.set(1)
            assertEquals(SecretState.ABSENT, store.states.first()[SecretId.ANTHROPIC])
        }

    @Test
    fun aKeyTheKeyStoreCannotOpenForNowIsStored() =
        runBlocking {
            var down = false
            val source =
                object : SoftwareKeySource(key) {
                    override fun find(): SecretKey? = if (down) throw InvalidKeyException("busy") else super.find()
                }
            val store = SecretStore(stores.open("secrets"), SecretCipher(source), legacyKeystore = false)
            store.set(SecretId.OPENAI, Secret("sk-openai"))

            // The key store fails for the read and for the state after it.
            down = true
            assertNull(store.get(SecretId.OPENAI))
            assertEquals(SecretState.UNREADABLE, store.states.first()[SecretId.OPENAI])
            assertTrue(store.isStored(SecretId.OPENAI))

            down = false
            assertEquals("sk-openai", store.get(SecretId.OPENAI)?.reveal())
        }
}
