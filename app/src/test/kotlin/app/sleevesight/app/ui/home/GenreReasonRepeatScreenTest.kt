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
 * A reason's page that *Sources* leaves out as a repeat (#1434), its id now another page's: the
 * reason's own S7, a passage the list's longer page holds whole, is left out, and *Sources* numbers
 * the list's other page S7. The reason's S7 shows, opens and is offered as S8, the line that holds
 * its passage, never S7; its S8, a page *Sources* lists no line for, shows no chip and no action,
 * not the S8 *Sources* numbers another page by.
 */
@RunWith(RobolectricTestRunner::class)
class GenreReasonRepeatScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val at = Instant.parse("2026-11-19T00:00:00Z")

    // The track's six web lines, S1 to S6.
    private val own =
        EvidenceSet.of(
            (1..6).map { EvidenceDraft(SourceId.WEB, Scope.WEB, "Page $it.", "https://example.org/$it", at) },
            JvmHasher,
        )

    // The list's pages: *Sources*' S7, another page, and S8, the one that holds the reason's passage.
    private val other = WebPage("https://en.wikipedia.org/wiki/Kaleo", null, "Kaleo is a band from Iceland.")
    private val holder =
        WebPage(
            "https://www.allmusic.com/song/way-down-we-go",
            null,
            "Way Down We Go is a brooding folk-rock ballad, built on a stomping beat and a gospel choir.",
        )

    // The reason's S7: the holder's words again, from mid-sentence; its S8, a page *Sources* never lists.
    private val repeat =
        WebPage(
            "https://www.iheart.com/song/way-down-we-go",
            null,
            ", a brooding folk-rock ballad, built on a stomping beat",
        )
    private val unlisted = WebPage("https://example.com/charts", null, "It topped the charts in Iceland.")

    private val searched =
        own.with(
            listOf(repeat, unlisted).map { EvidenceDraft(SourceId.WEB, Scope.WEB, it.text, it.url, at) },
            JvmHasher,
        )

    private val folkRock = GenreLabel("folk rock", "Folk Rock", "folkrock", level = GenreLevel.TRACK)

    private val playing: TrackState = track(evidence = own).copy(genres = TrackGenres())

    private val cited = mutableListOf<String>()

    private val notes =
        object : GenreNotes {
            override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

            override fun aiGenres(track: TrackState): Flow<AiGenreList?> =
                flowOf(AiGenreList(listOf(folkRock), MODEL, listOf(other.url, holder.url), listOf(other, holder)))

            override fun searchedPages(track: TrackState): Flow<List<WebPage>> = flowOf(listOf(repeat))

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> {
                val reply =
                    GroundingCheck.check(
                        "It is a brooding folk-rock ballad [S7]. It topped the charts in Iceland [S8].",
                        searched,
                        false,
                    )
                val grounded = (reply as InsightReply.Written).grounded
                return flowOf(GenreReason.Shown(InsightState.Written(grounded, false, searched = searched), MODEL))
            }

            override fun explain(
                track: TrackState,
                genre: String,
            ) = Unit
        }

    @Test
    fun aRepeatsCitationShowsItsHoldersIdAndAnUnlistedPagesShowsNone() {
        var shown: SourcesLines? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val lines = sourcesLines(ItemNotes.None, playing, notes)
                    shown = lines
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
            compose.onNodeWithTag(GENRE_PREFIX + "track:folkrock").unfold()
            compose.waitForIdle()
            // *Sources*: the other page S7, the holder S8; the repeat is not listed.
            val lines = checkNotNull(shown)
            val evidence = checkNotNull(lines.evidence).lines
            assertEquals(other.url, evidence.single { it.id == "S7" }.url)
            assertEquals(holder.url, evidence.single { it.id == "S8" }.url)
            assertTrue(evidence.none { it.url == repeat.url })
            assertEquals(mapOf(MovedPage(repeat.url, passageKey(repeat.text)) to "S8"), lines.moved)
            val reason = compose.onNodeWithText("brooding folk-rock ballad", substring = true).performScrollTo()
            val node = reason.fetchSemanticsNode()
            val text = node.config[SemanticsProperties.Text].joinToString { it.text }
            // One chip, S8 (the holder), for the repeat; none for the unlisted page, nor S7.
            assertEquals(text, 1, Regex("S\\d").findAll(text).count())
            assertTrue(text, "S8" in text)
            assertFalse(text, "S7" in text)
            // Read and offered as S8 alone.
            val actions = node.config[SemanticsActions.CustomActions].map { it.label }
            assertEquals(listOf("Source S8"), actions.filter { it.startsWith("Source") })
            val said = node.config[SemanticsProperties.ContentDescription].joinToString()
            assertFalse(said, "S7" in said)
            reason.performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "S7" }
            compose.waitForIdle()
            assertEquals(listOf("S8"), cited)
        }
    }

    private companion object {
        const val MODEL = "Claude Haiku 5.5"
    }
}
