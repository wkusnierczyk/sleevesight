package app.sleevesight.app.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label sources' and the engines' switches as stored (#84; moved out of `SettingsCodecTest`, at
 * detekt's size limit).
 */
class SettingsCodecLabelSourcesTest {
    @Test
    fun labelSourcesAndEnginesAreStoredOnlyAsTheUsersOverrides() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(
            Settings(labelSources = setOf(LabelSource.LASTFM, LabelSource.WIKIDATA), webEngines = emptySet()),
            prefs,
        )
        assertEquals(
            mutablePreferencesOf(
                stringSetPreferencesKey("labels.off") to setOf("MUSICBRAINZ"),
                stringSetPreferencesKey("web.engines.off") to setOf("EXA"),
            ),
            prefs,
        )
        SettingsCodec.write(Settings(), prefs)
        assertTrue(prefs.asMap().isEmpty())
    }
}
