package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.GenreListMode
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Why is this …?*'s cascade as the panel shows it (#695): a reason that cites no source, only the
 * model's own knowledge, has a line saying so under the question, above the answer, read in that
 * order; one from web pages is credited "by {model}, with web search"; any other "by {model}", with
 * no line; nothing explains it, "AI could not find an explanation.", then what was off and could
 * have, each linked to where it is set, and no such line with both on.
 */
@RunWith(RobolectricTestRunner::class)
class GenreCascadeScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun show(
        reason: GenreReason,
        opened: MutableList<SettingsTarget> = mutableListOf(),
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(
                            CascadeFixtures.playing,
                            Settings(),
                            notes = PanelNotes(CascadeFixtures.Notes(reason), openSettingsAt = { t -> opened += t }),
                        )
                    }
                }
            }
            compose.onNodeWithTag(CascadeFixtures.ROCK_CHIP).unfold()
            compose.waitForIdle()
            block()
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun aReasonWhollyFromTheModelSaysSoUnderTheQuestionAboveTheAnswer() =
        show(CascadeFixtures.fromModel) {
            compose.onNodeWithTag(GENRE_REASON_TAG).performScrollTo()
            compose.onNodeWithText(CascadeFixtures.LINE).assertExists()
            compose.onNodeWithText("by ${CascadeFixtures.MODEL}").assertExists()
            // Read in order, top to bottom in the reason's column: the question, the line, then the answer.

            fun top(text: String) =
                compose
                    .onNodeWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            val question = top("Why is this rock?")
            val line = top(CascadeFixtures.LINE)
            val answer = top("electric guitar, bass and drums")
            assertTrue("$question, $line, $answer", question < line && line < answer)
        }

    @Test
    fun aReasonFromWebPagesIsCreditedWithWebSearchAndHasNoLine() =
        show(CascadeFixtures.fromWeb) {
            compose.onNodeWithTag(GENRE_REASON_TAG).performScrollTo()
            compose.onNodeWithText("by ${CascadeFixtures.MODEL}, with web search").assertExists()
            assertEquals(0, count(GENRE_WHY_FROM_MODEL_TAG))
        }

    @Test
    fun aReasonCitingASourceIsCreditedToItsModelAloneWithNoLine() {
        for (reason in listOf(CascadeFixtures.sourced, CascadeFixtures.mixed)) {
            show(reason) {
                compose.onNodeWithTag(GENRE_REASON_TAG).performScrollTo()
                compose.onNodeWithText("by ${CascadeFixtures.MODEL}").assertExists()
                val web = compose.onAllNodesWithText("with web search", substring = true).fetchSemanticsNodes()
                assertEquals(0, web.size)
                assertEquals(0, count(GENRE_WHY_FROM_MODEL_TAG))
            }
        }
    }

    @Test
    fun nothingExplainsItWithBothOffItOffersBothLinked() {
        val opened = mutableListOf<SettingsTarget>()
        show(CascadeFixtures.notFound(GenreListMode.KNOWLEDGE, GenreListMode.WEB), opened) {
            compose.onNodeWithTag(GENRE_WHY_TAG).assertIsNotEnabled()
            compose.onNodeWithText("AI could not find an explanation.").assertExists()
            val line = "Enable AI service knowledge or AI web search."
            compose.onNodeWithText(line).performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.sources.linkTag
            }
            compose.onNodeWithText(line).performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.aiWebSearch.linkTag
            }
            assertEquals(listOf(SettingsTarget.sources, SettingsTarget.aiWebSearch), opened)
        }
    }

    @Test
    fun nothingExplainsItItNamesOnlyWhatIsOff() {
        show(CascadeFixtures.notFound(GenreListMode.KNOWLEDGE)) {
            compose.onNodeWithText("Enable AI service knowledge.").assertExists()
        }
        val opened = mutableListOf<SettingsTarget>()
        show(CascadeFixtures.notFound(GenreListMode.WEB), opened) {
            compose.onNodeWithText("Enable AI web search.").performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.aiWebSearch.linkTag
            }
            assertEquals(listOf(SettingsTarget.aiWebSearch), opened)
        }
    }

    @Test
    fun nothingExplainsItWithBothOnItSaysNoMore() =
        show(CascadeFixtures.notFound()) {
            compose.onNodeWithText("AI could not find an explanation.").assertExists()
            val enable = compose.onAllNodes(hasText("Enable", substring = true)).fetchSemanticsNodes()
            assertEquals(0, enable.size)
            // Not the line for a genre the AI's list lacks (#1059): the reason was asked, and found nothing.
            assertEquals(0, compose.onAllNodes(hasText("doesn’t list", substring = true)).fetchSemanticsNodes().size)
            assertEquals(1, count(GENRE_WHY_UNAVAILABLE_TAG))
        }
}

/** The panel's fixtures for #695's states, shared with [GenreCascadePicturesTest]. */
internal object CascadeFixtures {
    const val MODEL = "Claude Haiku 5.5"
    const val ROCK_CHIP = GENRE_PREFIX + "track:rock"
    const val LINE =
        "The sources don’t explain this genre. This is from the AI model’s own knowledge, not verified."

    // Rock, on the track's row, from Last.fm.
    val playing: TrackState =
        track().copy(
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.TRACK,
                            null,
                            listOf(
                                TrackLabel(
                                    GenreLabel("rock", "Rock", "rock", level = GenreLevel.TRACK),
                                    listOf(LabelCredit(LabelSource.LASTFM, "https://www.last.fm/tag/rock")),
                                ),
                            ),
                        ),
                    ),
                ),
        )

    // The track's evidence and a page the web search found, S1.
    private val searched: EvidenceSet =
        EvidenceSet.of(
            listOf(
                HomeFixtures.musicBrainzLine,
                HomeFixtures.wikipediaLine,
                HomeFixtures.wikidataLine,
                HomeFixtures.draft(
                    SourceId.WEB,
                    "Sundown is a soft rock single, its electric guitar over a steady beat.",
                    "https://example.org/sundown-review",
                    Scope.WEB,
                ),
            ),
            JvmHasher,
        )

    private fun written(
        reply: String,
        evidence: EvidenceSet = HomeFixtures.evidence,
        searched: EvidenceSet? = null,
    ): GenreReason.Shown {
        val grounded = (GroundingCheck.check(reply, evidence, true) as InsightReply.Written).grounded
        return GenreReason.Shown(InsightState.Written(grounded, false, searched = searched), MODEL)
    }

    val fromModel: GenreReason.Shown =
        written(
            "Gordon Lightfoot's band plays Sundown with electric guitar, bass and drums [AI]. " +
                "Its steady beat and electric lead guitar are what listeners usually file as rock [AI].",
        )

    val fromWeb: GenreReason.Shown =
        written(
            "A review hears Sundown as soft rock, its electric guitar over a steady beat [S1].",
            searched,
            searched,
        )

    val sourced: GenreReason.Shown = written("Sundown was recorded in 1973, the height of soft rock [MB1].")

    val mixed: GenreReason.Shown =
        written(
            "Sundown was recorded in 1973 [MB1]. Its electric lead guitar is what listeners usually file as rock [AI].",
        )

    fun notFound(vararg couldHelp: GenreListMode): GenreReason.Shown =
        GenreReason.Shown(InsightState.NoEvidence, couldHelp = couldHelp.toList())

    class Notes(
        private val start: GenreReason,
    ) : GenreNotes {
        override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = flowOf(start)

        override fun explain(
            track: TrackState,
            genre: String,
        ) = Unit
    }
}
