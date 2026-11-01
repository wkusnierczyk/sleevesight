package app.sleevesight.app.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.core.settings.ReadAloudMusic
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The read-aloud settings (#325) as stored: *Reduce volume*, the track line not first and new
 * insights not read by themselves by default, and for a settings file from before them; each
 * change under a key of its own, *Music while reading aloud* by name, and a name this version does not
 * know read as the default.
 */
class ReadAloudCodecTest {
    @Test
    fun theDefaultsLowerTheMusicAndReadNothingBeforeTheInsightNorByItself() {
        val defaults = Settings()
        assertEquals(ReadAloudMusic.LOWER, defaults.readAloudMusic)
        assertEquals(false, defaults.readTrackLineFirst)
        assertEquals(false, defaults.readNewInsightsAloud)
        // A settings file from before them reads as the defaults.
        assertEquals(defaults, SettingsCodec.read(mutablePreferencesOf()))
    }

    @Test
    fun eachIsStoredUnderAKeyOfItsOwn() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(
            Settings(readAloudMusic = ReadAloudMusic.PAUSE, readTrackLineFirst = true, readNewInsightsAloud = true),
            prefs,
        )
        assertEquals(
            mutablePreferencesOf(
                stringPreferencesKey("read_aloud.music") to "PAUSE",
                booleanPreferencesKey("read_aloud.track_line_first") to true,
                booleanPreferencesKey("read_aloud.new_insights") to true,
            ),
            prefs,
        )
    }

    @Test
    fun aChoiceThisVersionDoesNotKnowReadsAsLowerTheMusic() {
        val later = mutablePreferencesOf(stringPreferencesKey("read_aloud.music") to "MUTE")
        assertEquals(ReadAloudMusic.LOWER, SettingsCodec.read(later).readAloudMusic)
    }
}
