package app.sleevesight.app.settings

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * *Wait when busy* as stored (#1120): in milliseconds, under a key of its own, *Don’t wait* as zero,
 * and none stored for the default, 15 s.
 */
class MusicBrainzWaitCodecTest {
    @Test
    fun theWaitIsStoredInMillisecondsZeroIncluded() {
        for (wait in listOf(Duration.ZERO, 30.seconds, 1.minutes)) {
            val prefs = mutablePreferencesOf()
            SettingsCodec.write(Settings(musicBrainzWait = wait), prefs)
            assertEquals(mutablePreferencesOf(longPreferencesKey(KEY) to wait.inWholeMilliseconds), prefs)
            assertEquals(wait, SettingsCodec.read(prefs).musicBrainzWait)
        }
    }

    @Test
    fun theDefaultIsFifteenSecondsAndStoresNothing() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(), prefs)
        assertEquals(mutablePreferencesOf(), prefs)
        assertEquals(15.seconds, SettingsCodec.read(prefs).musicBrainzWait)
    }

    @Test
    fun aNegativeStoredWaitReadsAsTheDefault() {
        val prefs = mutablePreferencesOf(longPreferencesKey(KEY) to -1L)
        assertEquals(Settings.DEFAULT_MUSICBRAINZ_WAIT, SettingsCodec.read(prefs).musicBrainzWait)
    }

    private companion object {
        const val KEY = "sources.musicbrainz_wait_ms"
    }
}
