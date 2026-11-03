package app.sleevesight.app.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * *Sources on a new track* and *Song, Artist and Album on a new track* (#522): stored as choices, the
 * first read from the switch it was, *Keep Sources as you left them*, until it is stored.
 */
class NewTrackFoldCodecTest {
    @Test
    fun theOldSwitchOnReadsAsYouLeftThemAndOffReadsFolded() {
        val on = mutablePreferencesOf(KEEP_SOURCES to true)
        assertEquals(NewTrackFold.AS_LEFT, SettingsCodec.read(on).sourcesOnNewTrack)
        val off = mutablePreferencesOf(KEEP_SOURCES to false)
        assertEquals(NewTrackFold.FOLDED, SettingsCodec.read(off).sourcesOnNewTrack)
    }

    @Test
    fun aChoiceStoredDropsTheOldSwitchAndReadsAsChosen() {
        val prefs = mutablePreferencesOf(KEEP_SOURCES to true)
        val read = SettingsCodec.read(prefs)
        // Folded is the default, so it is stored as no key at all; the switch must go too, or it would
        // read as As you left them again.
        SettingsCodec.write(read.copy(sourcesOnNewTrack = NewTrackFold.FOLDED), prefs)
        assertEquals(NewTrackFold.FOLDED, SettingsCodec.read(prefs).sourcesOnNewTrack)
        assertFalse(KEEP_SOURCES in prefs.asMap())

        SettingsCodec.write(Settings(sourcesOnNewTrack = NewTrackFold.UNFOLDED), prefs)
        assertEquals("unfolded", prefs[SOURCES])
        assertEquals(NewTrackFold.UNFOLDED, SettingsCodec.read(prefs).sourcesOnNewTrack)
    }

    @Test
    fun theChoiceStoredWinsOverTheOldSwitch() {
        val prefs = mutablePreferencesOf(KEEP_SOURCES to false, SOURCES to "as_left")
        assertEquals(NewTrackFold.AS_LEFT, SettingsCodec.read(prefs).sourcesOnNewTrack)
    }

    @Test
    fun theItemsChoiceIsFoldedByDefaultAndStoredOtherwise() {
        assertEquals(NewTrackFold.FOLDED, Settings().itemsOnNewTrack)
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(itemsOnNewTrack = NewTrackFold.AS_LEFT), prefs)
        assertEquals("as_left", prefs[ITEMS])
        assertEquals(NewTrackFold.AS_LEFT, SettingsCodec.read(prefs).itemsOnNewTrack)
        SettingsCodec.write(Settings(), prefs)
        assertTrue(prefs.asMap().isEmpty())
    }

    @Test
    fun bothRoundTrip() {
        val chosen = Settings(sourcesOnNewTrack = NewTrackFold.AS_LEFT, itemsOnNewTrack = NewTrackFold.UNFOLDED)
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(chosen, prefs)
        assertEquals(chosen, SettingsCodec.read(prefs))
    }

    private companion object {
        val KEEP_SOURCES = booleanPreferencesKey("home.keep_sources_unfolded")
        val SOURCES = stringPreferencesKey("home.sources_on_new_track")
        val ITEMS = stringPreferencesKey("home.items_on_new_track")
    }
}
