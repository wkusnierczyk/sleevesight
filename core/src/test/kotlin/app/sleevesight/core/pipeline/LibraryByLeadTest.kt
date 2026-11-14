package app.sleevesight.core.pipeline

import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.lyrics.LyricsLibrary
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.ArtistRoles
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * LRCLIB asked again by the lead performer when the first credited artist finds nothing (#1133): on a
 * track whose roles tell a composer apart only (LRCLIB files *Nessun dorma* under Puccini and Pavarotti
 * alike), each answer kept under its own query; a pop track asked once, as before.
 */
class LibraryByLeadTest {
    private val normalizer = Normalizer(JvmAccentFolder)

    private val nessun =
        NowPlaying(
            title = "Turandot: \"Nessun dorma!\"",
            artists = "$PUCCINI, $PAVAROTTI, Zubin Mehta",
            album = "The Three Tenors in Concert 1994",
            duration = 204.seconds,
            player = "com.spotify.music",
        )
    private val track = normalizer.normalize(nessun)
    private val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, "Zubin Mehta"))

    // LRCLIB as written for the test: an answer by the query's artist, NotFound for any other.
    private class ByArtist(
        val answers: Map<String, Lookup<LibraryLyrics>>,
    ) : LyricsLibrary {
        val asked = mutableListOf<String>()

        override suspend fun lookUp(query: LibraryQuery): Lookup<LibraryLyrics> {
            asked += query.artist
            return answers[query.artist] ?: Lookup.NotFound
        }
    }

    @Test
    fun `the lead performer's query is the first one by the lead, only when a composer is told apart`() {
        val first = checkNotNull(LibraryQuery.of(track, nessun))
        assertEquals(PUCCINI, first.artist)
        assertEquals(first.copy(artist = PAVAROTTI), LibraryQuery.byLead(track, nessun, roles))
        assertNull(LibraryQuery.byLead(track, nessun, null))
        assertNull(LibraryQuery.byLead(track, nessun, ArtistRoles(emptyList(), track.artists)))
        // A lead who is the first credited artist is asked once.
        val pavarottiFirst = nessun.copy(artists = "$PAVAROTTI, $PUCCINI")
        val led = normalizer.normalize(pavarottiFirst)
        assertNull(LibraryQuery.byLead(led, pavarottiFirst, ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI))))
    }

    @Test
    fun `no match by the composer is asked again by the lead performer, each answer kept`() =
        runTest {
            val library = ByArtist(mapOf(PAVAROTTI to Lookup.Found(LibraryLyrics.LYRICS)))
            val cache = MemoryCache()
            val lookup = LibraryLookup(library, cache)
            val answer = assertIs<LibraryAnswer.Known>(lookup.ask(track, nessun, roles = roles))
            assertEquals(LibraryLyrics.LYRICS, answer.lyrics)
            assertEquals(listOf(PUCCINI, PAVAROTTI), library.asked)
            // Kept: a replay reads both, asking nothing.
            assertEquals(LibraryLyrics.LYRICS, lookup.kept(track, nessun, roles))
            val again = assertIs<LibraryAnswer.Known>(lookup.ask(track, nessun, roles = roles))
            assertEquals(true, again.cached)
            assertEquals(listOf(PUCCINI, PAVAROTTI), library.asked)
        }

    @Test
    fun `a match by the composer asks nothing more, and a track with no composer told is asked once`() =
        runTest {
            val found = ByArtist(mapOf(PUCCINI to Lookup.Found(LibraryLyrics.LYRICS)))
            LibraryLookup(found, MemoryCache()).ask(track, nessun, roles = roles)
            assertEquals(listOf(PUCCINI), found.asked)
            val none = ByArtist(emptyMap())
            val answer = assertIs<LibraryAnswer.Known>(LibraryLookup(none, MemoryCache()).ask(track, nessun))
            assertEquals(LibraryLyrics.NONE, answer.lyrics)
            assertEquals(listOf(PUCCINI), none.asked)
        }

    @Test
    fun `the lead's query failing is a failure, asked again later while the composer's no match is kept`() =
        runTest {
            val library = ByArtist(mapOf(PAVAROTTI to Lookup.Failed("Offline")))
            val lookup = LibraryLookup(library, MemoryCache())
            assertIs<LibraryAnswer.Failed>(lookup.ask(track, nessun, roles = roles))
            // Nothing kept for the lead: not known yet, so the run asks again.
            assertNull(lookup.kept(track, nessun, roles))
            assertEquals(LibraryLyrics.NONE, lookup.kept(track, nessun))
            lookup.ask(track, nessun, roles = roles)
            assertEquals(listOf(PUCCINI, PAVAROTTI, PAVAROTTI), library.asked)
        }

    private companion object {
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
    }
}
