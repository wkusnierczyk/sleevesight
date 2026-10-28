package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
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
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.KeptCard
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.settings.pastKeepHistory
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
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

    /** Whether the source cannot be reached (the phone offline, say): its lookups end unreachable. */
    var unreachable = false

    /** The text of the line it finds, when set (#617: what the source says now). */
    var text: String? = null

    /** The texts of the lines it finds, in order, when set; [text] is then not read (#617). */
    var lines: List<String>? = null

    /**
     * Whether a request of its verification fails (#617): its lines are then unverified, and the
     * result provisional, as Last.fm's when MusicBrainz's artist search fails.
     */
    var provisional = false

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
        val said = lines ?: listOf(text ?: "A line from ${id.tag}.")
        val found = said.map { EvidenceDraft(id, Scope.SONG, it, null, Instant.parse("2026-10-16T12:00:00Z")) }
        return when {
            unreachable -> SourceResult(Outcome.Unreachable, emptyList(), byName)
            // A report with no artists (a title first) finds nothing.
            track.artists.isEmpty() -> SourceResult(Outcome.None, emptyList(), byName)
            provisional ->
                SourceResult(
                    Outcome.SongArticle,
                    emptyList(),
                    byName,
                    unverified = found,
                    provisional = true,
                )
            else -> SourceResult(Outcome.SongArticle, found, byName)
        }
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

    /** An error the AI service answers with, as a provider maps it: the call fails, the run goes on. */
    var error: AiErrorKind? = null
    var streaming: Duration = Duration.ZERO

    /** The reply to the request numbered (from 1) as given. */
    var reply: (Int) -> String = { "It is a song [MB1]." }

    override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

    override fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent> =
        flow {
            requests++
            prompts += request.prompt
            failure?.let { throw it }
            error?.let {
                emit(CompletionEvent.Error(it))
                return@flow
            }
            emit(CompletionEvent.Delta(reply(requests)))
            delay(streaming)
            emit(CompletionEvent.Done(Usage(inputTokens = 10, outputTokens = 5), StopReason.END))
            completed++
        }
}

/**
 * A cache that keeps written cards, as the store's synthesis cache would; and, when
 * [keepsResults], the sources' results too, by source, lookup key and whole seconds. Like the
 * store's, it keeps nothing from work started before the last clear of [epoch] (#547).
 */
internal class CardCache(
    private val epoch: ClearEpoch = ClearEpoch(),
    private val clock: Clock? = null,
    private val lifetime: () -> Duration? = { null },
) : PipelineCache {
    private val cards = mutableMapOf<CardKey, String>()

    // When each card was kept, by [clock]; none without one, as a cache that does not say.
    private val keptAt = mutableMapOf<CardKey, Instant>()
    private val results = mutableMapOf<Triple<SourceId, String, Long?>, SourceResult>()
    var keepsResults = false

    /** How many cards, and results, it holds. */
    val held: Int get() = cards.size + results.size

    /** Forgets every card, as an eviction would. */
    fun clearCards() = cards.clear()

    /** Every card kept now reads [text], as cards kept under other settings would (#617). */
    fun replaceCards(text: String) = cards.replaceAll { _, _ -> text }

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
        if (!keepsResults) return
        epoch.ifCurrent(currentCoroutineContext()) {
            results[Triple(adapter.id, track.lookupKey, duration?.inWholeSeconds)] = result
        }
    }

    override suspend fun card(key: CardKey): String? = keptCard(key)?.text

    /** A read of a card waits for this once it has its answer, as a slow read would; null, none. */
    var readHeld: Deferred<Unit>? = null

    /** When each card kept was written, as kept. */
    val keptTimes: Collection<Instant> get() = keptAt.values

    // As the store serves it: none written longer ago than [lifetime] (*Keep history for*, #643),
    // which is deleted as it is read.
    override suspend fun keptCard(key: CardKey): KeptCard? {
        val at = keptAt[key]
        if (clock != null && pastKeepHistory(at, lifetime(), clock.now())) {
            cards.remove(key)
            keptAt.remove(key)
        }
        val kept = cards[key]?.let { KeptCard(it, at) }
        if (kept != null) readHeld?.await()
        return kept
    }

    override suspend fun keep(
        key: CardKey,
        text: String,
    ) {
        epoch.ifCurrent(currentCoroutineContext()) {
            cards[key] = text
            clock?.let { keptAt[key] = it.now() }
        }
    }

    override suspend fun keep(
        key: CardKey,
        text: String,
        writtenAt: Instant,
    ) {
        epoch.ifCurrent(currentCoroutineContext()) {
            cards[key] = text
            keptAt[key] = writtenAt
        }
    }
}

/** A clock the tests move ([at]), which moves on by [tick] each time it is read. */
internal class MovedClock(
    var at: Instant = Instant.parse("2026-10-27T12:00:00Z"),
    var tick: Duration = Duration.ZERO,
) : Clock {
    override fun now(): Instant = at.also { at += tick }
}
