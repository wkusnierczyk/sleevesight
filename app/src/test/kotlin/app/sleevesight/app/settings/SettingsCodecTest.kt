package app.sleevesight.app.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class SettingsCodecTest {
    private fun roundTrip(settings: Settings): Settings {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(settings, prefs)
        return SettingsCodec.read(prefs)
    }

    @Test
    fun nothingStoredReadsAsTheDefaults() {
        assertEquals(Settings(), SettingsCodec.read(mutablePreferencesOf()))
    }

    @Test
    fun theDefaultsAreNotStored() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(), prefs)
        assertTrue(prefs.asMap().toString(), prefs.asMap().isEmpty())
    }

    @Test
    fun aSettingBackAtItsDefaultLosesItsKey() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(lookUpInBackground = true), prefs)
        SettingsCodec.write(Settings(), prefs)
        assertTrue(prefs.asMap().isEmpty())
    }

    @Test
    fun everySettingRoundTrips() {
        val changed =
            Settings(
                layout = Layout.EXPANDABLE_BOXES,
                aiProvider = ProviderId.ANTHROPIC,
                aiModel = "claude-opus-5-5",
                followedPlayers = mapOf(Settings.SPOTIFY to false, "com.example.player" to true),
                enabledSources = setOf(SourceSwitch.MUSICBRAINZ, SourceSwitch.MODEL),
                writeInsights = false,
                writeAutomatically = WriteAutomatically.WHEN_I_SKIP,
                crossfade = Crossfade.Fixed(6.seconds),
                prepareNextTrack = PrepareNextTrack.EVIDENCE,
                lookUpInBackground = true,
                cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.WEB to 3.days),
                artistCacheLifetime = 30.days,
                cacheSizeLimit = 10_000_000,
                aiEffort = ReasoningEffort.HIGH,
            )
        assertEquals(changed, roundTrip(changed))
    }

    @Test
    fun theProvidersDefaultEffortIsKeptApartFromNoChoice() {
        assertNull(roundTrip(Settings(aiEffort = null)).aiEffort)
        assertEquals(Settings.DEFAULT_AI_EFFORT, roundTrip(Settings()).aiEffort)
    }

    @Test
    fun noSourcesAndNoPlayersAreChoicesToo() {
        assertEquals(emptySet<SourceSwitch>(), roundTrip(Settings(enabledSources = emptySet())).enabledSources)
        assertEquals(emptyMap<String, Boolean>(), roundTrip(Settings(followedPlayers = emptyMap())).followedPlayers)
    }

    @Test
    fun valuesThisVersionCannotUseReadAsTheirDefaults() {
        val prefs =
            mutablePreferencesOf(
                stringPreferencesKey("layout") to "CAROUSEL",
                stringPreferencesKey("ai.provider") to "SOMEONE",
                stringPreferencesKey("ai.effort") to "EXTREME",
                stringPreferencesKey("insights.write_automatically") to "SOMETIMES",
                stringPreferencesKey("insights.prepare_next_track") to "",
                longPreferencesKey("insights.crossfade_ms") to 60_000L,
                longPreferencesKey("cache.lifetime_ms.WEB") to -1L,
                longPreferencesKey("cache.artist_lifetime_ms") to 0L,
                longPreferencesKey("cache.size_limit") to -5L,
                stringSetPreferencesKey("sources.enabled") to setOf("LASTFM", "TELEPATHY"),
                booleanPreferencesKey("lookups.background") to true,
            )
        val read = SettingsCodec.read(prefs)
        assertEquals(
            Settings(enabledSources = setOf(SourceSwitch.LASTFM), lookUpInBackground = true),
            read,
        )
    }
}
