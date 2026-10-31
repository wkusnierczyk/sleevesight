package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.LaterLabels
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The *Genres* button under the album and its panel (#772): shown for every track while *Show
 * genres* is on, beside *Dates*, opening in its place and *Close* folding it back (and the genre open
 * in it), the panels stacked newest first, a new track closing both; a genre opening in a box of its
 * own inside, switched and folded; why the panel has no chip, its *Retry* and its links to the
 * settings; the focus handed between them, and what a screen reader hears. The chips and the genre's
 * box themselves are `GenresScreenTest`'s and `GenrePanelFocusScreenTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GenresButtonScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private fun label(tag: String): TrackLabel {
        val shown = tag.replaceFirstChar(Char::uppercase)
        val genre = GenreLabel(tag, "family", shown, tag, level = GenreLevel.TRACK)
        return TrackLabel(genre, listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/$tag")))
    }

    private val folkAndBallad =
        TrackGenres(listOf(GenreRow(GenreLevel.TRACK, null, listOf(label("folk"), label("ballad")))))

    // [playing] with the genres [genres], the sources [sources], and the requests after the evidence [later].
    private fun labelled(
        genres: TrackGenres = folkAndBallad,
        playing: NowPlaying = sundown,
        sources: Map<SourceId, SourceState> =
            mapOf(SourceId.MUSICBRAINZ to done(), SourceId.LASTFM to done()),
        later: Map<LabelSource, LaterLabels> = emptyMap(),
    ): TrackState = track(playing, sources = sources).copy(genres = genres, laterLabels = later)

    private fun state(
        track: TrackState = labelled(),
        settings: Settings = Settings(),
        view: (TrackState) -> PipelineView = { PipelineView(it, aiReady = true) },
    ) = NowPlayingState(
        view(track),
        track.nowPlaying,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
    )

    // What Home shows: [show] starts it, and a test changes it as the pipeline would (a new track).
    private val current = mutableStateOf(state())

    // Where the screen's taps went: *Retry*'s, and the links to the settings.
    private val retried = mutableListOf<Triple<RunKey, SourceId, NowPlaying>>()
    private val opened = mutableListOf<SettingsTarget>()

    private val actions =
        NowPlayingActions(
            normalizer::normalize,
            openSettingsAt = { opened += it },
            askAgain = { key, source, shown -> retried += Triple(key, source, shown) },
        )

    private fun show(
        initial: NowPlayingState = state(),
        block: () -> Unit,
    ) {
        current.value = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(current.value, actions) }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .boundsInRoot.top

    // Opens the panel, and checks it holds [line] and no chip.
    private fun opensTo(line: String) {
        assertEquals(1, count(GENRES_CHIP_TAG))
        tap(GENRES_CHIP_TAG)
        compose.onNodeWithText(line).assertExists()
        assertEquals(0, count(FOLK))
    }

    @Test
    fun theButtonShowsForEveryTrackWhileShowGenresIsOn() {
        val gathering = labelled(TrackGenres(), sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Pending))
        val cases =
            listOf(
                state(),
                state(gathering),
                state(labelled(TrackGenres())),
                state(labelled(TrackGenres()), Settings(labelSources = emptySet())),
                NowPlayingState(
                    PipelineView(),
                    sundown,
                    PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                    settings = Settings(),
                ),
            )
        for (case in cases) show(case) { assertEquals(1, count(GENRES_CHIP_TAG)) }
        // *Show genres* off: no button, and no chip, as *Lyrics* has none with its setting off.
        show(state(settings = Settings(showGenres = false))) {
            assertEquals(0, count(GENRES_CHIP_TAG))
            assertEquals(0, count(FOLK))
            assertEquals(1, count(DATES_CHIP_TAG))
        }
    }

    @Test
    fun theButtonOpensThePanelInItsPlaceWithTheChipsAndCloseFoldsItBack() =
        show {
            // At rest, the button alone: no chip on Home.
            assertEquals(0, count(FOLK))
            assertEquals(0, count(GENRES_PANEL_TAG))
            tap(GENRES_CHIP_TAG)
            assertEquals(0, count(GENRES_CHIP_TAG))
            assertEquals(1, count(GENRES_PANEL_TAG))
            compose
                .onNodeWithTag(GENRES_PANEL_HEADING_TAG)
                .assertTextEquals(text(R.string.home_genres))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose
                .onNodeWithTag(GENRES_PANEL_CLOSE_TAG)
                .assertTextEquals(text(R.string.page_close))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertEquals(1, count(FOLK))
            assertEquals(1, count(BALLAD))
            assertEquals(0, count(GENRE_PANEL_TAG))

            tap(GENRES_PANEL_CLOSE_TAG)
            assertEquals(1, count(GENRES_CHIP_TAG))
            assertEquals(0, count(GENRES_PANEL_TAG))
            assertEquals(0, count(FOLK))
        }

    @Test
    fun aScreenReaderReadsTheButtonAsGenresAButtonToShowGenres() =
        show {
            assertEquals("Genres", text(R.string.home_genres))
            assertEquals("show genres", text(R.string.home_genres_show))
            compose
                .onNodeWithTag(GENRES_CHIP_TAG)
                .performScrollTo()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.home_genres)),
                    ),
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assert(
                    SemanticsMatcher("click labelled \"show genres\"") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label == text(R.string.home_genres_show)
                    },
                ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
        }

    @Test
    fun theButtonsSitOnOneLineDatesFirst() =
        show {
            val dates = compose.onNodeWithTag(DATES_CHIP_TAG).fetchSemanticsNode().boundsInRoot
            val genres = compose.onNodeWithTag(GENRES_CHIP_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals(dates.center.y, genres.center.y, 1f)
            assertTrue(genres.left >= dates.right)
        }

    @Test
    fun aPanelOpenedLaterGoesAboveTheOneOpenAndClosingOneLeavesTheOther() {
        // Dates, then Genres: Genres above.
        show {
            tap(DATES_CHIP_TAG)
            tap(GENRES_CHIP_TAG)
            assertTrue(top(GENRES_PANEL_TAG) < top(DATES_PANEL_TAG))
            tap(GENRES_PANEL_CLOSE_TAG)
            assertEquals(1, count(DATES_PANEL_TAG))
            assertEquals(0, count(GENRES_PANEL_TAG))
            // The closed button's line stays on top.
            assertTrue(top(GENRES_CHIP_TAG) < top(DATES_PANEL_TAG))
        }
        // Genres, then Dates: Dates above.
        show {
            tap(GENRES_CHIP_TAG)
            tap(DATES_CHIP_TAG)
            assertTrue(top(DATES_PANEL_TAG) < top(GENRES_PANEL_TAG))
            tap(DATES_PANEL_CLOSE_TAG)
            assertEquals(1, count(GENRES_PANEL_TAG))
            assertEquals(0, count(DATES_PANEL_TAG))
            assertTrue(top(DATES_CHIP_TAG) < top(GENRES_PANEL_TAG))
        }
    }

    @Test
    fun aChipOpensItsGenreInABoxInsideAnotherSwitchesItAndTheSameChipOrItsCloseFoldsIt() =
        show {
            tap(GENRES_CHIP_TAG)
            tap(FOLK)
            assertEquals(1, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk")
            compose.onNodeWithTag(FOLK).assertIsSelected()
            // Inside the panel, under the chips.
            assertTrue(top(GENRE_PANEL_TAG) > top(FOLK))
            tap(BALLAD)
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Ballad")
            tap(BALLAD)
            assertEquals(0, count(GENRE_PANEL_TAG))
            assertEquals(1, count(BALLAD))
            tap(FOLK)
            tap(GENRE_PANEL_CLOSE_TAG)
            assertEquals(0, count(GENRE_PANEL_TAG))
            assertEquals(1, count(FOLK))
            assertEquals(1, count(GENRES_PANEL_TAG))
        }

    @Test
    fun aGenreOpenInGenresStaysOpenAsDatesOpensAboveItOrClosesOverIt() {
        // Genres first, a genre open, then Dates above it.
        show {
            tap(GENRES_CHIP_TAG)
            tap(FOLK)
            tap(DATES_CHIP_TAG)
            assertTrue(top(DATES_PANEL_TAG) < top(GENRES_PANEL_TAG))
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk")
            compose.onNodeWithTag(FOLK).assertIsSelected()
            // And Dates closed again.
            tap(DATES_PANEL_CLOSE_TAG)
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk")
        }
        // Dates first, then Genres above it with a genre open, then Dates closed under it.
        show {
            tap(DATES_CHIP_TAG)
            tap(GENRES_CHIP_TAG)
            tap(FOLK)
            tap(DATES_PANEL_CLOSE_TAG)
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk")
            tap(DATES_CHIP_TAG)
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk")
        }
    }

    @Test
    fun thePanelsCloseFoldsTheGenreOpenInItToo() =
        show {
            tap(GENRES_CHIP_TAG)
            tap(FOLK)
            assertEquals(1, count(GENRE_PANEL_TAG))
            tap(GENRES_PANEL_CLOSE_TAG)
            assertEquals(0, count(GENRE_PANEL_TAG))
            tap(GENRES_CHIP_TAG)
            assertEquals(0, count(GENRE_PANEL_TAG))
        }

    @Test
    fun aNewTrackClosesBothPanels() =
        show {
            tap(DATES_CHIP_TAG)
            tap(GENRES_CHIP_TAG)
            tap(FOLK)
            current.value = state(labelled(playing = carefree))
            compose.waitForIdle()
            assertEquals(0, count(DATES_PANEL_TAG))
            assertEquals(0, count(GENRES_PANEL_TAG))
            assertEquals(0, count(GENRE_PANEL_TAG))
            assertEquals(1, count(DATES_CHIP_TAG))
            assertEquals(1, count(GENRES_CHIP_TAG))
        }

    @Test
    fun whileTheGenresAreGatheredThePanelNamesTheSourcesStillAsked() {
        assertEquals(
            "Looking on Last.fm and MusicBrainz…",
            app.getString(R.string.home_genres_looking, "Last.fm and MusicBrainz"),
        )
        val both = mapOf(SourceId.MUSICBRAINZ to SourceState.Pending, SourceId.LASTFM to SourceState.Pending)
        show(state(labelled(TrackGenres(), sources = both))) { opensTo("Looking on Last.fm and MusicBrainz…") }
        val one = mapOf(SourceId.MUSICBRAINZ to SourceState.Pending, SourceId.LASTFM to done())
        show(state(labelled(TrackGenres(), sources = one))) { opensTo("Looking on MusicBrainz…") }
        // Before the pipeline reaches the track: the genre sources on.
        show(
            NowPlayingState(
                PipelineView(),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            ),
        ) {
            opensTo("Looking on Last.fm and MusicBrainz…")
        }
        // Last.fm's genres switched off: MusicBrainz alone.
        show(state(labelled(TrackGenres(), sources = both), Settings(labelSources = setOf(LabelSource.MUSICBRAINZ)))) {
            opensTo("Looking on MusicBrainz…")
        }
    }

    @Test
    fun untilTheRequestsAfterTheEvidenceAnswerThePanelSaysItIsLookingThenWhatTheyFound() {
        val asking = mapOf(LabelSource.LASTFM to LaterLabels.ASKING, LabelSource.MUSICBRAINZ to LaterLabels.ASKING)
        show(state(labelled(TrackGenres(), later = asking))) {
            opensTo("Looking on Last.fm and MusicBrainz…")
            compose.onNodeWithText(text(R.string.home_genres_none)).assertDoesNotExist()
            // They answer with genres: the chips, and no more looking.
            val done = mapOf(LabelSource.LASTFM to LaterLabels.DONE, LabelSource.MUSICBRAINZ to LaterLabels.DONE)
            current.value = state(labelled(later = done))
            compose.waitForIdle()
            assertEquals(1, count(FOLK))
            compose.onNodeWithText("Looking on", substring = true).assertDoesNotExist()
            // Or with none: none found.
            current.value = state(labelled(TrackGenres(), later = done))
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.home_genres_none)).assertExists()
        }
    }

    @Test
    fun underChipsAlreadyInTheSourceStillAskedIsNamed() =
        show(
            state(
                labelled(
                    later =
                        mapOf(
                            LabelSource.LASTFM to LaterLabels.ASKING,
                            LabelSource.MUSICBRAINZ to LaterLabels.DONE,
                        ),
                ),
            ),
        ) {
            tap(GENRES_CHIP_TAG)
            assertEquals(1, count(FOLK))
            val looking = compose.onNodeWithText("Looking on Last.fm…")
            looking.assertExists()
            assertTrue(top(FOLK) < looking.fetchSemanticsNode().boundsInRoot.top)
        }

    @Test
    fun aRequestAfterTheEvidenceThatFailedSaysItsSourceCouldNotBeReachedNotNoneFound() =
        show(state(labelled(TrackGenres(), later = mapOf(LabelSource.MUSICBRAINZ to LaterLabels.FAILED)))) {
            opensTo("MusicBrainz could not be reached.")
            compose.onNodeWithText(text(R.string.home_genres_none)).assertDoesNotExist()
            // *Retry* only where the pipeline offers it, for a source its lookup did not reach.
            assertEquals(0, count(RETRY_TAG))
        }

    @Test
    fun withNoGenreFoundThePanelSaysNoneFound() =
        show(state(labelled(TrackGenres()))) {
            assertEquals("None found.", text(R.string.home_genres_none))
            opensTo(text(R.string.home_genres_none))
        }

    @Test
    fun withEveryGenreSourceOffInSourcesThePanelLinksToSettingsSources() {
        val off = Settings(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.MUSICBRAINZ - SourceSwitch.LASTFM)
        show(state(labelled(TrackGenres()), off)) {
            assertEquals("Genre sources are disabled.", text(R.string.home_genres_off))
            opensTo(text(R.string.home_genres_off))
            val see = app.getString(R.string.home_genres_off_see, text(R.string.home_settings_sources_link))
            assertEquals("See Settings → Sources.", see)
            compose.onNodeWithText(see).performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.sources.linkTag
            }
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.sources), opened)
        }
    }

    @Test
    fun withTheSourcesOnAndTheirGenreSwitchesOffThePanelLinksToSettingsGenres() {
        // Last.fm off under *Sources*, MusicBrainz on there but its genres off: the switch is in *Genres*.
        val off =
            Settings(
                enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.LASTFM,
                labelSources = setOf(LabelSource.LASTFM),
            )
        show(state(labelled(TrackGenres()), off)) {
            opensTo(text(R.string.home_genres_off))
            assertEquals("Settings → Genres", text(R.string.home_settings_genres_link))
            val see = app.getString(R.string.home_genres_off_see, text(R.string.home_settings_genres_link))
            assertEquals("See Settings → Genres.", see)
            compose.onNodeWithText(see).performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.genres.linkTag
            }
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.genres), opened)
        }
    }

    // Last.fm unreachable, MusicBrainz's genres found; *Retry* offered for the run under its key.
    private fun lastFmUnreachable(offered: Boolean = true): NowPlayingState {
        val key = RunKey.of(normalizer, sundown)
        val sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.LASTFM to done(Outcome.Unreachable))
        return state(labelled(sources = sources)) {
            PipelineView(
                it,
                aiReady = true,
                askAgainKey = key.takeIf { offered },
                askable = if (offered) setOf(SourceId.LASTFM) else emptySet(),
            )
        }
    }

    @Test
    fun aSourceNotReachedGetsRetryOnItsLineUnderTheOtherSourcesChips() =
        show(lastFmUnreachable()) {
            tap(GENRES_CHIP_TAG)
            val line = "Last.fm could not be reached."
            assertEquals(line, app.getString(R.string.home_genre_source_unreachable, "Last.fm"))
            compose.onNodeWithText(line).assertExists()
            assertTrue(
                top(FOLK) <
                    compose
                        .onNodeWithText(line)
                        .fetchSemanticsNode()
                        .boundsInRoot.top,
            )
            compose
                .onNodeWithTag(RETRY_TAG)
                .performScrollTo()
                .assertTextEquals(text(R.string.home_retry))
                .assert(
                    SemanticsMatcher("click labelled \"ask Last.fm again\"") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label == "ask Last.fm again"
                    },
                )
            tap(RETRY_TAG)
            assertEquals(listOf(Triple(RunKey.of(normalizer, sundown), SourceId.LASTFM, sundown)), retried)
        }

    @Test
    fun aTapOnRetryHandsTheFocusToTheGenresClose() =
        show(lastFmUnreachable()) {
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithTag(RETRY_TAG).requestFocus()
            compose.waitForIdle()
            tap(RETRY_TAG)
            val asked = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.LASTFM to SourceState.Pending)
            current.value = state(labelled(sources = asked))
            compose.waitForIdle()
            assertEquals(0, count(RETRY_TAG))
            compose.onNodeWithText("Looking on Last.fm…").assertExists()
            compose.onNodeWithTag(GENRES_PANEL_CLOSE_TAG).assertIsFocused()
        }

    @Test
    fun whenRetryIsNotOfferedTheMessageStandsAlone() =
        show(lastFmUnreachable(offered = false)) {
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithText("Last.fm could not be reached.").assertExists()
            assertEquals(0, count(RETRY_TAG))
        }

    @Test
    fun fromAKeyboardTheFocusMovesToCloseOnOpeningAndBackToTheButtonOnClosing() =
        show {
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithTag(GENRES_PANEL_CLOSE_TAG).assertIsFocused()
            tap(GENRES_PANEL_CLOSE_TAG)
            compose.onNodeWithTag(GENRES_CHIP_TAG).assertIsFocused()
        }

    @Test
    fun aChipMovesTheFocusToItsGenresCloseAndThatCloseBackToTheChip() =
        show {
            tap(GENRES_CHIP_TAG)
            tap(FOLK)
            compose.onNodeWithTag(GENRE_PANEL_CLOSE_TAG).assertIsFocused()
            tap(GENRE_PANEL_CLOSE_TAG)
            compose.onNodeWithTag(FOLK).assertIsFocused()
        }

    @Test
    fun aNewTrackClosingThePanelWithTheFocusInItHandsTheFocusToTheButton() =
        show {
            tap(GENRES_CHIP_TAG)
            tap(FOLK)
            compose.onNodeWithTag(GENRE_PANEL_CLOSE_TAG).assertIsFocused()
            current.value = state(labelled(playing = carefree))
            compose.waitForIdle()
            compose.onNodeWithTag(GENRES_CHIP_TAG).assertIsFocused()
        }

    private companion object {
        const val FOLK = GENRE_PREFIX + "track:folk"
        const val BALLAD = GENRE_PREFIX + "track:ballad"
    }
}
