package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.logo.SPINNING_RECORD_TAG
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.lyrics.LyricsLink
import app.sleevesight.core.lyrics.LyricsSite
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import app.sleevesight.core.synthesis.Original
import app.sleevesight.core.synthesis.ReleaseType
import app.sleevesight.core.synthesis.Released
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

/**
 * *Search with AI* under the empty *Dates*, *Genres* and *Lyrics* (#773), with the searches faked:
 * the button under each empty state, not while looking, nor for an instrumental, nor with something
 * to show; disabled with no AI service, a screen reader told why; under way with *Stop*; the answer
 * under a rule, credited to the model, as the panel shows its source's; nothing found; a failure,
 * with *Retry* on its line; an AI-named genre opening in its box with its definition alone; and no
 * search at all with *Show insights* off. When the setting starts a search is `AutoSearchesTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class AiSearchScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val model = "Claude Haiku 5.5"

    // Each kind's search as the test sets it, and the asks and stops that reached it.
    private val feed =
        object : AiSearchFeed {
            val states = SearchKind.entries.associateWith { MutableStateFlow<AiSearch>(AiSearch.Offered) }
            val asked = mutableListOf<Pair<SearchKind, SearchFacts>>()
            val stopped = mutableListOf<SearchKind>()

            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> = states.getValue(kind)

            override fun ask(
                track: TrackState,
                kind: SearchKind,
                facts: SearchFacts,
                automatic: Boolean,
                genre: String?,
            ): Boolean {
                asked += kind to facts
                return true
            }

            override fun stop(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ) {
                stopped += kind
            }
        }

    private val folkRockDefinition = "Folk rock is a hybrid music genre combining elements of folk and rock."

    // Every genre's definition found, as Wikipedia's would be; no reason offered.
    private val notes =
        object : GenreNotes {
            override fun definition(genre: String): Flow<DefinitionState?> =
                flowOf(
                    DefinitionState.Found(
                        GenreDefinition(folkRockDefinition, "Folk rock", "https://en.wikipedia.org/wiki/$genre"),
                    ),
                )

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> = flowOf(GenreReason.Offered)

            override fun explain(
                track: TrackState,
                genre: String,
            ) = Unit
        }

    private val actions = NowPlayingActions(normalizer::normalize, genreNotes = notes, aiSearches = feed)

    private val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)

    private fun state(
        track: TrackState,
        settings: Settings = Settings(),
    ) = NowPlayingState(PipelineView(track, aiReady = true), track.nowPlaying, playback, settings = settings)

    // Sundown with MusicBrainz's [musicBrainz] state and Last.fm's done, no date and no genre.
    private fun empty(musicBrainz: SourceState = done(Outcome.None)): TrackState =
        track(sources = mapOf(SourceId.MUSICBRAINZ to musicBrainz, SourceId.LASTFM to done()))

    private val current = mutableStateOf(state(empty()))

    private fun show(
        initial: NowPlayingState = state(empty()),
        block: () -> Unit,
    ) {
        current.value = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    ArtTheme(null) { errorColour = MaterialTheme.colorScheme.error }
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(current.value, actions) }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun set(
        kind: SearchKind,
        search: AiSearch,
    ) {
        feed.states.getValue(kind).value = search
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun shows(line: String) = compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty()

    // The font style [tag]'s text is laid out in.
    private fun fontStyle(tag: String): FontStyle? {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts
            .single()
            .layoutInput.style.fontStyle
    }

    // The colour [line]'s text is laid out in.
    private fun colour(line: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(line)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts
            .single()
            .layoutInput.style.color
    }

    // The error colour of Home's theme, as the art's colours leave it (no art here).
    private var errorColour = Color.Unspecified

    // Where a node is in the scrolled column, not clipped to the screen.
    private fun placed(node: SemanticsNode) = Rect(node.positionInRoot, node.size.toSize())

    private fun bounds(tag: String) = placed(compose.onNodeWithTag(tag).fetchSemanticsNode())

    private fun boundsOf(line: String) = placed(compose.onNodeWithText(line).fetchSemanticsNode())

    @Test
    fun theTextsAreThePickedOnesAndTheProposedOnesStandApart() {
        assertEquals("Ask AI", text(R.string.home_ai_search))
        assertEquals("ask your AI service, a paid call", text(R.string.home_ai_search_action))
        assertEquals("No AI service is enabled.", text(R.string.home_ai_search_unready))
        assertEquals("Searching the web with AI…", text(R.string.home_ai_searching))
        assertEquals("Asking AI…", text(R.string.home_ai_asking))
        assertEquals("Stop", text(R.string.home_ai_stop))
        assertEquals("stop the call", text(R.string.home_ai_stop_action))
        assertEquals("by Claude Haiku 5.5", app.getString(R.string.home_ai_by, model))
        assertEquals("Asked automatically, by Claude Haiku 5.5", app.getString(R.string.home_ai_by_auto, model))
        assertEquals(
            "Claude Haiku 5.5 couldn’t say from its own knowledge.",
            app.getString(R.string.home_ai_nothing, model),
        )
        assertEquals("Claude Haiku 5.5 found nothing on the web.", app.getString(R.string.home_ai_nothing_web, model))
        assertEquals("Asked automatically: x", app.getString(R.string.home_ai_nothing_auto, "x"))
        assertEquals("From discogs.com", app.getString(R.string.home_ai_from, "discogs.com"))
        assertEquals("From the AI model’s own knowledge, not verified", text(R.string.home_cite_model))
        assertEquals("The song’s page, from the web search for this track.", text(R.string.home_lyrics_web_note))
        assertEquals(
            "Asking AI failed: no connection.",
            app.getString(R.string.home_ai_failed, text(R.string.home_failure_offline)),
        )
        assertEquals("ask your AI service again, a paid call", text(R.string.home_ai_retry_action))
    }

    @Test
    fun underEachEmptyStateOfDatesTheButtonShowsUnderTheLine() {
        val cases =
            listOf(
                done(Outcome.SourceOff) to R.string.home_musicbrainz_off,
                done(Outcome.Unreachable) to R.string.home_musicbrainz_unreachable,
                done(Outcome.None) to R.string.home_musicbrainz_not_found,
                done(Outcome.SongArticle) to R.string.home_dates_none,
            )
        for ((musicBrainz, line) in cases) {
            show(state(empty(musicBrainz))) {
                tap(DATES_CHIP_TAG)
                assertEquals(1, count(AI_SEARCH_TAG))
                assertTrue(bounds(AI_SEARCH_TAG).top >= boundsOf(text(line)).bottom)
            }
        }
    }

    @Test
    fun theButtonReadsAsPickedAndATapAsksForTheTracksDates() =
        show {
            tap(DATES_CHIP_TAG)
            compose
                .onNodeWithTag(AI_SEARCH_TAG)
                .performScrollTo()
                .assertTextEquals(text(R.string.home_ai_search))
                .assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(
                    SemanticsMatcher("click labelled \"ask your AI service, a paid call\"") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label == text(R.string.home_ai_search_action)
                    },
                ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assertTouchHeightIsEqualTo(48.dp)
            // No AI mark: the plain label was picked.
            assertEquals(0, compose.onAllNodesWithText(text(R.string.home_ai_mark)).fetchSemanticsNodes().size)
            tap(AI_SEARCH_TAG)
            val (kind, facts) = feed.asked.single()
            assertEquals(SearchKind.DATES, kind)
            assertEquals("Sundown", facts.title)
            assertEquals(listOf("Gordon Lightfoot"), facts.artists)
            assertEquals(sundown.album, facts.album)
        }

    @Test
    fun whileMusicBrainzIsAskedOrWithDatesToShowThereIsNoButton() {
        show(state(empty(SourceState.Pending))) {
            tap(DATES_CHIP_TAG)
            assertTrue(shows(text(R.string.home_musicbrainz_looking)))
            assertEquals(0, count(AI_SEARCH_TAG))
        }
        val dated =
            empty(done()).copy(
                resolution =
                    Resolution(releaseDates = listOf(ReleaseDate("Sundown", "1974-01", "g"))),
            )
        show(state(dated)) {
            tap(DATES_CHIP_TAG)
            assertEquals(0, count(AI_SEARCH_TAG))
        }
    }

    @Test
    fun withNoAiServiceReadyTheButtonIsDisabledAndAScreenReaderSaysWhy() =
        show {
            set(SearchKind.DATES, AiSearch.Unready)
            tap(DATES_CHIP_TAG)
            compose
                .onNodeWithTag(AI_SEARCH_TAG)
                .assertIsNotEnabled()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        text(R.string.home_ai_search_unready),
                    ),
                ).performClick()
            compose.waitForIdle()
            assertTrue(feed.asked.isEmpty())
        }

    @Test
    fun whileItSearchesTheTurningRecordThenTheLineWithStopBesideIt() =
        show {
            tap(DATES_CHIP_TAG)
            set(SearchKind.DATES, AiSearch.Searching())
            assertEquals(0, count(AI_SEARCH_TAG))
            compose.onNodeWithTag(AI_SEARCHING_TAG).assertTextEquals("Asking AI…")
            // The record first on the line, hidden from a screen reader: the text says what is under way.
            val record = compose.onNodeWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(placed(record).right <= bounds(AI_SEARCHING_TAG).left)
            assertEquals(null, record.config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(null, record.config.getOrNull(SemanticsProperties.Text))
            compose
                .onNodeWithTag(AI_SEARCH_STOP_TAG)
                .assertTextEquals(text(R.string.home_ai_stop))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(
                    SemanticsMatcher("click labelled \"stop the call\"") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label == text(R.string.home_ai_stop_action)
                    },
                ).assertTouchHeightIsEqualTo(48.dp)
            // At the right of the line, centred on it.
            val line = bounds(AI_SEARCHING_TAG)
            val stop = bounds(AI_SEARCH_STOP_TAG)
            assertTrue(stop.left >= line.right)
            assertEquals(line.center.y, stop.center.y, 1f)
            tap(AI_SEARCH_STOP_TAG)
            assertEquals(listOf(SearchKind.DATES), feed.stopped)
        }

    @Test
    fun datesFoundShowUnderARuleAsThePanelsSentencesCreditedToTheModelInItalics() =
        show {
            tap(DATES_CHIP_TAG)
            set(
                SearchKind.DATES,
                AiSearch.Answered(
                    SearchAnswer.Dates(
                        album = "1974-01",
                        first = Released("1973-11", "Sundown", ReleaseType.SINGLE),
                        original = Original("Someone", "1972", Released("1972", "First", ReleaseType.EP)),
                    ),
                    model,
                ),
            )
            assertEquals(0, count(AI_SEARCH_TAG))
            val rule = bounds(AI_SEARCH_RULE_TAG)
            assertTrue(rule.top >= boundsOf(text(R.string.home_musicbrainz_not_found)).bottom)
            val sentences =
                listOf(
                    "This album was released in January 1974.",
                    "This track was first released on the single Sundown in November 1973.",
                    "The song was originally recorded in 1972 by Someone, and released on the EP First.",
                )
            for (sentence in sentences) assertTrue(sentence, boundsOf(sentence).top >= rule.bottom)
            compose.onNodeWithTag(AI_SEARCH_BY_TAG).assertTextEquals("by Claude Haiku 5.5")
            assertTrue(bounds(AI_SEARCH_BY_TAG).top >= boundsOf(sentences.last()).bottom)
            assertEquals(FontStyle.Italic, fontStyle(AI_SEARCH_BY_TAG))
        }

    @Test
    fun aFirstReleaseNamedAsTheAlbumPlayedBesideTheAlbumsDateIsSaidToBeThisAlbum() =
        show {
            tap(DATES_CHIP_TAG)
            val first = Released("1974-01", sundown.album, ReleaseType.ALBUM)
            set(SearchKind.DATES, AiSearch.Answered(SearchAnswer.Dates(album = "1974-01", first = first), model))
            compose.waitUntil(WAIT_MS) { shows("This album was released in January 1974.") }
            assertTrue(shows(text(R.string.home_dates_first_here)))
        }

    @Test
    fun aFirstReleaseNamedAsTheAlbumPlayedWithNoAlbumDateKeepsItsDate() =
        show {
            tap(DATES_CHIP_TAG)
            val first = Released("1974-01", sundown.album, ReleaseType.ALBUM)
            set(SearchKind.DATES, AiSearch.Answered(SearchAnswer.Dates(first = first), model))
            compose.waitUntil(WAIT_MS) { shows("This track was first released on Sundown in January 1974.") }
            assertFalse(shows(text(R.string.home_dates_first_here)))
        }

    @Test
    fun anAnswerWithNothingUsableSaysTheModelFoundNothingUnderTheRuleWithNoButton() =
        show {
            tap(DATES_CHIP_TAG)
            set(SearchKind.DATES, AiSearch.NothingFound(model))
            compose
                .onNodeWithTag(
                    AI_SEARCH_NOTHING_TAG,
                ).assertTextEquals("Claude Haiku 5.5 couldn’t say from its own knowledge.")
            assertTrue(bounds(AI_SEARCH_NOTHING_TAG).top >= bounds(AI_SEARCH_RULE_TAG).bottom)
            assertEquals(0, count(AI_SEARCH_TAG))
        }

    @Test
    fun aFailureSaysWhyInTheErrorColourWithRetryOnItsLineWhichAsksAgain() =
        show {
            tap(DATES_CHIP_TAG)
            set(SearchKind.DATES, AiSearch.Failed("offline"))
            val line = "Asking AI failed: no connection."
            assertTrue(shows(line))
            // No button under it, no rule: Retry at the right of the line, centred on it.
            assertEquals(0, count(AI_SEARCH_TAG))
            assertEquals(0, count(AI_SEARCH_RULE_TAG))
            val retry = bounds(RETRY_TAG)
            assertTrue(retry.left >= boundsOf(line).right)
            assertEquals(boundsOf(line).center.y, retry.center.y, 1f)
            compose
                .onNodeWithTag(RETRY_TAG)
                .assertTextEquals(text(R.string.home_retry))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(
                    SemanticsMatcher("click labelled \"ask your AI service again, a paid call\"") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label == text(R.string.home_ai_retry_action)
                    },
                )
            assertEquals(errorColour, colour(line))
            set(SearchKind.DATES, AiSearch.Failed(AiSearches.MALFORMED))
            assertTrue(shows("Asking AI failed: ${text(R.string.home_failure_malformed)}."))
            tap(RETRY_TAG)
            assertEquals(listOf(SearchKind.DATES), feed.asked.map { it.first })
        }

    @Test
    fun withShowInsightsOffThereIsNoButtonAtAll() =
        show {
            set(SearchKind.DATES, AiSearch.None)
            tap(DATES_CHIP_TAG)
            assertTrue(shows(text(R.string.home_musicbrainz_not_found)))
            assertEquals(0, count(AI_SEARCH_TAG))
        }

    // A genre source's label, Last.fm's.
    private fun labelled(tag: String): TrackGenres {
        val genre = GenreLabel(tag, "family", tag.replaceFirstChar(Char::uppercase), tag, level = GenreLevel.TRACK)
        val label = TrackLabel(genre, listOf(LabelCredit(LabelSource.LASTFM, null)))
        return TrackGenres(listOf(GenreRow(GenreLevel.TRACK, null, listOf(label))))
    }

    @Test
    fun underEachEmptyStateOfGenresTheButtonShowsNotWhileLookingNorWithGenres() {
        val off = Settings(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.MUSICBRAINZ - SourceSwitch.LASTFM)
        val unreachable =
            track(
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to done(),
                        SourceId.LASTFM to done(Outcome.Unreachable),
                    ),
            )
        for ((shown, line) in listOf(
            state(empty()) to R.string.home_genres_none,
            state(empty(), off) to R.string.home_genres_off,
            state(unreachable) to null,
        )) {
            show(shown) {
                tap(GENRES_CHIP_TAG)
                assertEquals(1, count(AI_SEARCH_TAG))
                line?.let { assertTrue(bounds(AI_SEARCH_TAG).top >= boundsOf(text(it)).bottom) }
            }
        }
        show(state(empty(SourceState.Pending))) {
            tap(GENRES_CHIP_TAG)
            assertEquals(0, count(AI_SEARCH_TAG))
        }
        show(state(empty().copy(genres = labelled("folk")))) {
            tap(GENRES_CHIP_TAG)
            assertEquals(0, count(AI_SEARCH_TAG))
        }
    }

    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    @Test
    fun genresNamedShowAsChipsAndOneUnfoldsItsDefinitionAlone() =
        show {
            tap(GENRES_CHIP_TAG)
            val named = vocabulary.labelsFor(listOf("folk rock", "rock"), level = GenreLevel.TRACK)
            set(SearchKind.GENRES, AiSearch.Answered(SearchAnswer.Genres(named), model))
            val chip = GENRE_PREFIX + "track:folkrock"
            compose.onNodeWithTag(chip).assertTextEquals("Folk Rock")
            assertEquals(1, count(GENRE_PREFIX + "track:rock"))
            assertTrue(bounds(chip).top >= bounds(AI_SEARCH_RULE_TAG).bottom)
            assertTrue(bounds(AI_SEARCH_BY_TAG).top >= bounds(chip).bottom)
            tap(chip)
            // Unfolded under the chips, with no heading or *Close* of its own (#786).
            assertEquals(0, count(GENRE_PANEL_HEADING_TAG))
            compose.onNodeWithTag(GENRE_DEFINITION_TAG).assertExists()
            assertTrue(shows(folkRockDefinition))
            // Nothing else: no second credit, no *Why is this …?*, no table.
            assertEquals(1, compose.onAllNodesWithText("by Claude Haiku 5.5").fetchSemanticsNodes().size)
            assertEquals(0, count(GENRE_WHY_TAG))
            assertEquals(0, count(GENRES_ROW_PREFIX + "0"))
            // The same chip folds it, as the others do.
            tap(chip)
            assertEquals(0, count(GENRE_PANEL_TAG))
        }

    @Test
    fun onTheGenresPageTheSearchShowsUnderNoGenre() {
        val pages = Settings(layout = Layout.SEPARATE_PAGES)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { NowPlayingPageContent(HomePage.Genres, state(empty(), pages), actions) {} }
            }
            compose.waitForIdle()
            assertEquals(1, count(AI_SEARCH_TAG))
            tap(AI_SEARCH_TAG)
            assertEquals(listOf(SearchKind.GENRES), feed.asked.map { it.first })
        }
    }

    // MusicBrainz found the song, and says [lyrics] of it.
    private fun lyrics(lyrics: WorkLyrics) =
        SourceState.Done(
            SourceResult(Outcome.SongArticle, emptyList(), 10.milliseconds, lyrics = lyrics),
            cached = false,
        )

    @Test
    fun underEachEmptyStateOfLyricsTheButtonShowsAfterTheGeniusSearch() {
        val cases =
            listOf(
                done(Outcome.SourceOff),
                done(Outcome.Unreachable),
                done(Outcome.None),
                lyrics(WorkLyrics()),
            )
        for (musicBrainz in cases) {
            show(state(empty(musicBrainz))) {
                tap(LYRICS_CHIP_TAG)
                assertEquals(1, count(AI_SEARCH_TAG))
                assertTrue(bounds(AI_SEARCH_TAG).top >= bounds(LYRICS_SEARCH_TAG).bottom)
            }
        }
        // With the search off, after the line saying so.
        show(state(empty(lyrics(WorkLyrics())), Settings(lyricsSearch = false))) {
            tap(LYRICS_CHIP_TAG)
            assertTrue(bounds(AI_SEARCH_TAG).top >= boundsOf(text(R.string.home_lyrics_search_off)).bottom)
        }
    }

    @Test
    fun forLyricsNoButtonWhileLookingForAnInstrumentalOrWithAPage() {
        val pages = lyrics(WorkLyrics(listOf("https://genius.com/Gordon-lightfoot-sundown-lyrics")))
        for (musicBrainz in listOf(SourceState.Pending, lyrics(WorkLyrics(instrumental = true)), pages)) {
            show(state(empty(musicBrainz))) {
                tap(LYRICS_CHIP_TAG)
                assertEquals(0, count(AI_SEARCH_TAG))
            }
        }
    }

    @Test
    fun lyricsPagesFoundShowAsTheLinksDoCreditedToTheModel() =
        show(state(empty(lyrics(WorkLyrics())))) {
            tap(LYRICS_CHIP_TAG)
            val page = "https://genius.com/Gordon-lightfoot-sundown-lyrics"
            set(
                SearchKind.LYRICS,
                AiSearch.Answered(SearchAnswer.Lyrics(listOf(LyricsLink(LyricsSite.GENIUS, page))), model),
            )
            val link = bounds(lyricsPageTag(LyricsSite.GENIUS.name))
            assertTrue(link.top >= bounds(AI_SEARCH_RULE_TAG).bottom)
            assertTrue(shows(app.getString(R.string.home_lyrics_page, "Genius")))
            // "Opens in your browser." under it, not where it goes: no note of an unchecked page.
            assertTrue(shows(text(R.string.home_lyrics_browser)))
            assertTrue(!shows(text(R.string.home_lyrics_page_note)))
            assertTrue(bounds(AI_SEARCH_BY_TAG).top >= link.bottom)
        }

    @Test
    fun withNoSearchesHereThePanelsShowTheirEmptyStatesAlone() {
        val none = NowPlayingActions(normalizer::normalize)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state(empty()), none) }
                }
            }
            compose.waitForIdle()
            tap(DATES_CHIP_TAG)
            assertTrue(shows(text(R.string.home_musicbrainz_not_found)))
            assertEquals(0, count(AI_SEARCH_TAG))
            assertEquals(AiSearchFeed.None, none.aiSearches)
        }
    }
}
