package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.lyrics.LyricsLibrary
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.days

/**
 * LRCLIB asked from the *Genres* panel about an Instrumental the AI's genre list added (#997): a
 * failure shown as such, which *Retry* asks again; an answer held no longer than LRCLIB's lifetime;
 * nothing with LRCLIB off.
 */
class AppGenreLibraryTest {
    private val clock = MovedClock()

    /** LRCLIB as written for the test: [answer] for every query, each query counted. */
    private class FakeLibrary(
        var answer: Lookup<LibraryLyrics>,
    ) : LyricsLibrary {
        val asked = mutableListOf<LibraryQuery>()

        override suspend fun lookUp(query: LibraryQuery): Lookup<LibraryLyrics> = answer.also { asked += query }
    }

    private val library = FakeLibrary(Lookup.Found(LibraryLyrics.LYRICS))

    // A cache that keeps no answer of LRCLIB's: the panel's own memory is all there is.
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                cache = CardCache(clock = clock),
                lyricsLibrary = library,
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings())

    private val definitions =
        GenreDefinitions(
            object : GenreArticles {
                override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> = Lookup.NotFound
            },
            GenreVocabulary.load(JvmResourceReader),
        )

    private fun TestScope.notes() = AppGenreNotes(definitions, parts, settings, this, clock = clock)

    private val track = HomeFixtures.track()

    @Test
    fun `a failed lookup shows as unreachable, and Retry asks it again`() =
        runTest {
            library.answer = Lookup.Failed("network")
            val notes = notes()
            notes.libraryLyrics(track).first()
            advanceUntilIdle()
            assertEquals(PanelLibrary.Unreachable, notes.libraryLyrics(track).first())
            library.answer = Lookup.Found(LibraryLyrics.LYRICS)
            notes.retryLibrary(track)
            advanceUntilIdle()
            assertEquals(PanelLibrary.Answered(LibraryLyrics.LYRICS), notes.libraryLyrics(track).first())
            assertEquals(LibraryQuery("Gordon Lightfoot", "Sundown", "Sundown", 180), library.asked.first())
        }

    @Test
    fun `an answer is held no longer than LRCLIB's lifetime, then asked again`() =
        runTest {
            val notes = notes()
            notes.libraryLyrics(track).first()
            advanceUntilIdle()
            assertEquals(PanelLibrary.Answered(LibraryLyrics.LYRICS), notes.libraryLyrics(track).first())
            assertEquals(1, library.asked.size)
            // Past seven days, the answer held is not shown; the next play asks again.
            clock.at += 7.days
            library.answer = Lookup.NotFound
            assertNull(notes.libraryLyrics(track).first())
            advanceUntilIdle()
            assertEquals(PanelLibrary.Answered(LibraryLyrics.NONE), notes.libraryLyrics(track).first())
            assertEquals(2, library.asked.size)
        }

    @Test
    fun `with LRCLIB off nothing is asked or shown`() =
        runTest {
            settings.value = Settings(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.LRCLIB)
            val notes = notes()
            assertNull(notes.libraryLyrics(track).first())
            advanceUntilIdle()
            assertEquals(emptyList<LibraryQuery>(), library.asked)
        }

    @Test
    fun `after a failure, reopening the panel asks nothing in the same play, a new play or Retry does`() =
        runTest {
            library.answer = Lookup.Failed("network")
            val notes = notes()
            val playing = track.copy(nowPlaying = track.nowPlaying.copy(play = 1))
            notes.libraryLyrics(playing).first()
            advanceUntilIdle()
            assertEquals(1, library.asked.size)
            // *Genres* closed and opened again, twice: the failure shown, nothing asked.
            repeat(2) {
                assertEquals(PanelLibrary.Unreachable, notes.libraryLyrics(playing).first())
                advanceUntilIdle()
            }
            assertEquals(1, library.asked.size)
            // The next play of the track asks again; so does *Retry*.
            notes.libraryLyrics(track.copy(nowPlaying = track.nowPlaying.copy(play = 2))).first()
            advanceUntilIdle()
            assertEquals(2, library.asked.size)
            notes.retryLibrary(playing)
            advanceUntilIdle()
            assertEquals(3, library.asked.size)
        }
}
