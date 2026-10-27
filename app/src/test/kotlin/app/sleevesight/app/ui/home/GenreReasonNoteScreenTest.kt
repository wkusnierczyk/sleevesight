package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreDefinition
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
import app.sleevesight.core.resolution.Lookup
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
 * A genre's reason when the sources say nothing about how the track sounds (#664): the app's own
 * line above the reply, in the quiet note style, with where the label came from, then the reply's
 * cited sentences; and the note under it naming the red underline.
 */
@RunWith(RobolectricTestRunner::class)
class GenreReasonNoteScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun alternative(
        credit: LabelSource,
        level: GenreLevel = GenreLevel.ARTIST,
    ) = TrackLabel(
        GenreLabel(
            "alternative",
            "rock",
            "Alternative",
            "alternative",
            level = level,
            artist = ARTIST.takeIf { level == GenreLevel.ARTIST },
        ),
        listOf(LabelCredit(credit, "https://example.org/alternative")),
    )

    private fun track(rows: List<GenreRow> = listOf(GenreRow(GenreLevel.ARTIST, ARTIST, listOf(alternative(LASTFM))))) =
        HomeFixtures.track(playing = FAJNIE, evidence = EVIDENCE).copy(genres = TrackGenres(rows))

    private fun written(reply: String) =
        InsightState.Written((GroundingCheck.check(reply, EVIDENCE, false) as InsightReply.Written).grounded, false)

    private class Notes(
        private val reason: GenreReason,
    ) : GenreNotes {
        override fun definition(genre: String): Flow<Lookup<GenreDefinition>?> = flowOf(null)

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
        reason: GenreReason,
        track: TrackState = track(),
        chip: String = "artist:$ARTIST:alternative",
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Genres(track, Settings(), notes = PanelNotes(Notes(reason)))
                    }
                }
            }
            compose.onNodeWithTag(GENRE_PREFIX + chip).performClick()
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
    fun theCaseOfTheIssueGivesTheAppsLineThenCitedSentencesOnly() {
        val reason = written(REPLY)
        // The reply as the new prompt asks for it: every sentence cited, none about the evidence.
        val sentences = reason.grounded.paragraphs.flatten()
        assertTrue(sentences.all { it.status == SentenceStatus.OK })
        show(GenreReason.Shown(reason, silent = true)) {
            compose
                .onNodeWithTag(GENRE_SILENT_TAG)
                .assertTextEquals(
                    "The sources say nothing about how this track sounds. " +
                        "The label comes from listeners’ tags for Maciej Maleńczuk on Last.fm.",
                )
            // Above the reply, under the question.
            val text = compose.onNodeWithText("a Polish singer", substring = true).fetchSemanticsNode()
            assertTrue(top(GENRE_SILENT_TAG) < text.boundsInRoot.top)
            // The app's statement: plain text (no citation, as the text above shows), no underline.
            val line = compose.onNodeWithTag(GENRE_SILENT_TAG).fetchSemanticsNode()
            val styled =
                line.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .flatMap { it.spanStyles }
            assertTrue(styled.none { it.item.textDecoration == TextDecoration.Underline })
            // No sentence is unbacked, so no note under the reply.
            val note = app.resources.getQuantityString(R.plurals.home_note_unbacked, 1, 1)
            compose.onNodeWithText(note).assertDoesNotExist()
        }
    }

    @Test
    fun theLineShowsWhileTheReplyIsWrittenToo() =
        show(GenreReason.Shown(InsightState.Writing("Maciej Maleńczuk is a Polish singer"), silent = true)) {
            assertEquals(1, count(GENRE_SILENT_TAG))
        }

    @Test
    fun evidenceThatSpeaksOfTheSoundGivesNoLine() =
        show(GenreReason.Shown(written(REPLY), silent = false)) { assertEquals(0, count(GENRE_SILENT_TAG)) }

    @Test
    fun noLineBesideAFailureOrNoEvidence() {
        show(
            GenreReason.Shown(InsightState.Failed("offline"), silent = true),
        ) { assertEquals(0, count(GENRE_SILENT_TAG)) }
        show(GenreReason.Shown(InsightState.NoEvidence, silent = true)) { assertEquals(0, count(GENRE_SILENT_TAG)) }
    }

    @Test
    fun theLineNamesEverySourceAndEverythingTagged() {
        val rows =
            listOf(
                GenreRow(GenreLevel.TRACK, null, listOf(alternative(MUSICBRAINZ, GenreLevel.TRACK))),
                GenreRow(GenreLevel.ARTIST, ARTIST, listOf(alternative(LASTFM))),
                GenreRow(GenreLevel.ALBUM, null, listOf(alternative(LASTFM, GenreLevel.ALBUM))),
            )
        show(GenreReason.Shown(written(REPLY), silent = true), track(rows), chip = "track:alternative") {
            compose
                .onNodeWithTag(GENRE_SILENT_TAG)
                .assertTextEquals(
                    "The sources say nothing about how this track sounds. The label comes from tags for " +
                        "this track, Maciej Maleńczuk and this album on Last.fm and MusicBrainz.",
                )
        }
    }

    @Test
    fun theNoteUnderAReasonNamesTheRedUnderline() =
        show(GenreReason.Shown(written("$REPLY It feels alternative."), silent = true)) {
            val note = app.resources.getQuantityString(R.plurals.home_note_unbacked, 1, 1)
            assertEquals("The sentence underlined in red is not backed by the sources.", note)
            compose.onNodeWithText(note).assertExists()
        }

    private companion object {
        const val ARTIST = "Maciej Maleńczuk"
        val LASTFM = LabelSource.LASTFM
        val MUSICBRAINZ = LabelSource.MUSICBRAINZ
        val AT: Instant = Instant.parse("2026-10-27T00:00:00Z")

        val FAJNIE = NowPlaying("Fajnie", ARTIST, "Fajnie", player = Settings.SPOTIFY)

        fun line(
            source: SourceId,
            scope: Scope,
            text: String,
            aggregate: Boolean = false,
        ) = EvidenceDraft(source, scope, text, null, AT, aggregate)

        /** #664's case: no line about how the track sounds. */
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
