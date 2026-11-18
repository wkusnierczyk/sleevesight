package app.sleevesight.app.secrets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.SoftwareKeySource
import app.sleevesight.app.softwareKey
import app.sleevesight.core.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What [SecretStore] tells its [KeyEdits] (#1425): each key saved, replaced or removed through the
 * app, by hand, by *Load keys* (a save each) or by the OpenRouter sign-in, and every key at a reset,
 * before the write, while the file still holds the key it replaces, and after it, with the key
 * written, or none when the write failed; never a read. In memory only.
 */
class SecretEditsTest {
    private val data = MemoryStore()
    private val key = softwareKey()
    private val keystore = SoftwareKeySource(key)

    /** What the store told, in order, with what [SecretStore.get] read for each key at [before]. */
    private class Told : KeyEdits {
        lateinit var store: SecretStore
        val heard = mutableListOf<String>()

        /** What [before] says: whether what was learnt with the keys is kept forgotten. */
        var forgets = true

        override suspend fun before(ids: Set<SecretId>): Boolean {
            val held = ids.map { "${it.name}=${store.get(it)?.reveal()}" }
            heard += "before ${held.joinToString()}"
            return forgets
        }

        override fun after(
            ids: Set<SecretId>,
            now: Map<SecretId, Secret?>,
        ) {
            val told = ids.map { if (it in now) "${it.name}=${now[it]?.reveal()}" else "${it.name}?" }
            heard += "after ${told.joinToString()}"
        }
    }

    private val told = Told()

    private fun store(dataStore: DataStore<Preferences> = data) =
        SecretStore(dataStore, SecretCipher(keystore), false, Dispatchers.Unconfined, edits = told)
            .also { told.store = it }

    @Test
    fun aSaveAReplacementAndARemovalAreEachToldBeforeAndAfterTheWrite() =
        runTest {
            val store = store()
            store.set(SecretId.ANTHROPIC, Secret("sk-old"))
            store.set(SecretId.ANTHROPIC, Secret("sk-new"))
            store.clear(SecretId.ANTHROPIC)
            val expected =
                listOf(
                    "before ANTHROPIC=null",
                    "after ANTHROPIC=sk-old",
                    "before ANTHROPIC=sk-old",
                    "after ANTHROPIC=sk-new",
                    "before ANTHROPIC=sk-new",
                    "after ANTHROPIC=null",
                )
            assertEquals(expected, told.heard)
        }

    @Test
    fun aKeyIsNotSavedWhileWhatWasLearntWithTheOneItReplacesCannotBeForgotten() =
        runTest {
            val store = store()
            store.set(SecretId.ANTHROPIC, Secret("sk-old"))
            told.forgets = false
            assertEquals(SaveResult.NOT_WRITTEN, store.set(SecretId.ANTHROPIC, Secret("sk-new")))
            assertEquals("sk-old", store.get(SecretId.ANTHROPIC)?.reveal())
            assertEquals(listOf("before ANTHROPIC=sk-old", "after ANTHROPIC?"), told.heard.drop(2))

            // A removal goes ahead: what is kept then belongs to no key, and the next save forgets it.
            assertTrue(store.clear(SecretId.ANTHROPIC))
            assertNull(store.get(SecretId.ANTHROPIC))
        }

    @Test
    fun aPurgeThatCannotRemoveTheEntriesIsToldEndedOnlyOnceTheKeystoreKeyIsDeleted() =
        runTest {
            store().set(SecretId.ANTHROPIC, Secret("sk-old"))
            val failing =
                object : DataStore<Preferences> by data {
                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                        throw IOException("disk full")
                }
            told.heard.clear()
            val deletionsAtEnd = mutableListOf<Int>()
            val watching =
                object : KeyEdits by told {
                    override fun after(
                        ids: Set<SecretId>,
                        now: Map<SecretId, Secret?>,
                    ) {
                        deletionsAtEnd += keystore.deletions
                        told.after(ids, now)
                    }
                }
            val store =
                SecretStore(failing, SecretCipher(keystore), false, Dispatchers.Unconfined, edits = watching)
                    .also { told.store = it }
            val result = store.reset()
            assertFalse(result.removed)
            assertTrue(result.sealDeleted)
            assertEquals("the Keystore key was deleted before the end was told", listOf(1), deletionsAtEnd)
            val none = SecretId.entries.joinToString { "${it.name}=null" }
            assertEquals("unreadable, the keys hold none", "after $none", told.heard.last())
            assertNull(store.get(SecretId.ANTHROPIC))
        }

    @Test
    fun aResetTellsEveryKey() =
        runTest {
            val store = store()
            store.reset()
            val all = SecretId.entries
            val none = all.joinToString { "${it.name}=null" }
            assertEquals(listOf("before $none", "after $none"), told.heard)
        }

    @Test
    fun theOpenRouterSignInIsTold() =
        runTest {
            saveAndSwitchOn(store(), Secret("sk-or")) {}
            assertEquals(listOf("before OPENROUTER=null", "after OPENROUTER=sk-or"), told.heard)
        }

    @Test
    fun readsTellNothingAndAFailedWriteTellsItsKeyNotWritten() =
        runTest {
            SecretStore(data, SecretCipher(keystore), legacyKeystore = false, dispatcher = Dispatchers.Unconfined)
                .set(SecretId.ANTHROPIC, Secret("sk-old"))
            val failing =
                object : DataStore<Preferences> by data {
                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                        throw IOException("disk full")
                }
            val store = store(failing)
            store.get(SecretId.ANTHROPIC)
            store.isStored(SecretId.ANTHROPIC)
            store.states
            assertEquals(emptyList<String>(), told.heard)

            assertFalse(store.clear(SecretId.ANTHROPIC))
            assertEquals(listOf("before ANTHROPIC=sk-old", "after ANTHROPIC?"), told.heard)
        }
}
