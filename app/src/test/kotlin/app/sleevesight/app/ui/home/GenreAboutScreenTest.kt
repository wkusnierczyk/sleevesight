package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.home.HomeFixtures.track
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
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
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

    // Notes that give [definition], and the reason [reason] holds; a tap writes [written].
    private inner class Notes(
        private val definition: GenreDefinition? = jazz,
        start: GenreReason = GenreReason.Offered,
        private val lookedUp: (String) -> Flow<Lookup<GenreDefinition>?> = {
            flowOf(definition?.let { Lookup.Found(it) } ?: Lookup.NotFound)
        },
    ) : GenreNotes {
        val reason = MutableStateFlow(start)
        var taps = 0

        override fun definition(genre: String): Flow<Lookup<GenreDefinition>?> = lookedUp(genre)

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
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(track, Settings(), notes = PanelNotes(notes, { id -> cited += id }))
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
            val order = listOf(GENRE_PANEL_HEADING_TAG, GENRE_DEFINITION_TAG, GENRE_WHY_TAG, GENRES_ROW_PREFIX + "0")
            val tops = order.map(::top)
            assertEquals("heading, definition, Why, table, in that order", tops.sorted(), tops)
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
    fun aFailedReasonSaysWhyAndOffersWhyAgain() {
        val notes = Notes(start = GenreReason.Shown(InsightState.Failed("offline")))
        showBox(notes) {
            compose.onNodeWithText(text(R.string.genre_why_failed, text(R.string.home_failure_offline))).assertExists()
            compose.onNodeWithTag(GENRE_WHY_TAG).performScrollTo().performClick()
            assertEquals(1, notes.taps)
        }
    }

    @Test
    fun aGenreOnlyMusicBrainzGivesShowsWhyDisabledWithWhyUnderIt() {
        val notes = Notes(start = GenreReason.Withheld)
        val only =
            playing.copy(
                genres =
                    TrackGenres(
                        listOf(GenreRow(GenreLevel.ARTIST, "Miles Davis", listOf(label(LabelSource.MUSICBRAINZ)))),
                    ),
            )
        showBox(notes, track = only) {
            val question = text(R.string.genre_why, "jazz")
            // The same button, with its AI mark, disabled: TalkBack says so, and a tap does nothing.
            compose.onNodeWithTag(GENRE_WHY_TAG).assertIsNotEnabled()
            compose.onNodeWithTag(GENRE_WHY_TAG).assertContentDescriptionEquals("$question, uses AI")
            val marks =
                compose
                    .onAllNodes(hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(GENRE_WHY_TAG)), true)
                    .fetchSemanticsNodes()
            assertEquals(1, marks.size)
            compose.onNodeWithTag(GENRE_WHY_TAG).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag(GENRE_WHY_WITHHELD_TAG).assertTextEquals(text(R.string.genre_why_withheld))
            val tops = listOf(GENRE_WHY_TAG, GENRE_WHY_WITHHELD_TAG, GENRES_ROW_PREFIX + "0").map(::top)
            assertEquals("Why, its note, table, in that order", tops.sorted(), tops)
            compose.onNodeWithTag(GENRE_WHY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(0, notes.taps)
            assertEquals(0, count(GENRE_REASON_TAG))
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
                    flowOf(Lookup.Found(jazz))
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
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Modal Jazz")
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
            val tops = listOf(GENRE_PANEL_HEADING_TAG, GENRE_NO_DEFINITION_TAG, GENRE_WHY_TAG).map(::top)
            assertEquals(tops.sorted(), tops)
        }

    @Test
    fun noLineWhileTheDefinitionIsLookedUpOrAfterAFailedLookup() {
        showBox(Notes(lookedUp = { flowOf(null) })) { assertEquals(0, count(GENRE_NO_DEFINITION_TAG)) }
        showBox(Notes(lookedUp = { flowOf(Lookup.Failed("offline")) })) {
            assertEquals(0, count(GENRE_NO_DEFINITION_TAG))
            assertEquals(0, count(GENRE_DEFINITION_TAG))
        }
    }

    @Test
    fun noDefinitionAndNoWhyLeaveTheTableAlone() =
        showBox(Notes(lookedUp = { flowOf(null) }, start = GenreReason.None)) {
            assertEquals(0, count(GENRE_DEFINITION_TAG))
            assertEquals(0, count(GENRE_WHY_TAG))
            assertEquals(0, count(GENRE_WHY_WITHHELD_TAG))
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
    }
}
