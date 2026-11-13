package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.AiGenreList
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.app.pipeline.VocalsShown
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.VocalsAnswer
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.WebPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The page the check of the track's vocals cites, in *Sources* (#1053): one more web line, numbered
 * after the others (the track's own S1 to S6, the AI's genre list's S7, the check's S8), named
 * *vocals check*; listed only when the answer was taken, and once when another line has its address.
 * The note's citation shows, opens and is read as that line.
 */
@RunWith(RobolectricTestRunner::class)
class GenreVocalsSourcesScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val at = Instant.parse("2026-11-13T00:00:00Z")

    // The track's six web lines, S1 to S6.
    private val own =
        EvidenceSet.of(
            (1..6).map { EvidenceDraft(SourceId.WEB, Scope.WEB, "Page $it.", "https://example.org/$it", at) },
            JvmHasher,
        )

    private val listed = WebPage("https://en.wikipedia.org/wiki/Sababa_5", null, "A funk band from Tel Aviv.")
    private val funk = GenreLabel("funk", "Funk", "funk", level = GenreLevel.TRACK)

    private val playing: TrackState = VocalsFixtures.nasnusa.copy(evidence = own)

    private val cited = mutableListOf<String>()

    private fun notes(shown: VocalsShown?) =
        object : VocalsFixtures.Notes(shown) {
            override fun aiGenres(track: TrackState): Flow<AiGenreList?> =
                flowOf(AiGenreList(listOf(funk), MODEL, listOf(listed.url), listOf(listed)))
        }

    // *Sources*' lines for [shown], with the genres' panel open on Instrumental when [open].
    private fun show(
        shown: VocalsShown?,
        open: Boolean = false,
        block: (SourcesLines) -> Unit,
    ) {
        var sources: SourcesLines? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val notes = notes(shown)
                    val lines = sourcesLines(ItemNotes.None, playing, notes)
                    sources = lines
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        // What *Sources* names each added line by.
                        for (line in added(lines)) {
                            Text("${line.id} ${lines.what(line)}")
                        }
                        if (open) {
                            val panel =
                                PanelNotes(notes, { id -> cited += id }, lines = lines.evidence, asked = lines.asked)
                            GenresContent(playing, Settings(), notes = panel)
                        }
                    }
                }
            }
            compose.waitForIdle()
            if (open) compose.onNodeWithTag(VocalsFixtures.INSTRUMENTAL_CHIP).performScrollTo().performClick()
            compose.waitForIdle()
            block(checkNotNull(sources))
        }
    }

    // The lines *Sources* lists after the track's own.
    private fun added(lines: SourcesLines): List<EvidenceLine> = checkNotNull(lines.evidence).lines.drop(own.lines.size)

    @Test
    fun theChecksPageIsListedAfterTheOthersNamedVocalsCheck() =
        show(VocalsFixtures.answered(VocalsAnswer.INSTRUMENTAL)) { sources ->
            val lines = checkNotNull(sources.evidence).lines
            assertEquals(listed.url, lines.single { it.id == "S7" }.url)
            val page = lines.single { it.id == "S8" }
            assertEquals(VocalsFixtures.page.url, page.url)
            // Its passage, as other web lines show theirs.
            assertEquals(VocalsFixtures.page.text, page.text)
            assertEquals(mapOf("S7" to LinePurpose.GENRES, "S8" to LinePurpose.VOCALS_CHECK), sources.purposes)
            compose.onNodeWithText("S7 genres").assertExists()
            compose.onNodeWithText("S8 vocals check").assertExists()
            // A genre's reason is asked on the lines as they were before any page joined.
            assertEquals(own, sources.asked)
        }

    @Test
    fun anAnswerNotTakenListsNoPage() =
        show(VocalsFixtures.answered(VocalsAnswer.UNKNOWN, tapped = true, page = null)) { sources ->
            assertEquals(listOf("S7"), added(sources).map { it.id })
            assertNull(sources.purposes["S8"])
        }

    @Test
    fun aPageAnotherLineHasIsThatLineNotListedTwice() {
        val known = VocalsFixtures.answered(VocalsAnswer.INSTRUMENTAL, page = listed.copy(url = listed.url + "/"))
        show(known) { sources ->
            assertEquals(listOf("S7"), added(sources).map { it.id })
            assertEquals(mapOf("S7" to LinePurpose.GENRES), sources.purposes)
        }
    }

    @Test
    fun theNotesCitationShowsOpensAndIsReadAsTheChecksLine() =
        show(VocalsFixtures.answered(VocalsAnswer.INSTRUMENTAL), open = true) {
            val note =
                compose
                    .onNodeWithContentDescription("Web search found this track described as instrumental. (Source S8)")
                    .performScrollTo()
            note.performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == GENRE_CITE_PREFIX + "S8" }
            compose.waitForIdle()
            assertEquals(listOf("S8"), cited)
        }

    private companion object {
        const val MODEL = "Claude Haiku 5.5"
    }
}
