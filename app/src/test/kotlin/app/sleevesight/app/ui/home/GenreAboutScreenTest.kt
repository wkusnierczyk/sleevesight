package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.logo.SPINNING_RECORD_TAG
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A genre's panel above its table (#523), in both layouts: the definition with its credit, then
 * *Why is this …?* with the **AI** mark, or the reason once written, cited; then the table. Neither
 * shows when the notes have none.
 */
@RunWith(RobolectricTestRunner::class)
class GenreAboutScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private val jazz =
        GenreDefinition(
            "Jazz is a music genre that originated in New Orleans. It is played widely.",
            "Jazz",
            "https://en.wikipedia.org/wiki/Jazz",
        )

    private fun label(source: LabelSource = LabelSource.LASTFM) =
        TrackLabel(
            GenreLabel("jazz", "jazzblues", "Jazz", "jazz", level = GenreLevel.ARTIST, artist = "Miles Davis"),
            listOf(LabelCredit(source, "https://example.org/jazz")),
        )

    private val playing: TrackState =
        track().copy(genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "Miles Davis", listOf(label())))))

    private val written: InsightState.Written =
        InsightState.Written(
            (GroundingCheck.check(REASON, HomeFixtures.evidence, false) as InsightReply.Written).grounded,
            cached = false,
        )

    // Notes that give [definition], and the reason [reason] holds; a tap writes [written]. *Retry*
    // under a failed lookup is counted in [retried], and turns [retryGives] to looking.
    private inner class Notes(
        private val definition: GenreDefinition? = jazz,
        start: GenreReason = GenreReason.Offered,
        private val lookedUp: (String) -> Flow<DefinitionState?> = {
            flowOf(definition?.let { DefinitionState.Found(it) } ?: DefinitionState.NotFound)
        },
        private val retryGives: MutableStateFlow<DefinitionState?> = MutableStateFlow(null),
    ) : GenreNotes {
        val reason = MutableStateFlow(start)
        var taps = 0
        val retried = mutableListOf<String>()

        override fun definition(genre: String): Flow<DefinitionState?> = lookedUp(genre)

        override fun retryDefinition(genre: String) {
            retried += genre
            retryGives.value = DefinitionState.Looking
        }

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = reason

        override fun explain(
            track: TrackState,
            genre: String,
        ) {
            taps++
            reason.value = GenreReason.Shown(written)
        }
    }

    private fun show(
        content: @Composable () -> Unit,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun showBox(
        notes: GenreNotes,
        cited: MutableList<String> = mutableListOf(),
        track: TrackState = playing,
        feed: AiSearchFeed = AiSearchFeed.None,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(
                            track,
                            Settings(),
                            notes = PanelNotes(notes, { id -> cited += id }),
                            search = PanelSearch(feed, track, track.nowPlaying),
                        )
                    }
                }
            }
            compose.onNodeWithTag(GENRE_PREFIX + "artist:Miles Davis:jazz").performClick()
            compose.waitForIdle()
            block()
        }
    }

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun thePanelShowsTheDefinitionWithItsCreditThenWhyThenTheTable() =
        showBox(Notes()) {
            compose.onNodeWithText(jazz.text).assertExists()
            compose.onNodeWithText("From Wikipedia · CC BY-SA 4.0").assertExists()
            val order = listOf(JAZZ_CHIP, GENRE_DEFINITION_TAG, GENRE_WHY_TAG, GENRES_ROW_PREFIX + "0")
            val tops = order.map(::top)
            assertEquals("chip, definition, Why, tags, in that order", tops.sorted(), tops)
        }

    @Test
    fun whyCarriesTheAiMarkAndReadsAsUsesAi() =
        showBox(Notes()) {
            val question = text(R.string.genre_why, "jazz")
            compose.onNodeWithTag(GENRE_WHY_TAG).assertContentDescriptionEquals("$question, uses AI")
            val marks =
                compose
                    .onAllNodes(hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(GENRE_WHY_TAG)), true)
                    .fetchSemanticsNodes()
            assertEquals(1, marks.size)
            // A tap answers across 48 dp at least.
            compose.onNodeWithTag(GENRE_WHY_TAG).assertHeightIsAtLeast(48.dp)
        }

    @Test
    fun aTapWritesTheReasonWhichShowsUnderItsQuestionCited() {
        val notes = Notes()
        val cited = mutableListOf<String>()
        showBox(notes, cited) {
            compose.onNodeWithTag(GENRE_WHY_TAG).performClick()
            compose.waitForIdle()
            assertEquals(1, notes.taps)
            assertEquals(0, count(GENRE_WHY_TAG))
            compose.onNodeWithTag(GENRE_REASON_TAG).performScrollTo()
            compose.onNodeWithText(text(R.string.genre_why, "jazz")).assertExists()
            compose.onNodeWithText("Its modal tunes are jazz", substring = true).assertExists()
            // Its citation goes where the panel's citations go: on Home, to *Sources*' line.
            val actions =
                compose
                    .onAllNodes(hasAnyAncestor(hasTestTag(GENRE_REASON_TAG)))
                    .fetchSemanticsNodes()
                    .flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
            actions.first().action()
            assertEquals(listOf("MB1"), cited)
        }
    }

    @Test
    fun aReasonKeptFromBeforeShowsWithNoButton() =
        showBox(Notes(start = GenreReason.Shown(written.copy(cached = true)))) {
            assertEquals(0, count(GENRE_WHY_TAG))
            compose.onNodeWithText(text(R.string.home_note_kept), substring = true).assertExists()
        }

    @Test
    fun aFailedReasonSaysWhyAndOffersRetry() {
        val notes = Notes(start = GenreReason.Shown(InsightState.Failed("offline")))
        showBox(notes) {
            compose.onNodeWithText(text(R.string.genre_why_failed, text(R.string.home_failure_offline))).assertExists()
            assertEquals(0, count(GENRE_WHY_TAG))
            compose
                .onNode(hasText(text(R.string.home_retry)) and hasAnyAncestor(hasTestTag(GENRE_WHY_FAILED_TAG)))
                .performScrollTo()
                .performClick()
            assertEquals(1, notes.taps)
        }
    }

    @Test
    fun anotherGenresDefinitionNeverShowsWhileThisOnesIsLookedUp() {
        // Modal jazz's lookup never answers.
        val notes =
            Notes(lookedUp = { genre ->
                if (genre ==
                    "jazz"
                ) {
                    flowOf(DefinitionState.Found(jazz))
                } else {
                    flow { awaitCancellation() }
                }
            })
        val modal =
            TrackLabel(
                GenreLabel(
                    "modal jazz",
                    "jazzblues",
                    "Modal Jazz",
                    "modaljazz",
                    level = GenreLevel.ARTIST,
                    artist = "Miles Davis",
                ),
                listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/modal")),
            )
        val two =
            playing.copy(
                genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "Miles Davis", listOf(label(), modal)))),
            )
        showBox(notes, track = two) {
            compose.onNodeWithText(jazz.text).assertExists()
            compose.onNodeWithTag(GENRE_PREFIX + "artist:Miles Davis:modaljazz").performClick()
            compose.waitForIdle()
            assertEquals(0, count(GENRE_DEFINITION_TAG))
            compose.onNodeWithText(jazz.text).assertDoesNotExist()
        }
    }

    @Test
    fun aGenreWithNoArticleSaysSoInTheDefinitionsPlace() =
        showBox(Notes(definition = null)) {
            assertEquals(0, count(GENRE_DEFINITION_TAG))
            compose.onNodeWithTag(GENRE_NO_DEFINITION_TAG).assertTextEquals(text(R.string.genre_definition_none))
            assertEquals("No definition from Wikipedia for this genre.", text(R.string.genre_definition_none))
            val tops = listOf(JAZZ_CHIP, GENRE_NO_DEFINITION_TAG, GENRE_WHY_TAG).map(::top)
            assertEquals(tops.sorted(), tops)
        }

    @Test
    fun whileTheDefinitionIsLookedUpTheRecordAndLookingOnWikipediaShowInItsPlace() =
        showBox(Notes(lookedUp = { flowOf(DefinitionState.Looking) })) {
            assertEquals("Looking on Wikipedia…", text(R.string.genre_definition_looking))
            compose
                .onNodeWithTag(GENRE_DEFINITION_LOOKING_TAG, useUnmergedTree = true)
                .assertTextEquals(text(R.string.genre_definition_looking))
            assertEquals(0, count(GENRE_DEFINITION_TAG))
            assertEquals(0, count(GENRE_NO_DEFINITION_TAG))
            // The record before it, which a screen reader skips: no text, no description.
            val record = compose.onNodeWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(null, record.config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(null, record.config.getOrNull(SemanticsProperties.Text))
            val line = compose.onNodeWithTag(GENRE_DEFINITION_LOOKING_TAG, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(record.boundsInRoot.right <= line.boundsInRoot.left)
            // In the definition's place, above *Why is this …?*.
            assertTrue(top(GENRE_DEFINITION_LOOKING_TAG) < top(GENRE_WHY_TAG))
        }

    @Test
    fun aFailedLookupSaysSoWithRetryWhichAsksWikipediaAgainAndLooksAgain() {
        val lookup = MutableStateFlow<DefinitionState?>(DefinitionState.Failed)
        val retrying = Notes(lookedUp = { lookup }, retryGives = lookup)
        showBox(retrying) {
            assertEquals("Wikipedia could not be reached.", text(R.string.genre_definition_failed))
            compose.onNodeWithText(text(R.string.genre_definition_failed)).assertExists()
            val retry =
                compose.onNode(
                    hasTestTag(RETRY_TAG) and hasAnyAncestor(hasTestTag(GENRE_DEFINITION_FAILED_TAG)),
                )
            // "Retry. Button. Double-tap to ask Wikipedia again".
            retry.assertTextEquals(text(R.string.home_retry))
            assertEquals(
                text(R.string.home_retry_action, "Wikipedia"),
                retry
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsActions.OnClick)
                    ?.label,
            )
            assertEquals("ask Wikipedia again", text(R.string.home_retry_action, "Wikipedia"))
            assertEquals(Role.Button, retry.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
            retry.assertHeightIsAtLeast(48.dp)
            // On the line's right, and in the error colour, as the line is.
            assertTrue(retry.fetchSemanticsNode().boundsInRoot.left > lineOf(R.string.genre_definition_failed).left)
            retry.performClick()
            compose.waitForIdle()
            assertEquals(listOf("jazz"), retrying.retried)
            compose
                .onNodeWithTag(GENRE_DEFINITION_LOOKING_TAG, useUnmergedTree = true)
                .assertTextEquals(text(R.string.genre_definition_looking))
            assertEquals(0, count(GENRE_DEFINITION_FAILED_TAG))
        }
    }

    @Test
    fun aGenreWithNoArticleOffersSearchWithAiForItsDefinitionInEachOfItsStates() {
        val feed = DefinitionFeed()
        showBox(Notes(definition = null), feed = feed) {
            // Offered: under the line saying there is none, above *Why is this …?*.
            assertTrue(top(GENRE_NO_DEFINITION_TAG) < top(AI_SEARCH_TAG))
            assertTrue(top(AI_SEARCH_TAG) < top(GENRE_WHY_TAG))
            assertEquals(listOf<String?>("jazz"), feed.searched)
            compose.onNodeWithTag(AI_SEARCH_TAG).performClick()
            compose.waitForIdle()
            assertEquals(listOf("jazz" to "Jazz"), feed.asked)
            // Under way: the record, "Asking AI…" (a definition is asked from the model's knowledge), and *Stop*.
            feed.state.value = AiSearch.Searching()
            compose.waitForIdle()
            compose.onNodeWithTag(AI_SEARCHING_TAG, useUnmergedTree = true).assertTextEquals("Asking AI…")
            compose.onNodeWithTag(AI_SEARCH_STOP_TAG).performClick()
            compose.waitForIdle()
            assertEquals(listOf<String?>("jazz"), feed.stopped)
            // Answered: under a rule, then "by {model}".
            feed.state.value = AiSearch.Answered(SearchAnswer.Definition(AI_JAZZ), MODEL)
            compose.waitForIdle()
            compose.onNodeWithText(AI_JAZZ).assertExists()
            assertTrue(
                top(AI_SEARCH_RULE_TAG) <
                    compose
                        .onNodeWithText(AI_JAZZ)
                        .fetchSemanticsNode()
                        .boundsInRoot.top,
            )
            compose.onNodeWithTag(AI_SEARCH_BY_TAG).assertTextEquals("by $MODEL")
            // Nothing found.
            feed.state.value = AiSearch.NothingFound(MODEL)
            compose.waitForIdle()
            compose.onNodeWithTag(AI_SEARCH_NOTHING_TAG).assertTextEquals("$MODEL couldn’t say from its own knowledge.")
            // Failed, with *Retry*, which asks again.
            feed.state.value = AiSearch.Failed("offline")
            compose.waitForIdle()
            compose.onNodeWithTag(AI_SEARCH_FAILED_TAG).assertExists()
            compose.onNode(hasTestTag(RETRY_TAG) and hasAnyAncestor(hasTestTag(AI_SEARCH_FAILED_TAG))).performClick()
            compose.waitForIdle()
            assertEquals(2, feed.asked.size)
            // No AI service: disabled, a screen reader told why.
            feed.state.value = AiSearch.Unready
            compose.waitForIdle()
            compose.onNodeWithTag(AI_SEARCH_TAG).assertIsNotEnabled()
            assertEquals(
                text(R.string.home_ai_search_unready),
                compose.onNodeWithTag(AI_SEARCH_TAG).fetchSemanticsNode().config.getOrNull(
                    SemanticsProperties.StateDescription,
                ),
            )
        }
    }

    @Test
    fun aDefinitionFoundOffersNoSearch() {
        val feed = DefinitionFeed()
        showBox(Notes(), feed = feed) { assertEquals(0, count(AI_SEARCH_TAG)) }
    }

    // A genre's definition's search, as the test sets it, and what reached it.
    private class DefinitionFeed : AiSearchFeed {
        val state = MutableStateFlow<AiSearch>(AiSearch.Offered)
        val searched = mutableListOf<String?>()
        val asked = mutableListOf<Pair<String?, String?>>()
        val stopped = mutableListOf<String?>()

        override fun search(
            track: TrackState,
            kind: SearchKind,
            genre: String?,
        ): Flow<AiSearch> {
            if (kind == SearchKind.DEFINITION) searched += genre
            return if (kind == SearchKind.DEFINITION) state else flowOf(AiSearch.None)
        }

        override fun ask(
            track: TrackState,
            kind: SearchKind,
            facts: SearchFacts,
            automatic: Boolean,
            genre: String?,
        ): Boolean {
            asked += genre to facts.genre
            return true
        }

        override fun stop(
            track: TrackState,
            kind: SearchKind,
            genre: String?,
        ) {
            stopped += genre
        }
    }

    // Where the line [id] is laid out.
    private fun lineOf(id: Int) = compose.onNodeWithText(text(id)).fetchSemanticsNode().boundsInRoot

    @Test
    fun noDefinitionAndNoWhyLeaveTheTableAlone() =
        showBox(Notes(lookedUp = { flowOf(null) }, start = GenreReason.None)) {
            assertEquals(0, count(GENRE_DEFINITION_TAG))
            assertEquals(0, count(GENRE_WHY_TAG))
            assertEquals(0, count(GENRE_WHY_UNAVAILABLE_TAG))
            assertEquals(0, count(GENRE_REASON_TAG))
            compose.onNodeWithTag(GENRES_ROW_PREFIX + "0").assertExists()
        }

    @Test
    fun theGenresPageShowsTheSameAboveItsTable() {
        val notes = Notes()
        show({ GenresPage(playing, Settings(layout = Layout.SEPARATE_PAGES), PanelNotes(notes)) }) {
            compose.onNodeWithText(jazz.text).assertExists()
            val tops = listOf(GENRE_DEFINITION_TAG, GENRE_WHY_TAG, GENRES_ROW_PREFIX + "0").map(::top)
            assertEquals(tops.sorted(), tops)
            compose.onNodeWithTag(GENRE_WHY_TAG).performClick()
            compose.waitForIdle()
            assertEquals(1, notes.taps)
            compose.onNodeWithTag(GENRE_REASON_TAG).assertExists()
        }
    }

    @Test
    fun homeTakesItsNotesFromTheFeed() {
        val notes = Notes()
        val state =
            NowPlayingState(PipelineView(playing, aiReady = true), playing.nowPlaying, settings = Settings())
        show({ NowPlayingContent(state, NowPlayingActions(HomeFixtures.normalizer::normalize, genreNotes = notes)) }) {
            // The chips are in *Genres*' panel (#772).
            compose.onNodeWithTag(GENRES_CHIP_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(GENRE_PREFIX + "artist:Miles Davis:jazz").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(GENRE_DEFINITION_TAG).assertExists()
            compose.onNodeWithTag(GENRE_WHY_TAG).assertExists()
        }
    }

    @Test
    fun aGenreInASentenceIsInLowerCaseButForAcronymsAndSingleLetters() {
        assertEquals("jazz", inSentence("Jazz"))
        assertEquals("hip-hop", inSentence("Hip-Hop"))
        assertEquals("UK garage", inSentence("UK Garage"))
        assertEquals("RnB", inSentence("RnB"))
        assertEquals("R&B", inSentence("R&B"))
        assertEquals("drum and bass", inSentence("Drum And Bass"))
    }

    private companion object {
        const val REASON = "Its modal tunes are jazz [MB1]."
        const val JAZZ_CHIP = GENRE_PREFIX + "artist:Miles Davis:jazz"
        const val MODEL = "Claude Haiku 5.5"
        const val AI_JAZZ = "Jazz is music of swing, blue notes and improvisation, born in New Orleans."
    }
}
