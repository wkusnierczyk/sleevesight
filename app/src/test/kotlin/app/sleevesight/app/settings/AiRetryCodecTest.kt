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
 * *Retry when the AI fails* as stored (#1164): in milliseconds, under a key of its own, *Don’t retry*
 * as zero, and none stored for the default, 30 s.
 */
class AiRetryCodecTest {
    @Test
    fun theRetryIsStoredInMillisecondsZeroIncluded() {
        for (retry in listOf(Duration.ZERO, 15.seconds, 1.minutes)) {
            val prefs = mutablePreferencesOf()
            SettingsCodec.write(Settings(aiRetry = retry), prefs)
            assertEquals(mutablePreferencesOf(longPreferencesKey(KEY) to retry.inWholeMilliseconds), prefs)
            assertEquals(retry, SettingsCodec.read(prefs).aiRetry)
        }
    }

    @Test
    fun theDefaultIsThirtySecondsAndStoresNothing() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(), prefs)
        assertEquals(mutablePreferencesOf(), prefs)
        assertEquals(30.seconds, SettingsCodec.read(prefs).aiRetry)
    }

    @Test
    fun aNegativeStoredRetryReadsAsTheDefault() {
        val prefs = mutablePreferencesOf(longPreferencesKey(KEY) to -1L)
        assertEquals(Settings.DEFAULT_AI_RETRY, SettingsCodec.read(prefs).aiRetry)
    }

    private companion object {
        const val KEY = "ai.retry_ms"
    }
}
