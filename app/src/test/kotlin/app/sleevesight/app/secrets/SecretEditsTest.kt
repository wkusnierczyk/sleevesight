package app.sleevesight.app.secrets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.softwareKey
import app.sleevesight.app.testCipher
import app.sleevesight.core.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** What the store told, in order, with what [SecretStore.get] read for each key at [before]. */
    private class Told : KeyEdits {
        lateinit var store: SecretStore
        val heard = mutableListOf<String>()

        override suspend fun before(ids: Set<SecretId>) {
            val held = ids.map { "${it.name}=${store.get(it)?.reveal()}" }
            heard += "before ${held.joinToString()}"
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
        SecretStore(dataStore, testCipher(key), false, Dispatchers.Unconfined, edits = told).also { told.store = it }

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
            SecretStore(data, testCipher(key), legacyKeystore = false, dispatcher = Dispatchers.Unconfined)
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
