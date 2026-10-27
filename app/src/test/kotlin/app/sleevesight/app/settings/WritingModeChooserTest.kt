package app.sleevesight.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * The insight card's menu of *Reactive*'s modes as the app saves it (#615,
 * `SettingsStore.writingModeChooser`): each choice is *Reactive*, read back after a restart, and is
 * told on, the mode already chosen included (#625); the key the toggle before it stored (#553) is
 * left unread.
 */
class WritingModeChooserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }
    private val saves = CoroutineScope(Job() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun store() = SettingsStore(stores.open("settings"))

    // Chooses [mode] in the menu on [store], and waits for the save.
    private fun choose(
        store: SettingsStore,
        mode: WriteAutomatically,
    ) = runBlocking {
        store.writingModeChooser(saves)(mode)
        saves.coroutineContext.job.children
            .toList()
            .forEach { it.join() }
    }

    @Test
    fun eachChoiceIsReactiveAndHoldsAcrossARestart() {
        for (mode in WriteAutomatically.entries) {
            val first = store()
            choose(first, mode)
            assertEquals(mode, runBlocking { first.settings.first() }.writeAutomatically)
            stores.close()
            assertEquals(mode, runBlocking { store().settings.first() }.writeAutomatically)
            stores.close()
        }
    }

    @Test
    fun eachChoiceIsToldOnTheModeAlreadyChosenIncluded() {
        // The pipeline hears each choice, where an automatic mode is the first action (#625).
        val told = mutableListOf<WriteAutomatically>()
        val settled = mutableListOf<WriteAutomatically?>()
        val store = store()
        val modes = listOf(WriteAutomatically.WHEN_I_SKIP, WriteAutomatically.WHEN_I_SKIP, WriteAutomatically.NEVER)
        val chooser =
            store.writingModeChooser(saves) { mode ->
                told += mode
                { stored -> settled += stored }
            }
        // One at a time: each save's end is told on with the mode it just saved.
        for (mode in modes) {
            assertTrue(runBlocking { chooser(mode).await() })
            assertEquals(mode, settled.last())
        }
        assertEquals(modes, told)
        assertEquals(modes, settled)
    }

    // A store whose writes all fail with [failure], over [real] for reads.
    private fun failing(
        real: DataStore<Preferences>,
        failure: Exception,
    ) = object : DataStore<Preferences> {
        override val data = real.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
    }

    @Test
    fun aSaveThatFailsSaysSoAndTellsTheModeTheStoreStillHolds() {
        // So the pipeline writes by the narrower of the choice and the store (#625). Any failure
        // counts, not only an I/O error, so a caller waiting for the result never sees it thrown;
        // and a choice equal to the stored mode is told on the same way.
        val real = stores.open("settings")
        runBlocking { SettingsStore(real).update { it.copy(writeAutomatically = WriteAutomatically.WHEN_I_SKIP) } }
        for (failure in listOf(IOException("cannot write"), IllegalStateException("not an I/O error"))) {
            for (mode in listOf(WriteAutomatically.ON_EVERY_TRACK_CHANGE, WriteAutomatically.WHEN_I_SKIP)) {
                var stored: WriteAutomatically? = null
                val chooser = SettingsStore(failing(real, failure)).writingModeChooser(saves) { { stored = it } }
                val saved = runBlocking { chooser(mode).await() }
                assertFalse("$failure, $mode", saved)
                assertEquals("$failure, $mode", WriteAutomatically.WHEN_I_SKIP, stored)
            }
        }
    }

    @Test
    fun theToggleStoredLastModeIsLeftUnread() {
        // As the toggle (#553) left a file: Now, with On track skip kept as the mode to restore.
        runBlocking {
            stores.open("settings").edit {
                it[stringPreferencesKey("insights.write_automatically")] = "NEVER"
                it[stringPreferencesKey("insights.last_automatic_mode")] = "WHEN_I_SKIP"
            }
        }
        stores.close()
        val store = store()
        assertEquals(WriteAutomatically.NEVER, runBlocking { store.settings.first() }.writeAutomatically)
        choose(store, WriteAutomatically.ON_EVERY_TRACK_CHANGE)
        assertEquals(
            WriteAutomatically.ON_EVERY_TRACK_CHANGE,
            runBlocking { store.settings.first() }.writeAutomatically,
        )
    }
}
