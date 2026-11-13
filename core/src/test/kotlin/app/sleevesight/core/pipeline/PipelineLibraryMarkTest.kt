package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.genres.WeakEvidence
import app.sleevesight.core.genres.wantsVocalsCheck
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.lyrics.LyricsLibrary
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * LRCLIB's instrumental mark in a run (#1024): a song whose work credits composers alone asks LRCLIB,
 * and its mark adds Instrumental on the track's row, credited to LRCLIB, also on a replay served from
 * the kept answer with no request; its lyrics or no match add nothing; a vocal credit or a language of
 * the work asks LRCLIB nothing.
 */
class PipelineLibraryMarkTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    // *A Bite of Sand* by Shay Hazan (#998): Jazz and Electronic.
    private val bite =
        NowPlaying(
            title = "A Bite of Sand",
            artists = "Shay Hazan",
            album = "Wusul",
            duration = 4.minutes,
            player = Settings.SPOTIFY,
        )

    private val lastFm =
        FakeAdapter(SourceId.LASTFM, verifies = false) { _, _ ->
            SourceResult(
                Outcome.ArtistArticle,
                listOf(line(SourceId.LASTFM, "Shay Hazan has 1 listener.")),
                Duration.ZERO,
                genres = vocabulary.labelsFor(listOf("jazz", "electronic"), artist = "Shay Hazan"),
            )
        }

    // MusicBrainz's song, its work's writing credits as [lyrics] says.
    private fun musicBrainz(lyrics: WorkLyrics) =
        FakeAdapter(SourceId.MUSICBRAINZ, verifies = false) { _, _ ->
            SourceResult(
                Outcome.SongArticle,
                listOf(line(SourceId.MUSICBRAINZ, "“A Bite of Sand” was written by Shay Hazan.")),
                Duration.ZERO,
                lyrics = lyrics,
            )
        }

    /** LRCLIB as written for the test: [answer] for every query, each query recorded. */
    private class FakeLibrary(
        var answer: Lookup<LibraryLyrics>,
    ) : LyricsLibrary {
        val asked = mutableListOf<LibraryQuery>()

        override suspend fun lookUp(query: LibraryQuery): Lookup<LibraryLyrics> = answer.also { asked += query }
    }

    private fun pipeline(
        library: LyricsLibrary,
        cache: PipelineCache = MemoryCache(),
        lyrics: WorkLyrics = WorkLyrics(composersOnly = true),
    ) = Pipeline(
        PipelineParts(
            normalizer = Normalizer(JvmAccentFolder),
            adapters = listOf(musicBrainz(lyrics), lastFm),
            resolver = FakeResolver(),
            hasher = JvmHasher,
            cache = cache,
            lyricsLibrary = library,
            vocabulary = vocabulary,
        ),
        PipelineTime(),
    ) { Settings() }

    private fun TrackState.instrumental(): TrackLabel? =
        genres.everyLabel.firstOrNull { it.genre.identity == TrackGenres.INSTRUMENTAL }

    @Test
    fun `composers alone ask LRCLIB, whose instrumental mark adds Instrumental credited to it`() =
        runTest {
            val library = FakeLibrary(Lookup.Found(LibraryLyrics.INSTRUMENTAL))
            val state = pipeline(library).run(bite, write = false)
            assertEquals(1, library.asked.size)
            val label = state.instrumental()
            assertEquals(SourceId.LRCLIB, label?.markedBy)
            assertEquals(
                GenreLevel.TRACK,
                state.genres.rows
                    .first()
                    .level,
            )
            assertFalse(label?.aiAdded == true)
            val chip = state.genres.chips().single { it.label.genre.identity == TrackGenres.INSTRUMENTAL }
            // Weak as LRCLIB's mark alone (#1055); the paid check settled all the same.
            assertTrue(chip.weak)
            assertEquals(WeakEvidence.LibraryMark, state.genres.weakEvidence(TrackGenres.INSTRUMENTAL))
            assertFalse(state.genres.wantsVocalsCheck())
        }

    @Test
    fun `a replay adds it from the kept answer, asking LRCLIB nothing`() =
        runTest {
            val cache = MemoryCache()
            val library = FakeLibrary(Lookup.Found(LibraryLyrics.INSTRUMENTAL))
            pipeline(library, cache).run(bite, write = false)
            library.answer = Lookup.Found(LibraryLyrics.LYRICS)
            val replay = pipeline(library, cache).run(bite, write = false)
            assertEquals(1, library.asked.size)
            assertEquals(SourceId.LRCLIB, replay.instrumental()?.markedBy)
        }

    @Test
    fun `LRCLIB's lyrics or no match add nothing, and no match leaves the check wanted`() =
        runTest {
            val sung = pipeline(FakeLibrary(Lookup.Found(LibraryLyrics.LYRICS))).run(bite, write = false)
            assertNull(sung.instrumental())
            assertFalse(sung.genres.wantsVocalsCheck())
            val unknown = pipeline(FakeLibrary(Lookup.NotFound)).run(bite, write = false)
            assertNull(unknown.instrumental())
            assertTrue(unknown.genres.wantsVocalsCheck())
        }

    @Test
    fun `with a vocal credit or a language of the work LRCLIB is not asked, and nothing is added`() =
        runTest {
            for (lyrics in listOf(
                WorkLyrics(composersOnly = true, vocalists = listOf("Ana")),
                WorkLyrics(composersOnly = true, languages = listOf("heb")),
            )) {
                val library = FakeLibrary(Lookup.Found(LibraryLyrics.INSTRUMENTAL))
                val state = pipeline(library, lyrics = lyrics).run(bite, write = false)
                assertTrue(library.asked.isEmpty(), "$lyrics")
                assertNull(state.instrumental(), "$lyrics")
            }
        }
}
