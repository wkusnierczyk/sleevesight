package app.sleevesight.app.settings

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * *AI web search* as stored (#801): the choice by name, under a key of its own; the limit is fixed at
 * 3 ([Settings.AI_WEB_SEARCH_LIMIT]) with no setting, so a limit stored while it could be set is
 * ignored.
 */
class AiWebSearchCodecTest {
    @Test
    fun theChoiceIsStoredByName() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(aiWebSearch = AiWebSearch.UP_TO), prefs)
        assertEquals(mutablePreferencesOf(stringPreferencesKey("insights.ai_web_search") to "UP_TO"), prefs)
        assertEquals(AiWebSearch.UP_TO, SettingsCodec.read(prefs).aiWebSearch)
    }

    @Test
    fun aStoredLimitIsIgnored() {
        val before = mutablePreferencesOf(intPreferencesKey("insights.ai_web_search_limit") to 5)
        assertEquals(Settings(), SettingsCodec.read(before))
    }
}
