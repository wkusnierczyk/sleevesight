package app.sleevesight.app.settings

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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The insight card's menu of *Reactive*'s modes as the app saves it (#615,
 * `SettingsStore.writingModeChooser`): each choice is *Reactive*, read back after a restart; the
 * key the toggle before it stored (#553) is left unread.
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
