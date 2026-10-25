package app.sleevesight.app.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
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
 * *Report button* (#452), a developer option: on by default in the test build, as developer mode
 * is (#451), off in any other; a choice the user stored wins in either; and turning developer mode
 * off turns it off.
 */
class ReportButtonSettingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }

    @After
    fun close() = stores.close()

    // The test build's defaults, as `SettingsStore.BUILD_DEFAULTS` has them with the flag on.
    private val testBuild = Settings(developerMode = true, reportButton = true)

    private fun store(defaults: Settings) = SettingsStore(stores.open("settings"), defaults)

    @Test
    fun theTestBuildStartsWithTheButtonOnAndAnyOtherBuildWithItOff() =
        runBlocking {
            assertTrue(store(testBuild).settings.first().reportButton)
            stores.close()
            assertFalse(store(Settings()).settings.first().reportButton)
        }

    @Test
    fun turningDeveloperModeOffTurnsTheButtonOffAndItStaysOffAfterARestart() =
        runBlocking {
            assertFalse(testBuild.withoutDeveloperMode().reportButton)
            store(testBuild).update { it.withoutDeveloperMode() }
            stores.close()

            val read = store(testBuild).settings.first()
            assertFalse(read.developerMode)
            assertFalse(read.reportButton)
            stores.close()
            // Developer mode on again (seven taps on *Version*): the button stays as it was left.
            store(testBuild).update { it.copy(developerMode = true) }
            stores.close()
            assertFalse(store(testBuild).settings.first().reportButton)
        }

    // The stored *Report button*, or null with none stored.
    private fun stored(): Boolean? =
        runBlocking { stores.open("settings").data.first()[booleanPreferencesKey("developer.report_button")] }
            .also { stores.close() }

    @Test
    fun aChoiceStoredAgainstOneBuildsDefaultHoldsWhereTheDefaultDisagrees() =
        runBlocking {
            // Turned on in a public build, where it is off by default; then another setting changed in
            // the test build, where it is on by default: back in the public build, it is still on.
            store(Settings()).update { it.copy(reportButton = true) }
            stores.close()
            store(testBuild).update { it.copy(theme = ThemeChoice.DARK) }
            stores.close()
            assertEquals(true, stored())
            assertTrue(store(Settings()).settings.first().reportButton)
            stores.close()

            // Turned off in the test build; then another setting changed in a public build: back in
            // the test build, it is still off.
            store(testBuild).update { it.copy(reportButton = false) }
            stores.close()
            store(Settings()).update { it.copy(theme = ThemeChoice.LIGHT) }
            stores.close()
            assertEquals(false, stored())
            assertFalse(store(testBuild).settings.first().reportButton)
        }
}
