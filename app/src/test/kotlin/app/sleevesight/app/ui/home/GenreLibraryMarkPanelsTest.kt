package app.sleevesight.app.ui.home

import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.genres.instrumental
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.withLibraryMark
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * LRCLIB's Instrumental alone (#1024) is no tag: *Search with AI* for the genres is still among the
 * empty panels, for the setting to start by itself, while *Genres* says neither "none found" nor the
 * off state beside its chip; with a source's tag, the genres are no empty panel.
 */
class GenreLibraryMarkPanelsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val playing =
        NowPlaying(
            title = "A Bite of Sand",
            artists = "Shay Hazan",
            album = "Wusul",
            duration = 4.minutes,
            player = Settings.SPOTIFY,
        )
    private val marked = Singing(composersOnly = true, library = LibraryLyrics.INSTRUMENTAL)

    @Test
    fun theMarksInstrumentalAloneLeavesTheGenresSearchOffered() {
        val genres = combineGenres(emptyMap(), listOf("Shay Hazan"), marked).withLibraryMark(vocabulary.instrumental())
        val track = HomeFixtures.track(playing).copy(genres = genres)
        assertFalse(genres.isEmpty)
        assertFalse(genres.hasTags)
        assertTrue(SearchKind.GENRES in emptyPanels(track, Settings()))
        val shown = GenresShown.of(track, Settings())
        assertFalse(shown.none)
        assertNull(GenresShown.of(track, Settings(labelSources = emptySet())).off)
    }

    @Test
    fun withASourcesTagTheGenresAreNoEmptyPanel() {
        val jazz = vocabulary.labelsFor(listOf("jazz"), level = GenreLevel.ARTIST, artist = "Shay Hazan")
        val genres =
            combineGenres(mapOf(LabelSource.LASTFM to jazz), listOf("Shay Hazan"), marked)
                .withLibraryMark(vocabulary.instrumental())
        assertTrue(genres.hasTags)
        assertFalse(SearchKind.GENRES in emptyPanels(HomeFixtures.track(playing).copy(genres = genres), Settings()))
    }
}
