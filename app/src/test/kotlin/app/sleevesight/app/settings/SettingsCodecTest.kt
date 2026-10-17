package app.sleevesight.app.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
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
                webEngines = emptySet(),
                labelSources = setOf(LabelSource.LASTFM),
                writeInsights = false,
                writeAutomatically = WriteAutomatically.WHEN_I_SKIP,
                crossfade = Crossfade.Fixed(6.seconds),
                prepareNextTrack = PrepareNextTrack.OFF,
                lookUpInBackground = true,
                cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.WEB to 3.days),
                artistCacheLifetime = 30.days,
                cacheSizeLimit = 10_000_000,
                aiEffort = ReasoningEffort.HIGH,
                historyLifetime = 7.days,
                sessionGap = 10.minutes,
                showDiagnostics = true,
            )
        assertEquals(changed, roundTrip(changed))
    }

    @Test
    fun keepingTheHistoryForeverIsKeptApartFromNoChoice() {
        assertNull(roundTrip(Settings(historyLifetime = null)).historyLifetime)
        assertEquals(Settings.DEFAULT_HISTORY_LIFETIME, roundTrip(Settings()).historyLifetime)
    }

    @Test
    fun theProvidersDefaultEffortIsKeptApartFromNoChoice() {
        assertNull(roundTrip(Settings(aiEffort = null)).aiEffort)
        assertEquals(Settings.DEFAULT_AI_EFFORT, roundTrip(Settings()).aiEffort)
    }

    @Test
    fun noSourcesAndNoPlayersAreChoicesToo() {
        assertEquals(emptySet<SourceSwitch>(), roundTrip(Settings(enabledSources = emptySet())).enabledSources)
        // A default player missing from the map is not followed, so it is stored as switched off.
        assertEquals(
            mapOf(Settings.SPOTIFY to false),
            roundTrip(Settings(followedPlayers = emptyMap())).followedPlayers,
        )
    }

    private fun storedPlayers(players: Map<String, Boolean>): Preferences {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(followedPlayers = players), prefs)
        return prefs
    }

    @Test
    fun aPlayerSwitchIsStoredOnlyAsTheUsersOverride() {
        // The default untouched: nothing stored.
        assertEquals(mutablePreferencesOf(), storedPlayers(mapOf(Settings.SPOTIFY to true)))
        // The default switched off.
        assertEquals(
            mutablePreferencesOf(stringSetPreferencesKey("players.off") to setOf(Settings.SPOTIFY)),
            storedPlayers(mapOf(Settings.SPOTIFY to false)),
        )
        // A new player seen, so listed off; the default's switch is not stored.
        assertEquals(
            mutablePreferencesOf(stringSetPreferencesKey("players.off") to setOf(OTHER_PLAYER)),
            storedPlayers(mapOf(Settings.SPOTIFY to true, OTHER_PLAYER to false)),
        )
        // A new player switched on.
        assertEquals(
            mutablePreferencesOf(stringSetPreferencesKey("players.on") to setOf(OTHER_PLAYER)),
            storedPlayers(mapOf(Settings.SPOTIFY to true, OTHER_PLAYER to true)),
        )
    }

    @Test
    fun aPlayerSwitchTheUserDidNotMoveFollowsItsDefault() {
        // Another player seen and switched on; Spotify's switch never moved, so it reads as its
        // default, as would a player a later version follows by default.
        val read =
            SettingsCodec.read(
                mutablePreferencesOf(
                    stringSetPreferencesKey("players.on") to setOf(OTHER_PLAYER),
                    stringSetPreferencesKey("players.off") to setOf("com.example.third"),
                ),
            )
        assertEquals(
            mapOf(Settings.SPOTIFY to true, OTHER_PLAYER to true, "com.example.third" to false),
            read.followedPlayers,
        )
    }

    @Test
    fun aSourceSwitchIsStoredOnlyAsTheUsersOverride() {
        val prefs = mutablePreferencesOf()
        val chosen = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL - SourceSwitch.LASTFM
        SettingsCodec.write(Settings(enabledSources = chosen), prefs)
        assertEquals(
            mutablePreferencesOf(
                stringSetPreferencesKey("sources.on") to setOf("MODEL"),
                stringSetPreferencesKey("sources.off") to setOf("LASTFM"),
            ),
            prefs,
        )

        // Switched back to its default, an override goes.
        SettingsCodec.write(Settings(enabledSources = chosen + SourceSwitch.LASTFM), prefs)
        assertEquals(mutablePreferencesOf(stringSetPreferencesKey("sources.on") to setOf("MODEL")), prefs)
    }

    @Test
    fun labelSourcesAndEnginesAreStoredOnlyAsTheUsersOverrides() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(labelSources = setOf(LabelSource.LASTFM), webEngines = emptySet()), prefs)
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

    @Test
    fun labelSourcesNotMovedFollowTheirDefaults() {
        // A default revised later (#100: MusicBrainz's off in the release) reaches whoever did not move it.
        val read =
            SettingsCodec.read(
                mutablePreferencesOf(
                    stringSetPreferencesKey("labels.off") to setOf("LASTFM", "DISCOGS"),
                ),
            )
        assertEquals(Settings.DEFAULT_LABEL_SOURCES - LabelSource.LASTFM, read.labelSources)
        assertEquals(Settings.DEFAULT_WEB_ENGINES, read.webEngines)
        assertEquals(setOf(WebEngine.EXA), read.webEngines)
    }

    @Test
    fun aSwitchTheUserDidNotMoveFollowsItsDefault() {
        // Only the model's switch was moved; Discogs, hidden and never moved, stays at its default (on),
        // as would a switch a later version adds.
        val read = SettingsCodec.read(mutablePreferencesOf(stringSetPreferencesKey("sources.on") to setOf("MODEL")))
        assertEquals(Settings.DEFAULT_SOURCES + SourceSwitch.MODEL, read.enabledSources)
        assertTrue(SourceSwitch.DISCOGS in read.enabledSources)
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
                longPreferencesKey("history.lifetime_ms") to 0L,
                longPreferencesKey("history.session_gap_ms") to -1L,
                stringSetPreferencesKey("sources.off") to setOf("LASTFM", "TELEPATHY"),
                booleanPreferencesKey("lookups.background") to true,
            )
        val read = SettingsCodec.read(prefs)
        assertEquals(
            Settings(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.LASTFM, lookUpInBackground = true),
            read,
        )
    }

    @Test
    fun evidenceAndInsightStoredReadsAsEvidence() {
        // No longer offered (D13, #20): an insight prepared from a queue item would be paid twice.
        val prefs = mutablePreferencesOf(stringPreferencesKey("insights.prepare_next_track") to "EVIDENCE_AND_INSIGHT")
        assertEquals(PrepareNextTrack.EVIDENCE, SettingsCodec.read(prefs).prepareNextTrack)
    }

    private companion object {
        const val OTHER_PLAYER = "com.example.player"
    }
}
