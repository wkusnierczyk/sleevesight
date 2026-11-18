package app.sleevesight.app.secrets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.testCipher
import app.sleevesight.core.Secret
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * What [SecretStore.edits] says (#1425): each key saved, replaced or removed through the app, by
 * hand, by *Load keys* (a save each) or by the OpenRouter sign-in, and every key at a reset; never
 * a read, nor a save or removal that failed. In memory only.
 */
class SecretEditsTest {
    private val data = MemoryStore()

    private fun store(dataStore: DataStore<Preferences> = data) =
        SecretStore(dataStore, testCipher(), legacyKeystore = false, dispatcher = Dispatchers.Unconfined)

    /** What [store] tells, from now on. */
    private fun TestScope.edits(store: SecretStore): List<SecretId> {
        val heard = mutableListOf<SecretId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { store.edits.collect { heard += it } }
        return heard
    }

    @Test
    fun aSaveAReplacementAndARemovalEachTellTheirKey() =
        runTest {
            val store = store()
            val heard = edits(store)
            store.set(SecretId.ANTHROPIC, Secret("sk-old"))
            store.set(SecretId.ANTHROPIC, Secret("sk-new"))
            store.clear(SecretId.OPENAI)
            runCurrent()
            assertEquals(listOf(SecretId.ANTHROPIC, SecretId.ANTHROPIC, SecretId.OPENAI), heard)
        }

    @Test
    fun aResetTellsEveryKey() =
        runTest {
            val store = store()
            store.set(SecretId.GOOGLE, Secret("g"))
            val heard = edits(store)
            store.reset()
            runCurrent()
            assertEquals(SecretId.entries.toList(), heard)
        }

    @Test
    fun theOpenRouterSignInTellsItsKey() =
        runTest {
            val store = store()
            val heard = edits(store)
            saveAndSwitchOn(store, Secret("sk-or")) {}
            runCurrent()
            assertEquals(listOf(SecretId.OPENROUTER), heard)
        }

    @Test
    fun readsAndFailedWritesTellNothing() =
        runTest {
            val saved = store()
            saved.set(SecretId.ANTHROPIC, Secret("sk-old"))
            val failing =
                object : DataStore<Preferences> by data {
                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                        throw IOException("disk full")
                }
            val store = store(failing)
            val heard = edits(store)
            store.get(SecretId.ANTHROPIC)
            store.isStored(SecretId.ANTHROPIC)
            assertFalse(store.set(SecretId.ANTHROPIC, Secret("sk-new")) == SaveResult.SAVED)
            assertFalse(store.clear(SecretId.ANTHROPIC))
            runCurrent()
            assertEquals(emptyList<SecretId>(), heard)
        }
}
