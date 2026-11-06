package app.sleevesight.core.lyrics

import app.sleevesight.core.resolution.Lookup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One request in flight per query (#997's review): the run and the *Genres* panel asking about one
 * track while LRCLIB has not answered share one request; another query, or one asked once the first
 * answered, is its own; a first caller cancelled leaves the one waiting to ask itself.
 */
class SharedLyricsLibraryTest {
    private val query = LibraryQuery("Sababa 5", "Nasnusa", "Kokoro", 234)

    /** Answers each query with [answer] once it completes, counting the requests. */
    private class Held : LyricsLibrary {
        var answer = CompletableDeferred<Lookup<LibraryLyrics>>()
        val asked = mutableListOf<LibraryQuery>()

        override suspend fun lookUp(query: LibraryQuery): Lookup<LibraryLyrics> {
            asked += query
            return answer.await()
        }
    }

    @Test
    fun `two asks of one query while the first is out share its request and answer`() =
        runTest {
            val held = Held()
            val shared = SharedLyricsLibrary(held)
            val run = async { shared.lookUp(query) }
            val panel = async { shared.lookUp(query) }
            val other = async { shared.lookUp(query.copy(album = null)) }
            runCurrent()
            assertEquals(listOf(query, query.copy(album = null)), held.asked)
            held.answer.complete(Lookup.Found(LibraryLyrics.LYRICS))
            assertEquals(Lookup.Found(LibraryLyrics.LYRICS), run.await())
            assertEquals(Lookup.Found(LibraryLyrics.LYRICS), panel.await())
            assertEquals(Lookup.Found(LibraryLyrics.LYRICS), other.await())
            // Answered, the next ask is a request of its own.
            shared.lookUp(query)
            assertEquals(3, held.asked.size)
        }

    @Test
    fun `when the first caller is cancelled, the one waiting asks itself`() =
        runTest {
            val held = Held()
            val shared = SharedLyricsLibrary(held)
            val first = async { shared.lookUp(query) }
            runCurrent()
            val second = async { shared.lookUp(query) }
            runCurrent()
            first.cancel()
            runCurrent()
            assertEquals(2, held.asked.size)
            held.answer.complete(Lookup.NotFound)
            assertEquals(Lookup.NotFound, second.await())
        }
}
