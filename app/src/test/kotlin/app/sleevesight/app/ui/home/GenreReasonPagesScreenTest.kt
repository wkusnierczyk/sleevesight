package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The pages a genre's reason found on the web and those the AI's genre list rests on, numbered
 * together in *Sources* (#1205): each numbers its pages after the track's own lines on its own, so
 * both may call a different page S7. *Sources* lists the list's page as S7 and the reason's as S8,
 * and the reason's own S7 shows, and opens, as S8, the line of its page.
 */
@RunWith(RobolectricTestRunner::class)
class GenreReasonPagesScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val at = Instant.parse("2026-11-13T00:00:00Z")

    // The track's six web lines, S1 to S6.
    private val own =
        EvidenceSet.of(
            (1..6).map { EvidenceDraft(SourceId.WEB, Scope.WEB, "Page $it.", "https://example.org/$it", at) },
            JvmHasher,
        )

    private val listed = WebPage("https://en.wikipedia.org/wiki/Way_Down_We_Go", null, "A song by Kaleo.")
    private val found = WebPage("https://lyricstories.com/way-down-we-go", null, "A brooding folk-rock ballad.")

    // The reason's own evidence: the track's lines, then the page its search found, its S7.
    private val searched =
        own.with(listOf(EvidenceDraft(SourceId.WEB, Scope.WEB, found.text, found.url, at)), JvmHasher)

    private val folkRock = GenreLabel("folk rock", "Folk Rock", "folkrock", level = GenreLevel.TRACK)

    private val playing: TrackState = track(evidence = own).copy(genres = TrackGenres())

    private val cited = mutableListOf<String>()

    private val notes =
        object : GenreNotes {
            override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

            override fun aiGenres(track: TrackState): Flow<AiGenreList?> =
                flowOf(AiGenreList(listOf(folkRock), MODEL, listOf(listed.url), listOf(listed)))

            override fun searchedPages(track: TrackState): Flow<List<WebPage>> = flowOf(listOf(found))

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> {
                val reply = GroundingCheck.check("It is a folk-rock ballad [S7].", searched, false)
                val grounded = (reply as InsightReply.Written).grounded
                return flowOf(GenreReason.Shown(InsightState.Written(grounded, false, searched = searched), MODEL))
            }

            override fun explain(
                track: TrackState,
                genre: String,
            ) = Unit
        }

    @Test
    fun aReasonsPageIsListedAfterTheListsAndItsCitationShowsAndOpensItsLine() {
        var shown: EvidenceSet? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val lines = sourcesLines(ItemNotes.None, playing, notes)
                    shown = lines.evidence
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
                                ),
                        )
                    }
                }
            }
            compose.onNodeWithTag(GENRE_PREFIX + "track:folkrock").unfold()
            compose.waitForIdle()
            // *Sources*: the list's page S7, the reason's S8, both named *genres*.
            val lines = checkNotNull(shown).lines
            assertEquals(listed.url, lines.single { it.id == "S7" }.url)
            assertEquals(found.url, lines.single { it.id == "S8" }.url)
            // The reason's S7 shows as S8, and opens S8.
            val reason = compose.onNodeWithText("folk-rock ballad", substring = true).performScrollTo()
            val text = reason.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
            assertTrue(text, "S8" in text && "S7" !in text)
            reason.performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "S7" }
            compose.waitForIdle()
            assertEquals(listOf("S8"), cited)
        }
    }

    private companion object {
        const val MODEL = "Claude Haiku 5.5"
    }
}
