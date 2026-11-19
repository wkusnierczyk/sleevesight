package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.AiGenreList
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * A passage the cited page's own line holds, and another's too (#1435): Last.fm's L1 and the S1 of
 * another site say "Turn, a song by Damien Jurado." as Spotify's S2, "Play on Spotify. …", does.
 * The reason's S3, Spotify's page by that passage, shows, opens and is offered as S2, Spotify's
 * line, never L1 nor S1.
 */
@RunWith(RobolectricTestRunner::class)
class SourcesCitedPassageScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val at = Instant.parse("2026-11-19T00:00:00Z")

    private val spotify = "https://open.spotify.com/track/turn"
    private val song = "Turn, a song by Damien Jurado."

    // The track's lines: Last.fm's L1, another site's S1, Spotify's S2.
    private val own =
        EvidenceSet.of(
            listOf(
                EvidenceDraft(SourceId.LASTFM, Scope.SONG, song, null, at),
                EvidenceDraft(SourceId.WEB, Scope.WEB, "Out now: $song", "https://example.org/turn", at),
                EvidenceDraft(SourceId.WEB, Scope.WEB, "Play on Spotify. $song", spotify, at),
            ),
            JvmHasher,
        )

    // The list's page: Spotify's, by the passage both lines hold.
    private val page = WebPage(spotify, null, song)

    // The reason's S3: Spotify's page, by that passage.
    private val searched = own.with(listOf(EvidenceDraft(SourceId.WEB, Scope.WEB, song, spotify, at)), JvmHasher)

    private val folk = GenreLabel("folk", "Folk", "folk", level = GenreLevel.TRACK)

    private val playing: TrackState = track(evidence = own).copy(genres = TrackGenres())

    private val cited = mutableListOf<String>()

    private val notes =
        object : GenreNotes {
            override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

            override fun aiGenres(track: TrackState): Flow<AiGenreList?> =
                flowOf(AiGenreList(listOf(folk), MODEL, listOf(spotify), listOf(page)))

            override fun searchedPages(track: TrackState): Flow<List<WebPage>> = flowOf(emptyList())

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> {
                val reply = GroundingCheck.check("Turn is a song by Damien Jurado [S3].", searched, false)
                val grounded = (reply as InsightReply.Written).grounded
                return flowOf(GenreReason.Shown(InsightState.Written(grounded, false, searched = searched), MODEL))
            }

            override fun explain(
                track: TrackState,
                genre: String,
            ) = Unit
        }

    @Test
    fun aPassageThePagesLineAndAnotherSourceHoldOpensThePagesLine() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val lines = sourcesLines(ItemNotes.None, playing, notes)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(
                            playing,
                            Settings(),
                            notes =
                                PanelNotes(
                                    notes,
                                    { id -> cited += id },
                                    lines = lines.evidence,
                                    asked = lines.asked,
                                    moved = lines.moved,
                                ),
                        )
                    }
                }
            }
            compose.onNodeWithTag(GENRE_PREFIX + "track:folk").unfold()
            compose.waitForIdle()
            val reason = compose.onNodeWithText("a song by Damien Jurado", substring = true).performScrollTo()
            val node = reason.fetchSemanticsNode()
            val text = node.config[SemanticsProperties.Text].joinToString { it.text }
            // One chip, S2 (Spotify's line), not L1 (Last.fm's) nor S1 (the other site's).
            assertEquals(text, 1, Regex("[SL]\\d").findAll(text).count())
            assertTrue(text, "S2" in text)
            assertFalse(text, "S1" in text || "L1" in text)
            // Offered to TalkBack as S2 alone.
            val actions = node.config[SemanticsActions.CustomActions].map { it.label }
            assertEquals(listOf("Source S2"), actions.filter { it.startsWith("Source") })
            reason.performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "S3" }
            compose.waitForIdle()
            assertEquals(listOf("S2"), cited)
        }
    }

    private companion object {
        const val MODEL = "Claude Haiku 5.5"
    }
}
