package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
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
 * *Sources* on a track whose own evidence is in and its insight written (#1199): the line a chip's
 * insight gathered on the web joins the lines listed, said to be about that artist, as it comes, so
 * the chip's citation opens it there, and the genre it names is that artist's, not the lead
 * performer's.
 */
@RunWith(RobolectricTestRunner::class)
class OthersSourcesScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private val playing =
        HomeFixtures.sundown.copy(title = "Sundown", artists = "$COMPOSER, $LEAD, $OTHER", mediaId = "spotify:track:9")

    // Already written: its evidence in, its key insight shown.
    private val shown: TrackState = track(playing, insight = written("Sundown was recorded in 1973 [MB1]."))

    // What the chip of [OTHER] gathered: a web page naming no artist, as the artist's web search keeps it.
    private val page =
        EvidenceDraft(
            SourceId.WEB,
            Scope.ARTIST,
            "They are a jazz quartet from Bristol.",
            "https://example.org/other",
            Instant.parse("2026-11-14T00:00:00Z"),
            about = OTHER,
        )

    private val genres =
        object : GenreNotes {
            override val vocabulary: GenreVocabulary = this@OthersSourcesScreenTest.vocabulary

            override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> = flowOf(GenreReason.Offered)

            override fun explain(
                track: TrackState,
                genre: String,
            ) = Unit
        }

    @Test
    fun aChipsNewlyGatheredWebLineIsListedAboutItsArtistAndItsGenreIsTheirs() {
        val notes = FakeItemNotes()
        var lines: EvidenceSet? = null
        var described: TrackState? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val sources = sourcesLines(notes, shown, genres).evidence
                    lines = sources
                    described = withDescribed(shown, PanelNotes(genres, lines = sources))
                }
            }
            compose.waitForIdle()
            assertNull(lines?.lines?.firstOrNull { it.text == page.text })
            notes.others.value = listOf(EvidenceSet.of(listOf(page), JvmHasher))
            compose.waitForIdle()
            val listed = checkNotNull(lines)
            val line = checkNotNull(listed.lines.firstOrNull { it.text == page.text })
            assertEquals(OTHER, line.about)
            // The chip's own citation, its line's id in its evidence, opens this line.
            val own = EvidenceSet.of(listOf(page), JvmHasher)
            assertEquals(line.id, shownId(own.lines.single().id, own, listed))
            // Jazz is the chip's artist's, not the first credited or the lead's.
            assertEquals(
                listOf(OTHER),
                described
                    ?.genres
                    ?.descriptions
                    ?.map { it.artist },
            )
        }
    }

    private companion object {
        const val COMPOSER = "Wolfgang Amadeus Mozart"
        const val LEAD = "Khatia Buniatishvili"
        const val OTHER = "Academy of St. Martin in the Fields"
    }
}
