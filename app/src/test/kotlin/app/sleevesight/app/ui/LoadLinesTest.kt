package app.sleevesight.app.ui

import app.sleevesight.app.settings.SettingsReset
import app.sleevesight.app.ui.settings.LOAD_ONLY_LINES
import app.sleevesight.app.ui.settings.PLAYERS_FIELD
import app.sleevesight.app.ui.settings.RESET_LINES
import app.sleevesight.app.ui.settings.fileTitle
import app.sleevesight.core.settings.SettingsFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * *Load settings?* (#815) accounts for every setting a file holds: each has a line when a load
 * changes it ([RESET_LINES], [LOAD_ONLY_LINES], or *Players*' own) and a title for a line under *Not
 * loaded:*. A setting added to the file fails here until it has both.
 */
class LoadLinesTest {
    private val inFile = SettingsFile.NAMES.toSet()

    @Test
    fun everySettingInTheFileHasALine() {
        val lined = (RESET_LINES + LOAD_ONLY_LINES).flatMap { it.fields }.toSet() + PLAYERS_FIELD
        assertEquals("settings in the file with no line", emptySet<String>(), inFile - lined)
        assertEquals("lines for settings never in a file", emptySet<String>(), lined - inFile)
    }

    @Test
    fun everySettingInTheFileHasATitle() {
        assertEquals(emptyList<String>(), inFile.filter { fileTitle(it) == null })
    }

    @Test
    fun theFilesDeveloperSettingsAreThoseOfTheResetAndOfDeveloper() {
        assertEquals(SettingsReset.DEVELOPER.map { it.name }.toSet(), SettingsFile.DEVELOPER)
        assertEquals(RESET_LINES.filter { it.developer }.flatMap { it.fields }.toSet(), SettingsFile.DEVELOPER)
    }
}
