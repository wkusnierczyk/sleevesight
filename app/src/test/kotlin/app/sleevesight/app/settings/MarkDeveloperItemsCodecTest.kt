package app.sleevesight.app.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * *Mark developer items* (#536) as stored: on with no key, as for an install from before the
 * setting; off under its own key; and its key gone once it is back on, by its switch or with
 * developer mode off.
 */
class MarkDeveloperItemsCodecTest {
    private val key = booleanPreferencesKey("developer.mark_items")

    @Test
    fun onWithNoKeyAsForAnInstallFromBefore() {
        // As stored before the setting existed: developer mode on, and no mark key.
        val before = mutablePreferencesOf(booleanPreferencesKey("developer.mode") to true)
        val read = SettingsCodec.read(before)
        assertTrue(read.markDeveloperItems)
        assertTrue(read.marksDeveloperItems)
    }

    @Test
    fun offIsStoredUnderItsKeyAndClearedOnceBackOnOrWithDeveloperModeOff() {
        val prefs = mutablePreferencesOf()
        val unmarked = Settings(developerMode = true, markDeveloperItems = false)
        SettingsCodec.write(unmarked, prefs)
        assertEquals(false, prefs[key])
        assertFalse(SettingsCodec.read(prefs).markDeveloperItems)

        SettingsCodec.write(unmarked.copy(markDeveloperItems = true), prefs)
        assertFalse("back on, it is the default and has no key", key in prefs)

        SettingsCodec.write(unmarked, prefs)
        SettingsCodec.write(unmarked.withoutDeveloperMode(), prefs)
        assertTrue(prefs.asMap().toString(), prefs.asMap().isEmpty())
    }
}
