package app.sleevesight.app.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.TrackLanguageSearch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * *Search in the track's language* as stored (#902): the choice by name, under a key of its own,
 * and none stored for the default, *If needed*.
 */
class TrackLanguageSearchCodecTest {
    @Test
    fun theChoiceIsStoredByName() {
        for (choice in listOf(TrackLanguageSearch.OFF, TrackLanguageSearch.ALWAYS)) {
            val prefs = mutablePreferencesOf()
            SettingsCodec.write(Settings(trackLanguageSearch = choice), prefs)
            assertEquals(mutablePreferencesOf(stringPreferencesKey(KEY) to choice.name), prefs)
            assertEquals(choice, SettingsCodec.read(prefs).trackLanguageSearch)
        }
    }

    @Test
    fun theDefaultIsIfNeededAndStoresNothing() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(), prefs)
        assertEquals(mutablePreferencesOf(), prefs)
        assertEquals(TrackLanguageSearch.IF_NEEDED, SettingsCodec.read(prefs).trackLanguageSearch)
    }

    @Test
    fun anUnknownStoredChoiceReadsAsTheDefault() {
        val prefs = mutablePreferencesOf(stringPreferencesKey(KEY) to "SOMETIMES")
        assertEquals(TrackLanguageSearch.IF_NEEDED, SettingsCodec.read(prefs).trackLanguageSearch)
    }

    private companion object {
        const val KEY = "sources.track_language_search"
    }
}
