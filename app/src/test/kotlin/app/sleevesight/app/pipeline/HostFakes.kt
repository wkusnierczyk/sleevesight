package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Instant

// The fakes the host's tests share.

/** An adapter that answers a song line after [byName], recording what it was asked. */
internal class CountingAdapter(
    override val id: SourceId,
) : SourceAdapter {
    var byName: Duration = Duration.ZERO
    val asked = mutableListOf<String>()
    var cancelled = 0

    override suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult {
        asked += track.lookupKey
        try {
            delay(byName)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            cancelled++
            throw e
        }
        resolution.await()
        // A report with no artists (a title first) finds nothing.
        if (track.artists.isEmpty()) return SourceResult(Outcome.None, emptyList(), byName)
        val line = EvidenceDraft(id, Scope.SONG, "A line from ${id.tag}.", null, Instant.parse("2026-10-16T12:00:00Z"))
        return SourceResult(Outcome.SongArticle, listOf(line), byName)
    }
}

/** A provider that replies with one cited sentence, counting its requests. */
internal class CountingProvider : AiProvider {
    override val id: ProviderId = ProviderId.ANTHROPIC
    var requests = 0
    var completed = 0

    /** The prompt of each request, in order. */
    val prompts = mutableListOf<String>()

    /** The requests whose prompt names [title]. */
    fun requestsFor(title: String): Int = prompts.count { title in it }

    var failure: Exception? = null
    var streaming: Duration = Duration.ZERO

    override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

    override fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent> =
        flow {
            requests++
            prompts += request.prompt
            failure?.let { throw it }
            emit(CompletionEvent.Delta("It is a song [MB1]."))
            delay(streaming)
            emit(CompletionEvent.Done(Usage(inputTokens = 10, outputTokens = 5), StopReason.END))
            completed++
        }
}

/**
 * A cache that keeps written cards, as the store's synthesis cache would; and, when
 * [keepsResults], the sources' results too, by source, lookup key and whole seconds.
 */
internal class CardCache : PipelineCache {
    private val cards = mutableMapOf<CardKey, String>()
    private val results = mutableMapOf<Triple<SourceId, String, Long?>, SourceResult>()
    var keepsResults = false

    /** Forgets every card, as an eviction would. */
    fun clearCards() = cards.clear()

    override suspend fun result(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
    ): SourceResult? =
        if (keepsResults) results[Triple(adapter.id, track.lookupKey, duration?.inWholeSeconds)] else null

    override suspend fun keep(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
        result: SourceResult,
    ) {
        if (keepsResults) results[Triple(adapter.id, track.lookupKey, duration?.inWholeSeconds)] = result
    }

    override suspend fun card(key: CardKey): String? = cards[key]

    override suspend fun keep(
        key: CardKey,
        text: String,
    ) {
        cards[key] = text
    }
}
