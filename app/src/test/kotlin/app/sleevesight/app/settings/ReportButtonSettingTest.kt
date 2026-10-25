package app.sleevesight.app.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * *Report button* (#452, #518), a developer option and a choice: *Auto* by default in the test
 * build, where developer mode is on by default (#451), *Hide* in any other; a choice the user stored
 * wins in either; turning developer mode off sets *Hide*; and the switch stored before it became a
 * choice carries over, on as *Auto* and off as *Hide*, in either build.
 */
class ReportButtonSettingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }

    @After
    fun close() = stores.close()

    // The test build's defaults, as `SettingsStore.BUILD_DEFAULTS` has them with the flag on.
    private val testBuild = Settings(developerMode = true, reportButton = ReportButton.AUTO)

    // Any other build's: `:core`'s.
    private val publicBuild = Settings()

    private val builds = listOf(testBuild, publicBuild)

    private val choiceKey = stringPreferencesKey("developer.report_button_choice")
    private val switchKey = booleanPreferencesKey("developer.report_button")

    private fun store(defaults: Settings) = SettingsStore(stores.open("settings"), defaults)

    private fun read(defaults: Settings) = runBlocking { store(defaults).settings.first() }.also { stores.close() }

    private fun update(
        defaults: Settings,
        transform: (Settings) -> Settings,
    ) = runBlocking { store(defaults).update(transform) }.also { stores.close() }

    // The stored choice, or null with none stored.
    private fun stored(): String? =
        runBlocking { stores.open("settings").data.first()[choiceKey] }.also { stores.close() }

    @Test
    fun theTestBuildStartsAtAutoAndAnyOtherBuildAtHide() {
        assertEquals(ReportButton.AUTO, read(testBuild).reportButton)
        assertEquals(ReportButton.HIDE, read(publicBuild).reportButton)
    }

    @Test
    fun turningDeveloperModeOffSetsHideAndItStaysHiddenAfterARestart() {
        update(testBuild) { it.withoutDeveloperMode() }
        val read = read(testBuild)
        assertEquals(false, read.developerMode)
        assertEquals(ReportButton.HIDE, read.reportButton)
        // Developer mode on again (seven taps on *Version*): the button stays as it was left.
        update(testBuild) { it.copy(developerMode = true) }
        assertEquals(ReportButton.HIDE, read(testBuild).reportButton)
    }

    @Test
    fun eachChoiceIsKeptAcrossARestartInEitherBuild() {
        for (build in builds) {
            for (choice in ReportButton.entries) {
                update(build) { it.copy(reportButton = choice) }
                assertEquals("$choice against ${build.reportButton}", choice, read(build).reportButton)
            }
        }
    }

    @Test
    fun aChoiceEqualToTheBuildsDefaultIsNotStored() {
        update(testBuild) { it.copy(reportButton = ReportButton.SHOW) }
        assertEquals(ReportButton.SHOW.name, stored())
        update(testBuild) { it.copy(reportButton = ReportButton.AUTO) }
        assertNull(stored())
        update(publicBuild) { it.copy(reportButton = ReportButton.HIDE) }
        assertNull(stored())
    }

    @Test
    fun aChoiceStoredAgainstOneBuildsDefaultHoldsWhereTheDefaultDisagrees() {
        // *Show* in a public build, where *Hide* is the default; then another setting changed in the
        // test build: still *Show* in either build.
        update(publicBuild) { it.copy(reportButton = ReportButton.SHOW) }
        update(testBuild) { it.copy(theme = ThemeChoice.DARK) }
        assertEquals(ReportButton.SHOW.name, stored())
        for (build in builds) assertEquals(ReportButton.SHOW, read(build).reportButton)

        // *Hide* in the test build; then another setting changed in a public build: back in the test
        // build, still *Hide*.
        update(testBuild) { it.copy(reportButton = ReportButton.HIDE) }
        update(publicBuild) { it.copy(theme = ThemeChoice.LIGHT) }
        assertEquals(ReportButton.HIDE.name, stored())
        assertEquals(ReportButton.HIDE, read(testBuild).reportButton)
    }

    @Test
    fun theOldSwitchOnReadsAsAutoAndOffAsHideInEitherBuild() {
        for (build in builds) {
            for ((switch, choice) in listOf(true to ReportButton.AUTO, false to ReportButton.HIDE)) {
                val prefs = mutablePreferencesOf(switchKey to switch)
                val read = SettingsCodec.read(prefs, build).reportButton
                assertEquals("switch $switch, default ${build.reportButton}", choice, read)
            }
        }
    }

    @Test
    fun aStoredChoiceWinsOverTheOldSwitch() {
        val prefs = mutablePreferencesOf(switchKey to true, choiceKey to ReportButton.HIDE.name)
        for (build in builds) assertEquals(ReportButton.HIDE, SettingsCodec.read(prefs, build).reportButton)
    }

    @Test
    fun theOldSwitchStaysUntilTheChoiceMovesAndThenGoes() {
        for (build in builds) {
            for (switch in listOf(true, false)) {
                val prefs = mutablePreferencesOf(switchKey to switch)
                val carried = SettingsCodec.read(prefs, build)
                // Another setting moved: the switch stays, and reads as before.
                SettingsCodec.write(carried.copy(theme = ThemeChoice.DARK), prefs, build)
                assertEquals(switch, prefs[switchKey])
                assertEquals(carried.reportButton, SettingsCodec.read(prefs, build).reportButton)

                // The choice moved, to each other choice, the build's default among them: the switch
                // goes, and the choice reads as made, not as the switch was.
                for (choice in ReportButton.entries - carried.reportButton) {
                    val moved = mutablePreferencesOf(switchKey to switch)
                    SettingsCodec.write(carried.copy(reportButton = choice), moved, build)
                    assertNull(moved[switchKey])
                    assertEquals("switch $switch to $choice", choice, SettingsCodec.read(moved, build).reportButton)
                }
            }
        }
    }

    @Test
    fun aChoiceOfALaterVersionReadsAsTheBuildsDefault() {
        val prefs = mutablePreferencesOf(choiceKey to "SOMETIMES", switchKey to false)
        for (build in builds) assertEquals(build.reportButton, SettingsCodec.read(prefs, build).reportButton)
    }
}
