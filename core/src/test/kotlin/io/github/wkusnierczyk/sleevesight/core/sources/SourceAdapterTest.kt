package io.github.wkusnierczyk.sleevesight.core.sources

import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceSet
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.normalizer.Normalizer
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import io.github.wkusnierczyk.sleevesight.core.platform.JvmHasher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class SourceAdapterTest {
    private val track: NormalizedTrack = Normalizer(JvmAccentFolder).normalize("Bad Energy", "Reem")

    // A compile-shape check: these tests exercise a fake written here, not production code, and
    // show only that the interface allows the shape D4 asks for (find a candidate by name at once,
    // then await the resolution only to verify it). The behaviour itself, other sources' lines
    // landing before the resolution completes, belongs in the pipeline tests with fake adapters
    // (#44).
    private class FakeWikipedia(
        private val performerMbid: String,
    ) : SourceAdapter {
        override val id = SourceId.WIKIPEDIA
        var lookedUpByName = false

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult {
            lookedUpByName = true
            val resolved = resolution.await()
            if (resolved.artists.none { it.mbid == performerMbid }) {
                return SourceResult(
                    Outcome.Rejected("the article is about a different ${track.primary}"),
                    emptyList(),
                    5.milliseconds,
                )
            }
            val line =
                EvidenceDraft(
                    id,
                    Scope.SONG,
                    "A song by ${track.primary}.",
                    null,
                    Instant.parse("2026-10-13T12:00:00Z"),
                )
            return SourceResult(Outcome.SongArticle, listOf(line), 5.milliseconds)
        }
    }

    @Test
    fun `an adapter starts by name and awaits the resolution only to verify`() =
        runTest {
            val adapter = FakeWikipedia(performerMbid = "other-reem")
            val resolution = CompletableDeferred<Resolution>()
            val result = async { adapter.lookup(track, resolution) }
            yield()
            assertTrue(adapter.lookedUpByName)
            assertFalse(result.isCompleted)
            resolution.complete(Resolution(artists = listOf(ResolvedArtist("Reem", "this-reem"))))
            assertEquals(Outcome.Rejected("the article is about a different Reem"), result.await().outcome)
        }

    @Test
    fun `a verified match gives evidence under the adapter's tag`() =
        runTest {
            val adapter = FakeWikipedia(performerMbid = "this-reem")
            val resolution = CompletableDeferred(Resolution(artists = listOf(ResolvedArtist("Reem", "this-reem"))))
            val result = adapter.lookup(track, resolution)
            assertEquals(Outcome.SongArticle, result.outcome)
            assertEquals("W", adapter.tag)
            assertEquals(listOf("W1"), EvidenceSet.of(result.evidence, JvmHasher).ids)
        }

    @Test
    fun `nothing resolved is the empty resolution`() {
        assertEquals(Resolution(), Resolution.NONE)
        assertEquals(null, Resolution.NONE.recordingMbid)
    }
}
