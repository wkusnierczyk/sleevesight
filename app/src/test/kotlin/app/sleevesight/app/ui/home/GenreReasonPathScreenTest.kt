package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AiGenreList
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.Unavailability
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Why is this …?* on every path (#762), as the panel shows it: disabled, with why under it and its
 * links; "Looking for an explanation…" while the AI service is asked; the reason credited to its
 * model; and the genres the AI's genre list added, marked AI in the chips and the track's row, the
 * model among the row's sources.
 */
@RunWith(RobolectricTestRunner::class)
class GenreReasonPathScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    // Rock, on the track's row, from MusicBrainz alone.
    private val playing: TrackState =
        track().copy(
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.TRACK,
                            null,
                            listOf(
                                TrackLabel(
                                    GenreLabel("rock", "rock", "Rock", "rock", level = GenreLevel.TRACK),
                                    listOf(LabelCredit(LabelSource.MUSICBRAINZ, "https://musicbrainz.org/recording/1")),
                                ),
                            ),
                        ),
                    ),
                ),
        )

    private val blues = GenreLabel("blues", "jazzblues", "Blues", "blues", level = GenreLevel.TRACK)

    private inner class Notes(
        start: GenreReason,
        private val list: AiGenreList? = null,
    ) : GenreNotes {
        val reason = MutableStateFlow(start)
        var taps = 0

        override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

        override fun aiGenres(track: TrackState): Flow<AiGenreList?> = flowOf(list)

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = reason

        override fun explain(
            track: TrackState,
            genre: String,
        ) {
            taps++
        }
    }

    private fun show(
        notes: Notes,
        opened: MutableList<SettingsTarget> = mutableListOf(),
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(
                            playing,
                            Settings(),
                            notes = PanelNotes(notes, openSettingsAt = { target -> opened += target }),
                        )
                    }
                }
            }
            compose.onNodeWithTag(ROCK_CHIP).performClick()
            compose.waitForIdle()
            block()
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    // The disabled button, which a tap does nothing on.
    private fun disabled(notes: Notes) {
        compose.onNodeWithTag(GENRE_WHY_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(GENRE_WHY_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, notes.taps)
    }

    @Test
    fun withNoAiServiceTheButtonIsDisabledWithALinkToAiServices() {
        val notes = Notes(GenreReason.Unavailable(Unavailability.NO_SERVICE))
        val opened = mutableListOf<SettingsTarget>()
        show(notes, opened) {
            disabled(notes)
            val line = "Enable an AI service in Settings → AI services."
            compose.onNodeWithText(line).performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.aiServices.linkTag
            }
            assertEquals(listOf(SettingsTarget.aiServices), opened)
        }
    }

    @Test
    fun whenTheGenreListCannotRunItSaysWhatToEnableLinked() {
        val notes = Notes(GenreReason.Unavailable(Unavailability.CANNOT_LIST))
        val opened = mutableListOf<SettingsTarget>()
        show(notes, opened) {
            disabled(notes)
            compose.onNodeWithText("No AI service enabled.").assertExists()
            compose.onNodeWithText("Enable AI service knowledge.").performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.sources.linkTag
            }
            assertEquals(listOf(SettingsTarget.sources), opened)
        }
    }

    @Test
    fun whenTheAiCannotExplainItTheButtonStaysDisabledWithWhyUnderIt() {
        for (reason in listOf(
            GenreReason.Unavailable(Unavailability.NOT_LISTED),
            GenreReason.Shown(InsightState.NoEvidence),
        )) {
            val notes = Notes(reason)
            show(notes) {
                disabled(notes)
                compose.onNodeWithText("AI could not find an explanation.").assertExists()
                assertEquals(0, count(GENRE_REASON_TAG))
            }
        }
    }

    @Test
    fun whileTheAiServiceIsAskedItSaysSoInTheButtonsPlace() =
        show(Notes(GenreReason.Shown(InsightState.Writing("")))) {
            compose.onNodeWithTag(GENRE_WHY_LOOKING_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Looking for an explanation…").assertExists()
            assertEquals(0, count(GENRE_WHY_TAG))
            assertEquals(0, count(GENRE_REASON_TAG))
        }

    @Test
    fun aReasonIsCreditedToItsModel() {
        val grounded = (GroundingCheck.check(REASON, HomeFixtures.evidence, false) as InsightReply.Written).grounded
        show(Notes(GenreReason.Shown(InsightState.Written(grounded, false), MODEL))) {
            compose.onNodeWithTag(GENRE_REASON_TAG).performScrollTo()
            compose.onNodeWithText("by $MODEL").assertExists()
        }
    }

    @Test
    fun theGenresTheAiAddedAreMarkedAiInTheChipsAndTheTrackRowTheModelAmongItsSources() =
        show(Notes(GenreReason.Offered, AiGenreList(listOf(blues), MODEL))) {
            // After the chips, read with the model's name, marked AI.
            compose.onNodeWithTag(BLUES_CHIP).assertExists()
            val chip = compose.onNodeWithTag(BLUES_CHIP, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val rock = compose.onNodeWithTag(ROCK_CHIP, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(true, chip.left > rock.left || chip.top > rock.top)
            val marks =
                compose
                    .onAllNodes(hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(BLUES_CHIP)), true)
                    .fetchSemanticsNodes()
            assertEquals(1, marks.size)
            compose.onNode(hasContentDescription("Blues, from $MODEL")).assertExists()
            // The track's row: Rock, then Blues, read with the model's name; its sources name the model.
            compose
                .onNodeWithTag(GENRES_ROW_PREFIX + "0")
                .assertContentDescriptionEquals("Track, Rock, selected, Blues, from $MODEL")
            compose.onNodeWithTag(GENRE_SOURCES_ROW_PREFIX + "0").performScrollTo()
            compose.onNodeWithText("MusicBrainz · $MODEL AI", substring = true, useUnmergedTree = true).assertExists()
            // A chip the AI added opens its genre as any other.
            compose.onNodeWithTag(BLUES_CHIP).performScrollTo().performClick()
            compose.waitForIdle()
            compose
                .onNodeWithTag(
                    GENRES_ROW_PREFIX + "0",
                ).assertContentDescriptionEquals("Track, Rock, Blues, from $MODEL, selected")
        }

    private companion object {
        const val MODEL = "Claude Haiku 5.5"
        const val REASON = "It was recorded in 1973 [MB1]."
        const val ROCK_CHIP = GENRE_PREFIX + "track:rock"
        const val BLUES_CHIP = GENRE_PREFIX + "track:blues"
    }
}
