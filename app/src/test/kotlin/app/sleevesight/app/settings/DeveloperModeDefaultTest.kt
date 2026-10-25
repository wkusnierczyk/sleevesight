package app.sleevesight.app.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Developer mode's default per build (#451): on in a build for Play's testing tracks
 * (`-Psleevesight.developerMode=true`), off in any other, and a choice the user stored holds in
 * either. The first two tests read this build's flag, so CI runs this class under both builds
 * (`ci.yml`, *Developer mode by default*); the others pass each default themselves.
 */
class DeveloperModeDefaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }

    @After
    fun close() = stores.close()

    private val onByDefault = Settings(developerMode = true)

    private fun store(defaults: Settings) = SettingsStore(stores.open("settings"), defaults)

    @Test
    fun theBuildsDefaultsDifferFromCoresOnlyInDeveloperModeAsTheBuildSetsIt() {
        assertEquals(BuildConfig.DEVELOPER_MODE_DEFAULT, SettingsStore.BUILD_DEFAULTS.developerMode)
        assertEquals(Settings(), SettingsStore.BUILD_DEFAULTS.copy(developerMode = false))
    }

    @Test
    fun aFreshInstallStartsWithTheBuildsDeveloperMode() =
        runBlocking {
            val read = store(SettingsStore.BUILD_DEFAULTS).settings.first()
            assertEquals(BuildConfig.DEVELOPER_MODE_DEFAULT, read.developerMode)
            // Developer mode shows *Diagnostics*; it does not turn it on.
            assertFalse(read.showDiagnostics)
        }

    @Test
    fun onByDefaultAFreshInstallStartsInDeveloperModeAndStoresNothing() =
        runBlocking {
            assertTrue(store(onByDefault).settings.first().developerMode)
            stores.close()
            val stored = stores.open("settings").data.first()
            assertTrue(stored.toString(), stored.asMap().isEmpty())
        }

    @Test
    fun onByDefaultDeveloperModeTurnedOffStaysOffAfterARestart() =
        runBlocking {
            store(onByDefault).update { it.withoutDeveloperMode() }
            stores.close()

            assertFalse(store(onByDefault).settings.first().developerMode)
        }

    @Test
    fun onByDefaultDeveloperModeTurnedOffAfterDiagnosticsStaysOff() =
        runBlocking {
            val store = store(onByDefault)
            store.update { it.copy(showDiagnostics = true) }
            store.update { it.withoutDeveloperMode() }
            stores.close()

            val read = store(onByDefault).settings.first()
            assertFalse(read.developerMode)
            assertFalse(read.showDiagnostics)
        }

    @Test
    fun onByDefaultDeveloperModeTurnedOffThenOnAgainStoresNothing() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(), prefs, onByDefault)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("developer.mode") to false), prefs)
        SettingsCodec.write(onByDefault, prefs, onByDefault)
        assertTrue(prefs.asMap().toString(), prefs.asMap().isEmpty())
    }

    @Test
    fun aChoiceStoredInOneBuildHoldsInTheOther() {
        // A tester turned it off in the test build: off in the public build too.
        val testerOff = mutablePreferencesOf()
        SettingsCodec.write(Settings(), testerOff, onByDefault)
        assertFalse(SettingsCodec.read(testerOff, Settings()).developerMode)

        // A listener turned it on in the public build: on in the test build too.
        val listenerOn = mutablePreferencesOf()
        SettingsCodec.write(onByDefault, listenerOn, Settings())
        assertTrue(SettingsCodec.read(listenerOn, onByDefault).developerMode)
    }

    @Test
    fun aChoiceStoredInOneBuildSurvivesDiagnosticsMovedInTheOther() {
        // Turned on in the public build, then *Diagnostics* turned on and off in the test build.
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(onByDefault, prefs, Settings())
        SettingsCodec.write(Settings(developerMode = true, showDiagnostics = true), prefs, onByDefault)
        SettingsCodec.write(onByDefault, prefs, onByDefault)
        assertTrue(SettingsCodec.read(prefs, onByDefault).developerMode)
        assertTrue(SettingsCodec.read(prefs, Settings()).developerMode)
    }

    @Test
    fun withNoChoiceStoredEachBuildReadsItsOwnDefault() {
        // A tester who never moved it, after installing the public build over the test build.
        val untouched = mutablePreferencesOf()
        SettingsCodec.write(onByDefault, untouched, onByDefault)
        assertTrue(SettingsCodec.read(untouched, onByDefault).developerMode)
        assertFalse(SettingsCodec.read(untouched, Settings()).developerMode)
    }
}
