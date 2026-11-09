package app.sleevesight.app.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.core.settings.LookUpAfterClear
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * *Look up the track again* as stored (#1112): the choice by name, under a key of its own, and none
 * stored for the default, *Automatically*.
 */
class LookUpAfterClearCodecTest {
    @Test
    fun onRequestIsStoredByName() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(lookUpAfterClear = LookUpAfterClear.ON_REQUEST), prefs)
        assertEquals(mutablePreferencesOf(stringPreferencesKey(KEY) to "ON_REQUEST"), prefs)
        assertEquals(LookUpAfterClear.ON_REQUEST, SettingsCodec.read(prefs).lookUpAfterClear)
    }

    @Test
    fun theDefaultIsAutomaticallyAndStoresNothing() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(), prefs)
        assertEquals(mutablePreferencesOf(), prefs)
        assertEquals(LookUpAfterClear.AUTOMATICALLY, SettingsCodec.read(prefs).lookUpAfterClear)
    }

    @Test
    fun anUnknownStoredChoiceReadsAsTheDefault() {
        val prefs = mutablePreferencesOf(stringPreferencesKey(KEY) to "SOMETIMES")
        assertEquals(LookUpAfterClear.AUTOMATICALLY, SettingsCodec.read(prefs).lookUpAfterClear)
    }

    private companion object {
        const val KEY = "history.look_up_after_clear"
    }
}
