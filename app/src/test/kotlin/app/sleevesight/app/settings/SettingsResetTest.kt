package app.sleevesight.app.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.memorySettings
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
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.SymbolOpens
import app.sleevesight.core.settings.ThemeChoice
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * *Reset to the defaults* (#960): every setting back to the store's defaults, but those
 * [SettingsReset.KEPT] keeps, found from the defaults, so a setting added later is reset too.
 */
class SettingsResetTest {
    /** Developer mode on and the report button shown by default, as in a build for the testing tracks. */
    private val defaults = Settings(developerMode = true, reportButton = ReportButton.AUTO)

    /** Every setting off its default; [everySettingIsMovedInTheFixture] holds it to that. */
    private val moved =
        Settings(
            layout = Layout.SEPARATE_PAGES,
            aiProvider = ProviderId.OPENAI,
            aiModels = mapOf(ProviderId.ANTHROPIC to "claude-other"),
            aiEfforts = mapOf(ProviderId.ANTHROPIC to ReasoningEffort.HIGH),
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
            sessionGap = 2.minutes * 60,
            sourcesOnNewTrack = NewTrackFold.AS_LEFT,
            itemsOnNewTrack = NewTrackFold.UNFOLDED,
            onePanel = false,
            lyricsLinks = false,
            lyricsSearch = false,
            showPhoneName = true,
            showDiagnostics = true,
            showGroundingDetails = true,
            developerMode = false,
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
        )

    /** The properties of [Settings], each its field: a data class's fields are its properties. */
    private val fields: List<Field> =
        Settings::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

    private val kept = SettingsReset.KEPT.map { it.name }.toSet()

    @Test
    fun everySettingIsMovedInTheFixture() {
        // A setting added to `Settings` fails here until the fixture moves it, and so the tests
        // below check that a reset puts it back, or that it is kept.
        val unmoved = fields.filter { it.get(moved) == it.get(defaults) }.map { it.name }
        assertEquals("settings the fixture leaves at their defaults", emptyList<String>(), unmoved)
        // As the store reads it back, so every setting is stored.
        runBlocking {
            val store = memorySettings(defaults = defaults)
            store.update { moved }
            assertEquals(moved, store.settings.first())
        }
    }

    @Test
    fun theKeptSettingsAreSettings() {
        assertTrue("$kept", fields.map { it.name }.containsAll(kept))
    }

    @Test
    fun aResetPutsEverySettingBackToItsDefaultButThoseKept() {
        val reset = SettingsReset.reset(moved, defaults)
        for (field in fields) {
            val expected = if (field.name in kept) moved else defaults
            assertEquals(field.name, field.get(expected), field.get(reset))
        }
    }

    @Test
    fun theStoreResetsToItsOwnDefaultsAndKeepsThePlayersTheAiServiceDeveloperModeAndWhatIsDismissed() =
        runBlocking {
            val store = memorySettings(defaults = defaults)
            store.update { moved }
            store.resetToDefaults()
            val read = store.settings.first()
            assertEquals(
                defaults.copy(
                    followedPlayers = moved.followedPlayers,
                    aiProvider = ProviderId.OPENAI,
                    developerMode = false,
                    dismissed = setOf(Dismissable.PLAYERS_NOTE),
                    // *Forever* kept: the default, shorter, would delete history.
                    historyLifetime = null,
                ),
                read,
            )
            assertEquals(read, store.resetOf(moved))
        }

    @Test
    fun aResetLeavesStoredOnlyWhatItKeeps() =
        runBlocking {
            val data = MemoryStore()
            val store = memorySettings(data, defaults)
            store.update { moved }
            store.resetToDefaults()
            // What writing only the kept settings to an empty file stores, and nothing else.
            val only = mutablePreferencesOf()
            SettingsCodec.write(SettingsReset.reset(moved, defaults), only, defaults)
            assertEquals(
                only.asMap().keys,
                data.data
                    .first()
                    .asMap()
                    .keys,
            )
        }

    @Test
    fun keepHistoryForGoesBackToItsDefaultUnlessThatWouldDeleteHistory() {
        val shorter = Settings.DEFAULT_HISTORY_LIFETIME - 1.days
        val longer = Settings.DEFAULT_HISTORY_LIFETIME + 1.days
        assertEquals(
            Settings.DEFAULT_HISTORY_LIFETIME,
            SettingsReset.reset(moved.copy(historyLifetime = shorter), defaults).historyLifetime,
        )
        assertEquals(longer, SettingsReset.reset(moved.copy(historyLifetime = longer), defaults).historyLifetime)
        assertEquals(null, SettingsReset.reset(moved.copy(historyLifetime = null), defaults).historyLifetime)
    }

    @Test
    fun aResetAtTheDefaultsChangesNothing() {
        assertEquals(defaults, SettingsReset.reset(defaults, defaults))
    }

    @Test
    fun keepHistoryForAsLastAskedIsDroppedOnceAResetIsSaved() =
        runBlocking {
            val store = memorySettings(defaults = defaults)
            val asked = store.historyLifetimeAsked
            asked
                .save(7.days, { change ->
                    async {
                        change()
                        true
                    }
                }, this)
                .await()
            assertEquals(7.days, asked.held(null))
            store.resetToDefaults()
            assertEquals(defaults.historyLifetime, asked.held(store.settings.first().historyLifetime))
            assertNotEquals(7.days, store.settings.first().historyLifetime)
        }

    @Test
    fun aKeepHistoryForAskedForAfterAResetIsAskedForStaysHeldOnceTheResetIsSaved() =
        runBlocking<Unit> {
            val store = memorySettings(defaults = defaults)
            val saves = CoroutineScope(Job() + Dispatchers.Unconfined)
            try {
                // Saves held behind two gates: the reset waits for the first, the choice for the second.
                val first = CompletableDeferred<Unit>()
                val second = CompletableDeferred<Unit>()
                store.saveOrder.launch(saves) { first.await() }
                val reset = store.resetter(saves)(store.settings.first())
                store.saveOrder.launch(saves) { second.await() }
                val asked = store.historyLifetimeAsked
                asked.save(7.days, { change -> store.saving(saves, change) }, saves)
                first.complete(Unit)
                assertTrue(reset.await())
                // The choice, still waiting its turn, is what a shorter time is judged against.
                assertEquals(7.days, asked.held(store.settings.first().historyLifetime))
                second.complete(Unit)
            } finally {
                saves.cancel()
            }
        }
}
