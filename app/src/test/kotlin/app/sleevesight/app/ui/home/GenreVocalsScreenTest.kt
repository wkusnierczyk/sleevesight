package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.PanelLibrary
import app.sleevesight.app.pipeline.VocalsShown
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.VocalsAnswer
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.genres.wantsVocalsCheck
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.VocalsState
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

/**
 * The check of a track's vocals in *Genres* (#998): while it runs, its line in *Why is this
 * instrumental?*'s place; each answer's effect on the chips and its line, cited and read with its
 * citation; an Instrumental it added marked AI; and what starts it by itself, or not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GenreVocalsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // The genres told to the notes, and whether settled, last first.
    private val reported = mutableListOf<Pair<TrackState, Boolean>>()
    private val explained = mutableListOf<String>()

    private fun notes(shown: VocalsShown?) =
        VocalsFixtures.Notes(shown, onShown = { track, settled -> reported += track to settled }, onExplain = {
            explained +=
                it
        })

    private fun show(
        shown: VocalsShown?,
        playing: TrackState = VocalsFixtures.nasnusa,
        settings: Settings = Settings(),
        open: String? = VocalsFixtures.INSTRUMENTAL_CHIP,
        block: () -> Unit,
    ) = launch(shown, playing, settings, open, panel = true, block)

    // Home's line alone, *Genres* not open: [settings], [VocalsFixtures.nasnusa].
    private fun homeOnly(
        settings: Settings,
        block: () -> Unit,
    ) = launch(null, VocalsFixtures.nasnusa, settings, open = null, panel = false, block)

    @Suppress("LongParameterList") // [show]'s, and whether *Genres*' content is composed too.
    private fun launch(
        shown: VocalsShown?,
        playing: TrackState,
        settings: Settings,
        open: String?,
        panel: Boolean,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        val notes = PanelNotes(notes(shown))
                        AutoVocalsCheck(playing, settings, notes, PanelSearch())
                        if (panel) GenresContent(playing, settings, notes = notes)
                    }
                }
            }
            compose.waitForIdle()
            open?.let { compose.onNodeWithTag(it).performScrollTo().performClick() }
            compose.waitForIdle()
            block()
        }
    }

    @Test
    fun whileTheCheckRunsItsLineTakesTheButtonsPlace() =
        show(VocalsShown(VocalsState.Checking, VocalsFixtures.MODEL)) {
            compose.onNodeWithText("Checking whether this track has vocals…").assertExists()
            compose.onNodeWithTag(GENRE_WHY_TAG).assertDoesNotExist()
            // The weak line stays until it answers.
            compose.onNodeWithText("This track also credits Yurika Hanashima and may feature vocals.").assertExists()
        }

    @Test
    fun foundInstrumentalLiftsTheWeakMarkAndSaysSoWithItsPage() =
        show(VocalsFixtures.answered(VocalsAnswer.INSTRUMENTAL)) {
            compose.onNodeWithTag(VocalsFixtures.INSTRUMENTAL_CHIP).assertExists()
            compose.onNodeWithContentDescription("Instrumental, weak evidence").assertDoesNotExist()
            compose
                .onNodeWithContentDescription("Web search found this track described as instrumental. (Source S1)")
                .assertExists()
            compose.onNodeWithText("by ${VocalsFixtures.MODEL}, with web search").assertExists()
            compose.onNodeWithTag(GENRE_WHY_TAG).assertExists()
        }

    @Test
    fun vocalsAfterATapLeaveTheChipWeakWithTheirLineInTheWeakLinesPlace() =
        show(VocalsFixtures.answered(VocalsAnswer.VOCALS, tapped = true)) {
            compose.onNodeWithContentDescription("Instrumental, weak evidence").assertExists()
            compose
                .onNodeWithContentDescription(
                    "Web search found vocals on this track, so it is not Instrumental. (Source S1)",
                ).assertExists()
            compose
                .onNodeWithText(
                    "This track also credits Yurika Hanashima and may feature vocals.",
                ).assertDoesNotExist()
            compose.onNodeWithText("by ${VocalsFixtures.MODEL}, with web search").assertExists()
            // No reason for a genre the check found wrong: the button is gone for the play.
            compose.onNodeWithTag(GENRE_WHY_TAG).assertDoesNotExist()
        }

    @Test
    fun vocalsFoundByItselfDropInstrumental() =
        show(VocalsFixtures.answered(VocalsAnswer.VOCALS), open = null) {
            compose.onNodeWithTag(VocalsFixtures.INSTRUMENTAL_CHIP).assertDoesNotExist()
            compose.onNodeWithTag(GENRE_PREFIX + "artist:Sababa 5:funk").assertExists()
        }

    @Test
    fun nothingFoundAfterATapSaysSo_andByItselfLeavesTheWeakLine() {
        show(VocalsFixtures.answered(VocalsAnswer.UNKNOWN, tapped = true, page = null)) {
            compose
                .onNodeWithContentDescription(
                    "Web search found nothing on whether this track has vocals.",
                ).assertExists()
            compose
                .onNodeWithText(
                    "This track also credits Yurika Hanashima and may feature vocals.",
                ).assertDoesNotExist()
            compose.onNodeWithContentDescription("Instrumental, weak evidence").assertExists()
            // The reason follows.
            compose.onNodeWithTag(GENRE_WHY_TAG).assertExists()
        }
        show(VocalsFixtures.answered(VocalsAnswer.UNKNOWN, page = null)) {
            compose.onNodeWithText("This track also credits Yurika Hanashima and may feature vocals.").assertExists()
            compose.onNodeWithTag(GENRE_VOCALS_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun anInstrumentalTheCheckAddedIsMarkedAiAndReadWithItsModel() =
        show(
            VocalsFixtures.answered(VocalsAnswer.INSTRUMENTAL),
            playing = VocalsFixtures.bite,
            open = GENRE_PREFIX + "track:instrumental",
        ) {
            compose.onNodeWithContentDescription("Instrumental, from ${VocalsFixtures.MODEL}").assertExists()
            compose
                .onNodeWithContentDescription("Web search found this track described as instrumental. (Source S1)")
                .assertExists()
        }

    @Test
    fun theGenresAreToldSettledOnceTheFreeStepsAreDone() =
        show(null, settings = Settings(searchWithAi = true), open = null) {
            val (track, settled) = reported.last()
            assertEquals("Nasnusa", track.nowPlaying.title)
            assertTrue(settled)
            // As composed: Instrumental weak, calling for the check, which its owner starts.
            assertTrue(track.genres.wantsVocalsCheck())
        }

    @Test
    fun theGenresAreToldNotSettledWhileLrclibMayStillAnswer() =
        show(
            null,
            playing = VocalsFixtures.nasnusaBeforeLibrary,
            settings = Settings(searchWithAi = true),
            open = null,
        ) {
            assertFalse(reported.last().second)
        }

    @Test
    fun withTheSettingOffHomeDoesNotComposeTheGenresForTheCheck() {
        homeOnly(Settings(searchWithAi = false)) {
            assertTrue(reported.isEmpty())
        }
        homeOnly(Settings(searchWithAi = true, aiWebSearch = AiWebSearch.OFF)) {
            assertTrue(reported.isEmpty())
        }
        homeOnly(Settings(searchWithAi = true)) {
            assertTrue(reported.isNotEmpty())
        }
    }

    @Test
    fun anInstrumentalSearchWithAiFoundUnderAnEmptyGenresIsAskedOfLrclib() {
        val asked = mutableListOf<TrackState>()
        val named = VocalsFixtures.vocabulary.labelsFor(listOf("instrumental", "jazz"), level = GenreLevel.TRACK)
        val feed =
            object : AiSearchFeed by AiSearchFeed.None {
                override fun search(
                    track: TrackState,
                    kind: SearchKind,
                    genre: String?,
                ): Flow<AiSearch> =
                    flowOf(
                        if (kind ==
                            SearchKind.GENRES
                        ) {
                            AiSearch.Answered(SearchAnswer.Genres(named), "Model")
                        } else {
                            AiSearch.None
                        },
                    )

                // Read only, as the panel reads it: the same answer (delegation would read None's).
                override fun answered(
                    track: TrackState,
                    kind: SearchKind,
                ): Flow<AiSearch> = search(track, kind)
            }
        val notes =
            object : GenreNotes by VocalsFixtures.Notes(null) {
                override fun libraryLyrics(track: TrackState): Flow<PanelLibrary?> {
                    asked += track
                    return flowOf(PanelLibrary.Answered(LibraryLyrics.LYRICS))
                }
            }
        val empty = HomeFixtures.track(VocalsFixtures.nasnusa.nowPlaying)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(empty, Settings(), notes = PanelNotes(notes), search = PanelSearch(feed, empty))
                    }
                }
            }
            compose.waitForIdle()
            assertTrue(asked.isNotEmpty())
            // LRCLIB's lyrics drop it, as from the rows.
            compose.onNodeWithTag(GENRE_PREFIX + "track:jazz").assertExists()
            compose.onNodeWithTag(GENRE_PREFIX + "track:instrumental").assertDoesNotExist()
        }
    }

    @Test
    fun lrclibNotReachedForTheComposersHintAloneSaysNothing() {
        show(null, playing = VocalsFixtures.bite.copy(libraryUnreachable = true), open = null) {
            compose.onNodeWithText("LRCLIB could not be reached.").assertDoesNotExist()
        }
        // With an Instrumental shown, it says so, once.
        show(null, playing = VocalsFixtures.nasnusaBeforeLibrary.copy(libraryUnreachable = true), open = null) {
            compose.onNodeWithText("LRCLIB could not be reached.").assertExists()
        }
    }

    @Test
    fun aTapOnWhyIsThisInstrumentalAsksTheNotes() =
        show(null) {
            compose.onNodeWithTag(GENRE_WHY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(TrackGenres.INSTRUMENTAL), explained)
        }
}

/** The tracks and notes of the check of a track's vocals' screens (#998). */
internal object VocalsFixtures {
    const val MODEL = "Claude Haiku 5.5"
    const val INSTRUMENTAL_CHIP = GENRE_PREFIX + "artist:Sababa 5:instrumental"

    val vocabulary: GenreVocabulary = GenreVocabulary.load(JvmResourceReader)

    val page = WebPage("https://bandcamp.example/nasnusa", "Nasnusa", "Nasnusa, an instrumental.")

    private val nasnusaPlaying =
        NowPlaying(
            title = "Nasnusa",
            artists = "Sababa 5, Yurika Hanashima",
            album = "Kokoro",
            duration = 4.minutes,
            player = Settings.SPOTIFY,
        )

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    private fun nasnusa(singing: Singing): TrackState =
        HomeFixtures.track(nasnusaPlaying).copy(
            genres =
                combineGenres(
                    mapOf(LabelSource.LASTFM to artist("Sababa 5", "instrumental", "funk")),
                    listOf("Sababa 5", "Yurika Hanashima"),
                    singing,
                ),
        )

    /** *Nasnusa*: Sababa 5's Instrumental, weak for the featured singer; LRCLIB had no match. */
    val nasnusa: TrackState = nasnusa(Singing(library = LibraryLyrics.NONE))

    /** The same, LRCLIB not answered yet. */
    val nasnusaBeforeLibrary: TrackState = nasnusa(Singing())

    private fun bite(singing: Singing): TrackState =
        HomeFixtures.track(nasnusaPlaying.copy(title = "A Bite of Sand", artists = "Shay Hazan", album = "Wusul")).copy(
            genres =
                combineGenres(
                    mapOf(LabelSource.LASTFM to artist("Shay Hazan", "jazz", "electronic")),
                    listOf("Shay Hazan"),
                    singing,
                ),
        )

    /** *A Bite of Sand*: Jazz and Electronic, the work crediting composers alone. */
    val bite: TrackState = bite(Singing(composersOnly = true))

    /** The same with nothing said of its writing credits. */
    val biteNoSign: TrackState = bite(Singing())

    fun answered(
        answer: VocalsAnswer,
        tapped: Boolean = false,
        page: WebPage? = this.page,
    ) = VocalsShown(VocalsState.Checked(answer, "claude-haiku-5-5", page), MODEL, tapped)

    class Notes(
        private val shown: VocalsShown?,
        private val onShown: (TrackState, Boolean) -> Unit = { _, _ -> },
        private val onExplain: (String) -> Unit = {},
    ) : GenreNotes {
        override val vocabulary: GenreVocabulary = VocalsFixtures.vocabulary

        override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = flowOf(GenreReason.Offered)

        override fun explain(
            track: TrackState,
            genre: String,
        ) = onExplain(genre)

        override fun vocals(track: TrackState): Flow<VocalsShown?> = flowOf(shown)

        override fun genresShown(
            track: TrackState,
            settled: Boolean,
        ) = onShown(track, settled)
    }
}
