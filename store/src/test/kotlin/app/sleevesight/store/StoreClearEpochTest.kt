package app.sleevesight.store

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * *Clear history*'s boundary in the stores (#547): what work started before it finds is not kept
 * after it, in the retrieval, synthesis and artist-level caches; work started after it is.
 */
class StoreClearEpochTest {
    private val fixture = CacheFixture()
    private val epoch = ClearEpoch()
    private val cache = StorePipelineCache(fixture.caches, EmptyCoroutineContext, ArtistKeeping.ALWAYS, epoch)
    private val artists = StoreArtistResults({ fixture.caches }, EmptyCoroutineContext, ArtistKeeping.ALWAYS, epoch)
    private val sundown = fixture.normalizer.normalize("Sundown", "Gordon Lightfoot")
    private val adapter =
        object : SourceAdapter {
            override val id: SourceId = SourceId.WIKIPEDIA

            override suspend fun lookup(
                track: NormalizedTrack,
                resolution: Deferred<Resolution>,
            ): SourceResult = error("not looked up")
        }
    private val result =
        SourceResult(
            Outcome.SongArticle,
            listOf(EvidenceDraft(SourceId.WIKIPEDIA, Scope.SONG, "A line.", null, fixture.clock.now)),
            12.milliseconds,
        )
    private val key =
        CardKey(
            kind = CardKind.INSIGHT,
            track = sundown,
            duration = 213.seconds,
            evidenceFingerprint = "9f2c",
            promptVersion = 1,
            styleHash = "e3b0",
            provider = ProviderId.ANTHROPIC,
            model = "claude-haiku-5-5",
            modelKnowledge = false,
            effort = ReasoningEffort.LOW,
        )
    private val part = ArtistPart("test.lastfm", 1, 1, setOf(SourceId.LASTFM))

    @AfterTest
    fun close() = fixture.close()

    private suspend fun keepEverything() {
        cache.keep(adapter, sundown, 213.seconds, result)
        cache.keep(key, "It was a hit [W1].")
        artists.keep(part, "gordon lightfoot", "Bio.")
    }

    @Test
    fun `work started before a clear keeps nothing after it`() =
        runBlocking {
            val before = epoch.mark()
            epoch.advance()
            withContext(before) { keepEverything() }

            val empty = TableStats(0, 0)
            assertEquals(CacheStats(empty, empty, empty, empty), fixture.caches.stats())
        }

    @Test
    fun `work started after a clear keeps what it finds`() =
        runBlocking {
            epoch.advance()
            withContext(epoch.mark()) { keepEverything() }

            assertEquals(result, cache.result(adapter, sundown, 213.seconds))
            assertEquals("It was a hit [W1].", cache.card(key))
            assertNotNull(artists.get(part, "gordon lightfoot"))
        }
}
