package app.sleevesight.app.settings

import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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

    @Test
    fun aChoiceStoredInOneBuildHoldsInTheOther() =
        runBlocking {
            // Turned off in the test build: off in the public build too.
            store(testBuild).update { it.copy(reportButton = false) }
            stores.close()
            assertFalse(store(Settings()).settings.first().reportButton)
            stores.close()
            // Turned on in the public build: on in the test build too.
            store(Settings()).update { it.copy(reportButton = true) }
            stores.close()
            assertTrue(store(testBuild).settings.first().reportButton)
        }
}
