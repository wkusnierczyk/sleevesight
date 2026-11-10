package app.sleevesight.sources.web

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.SongWebQuery
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.testing.MemoryArtistResults
import app.sleevesight.sources.KeptDrafts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** A song's web lines (#958): searched only when asked, kept per track, "found nothing" included. */
class SongWebSourceTest {
    private val song = SongWebQuery(Normalizer(JvmAccentFolder).normalize("Phil's First Tear", "Kolonel Djafaar"))

    private class Web(
        private val lines: List<String> = listOf("A heavy horn line."),
        private val failing: Boolean = false,
    ) : SongWebSearch {
        var searches = 0

        override suspend fun search(
            song: SongWebQuery,
            sent: () -> Unit,
            mayPay: () -> Boolean,
        ): Lookup<List<EvidenceDraft>> {
            searches++
            sent()
            return if (failing) {
                Lookup.Failed("down")
            } else {
                Lookup.Found(lines.map { EvidenceDraft(SourceId.WEB, Scope.WEB, it, "https://example.com", AT) })
            }
        }

        override suspend fun pageLines(song: SongWebQuery): (WebPage, Instant) -> EvidenceDraft? =
            { page, at -> EvidenceDraft(SourceId.WEB, Scope.WEB, page.text, page.url, at) }
    }

    @Test
    fun `what a search found is kept by the track's key, and found again with no search`() =
        runTest {
            val web = Web()
            val kept = MemoryArtistResults()
            val source = SongWebSource(web, kept)
            assertNull(source.kept(song))
            var sent = 0
            val found = assertNotNull(source.search(song, sent = { sent++ }))
            assertEquals(1, sent)
            assertEquals(setOf(song.track.lookupKey), kept.keys(SongWebSource.PART))
            assertEquals(found.map { it.text }, source.kept(song)?.map { it.text })
            source.search(song)
            assertEquals(1, web.searches)
        }

    @Test
    fun `lines kept before Exa's references were decoded are kept again unless they hold one`() =
        runTest {
            val now = MemoryArtistResults()
            val found = assertNotNull(SongWebSource(Web(), now).search(song))
            val key = now.keys(SongWebSource.PART).single()
            val value = assertNotNull(now.get(SongWebSource.PART, key)).value
            // Kept before #1165 (extraction 1) with no reference: the same lines, with no search, and
            // kept again under the part's version.
            val before = MemoryArtistResults()
            before.keep(SongWebSource.BEFORE_DECODING, key, value)
            val web = Web()
            assertEquals(found, SongWebSource(web, before).search(song))
            assertEquals(0, web.searches)
            assertEquals(value, assertNotNull(before.get(SongWebSource.PART, key)).value)
            // With a reference in a line: searched again.
            val encoded = MemoryArtistResults()
            val referenced = found.map { it.copy(text = it.text + " &amp; more") }
            encoded.keep(SongWebSource.BEFORE_DECODING, key, KeptDrafts.encode(referenced))
            val again = SongWebSource(web, encoded)
            assertNull(again.kept(song))
            assertEquals(found, again.search(song))
            assertEquals(1, web.searches)
        }

    @Test
    fun `found nothing is kept, and a failure keeps nothing, to search again`() =
        runTest {
            val none = SongWebSource(Web(lines = emptyList()), MemoryArtistResults())
            assertEquals(emptyList(), none.search(song))
            assertEquals(emptyList(), none.kept(song))
            val kept = MemoryArtistResults()
            val failing = SongWebSource(Web(failing = true), kept)
            assertNull(failing.search(song))
            assertTrue(kept.keys(SongWebSource.PART).isEmpty())
            assertNull(failing.kept(song))
        }

    @Test
    fun `a closed gate sends and keeps nothing`() =
        runTest {
            val web = Web()
            val source = SongWebSource(web, MemoryArtistResults())
            assertNull(source.search(song, mayPay = { false }))
            assertEquals(0, web.searches)
            assertNull(source.kept(song))
        }

    @Test
    fun `kept as history and whatever the artist switch says, what is kept lasts from the last card written`() =
        runTest {
            assertTrue(SongWebSource.PART.keptAsHistory)
            assertEquals("song.web", SongWebSource.PART.name)
            val kept = MemoryArtistResults()
            val source = SongWebSource(Web(), kept)
            source.renew(song)
            assertTrue(kept.keys(SongWebSource.PART).isEmpty())
            source.search(song)
            source.renew(song)
            assertFalse(kept.keys(SongWebSource.PART).isEmpty())
        }

    @Test
    fun `with no store it keeps in memory, and with no engine it does not search`() =
        runTest {
            val web = Web()
            val source = SongWebSource(web)
            source.search(song)
            assertNotNull(source.kept(song))
            assertNull(SongWebSource(null).search(song))
        }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-06T00:00:00Z")
    }
}
