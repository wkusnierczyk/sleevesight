package app.sleevesight.app.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.Settings
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The insight card's toggle for automatic writing as the app saves it (#553,
 * `SettingsStore.automaticWritingSwitch`): as *Reactive*, with the last automatic mode kept, both
 * read back after a restart, so the toggle turned on in a new process restores the mode.
 */
class AutomaticWritingSwitchTest {
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

    // Turns the toggle [on] or off on [store], and waits for the save.
    private fun toggle(
        store: SettingsStore,
        on: Boolean,
    ) = runBlocking {
        store.automaticWritingSwitch(saves)(on)
        saves.coroutineContext.job.children
            .toList()
            .forEach { it.join() }
    }

    @Test
    fun offIsManualAndOnAgainRestoresTheModeAcrossARestart() {
        val first = store()
        runBlocking { first.update { it.withWriteAutomatically(WriteAutomatically.WHEN_I_SKIP) } }
        toggle(first, on = false)
        assertEquals(WriteAutomatically.NEVER, runBlocking { first.settings.first() }.writeAutomatically)
        stores.close()

        val second = store()
        val read = runBlocking { second.settings.first() }
        assertEquals(WriteAutomatically.NEVER, read.writeAutomatically)
        assertEquals(WriteAutomatically.WHEN_I_SKIP, read.lastAutomaticMode)
        toggle(second, on = true)
        assertEquals(WriteAutomatically.WHEN_I_SKIP, runBlocking { second.settings.first() }.writeAutomatically)
    }

    @Test
    fun onWithNoModeEverUsedIsOnTrackChange() {
        val store = store()
        runBlocking { store.update { it.copy(writeAutomatically = WriteAutomatically.NEVER) } }
        toggle(store, on = true)
        val read = runBlocking { store.settings.first() }
        assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, read.writeAutomatically)
    }

    @Test
    fun aFileFromBeforeTheToggleReadsItsModeAsTheLastAndKeepsItWhenTurnedOff() {
        // On skip stored, no last automatic mode: as an install from before #553 left it.
        runBlocking {
            stores.open("settings").edit { it[stringPreferencesKey("insights.write_automatically")] = "WHEN_I_SKIP" }
        }
        stores.close()
        val first = store()
        assertEquals(WriteAutomatically.WHEN_I_SKIP, runBlocking { first.settings.first() }.lastAutomaticMode)
        toggle(first, on = false)
        stores.close()

        val second = store()
        assertEquals(WriteAutomatically.WHEN_I_SKIP, runBlocking { second.settings.first() }.lastAutomaticMode)
        toggle(second, on = true)
        assertEquals(WriteAutomatically.WHEN_I_SKIP, runBlocking { second.settings.first() }.writeAutomatically)
    }

    @Test
    fun aStoredLastModeOfManualOrUnknownReadsAsTheDefault() {
        for (stored in listOf("NEVER", "SOMETIMES")) {
            val prefs = mutablePreferencesOf(stringPreferencesKey("insights.last_automatic_mode") to stored)
            assertEquals(Settings().lastAutomaticMode, SettingsCodec.read(prefs).lastAutomaticMode)
        }
    }
}
