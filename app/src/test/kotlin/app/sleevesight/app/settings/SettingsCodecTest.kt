package app.sleevesight.app.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.ThemeChoice
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                aiModels = mapOf(ProviderId.ANTHROPIC to "claude-opus-5-5", ProviderId.OPENAI to "gpt-other"),
                aiEfforts = mapOf(ProviderId.ANTHROPIC to ReasoningEffort.HIGH),
                followedPlayers = mapOf(Settings.SPOTIFY to false, "com.example.player" to true),
                enabledSources = setOf(SourceSwitch.MUSICBRAINZ, SourceSwitch.MODEL),
                webEngines = emptySet(),
                labelSources = setOf(LabelSource.LASTFM),
                showGenres = false,
                writeInsights = false,
                writeAutomatically = WriteAutomatically.WHEN_I_SKIP,
                pauseAutoWriteAfter = 10,
                crossfade = Crossfade.Fixed(6.seconds),
                prepareNextTrack = PrepareNextTrack.OFF,
                lookUpInBackground = true,
                artistFactsOncePerSession = false,
                cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.WEB to 3.days),
                keepArtistResults = true,
                artistCacheLifetime = 30.days,
                cacheSizeLimit = 10_000_000,
                historyLifetime = 7.days,
                sessionGap = 10.minutes,
                keepSourcesUnfolded = true,
                showPhoneName = true,
                showDiagnostics = true,
                developerMode = true,
                theme = ThemeChoice.DARK,
                font = FontChoice.SYSTEM_FONT,
                introAtStart = false,
            )
        assertEquals(changed, roundTrip(changed))
    }

    @Test
    fun keepingArtistResultsIsOffByDefaultAndForAnInstallFromBefore() {
        assertEquals(false, Settings().keepArtistResults)
        // As stored before the setting existed (#420), when the artist cache was kept unasked.
        val before =
            mutablePreferencesOf(
                booleanPreferencesKey("lookups.background") to true,
                longPreferencesKey("cache.artist_lifetime_ms") to 30.days.inWholeMilliseconds,
            )
        assertEquals(false, SettingsCodec.read(before).keepArtistResults)
    }

    @Test
    fun keepingArtistResultsOnIsStored() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(keepArtistResults = true), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("cache.keep_artist_results") to true), prefs)
        SettingsCodec.write(Settings(), prefs)
        assertTrue("turned off again, it is the default and has no key", prefs.asMap().isEmpty())
    }

    @Test
    fun aSettingsFileWithoutTheFontReadsAsTheAppFont() {
        // As stored before the setting existed (#377): other settings, and no font.
        val prefs = mutablePreferencesOf(booleanPreferencesKey("lookups.background") to true)
        assertEquals(FontChoice.APP_FONT, SettingsCodec.read(prefs).font)
    }

    @Test
    fun theSystemFontIsStoredByName() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(font = FontChoice.SYSTEM_FONT), prefs)
        assertEquals(mutablePreferencesOf(stringPreferencesKey("display.font") to "SYSTEM_FONT"), prefs)
    }

    @Test
    fun aFontThisVersionDoesNotKnowReadsAsTheAppFont() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("display.font") to "SERIF")
        assertEquals(FontChoice.APP_FONT, SettingsCodec.read(prefs).font)
    }

    @Test
    fun theThemeIsStoredByNameAndOneNotKnownReadsAsTheSystems() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(theme = ThemeChoice.LIGHT), prefs)
        assertEquals(mutablePreferencesOf(stringPreferencesKey("display.theme") to "LIGHT"), prefs)
        val later = mutablePreferencesOf(stringPreferencesKey("display.theme") to "SEPIA")
        assertEquals(ThemeChoice.SYSTEM, SettingsCodec.read(later).theme)
    }

    @Test
    fun showGenresOffIsStoredUnderItsKey() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(showGenres = false), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("genres.show") to false), prefs)
    }

    @Test
    fun artistFactsAreToldOncePerSessionByDefaultAndForAnInstallFromBefore() {
        assertEquals(true, Settings().artistFactsOncePerSession)
        // As stored before the setting existed (#407), when the facts were told once per session unasked.
        val before = mutablePreferencesOf(booleanPreferencesKey("lookups.background") to true)
        assertEquals(true, SettingsCodec.read(before).artistFactsOncePerSession)
    }

    @Test
    fun artistFactsOnEveryTrackAreStoredUnderTheirKey() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(artistFactsOncePerSession = false), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("insights.artist_facts_once") to false), prefs)
        assertEquals(false, SettingsCodec.read(prefs).artistFactsOncePerSession)
        SettingsCodec.write(Settings(), prefs)
        assertTrue("turned on again, it is the default and has no key", prefs.asMap().isEmpty())
    }

    @Test
    fun aFileFromBeforeDeveloperModeWithDiagnosticsOnReadsAsDeveloperModeOn() {
        val before = mutablePreferencesOf(booleanPreferencesKey("developer.diagnostics") to true)
        assertTrue(SettingsCodec.read(before).developerMode)
        assertFalse(SettingsCodec.read(mutablePreferencesOf()).developerMode)
    }

    @Test
    fun developerModeIsStoredOnlyWhereDiagnosticsWouldReadOtherwise() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(developerMode = true), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("developer.mode") to true), prefs)
        // With *Diagnostics* on, which reads as developer mode on, its own key is not needed.
        SettingsCodec.write(Settings(developerMode = true, showDiagnostics = true), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("developer.diagnostics") to true), prefs)
        // Turned off, with its options, nothing is left.
        SettingsCodec.write(Settings(developerMode = true, showDiagnostics = true).withoutDeveloperMode(), prefs)
        assertTrue(prefs.asMap().toString(), prefs.asMap().isEmpty())
        // A state no screen makes, kept as it is: developer mode off with *Diagnostics* on.
        assertEquals(Settings(showDiagnostics = true), roundTrip(Settings(showDiagnostics = true)))
    }

    @Test
    fun thePhonesNameIsHiddenByDefaultAndShownUnderItsKey() {
        // As stored before the setting existed (#433): other settings, and no name shown.
        val before = mutablePreferencesOf(booleanPreferencesKey("lookups.background") to true)
        assertFalse(SettingsCodec.read(before).showPhoneName)
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(showPhoneName = true), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("keyboard.show_phone_name") to true), prefs)
        SettingsCodec.write(Settings(), prefs)
        assertTrue("turned off again, it is the default and has no key", prefs.asMap().isEmpty())
    }

    @Test
    fun aSettingsFileWithoutTheIntroPlaysIt() {
        // As stored before the setting existed (#383): other settings, and no intro.
        val prefs = mutablePreferencesOf(booleanPreferencesKey("lookups.background") to true)
        assertTrue(SettingsCodec.read(prefs).introAtStart)
    }

    @Test
    fun theIntroSwitchedOffIsStoredUnderItsKey() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(introAtStart = false), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("display.intro") to false), prefs)
    }

    @Test
    fun keepingTheHistoryForeverIsKeptApartFromNoChoice() {
        assertNull(roundTrip(Settings(historyLifetime = null)).historyLifetime)
        assertEquals(Settings.DEFAULT_HISTORY_LIFETIME, roundTrip(Settings()).historyLifetime)
    }

    @Test
    fun neverPausingAutomaticWritingIsKeptApartFromNoChoice() {
        assertNull(roundTrip(Settings(pauseAutoWriteAfter = null)).pauseAutoWriteAfter)
        assertEquals(Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER, roundTrip(Settings()).pauseAutoWriteAfter)
    }

    @Test
    fun theProvidersDefaultEffortIsKeptApartFromNoChoice() {
        val providerDefault = Settings().withEffort(ProviderId.ANTHROPIC, null)
        assertEquals(providerDefault, roundTrip(providerDefault))
        assertNull(roundTrip(providerDefault).effort(ProviderId.ANTHROPIC))
        val scaleDefault = Settings.EFFORT_SCALES.getValue(ProviderId.ANTHROPIC).default
        assertEquals(scaleDefault, roundTrip(Settings()).effort(ProviderId.ANTHROPIC))
    }

    @Test
    fun eachProviderKeepsItsModelAndEffortUnderKeysOfItsOwn() {
        val prefs = mutablePreferencesOf()
        val anthropic =
            Settings(aiProvider = ProviderId.ANTHROPIC)
                .withModel(ProviderId.ANTHROPIC, "claude-opus-5-5")
                .withEffort(ProviderId.ANTHROPIC, ReasoningEffort.HIGH)
        SettingsCodec.write(anthropic, prefs)
        assertEquals(
            mutablePreferencesOf(
                stringPreferencesKey("ai.provider") to "ANTHROPIC",
                stringPreferencesKey("ai.model.ANTHROPIC") to "claude-opus-5-5",
                stringPreferencesKey("ai.effort.ANTHROPIC") to "HIGH",
            ),
            prefs,
        )

        // Switched off, and another provider's model set: Anthropic's stay, and come back with it.
        SettingsCodec.write(anthropic.copy(aiProvider = null).withModel(ProviderId.OPENAI, "gpt-other"), prefs)
        val off = SettingsCodec.read(prefs)
        assertNull(off.aiProvider)
        assertEquals("claude-opus-5-5", off.model(ProviderId.ANTHROPIC))
        assertEquals("gpt-other", off.model(ProviderId.OPENAI))
        val back = off.copy(aiProvider = ProviderId.ANTHROPIC)
        SettingsCodec.write(back, prefs)
        assertEquals("claude-opus-5-5", SettingsCodec.read(prefs).model)
        assertEquals(ReasoningEffort.HIGH, SettingsCodec.read(prefs).effort)
    }

    /** What a version before #227 stored: one model and one effort, for the one service chosen. */
    private fun legacy(vararg extra: Preferences.Pair<*>) =
        mutablePreferencesOf(
            stringPreferencesKey("ai.model") to "claude-opus-5-5",
            stringPreferencesKey("ai.effort") to "HIGH",
            *extra,
        )

    @Test
    fun anInstallFromBeforeKeepsItsProviderModelAndEffort() {
        val prefs = legacy(stringPreferencesKey("ai.provider") to "ANTHROPIC")
        val read = SettingsCodec.read(prefs)
        assertEquals(ProviderId.ANTHROPIC, read.aiProvider)
        assertEquals("claude-opus-5-5", read.model)
        assertEquals(ReasoningEffort.HIGH, read.effort)

        // The first write moves them to the provider's own keys, whatever it changes.
        SettingsCodec.write(read.copy(lookUpInBackground = true), prefs)
        assertEquals(
            mutablePreferencesOf(
                stringPreferencesKey("ai.provider") to "ANTHROPIC",
                stringPreferencesKey("ai.model.ANTHROPIC") to "claude-opus-5-5",
                stringPreferencesKey("ai.effort.ANTHROPIC") to "HIGH",
                booleanPreferencesKey("lookups.background") to true,
            ),
            prefs,
        )
        assertEquals(read.copy(lookUpInBackground = true), SettingsCodec.read(prefs))

        // Moved, so set back to the defaults they do not come back.
        SettingsCodec.write(
            SettingsCodec
                .read(prefs)
                .withModel(ProviderId.ANTHROPIC, null)
                .withEffort(ProviderId.ANTHROPIC, ReasoningEffort.LOW),
            prefs,
        )
        assertEquals("claude-haiku-5-5", SettingsCodec.read(prefs).model)
        assertEquals(ReasoningEffort.LOW, SettingsCodec.read(prefs).effort)
    }

    @Test
    fun anInstallFromBeforeWithNoServiceChosenKeepsTheEffortForAnthropic() {
        // Only Anthropic was offered, and an effort could be set with no service chosen.
        val prefs = mutablePreferencesOf(stringPreferencesKey("ai.effort") to "PROVIDER_DEFAULT")
        val read = SettingsCodec.read(prefs)
        assertNull(read.aiProvider)
        assertEquals(mapOf(ProviderId.ANTHROPIC to null), read.aiEfforts)
        SettingsCodec.write(read, prefs)
        assertEquals(mutablePreferencesOf(stringPreferencesKey("ai.effort.ANTHROPIC") to "PROVIDER_DEFAULT"), prefs)
    }

    @Test
    fun movingALegacyKeyKeepsAProvidersOwnKeyForAnotherSetting() {
        // A legacy model beside the provider's own effort, a level this version cannot read.
        val own =
            arrayOf(
                stringPreferencesKey("ai.provider") to "ANTHROPIC",
                stringPreferencesKey("ai.effort.ANTHROPIC") to "EXTREME",
            )
        val prefs = mutablePreferencesOf(stringPreferencesKey("ai.model") to "claude-opus-5-5", *own)

        SettingsCodec.write(SettingsCodec.read(prefs), prefs)

        // The legacy model moves; the effort, with no legacy key beside it, stays as it was.
        assertEquals(
            mutablePreferencesOf(*own, stringPreferencesKey("ai.model.ANTHROPIC") to "claude-opus-5-5"),
            prefs,
        )
    }

    @Test
    fun aLegacyKeyBesideAProvidersOwnIsTheLatestChoiceAndWins() {
        // Only a version from before #227 writes the legacy keys: beside the provider's own, after
        // a downgrade, so they are the latest choice.
        val prefs =
            legacy(
                stringPreferencesKey("ai.provider") to "ANTHROPIC",
                stringPreferencesKey("ai.model.ANTHROPIC") to "claude-sonnet-5-5",
                stringPreferencesKey("ai.effort.ANTHROPIC") to "MEDIUM",
            )
        val read = SettingsCodec.read(prefs)
        assertEquals("claude-opus-5-5", read.model)
        assertEquals(ReasoningEffort.HIGH, read.effort)

        // Moved at the first write, replacing the provider's own.
        SettingsCodec.write(read, prefs)
        assertEquals(
            mutablePreferencesOf(
                stringPreferencesKey("ai.provider") to "ANTHROPIC",
                stringPreferencesKey("ai.model.ANTHROPIC") to "claude-opus-5-5",
                stringPreferencesKey("ai.effort.ANTHROPIC") to "HIGH",
            ),
            prefs,
        )
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
    fun aPlayerInBothKeysIsOff() {
        // No write stores it; read, it fails closed, as a switch in both of a set's keys does.
        val read =
            SettingsCodec.read(
                mutablePreferencesOf(
                    stringSetPreferencesKey("players.on") to setOf(Settings.SPOTIFY, OTHER_PLAYER),
                    stringSetPreferencesKey("players.off") to setOf(Settings.SPOTIFY, OTHER_PLAYER),
                ),
            )
        assertEquals(mapOf(Settings.SPOTIFY to false, OTHER_PLAYER to false), read.followedPlayers)
    }

    @Test
    fun aPhoneCallSwitchedOnInAnOlderFileIsIgnored() {
        // An older version listed telecom like any player, and it was switched on (#435).
        val read =
            SettingsCodec.read(
                mutablePreferencesOf(
                    stringSetPreferencesKey("players.on") to setOf(TELECOM, OTHER_PLAYER),
                    stringSetPreferencesKey("players.off") to setOf("com.google.android.dialer"),
                ),
            )
        assertEquals(mapOf(Settings.SPOTIFY to true, OTHER_PLAYER to true), read.followedPlayers)
        assertFalse(read.follows(TELECOM))
    }

    @Test
    fun pruningDropsPlayersListedOffAndCallsButKeepsChoices() {
        val prefs =
            mutablePreferencesOf(
                stringSetPreferencesKey("players.on") to setOf(TELECOM, OTHER_PLAYER),
                stringSetPreferencesKey("players.off") to setOf(Settings.SPOTIFY, "com.example.video"),
            )
        SettingsCodec.prunePlayers(prefs)
        assertEquals(
            mutablePreferencesOf(
                // Switched on: kept, so the choice is not undone.
                stringSetPreferencesKey("players.on") to setOf(OTHER_PLAYER),
                // Listed by default and switched off: dropping it would switch it back on.
                stringSetPreferencesKey("players.off") to setOf(Settings.SPOTIFY),
            ),
            prefs,
        )
    }

    @Test
    fun pruningNeverTurnsAPlayerInBothKeysOn() {
        val prefs =
            mutablePreferencesOf(
                stringSetPreferencesKey("players.on") to setOf(OTHER_PLAYER),
                stringSetPreferencesKey("players.off") to setOf(OTHER_PLAYER),
            )
        SettingsCodec.prunePlayers(prefs)
        assertFalse(SettingsCodec.read(prefs).follows(OTHER_PLAYER))
    }

    @Test
    fun pruningWithNothingToDropLeavesNoKeys() {
        val prefs = mutablePreferencesOf(stringSetPreferencesKey("players.off") to setOf("com.example.video"))
        SettingsCodec.prunePlayers(prefs)
        assertEquals(mutablePreferencesOf(), prefs)
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
                stringPreferencesKey("ai.effort.ANTHROPIC") to "EXTREME",
                stringPreferencesKey("ai.model.ANTHROPIC") to " ",
                stringPreferencesKey("insights.write_automatically") to "SOMETIMES",
                stringPreferencesKey("insights.prepare_next_track") to "",
                longPreferencesKey("insights.crossfade_ms") to 60_000L,
                intPreferencesKey("insights.pause_auto_write_after") to -3,
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
    fun aChangeKeepsWhatALaterVersionStored() {
        // As after a downgrade: values and switches this version does not know, and a key of its own.
        val later =
            arrayOf(
                stringPreferencesKey("layout") to "CAROUSEL",
                stringPreferencesKey("ai.effort.ANTHROPIC") to "EXTREME",
                stringPreferencesKey("ai.effort.OPENAI") to "ULTRA",
                longPreferencesKey("insights.crossfade_ms") to 60_000L,
                stringSetPreferencesKey("sources.on") to setOf("TELEPATHY"),
                stringSetPreferencesKey("sources.off") to setOf("LASTFM", "OUIJA"),
                booleanPreferencesKey("future.setting") to true,
            )
        val prefs = mutablePreferencesOf(*later)

        // A change to another setting leaves them as they were.
        SettingsCodec.write(SettingsCodec.read(prefs).copy(lookUpInBackground = true), prefs)
        assertEquals(mutablePreferencesOf(*later, booleanPreferencesKey("lookups.background") to true), prefs)

        // A change to a set of switches keeps the names it does not know.
        val read = SettingsCodec.read(prefs)
        SettingsCodec.write(read.copy(enabledSources = read.enabledSources + SourceSwitch.MODEL), prefs)
        assertEquals(setOf("TELEPATHY", "MODEL"), prefs[stringSetPreferencesKey("sources.on")])
        assertEquals(setOf("LASTFM", "OUIJA"), prefs[stringSetPreferencesKey("sources.off")])

        // A change to the setting itself replaces what this version cannot read.
        SettingsCodec.write(SettingsCodec.read(prefs).copy(layout = Layout.EXPANDABLE_BOXES), prefs)
        assertEquals(Layout.EXPANDABLE_BOXES.name, prefs[stringPreferencesKey("layout")])
        assertEquals("EXTREME", prefs[stringPreferencesKey("ai.effort.ANTHROPIC")])
        assertEquals("ULTRA", prefs[stringPreferencesKey("ai.effort.OPENAI")])
    }

    @Test
    fun anOverrideALaterVersionStoredAgainstAnotherDefaultSurvivesAnEditOfAnotherSwitch() {
        // A later version with other defaults stored these; here each repeats this version's default.
        val prefs =
            mutablePreferencesOf(
                stringSetPreferencesKey("labels.on") to setOf("MUSICBRAINZ"),
                stringSetPreferencesKey("sources.off") to setOf("MODEL"),
                stringSetPreferencesKey("players.on") to setOf(Settings.SPOTIFY),
            )
        val read = SettingsCodec.read(prefs)
        SettingsCodec.write(
            read.copy(
                labelSources = read.labelSources - LabelSource.LASTFM,
                enabledSources = read.enabledSources - SourceSwitch.LASTFM,
                followedPlayers = read.followedPlayers + (OTHER_PLAYER to false),
            ),
            prefs,
        )
        assertEquals(
            mutablePreferencesOf(
                stringSetPreferencesKey("labels.on") to setOf("MUSICBRAINZ"),
                stringSetPreferencesKey("labels.off") to setOf("LASTFM"),
                stringSetPreferencesKey("sources.off") to setOf("MODEL", "LASTFM"),
                stringSetPreferencesKey("players.on") to setOf(Settings.SPOTIFY),
                stringSetPreferencesKey("players.off") to setOf(OTHER_PLAYER),
            ),
            prefs,
        )

        // The switch itself, moved, is rewritten against this version's default.
        val moved = SettingsCodec.read(prefs)
        SettingsCodec.write(moved.copy(labelSources = moved.labelSources - LabelSource.MUSICBRAINZ), prefs)
        assertEquals(setOf("LASTFM", "MUSICBRAINZ"), prefs[stringSetPreferencesKey("labels.off")])
        assertNull(prefs[stringSetPreferencesKey("labels.on")])
    }

    @Test
    fun evidenceAndInsightStoredReadsAsEvidence() {
        // No longer offered (D13, #20): an insight prepared from a queue item would be paid twice.
        val prefs = mutablePreferencesOf(stringPreferencesKey("insights.prepare_next_track") to "EVIDENCE_AND_INSIGHT")
        assertEquals(PrepareNextTrack.EVIDENCE, SettingsCodec.read(prefs).prepareNextTrack)
    }

    private companion object {
        const val OTHER_PLAYER = "com.example.player"
        const val TELECOM = "com.android.server.telecom"
    }
}
