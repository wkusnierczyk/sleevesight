package app.sleevesight.sources.web

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.ArtistWebQuery
import app.sleevesight.core.testing.MemoryArtistResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** An artist's web lines (#912): searched only when asked, kept per artist, "found nothing" included. */
class ArtistWebSourceTest {
    private val names = NameMatcher(JvmAccentFolder)
    private val artist = ArtistWebQuery("Shashwat Sachdev", "mb-sachdev")

    // A search for the artist: its [lines], or a failure; waiting for [answering] when set.
    private class Web(
        private val lines: List<String> = listOf("Shashwat Sachdev is an Indian film composer."),
        private val failing: Boolean = false,
    ) : ArtistWebSearch {
        val asked = mutableListOf<String>()
        var answering: CompletableDeferred<Unit>? = null

        override suspend fun search(
            artist: String,
            sent: () -> Unit,
        ): Lookup<List<EvidenceDraft>> {
            asked += artist
            sent()
            answering?.await()
            return if (failing) {
                Lookup.Failed("down")
            } else {
                Lookup.Found(lines.map { EvidenceDraft(SourceId.WEB, Scope.ARTIST, it, "https://example.com", AT) })
            }
        }
    }

    @Test
    fun `what a search found is kept per artist, by its id, and found again with no search`() =
        runTest {
            val web = Web()
            val kept = MemoryArtistResults()
            val source = ArtistWebSource(web, names, kept)
            assertNull(source.kept(artist))
            var sent = 0
            val found = checkNotNull(source.search(artist) { sent++ })
            assertEquals(1, sent)
            assertEquals(setOf("mb:mb-sachdev"), kept.keys(ArtistWebSource.PART))
            assertEquals(found, source.kept(artist))
            // Found again with no search, its request not told.
            assertEquals(found, source.search(artist) { sent++ })
            assertEquals(1, web.asked.size)
            assertEquals(1, sent)
            // With no id, by the name folded.
            source.search(ArtistWebQuery("Shashwat  SACHDEV", null))
            assertTrue("name:shashwat sachdev" in kept.keys(ArtistWebSource.PART))
        }

    @Test
    fun `a search that finds nothing is kept so, and one that fails keeps nothing`() =
        runTest {
            val kept = MemoryArtistResults()
            assertEquals(emptyList(), ArtistWebSource(Web(lines = emptyList()), names, kept).search(artist))
            assertEquals(emptyList(), ArtistWebSource(null, names, kept).kept(artist))
            val failing = MemoryArtistResults()
            val source = ArtistWebSource(Web(failing = true), names, failing)
            assertNull(source.search(artist))
            assertNull(source.kept(artist))
            assertTrue(failing.keys(ArtistWebSource.PART).isEmpty())
        }

    @Test
    fun `the pages the AI service found are kept as given, and without a store in memory`() =
        runTest {
            val lines = listOf(EvidenceDraft(SourceId.WEB, Scope.ARTIST, "He scored Uri.", "https://example.org", AT))
            for (store in listOf(MemoryArtistResults(), null)) {
                val source = if (store == null) ArtistWebSource(null, names) else ArtistWebSource(null, names, store)
                source.keep(artist, lines)
                assertEquals(lines, source.kept(artist))
            }
            // No engine: no search, nothing kept.
            assertNull(ArtistWebSource(null, names).search(ArtistWebQuery("Someone Else", null)))
        }

    @Test
    fun `two writes of an artist searching at once make one search`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val web = Web().apply { answering = gate }
            val source = ArtistWebSource(web, names, MemoryArtistResults())
            val first = async { source.search(artist) }
            val second = async { source.search(artist) }
            repeat(3) { yield() }
            gate.complete(Unit)
            assertEquals(first.await(), second.await())
            assertEquals(1, web.asked.size)
        }

    // A clock the test moves.
    private class Moving(
        var now: Instant = Instant.fromEpochMilliseconds(0),
    ) : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun `the lines kept again with each card written from them last as long as the last`() =
        runTest {
            val clock = Moving()
            // As *Keep history for* keeps them: 90 days from when they were last kept.
            val source = ArtistWebSource(Web(), names, MemoryArtistResults(clock, 90.days))
            source.search(artist)
            clock.now += 60.days
            source.renew(artist)
            clock.now += 40.days
            assertTrue(checkNotNull(source.kept(artist)).isNotEmpty())
            clock.now += 91.days
            assertNull(source.kept(artist))
            source.renew(artist)
            assertNull(source.kept(artist))
        }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-05T12:00:00Z")
    }
}
