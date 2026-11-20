package app.sleevesight.app.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import app.sleevesight.core.settings.SettingsView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The settings' view as stored (#1447): a new install starts in *Basic*, someone updating in *All*
 * ([SettingsViewMigration]); a view chosen is kept across a restart, and by a reset; it is never in
 * a settings file.
 */
class SettingsViewStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }

    @After
    fun close() = stores.close()

    // As the app opens its settings: the view's migration first.
    private fun store() = SettingsStore(stores.open("settings", listOf(SettingsViewMigration, PrunePlayersMigration)))

    @Test
    fun aNewInstallStartsInBasic() =
        runBlocking {
            assertEquals(SettingsView.BASIC, store().settings.first().settingsView)
        }

    @Test
    fun someoneUpdatingWithNothingChangedStartsInAll() =
        runBlocking {
            // A file an earlier version has read: its players pruned, no setting moved.
            stores.open("settings").edit { it[PrunePlayersMigration.DONE] = true }
            stores.close()
            assertEquals(SettingsView.ALL, store().settings.first().settingsView)
        }

    @Test
    fun someoneUpdatingWithASettingChangedStartsInAll() =
        runBlocking {
            stores.open("settings").edit { it[booleanPreferencesKey("genres.show")] = false }
            stores.close()
            assertEquals(SettingsView.ALL, store().settings.first().settingsView)
        }

    @Test
    fun theViewChosenIsKeptAcrossARestartAndTheNewInstallsViewIsSetOnce() =
        runBlocking<Unit> {
            store().update { it.copy(settingsView = SettingsView.ADVANCED) }
            stores.close()
            assertEquals(SettingsView.ADVANCED, store().settings.first().settingsView)
            stores.close()
            // *All*, the default, is not stored: the migration, run once, does not set *Basic* again.
            store().update { it.copy(settingsView = SettingsView.ALL) }
            stores.close()
            val again = store()
            assertEquals(SettingsView.ALL, again.settings.first().settingsView)
            stores.close()
            stores.open("settings").edit { prefs ->
                assertTrue(prefs[SettingsViewMigration.DONE] == true)
                assertEquals(null, prefs[stringPreferencesKey("display.settings_view")])
            }
        }

    @Test
    fun aResetKeepsTheView() {
        val current = Settings(settingsView = SettingsView.ADVANCED, showGenres = false)
        val reset = SettingsReset.reset(current, Settings())
        assertEquals(SettingsView.ADVANCED, reset.settingsView)
        assertEquals(true, reset.showGenres)
    }

    @Test
    fun theViewIsNeverInASettingsFile() {
        assertTrue("settingsView" in SettingsFile.LEFT_OUT)
        val written = SettingsFile.write(Settings(settingsView = SettingsView.BASIC), "0.10.0")
        assertTrue("settingsView" !in written)
    }
}
