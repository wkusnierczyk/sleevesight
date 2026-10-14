package io.github.wkusnierczyk.sleevesight.core.pipeline

import io.github.wkusnierczyk.sleevesight.core.Secret
import io.github.wkusnierczyk.sleevesight.core.ai.AiProvider
import io.github.wkusnierczyk.sleevesight.core.ai.CompletionEvent
import io.github.wkusnierczyk.sleevesight.core.ai.CompletionRequest
import io.github.wkusnierczyk.sleevesight.core.ai.ModelList
import io.github.wkusnierczyk.sleevesight.core.ai.StopReason
import io.github.wkusnierczyk.sleevesight.core.ai.Usage
import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.NowPlaying
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.ProviderId
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Instant

val RETRIEVED: Instant = Instant.parse("2026-10-14T12:00:00Z")

fun nowPlaying(
    title: String,
    artists: String,
    album: String? = null,
): NowPlaying = NowPlaying(title = title, artists = artists, album = album, player = "com.spotify.music")

fun line(
    source: SourceId,
    text: String,
    scope: Scope = Scope.SONG,
): EvidenceDraft = EvidenceDraft(source, scope, text, null, RETRIEVED)

/**
 * An adapter that answers after [byName] (its lookup by name), then, when [verifies], awaits the
 * resolution, and gives [answer] for it. It records what it was asked and whether it was cancelled.
 */
class FakeAdapter(
    override val id: SourceId,
    private val byName: Duration = Duration.ZERO,
    private val verifies: Boolean = true,
    private val failure: Exception? = null,
    private val answer: (NormalizedTrack, Resolution?) -> SourceResult = { _, _ ->
        SourceResult(Outcome.SongArticle, listOf(line(id, "A line from ${id.tag}.")), byName)
    },
) : SourceAdapter {
    val asked = mutableListOf<String>()
    val resolutions = mutableListOf<Resolution>()
    var cancelled = 0

    override suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult {
        asked += track.lookupKey
        try {
            delay(byName)
            failure?.let { throw it }
            val resolved = if (verifies) resolution.await().also { resolutions += it } else null
            return answer(track, resolved)
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
    }
}

/** A resolver that answers [resolution] after [delay], counting its calls. */
class FakeResolver(
    private val delay: Duration = Duration.ZERO,
    private val failure: Exception? = null,
    var resolution: Resolution = Resolution(outcome = Outcome.SongArticle, recordingMbid = "rec-1"),
) : TrackResolver {
    var calls = 0

    override suspend fun resolve(
        track: NormalizedTrack,
        duration: Duration?,
    ): Resolution {
        calls++
        delay(delay)
        failure?.let { throw it }
        return resolution
    }
}

/** A provider that replies with [reply], after [delay], recording each request. */
class FakeProvider(
    private val delay: Duration = Duration.ZERO,
    var reply: (CompletionRequest) -> List<CompletionEvent> = { done("It is a song [MB1].") },
) : AiProvider {
    override val id: ProviderId = ProviderId.ANTHROPIC
    val requests = mutableListOf<CompletionRequest>()

    override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

    override fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent> =
        flow {
            requests += request
            delay(delay)
            reply(request).forEach { emit(it) }
        }

    companion object {
        val USAGE: Usage = Usage(inputTokens = 900, outputTokens = 60)

        fun done(
            text: String,
            stop: StopReason = StopReason.END,
        ): List<CompletionEvent> = text.chunked(8).map { CompletionEvent.Delta(it) } + CompletionEvent.Done(USAGE, stop)
    }
}

/** A cache in memory, as the store's would answer, counting reads and writes. */
class MemoryCache : PipelineCache {
    val results = mutableMapOf<Pair<SourceId, String>, SourceResult>()
    val cards = mutableMapOf<CardKey, String>()
    var failing = false

    override suspend fun result(
        adapter: SourceAdapter,
        track: NormalizedTrack,
    ): SourceResult? {
        check(!failing) { "cache down" }
        return results[adapter.id to track.lookupKey]
    }

    override suspend fun keep(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        result: SourceResult,
    ) {
        check(!failing) { "cache down" }
        if (result.outcome != Outcome.Unreachable) results[adapter.id to track.lookupKey] = result
    }

    override suspend fun card(key: CardKey): String? = cards[key]

    override suspend fun keep(
        key: CardKey,
        text: String,
    ) {
        cards[key] = text
    }
}
