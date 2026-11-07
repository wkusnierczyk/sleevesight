package app.sleevesight.app.ui.home

import app.sleevesight.core.genres.DescribedIn
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.genres.withDescribed
import app.sleevesight.core.pipeline.LaterLabels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the *Genres* panel asks LRCLIB itself (#997's review): only for an Instrumental the AI's list
 * added, once the run's labels are in, so the run, which asks about its own Instrumental once they
 * are, and the panel never both ask.
 */
class PanelAsksLibraryTest {
    private fun genres(vararg tags: String) =
        TrackGenres(
            listOf(
                GenreRow(
                    GenreLevel.TRACK,
                    null,
                    tags.map { tag ->
                        TrackLabel(
                            GenreLabel(tag, tag, tag, level = GenreLevel.TRACK),
                            listOf(LabelCredit(LabelSource.LASTFM, null)),
                        )
                    },
                ),
            ),
        )

    private val playing = HomeFixtures.track().copy(genres = genres("folk"))
    private val joined = playing.copy(genres = genres("folk", "instrumental"))

    @Test
    fun anInstrumentalOnlyTheAiAddedIsAskedAboutOnceTheLabelsAreIn() {
        assertTrue(panelAsksLibrary(playing, joined))
        // A later label still asked for may bring Instrumental, which the run would ask about: wait.
        val asking = playing.copy(laterLabels = mapOf(LabelSource.MUSICBRAINZ to LaterLabels.ASKING))
        assertFalse(panelAsksLibrary(asking, asking.copy(genres = joined.genres)))
        assertFalse(panelAsksLibrary(playing.copy(genresAssembled = false), joined))
        // The run's own Instrumental: the run asks.
        val own = playing.copy(genres = genres("instrumental"))
        assertFalse(panelAsksLibrary(own, own))
        // No Instrumental at all, or no track.
        assertFalse(panelAsksLibrary(playing, playing))
        assertFalse(panelAsksLibrary(null, joined))
    }

    @Test
    fun anInstrumentalOnlyTheArtistsDescriptionNamesIsAskedAboutToo() {
        // #998: the artist's hint, a described row the run never has.
        val hint =
            GenreRow(
                GenreLevel.ARTIST,
                "A",
                listOf(
                    TrackLabel(
                        GenreLabel("instrumental", "Instrumental", "instrumental", artist = "A"),
                        emptyList(),
                        describedIn = DescribedIn("S8", "an instrumental band"),
                    ),
                ),
            )
        val described = playing.copy(genres = playing.genres.withDescribed(listOf(hint)))
        assertTrue(panelAsksLibrary(playing, described))
    }
}
