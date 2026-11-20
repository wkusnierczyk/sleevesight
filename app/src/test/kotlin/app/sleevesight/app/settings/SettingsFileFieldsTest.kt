package app.sleevesight.app.settings

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.DescriptionsOnReturn
import app.sleevesight.core.settings.Dismissable
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.LinkStyle
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.LookUpAfterClear
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.ReadAloudMusic
import app.sleevesight.core.settings.ReadAloudVoice
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import app.sleevesight.core.settings.SettingsView
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.SymbolOpens
import app.sleevesight.core.settings.ThemeChoice
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * *Save to a file* and *Load from a file* (#815) cover every setting: each property of [Settings]
 * is in the file ([SettingsFile.NAMES]) or left out on purpose ([SettingsFile.LEFT_OUT]), and each in
 * the file round-trips. A setting added to [Settings] fails here until it is one or the other.
 * Here, not in `:core`, whose tests take no JVM reflection.
 */
class SettingsFileFieldsTest {
    private val defaults = Settings()

    /** Every setting off its default, developer mode on; [everySettingIsMovedInTheFixture] holds it. */
    private val moved =
        Settings(
            layout = Layout.SEPARATE_PAGES,
            aiProvider = ProviderId.OPENAI,
            aiModels = mapOf(ProviderId.ANTHROPIC to "claude-other"),
            aiEfforts = mapOf(ProviderId.ANTHROPIC to ReasoningEffort.HIGH, ProviderId.GOOGLE to null),
            followedPlayers = mapOf("org.example.tunes" to true, Settings.SPOTIFY to false),
            onlyEnabledPlayersOnHome = true,
            showPlayerLogo = false,
            symbolOpens = SymbolOpens.PLAYER,
            enabledSources = setOf(SourceSwitch.MUSICBRAINZ, SourceSwitch.MODEL),
            webEngines = emptySet(),
            labelSources = setOf(LabelSource.LASTFM),
            showGenres = false,
            writeInsights = false,
            writeAutomatically = WriteAutomatically.WHEN_I_SKIP,
            pauseAutoWriteAfter = null,
            crossfade = Crossfade.Fixed(4.seconds),
            prepareNextTrack = PrepareNextTrack.OFF,
            lookUpInBackground = true,
            artistFactsOncePerSession = false,
            readAloudVoice = ReadAloudVoice("en-gb-x-test", "Test voice"),
            readAloudMusic = ReadAloudMusic.PAUSE,
            readTrackLineFirst = true,
            readNewInsightsAloud = true,
            searchWithAi = true,
            aiWebSearch = AiWebSearch.UP_TO,
            trackLanguageSearch = TrackLanguageSearch.ALWAYS,
            musicBrainzWait = 1.minutes,
            aiRetry = 15.seconds,
            cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.MUSICBRAINZ to 3.days),
            keepArtistResults = true,
            artistCacheLifetime = 2.days,
            cacheSizeLimit = 1234,
            historyLifetime = null,
            lookUpAfterClear = LookUpAfterClear.ON_REQUEST,
            sessionGap = 120.minutes,
            sourcesOnNewTrack = NewTrackFold.AS_LEFT,
            itemsOnNewTrack = NewTrackFold.UNFOLDED,
            onePanel = false,
            lyricsLinks = false,
            lyricsSearch = false,
            showPhoneName = true,
            showDiagnostics = true,
            showGroundingDetails = true,
            developerMode = true,
            reportButton = ReportButton.SHOW,
            linkStyle = LinkStyle.HIGHLIGHT,
            markDeveloperItems = false,
            foldDescriptions = false,
            descriptionsOnReturn = DescriptionsOnReturn.FOLDED,
            unfoldedDescriptions = listOf("a", "b"),
            theme = ThemeChoice.DARK,
            themeButton = false,
            font = FontChoice.SYSTEM_FONT,
            introAtStart = false,
            spinLogo = false,
            logoSpeed = LogoSpeed.RPM_45,
            dismissed = setOf(Dismissable.PLAYERS_NOTE),
            settingsView = SettingsView.BASIC,
        )

    private val fields: List<Field> =
        Settings::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

    @Test
    fun everySettingIsInTheFileOrLeftOut() {
        val all = fields.map { it.name }.toSet()
        val inFile = SettingsFile.NAMES.toSet()
        assertEquals(
            "settings neither in the file nor left out",
            emptySet<String>(),
            all - inFile - SettingsFile.LEFT_OUT,
        )
        assertEquals("names that are no setting", emptySet<String>(), inFile + SettingsFile.LEFT_OUT - all)
    }

    @Test
    fun everySettingIsMovedInTheFixture() {
        val unmoved = fields.filter { it.get(moved) == it.get(defaults) }.map { it.name }
        assertEquals("settings the fixture leaves at their defaults", emptyList<String>(), unmoved)
    }

    @Test
    fun everySettingInTheFileRoundTrips() {
        val contents = SettingsFile.read(SettingsFile.write(moved, "0.10.0"))!!
        assertEquals(emptyList<SettingsFile.Skipped>(), contents.skipped)
        val current = defaults.copy(developerMode = true)
        val loaded = SettingsFile.load(contents, current, developerMode = true)
        assertEquals(emptyList<SettingsFile.Skipped>(), loaded.skipped)
        for (field in fields) {
            val expected = if (field.name in SettingsFile.LEFT_OUT) current else moved
            assertEquals(field.name, field.get(expected), field.get(loaded.settings))
        }
    }
}
