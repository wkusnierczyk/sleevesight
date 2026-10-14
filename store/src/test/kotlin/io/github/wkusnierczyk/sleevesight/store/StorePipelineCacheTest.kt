package io.github.wkusnierczyk.sleevesight.store

import io.github.wkusnierczyk.sleevesight.core.ai.ReasoningEffort
import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.ProviderId
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.pipeline.CardKey
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import io.github.wkusnierczyk.sleevesight.core.synthesis.CardKind
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StorePipelineCacheTest {
    private val fixture = CacheFixture()
    private val cache = StorePipelineCache(fixture.caches, EmptyCoroutineContext)
    private val sundown = fixture.normalizer.normalize("Sundown", "Gordon Lightfoot")
    private val adapter =
        object : SourceAdapter {
            override val id: SourceId = SourceId.WIKIPEDIA

            override suspend fun lookup(
                track: NormalizedTrack,
                resolution: Deferred<Resolution>,
            ): SourceResult = error("not looked up")
        }

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `a source result is kept per adapter and track`() =
        runBlocking {
            val result =
                SourceResult(
                    Outcome.SongArticle,
                    listOf(EvidenceDraft(SourceId.WIKIPEDIA, Scope.SONG, "A line.", null, fixture.clock.now)),
                    12.milliseconds,
                )
            cache.keep(adapter, sundown, 213.seconds, result)

            assertEquals(result, cache.result(adapter, sundown, 213.seconds))
            assertNull(cache.result(adapter, sundown, null))
            assertNull(cache.result(adapter, fixture.normalizer.normalize("Sundown", "Someone Else"), 213.seconds))
        }

    @Test
    fun `nothing from web search reaches the store`() =
        runBlocking {
            val web =
                object : SourceAdapter {
                    override val id: SourceId = SourceId.WEB

                    override suspend fun lookup(
                        track: NormalizedTrack,
                        resolution: Deferred<Resolution>,
                    ): SourceResult = error("not looked up")
                }
            val snippet = EvidenceDraft(SourceId.WEB, Scope.WEB, "A highlight.", "https://x.example", fixture.clock.now)
            cache.keep(web, sundown, 213.seconds, SourceResult(Outcome.SongArticle, listOf(snippet), 12.milliseconds))

            assertNull(cache.result(web, sundown, 213.seconds))
            assertEquals(
                0,
                fixture.caches
                    .stats()
                    .retrieval.entries,
            )
        }

    @Test
    fun `a card is kept under the track's retrieval key`() =
        runBlocking {
            val key =
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
            cache.keep(key, "It was a hit [W1].")

            assertEquals("It was a hit [W1].", cache.card(key))
            val stored =
                fixture.caches.synthesis.get(
                    SynthesisKey(
                        CardKind.INSIGHT,
                        fixture.caches.retrieval.keyOf(sundown, 213.seconds),
                        "9f2c",
                        1,
                        "e3b0",
                        ProviderId.ANTHROPIC,
                        "claude-haiku-5-5",
                        false,
                        ReasoningEffort.LOW,
                    ),
                )
            assertEquals("It was a hit [W1].", stored?.text)
            assertNull(cache.card(key.copy(kind = CardKind.SONG)))
        }
}
