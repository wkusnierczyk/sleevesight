package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SentenceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The note under a genre's reason (#664), as under an insight: it names the red underline, and a
 * reply whose sentences are all cited, as the prompt now asks, has none.
 */
@RunWith(RobolectricTestRunner::class)
class GenreReasonNoteScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val track: TrackState =
        HomeFixtures.track(playing = FAJNIE, evidence = EVIDENCE).copy(
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.ARTIST,
                            ARTIST,
                            listOf(
                                TrackLabel(
                                    GenreLabel(
                                        "alternative",
                                        "rock",
                                        "Alternative",
                                        "alternative",
                                        level = GenreLevel.ARTIST,
                                        artist = ARTIST,
                                    ),
                                    listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/alternative")),
                                ),
                            ),
                        ),
                    ),
                ),
        )

    private fun written(reply: String) =
        InsightState.Written((GroundingCheck.check(reply, EVIDENCE, false) as InsightReply.Written).grounded, false)

    private class Notes(
        private val reason: GenreReason,
    ) : GenreNotes {
        override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = flowOf(reason)

        override fun explain(
            track: TrackState,
            genre: String,
        ) = Unit
    }

    private fun show(
        reason: InsightState.Written,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(track, Settings(), notes = PanelNotes(Notes(GenreReason.Shown(reason))))
                    }
                }
            }
            compose.onNodeWithTag(GENRE_PREFIX + "artist:$ARTIST:alternative").performClick()
            compose.waitForIdle()
            block()
        }
    }

    private fun note(count: Int) = app.resources.getQuantityString(R.plurals.home_note_unbacked, count, count)

    @Test
    fun theCaseOfTheIssueWithEverySentenceCitedHasNoNote() {
        val reason = written(REPLY)
        // The reply as the new prompt asks for it: every sentence cited, none about the evidence.
        assertTrue(
            reason.grounded.paragraphs
                .flatten()
                .all { it.status == SentenceStatus.OK },
        )
        show(reason) {
            compose.onNodeWithText("a Polish singer", substring = true).assertExists()
            compose.onAllNodesWithText("underlined in red", substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun theNoteUnderAReasonNamesTheRedUnderline() =
        show(
            written(
                "$REPLY The evidence does not support the Alternative label, because it says nothing about " +
                    "this track's instruments or sound.",
            ),
        ) {
            assertEquals("The sentence underlined in red is not backed by the sources.", note(1))
            compose.onNodeWithText(note(1)).assertExists()
        }

    @Test
    fun twoUnderlinedSentencesAreCountedInThePlural() =
        show(written("$REPLY It feels alternative. It is loud.")) {
            assertEquals("The 2 sentences underlined in red are not backed by the sources.", note(2))
            compose.onNodeWithText(note(2)).assertExists()
        }

    private companion object {
        const val ARTIST = "Maciej Maleńczuk"
        val AT: Instant = Instant.parse("2026-10-27T00:00:00Z")

        val FAJNIE = NowPlaying("Fajnie", ARTIST, "Fajnie", player = Settings.SPOTIFY)

        fun line(
            source: SourceId,
            scope: Scope,
            text: String,
            aggregate: Boolean = false,
        ) = EvidenceDraft(source, scope, text, null, AT, aggregate)

        /** #664's case: the writer, the artist's article, and the listeners' counts and tags. */
        val EVIDENCE: EvidenceSet =
            EvidenceSet.of(
                listOf(
                    line(SourceId.MUSICBRAINZ, Scope.SONG, "“Fajnie” was written by Maciej Maleńczuk."),
                    line(SourceId.WIKIPEDIA, Scope.ARTIST, "Maciej Maleńczuk is a Polish singer, poet and guitarist."),
                    line(SourceId.LASTFM, Scope.ARTIST, "Last.fm counts 120,000 listeners for Maciej Maleńczuk.", true),
                    line(
                        SourceId.LASTFM,
                        Scope.ARTIST,
                        "Last.fm listeners tag Maciej Maleńczuk as: alternative, polish, rock, blues.",
                        true,
                    ),
                ),
                JvmHasher,
            )

        /** The reply the new prompt asks for: what the evidence says, each sentence cited. */
        const val REPLY: String =
            "Maciej Maleńczuk is a Polish singer, poet and guitarist [W1]. He wrote “Fajnie” himself [MB1]."
    }
}
