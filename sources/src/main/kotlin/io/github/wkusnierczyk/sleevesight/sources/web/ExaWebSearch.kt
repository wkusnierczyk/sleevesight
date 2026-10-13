package io.github.wkusnierczyk.sleevesight.sources.web

import io.github.wkusnierczyk.sleevesight.core.Secret
import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.normalizer.AccentFolder
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import io.github.wkusnierczyk.sleevesight.net.HostPolicy
import io.github.wkusnierczyk.sleevesight.net.NetBody
import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetMethod
import io.github.wkusnierczyk.sleevesight.net.NetRequest
import io.github.wkusnierczyk.sleevesight.net.NetResponse
import io.github.wkusnierczyk.sleevesight.net.NetResult
import io.github.wkusnierczyk.sleevesight.net.NetSecrets
import io.github.wkusnierczyk.sleevesight.net.fetch
import kotlinx.coroutines.Deferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Web search through Exa, the first provider (D11; `design/design.md`, *Open questions*), on the
 * user's own key. One evidence line per kept result: Exa's highlight for it, verbatim apart from
 * whitespace, with the result's URL (architecture, *Source adapters*).
 *
 * The query names the song and its credited artists ([ExaSearchRequest.of]); the results are then
 * kept or dropped by [RecordingFilter], at most [MAX_LINES] in Exa's order. The adapter does not
 * wait for [Resolution]: a web result has no id to verify against, so the filter is its check, and
 * its lines stay unverified like any other source's that MusicBrainz cannot confirm.
 *
 * Outcomes: [Outcome.SongArticle] when a result is kept; [Outcome.Rejected] when results came back
 * and none named the song and an artist; [Outcome.None] when none came back; [Outcome.Unreachable]
 * for a network error or any answer other than a 2xx (a wrong key, exhausted credits, a bad
 * request), which carries nothing to show.
 */
class ExaWebSearch(
    private val net: NetClient,
    private val key: Secret,
    accentFolder: AccentFolder,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : SourceAdapter {
    override val id: SourceId = SourceId.WEB

    private val filter = RecordingFilter(accentFolder)

    override suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult {
        val start = timeSource.markNow()
        val result = net.fetch(request(track), ::parse)
        val retrievedAt = clock.now()
        val outcome: Outcome
        val evidence: List<EvidenceDraft>
        when (result) {
            is NetResult.Failure -> {
                outcome = Outcome.Unreachable
                evidence = emptyList()
            }

            is NetResult.Success -> {
                val response = result.value
                if (response == null) {
                    outcome = Outcome.Unreachable
                    evidence = emptyList()
                } else {
                    evidence = linesFor(track, response, retrievedAt)
                    outcome =
                        when {
                            evidence.isNotEmpty() -> Outcome.SongArticle
                            response.results.isEmpty() -> Outcome.None
                            else -> Outcome.Rejected(REJECTED)
                        }
                }
            }
        }
        return SourceResult(outcome, evidence, start.elapsedNow())
    }

    /** The search request for [track]: the key in a header, through [NetSecrets] only. */
    internal fun request(track: NormalizedTrack): NetRequest =
        NetRequest(
            url = ENDPOINT,
            method = NetMethod.POST,
            secrets = NetSecrets(headers = mapOf(KEY_HEADER to key.reveal())),
            body = NetBody(JSON.encodeToString(ExaSearchRequest.of(track)), "application/json"),
        )

    private fun linesFor(
        track: NormalizedTrack,
        response: ExaSearchResponse,
        retrievedAt: Instant,
    ): List<EvidenceDraft> {
        val seen = mutableSetOf<String>()
        return response.results
            .asSequence()
            .mapNotNull { result ->
                val snippet = snippetOf(result)
                if (snippet.isEmpty() || !seen.add(result.url)) return@mapNotNull null
                if (!filter.isAbout(track, result.title, snippet, result.url)) return@mapNotNull null
                EvidenceDraft(SourceId.WEB, Scope.WEB, snippet, result.url, retrievedAt)
            }.take(MAX_LINES)
            .toList()
    }

    companion object {
        const val HOST: String = "api.exa.ai"
        const val ENDPOINT: String = "https://$HOST/search"
        const val KEY_HEADER: String = "x-api-key"

        /**
         * Exa allows 10 requests a second to `/search` across a team's keys; one every 100 ms keeps
         * a single client within it. For `NetConfig.hosts` under [HOST].
         */
        val HOST_POLICY: HostPolicy = HostPolicy(minInterval = 100.milliseconds)

        /** The most lines kept per track. Fixed, as the Wikipedia quote count is (D1). */
        const val MAX_LINES: Int = 5

        internal const val REJECTED: String = "no result names both the song and a credited artist"

        internal val JSON: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        private val WHITESPACE = Regex("\\s+")

        /** The result's highlights joined as Exa joins its own fragments, whitespace collapsed. */
        internal fun snippetOf(result: ExaResult): String =
            result.highlights
                .map { it.replace(WHITESPACE, " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ... ")

        /** A 2xx body read as a search response; null for any other status, which has no results. */
        internal fun parse(response: NetResponse): ExaSearchResponse? =
            if (response.isSuccess) JSON.decodeFromString<ExaSearchResponse>(response.body) else null
    }
}

/**
 * The body of Exa's `POST /search` (https://exa.ai/docs/reference/search), as the adapter sends it.
 *
 * - `instant`: the cheapest type ($4 per 1000 against $7 for `auto`) and about a tenth of the
 *   latency (0.3 s against 3 s in the spike), with results as relevant on the fixture's tracks.
 * - [numResults] 10, the most the base price covers, so the filter has enough to choose from.
 * - [excludeDomains]: [RecordingFilter.OWN_SOURCE_HOSTS], which the filter drops anyway.
 * - Highlights of at most 300 characters, steered by the title and artists: the snippet. Exa's
 *   results carry no snippet without a `contents` option.
 */
@Serializable
internal data class ExaSearchRequest(
    val query: String,
    val type: String = "instant",
    val numResults: Int = 10,
    val excludeDomains: List<String> = RecordingFilter.OWN_SOURCE_HOSTS,
    val contents: ExaContents,
) {
    companion object {
        /**
         * The query for [track]: `"<title>" by <artists>, song`, with the artists as a sentence
         * would list them. Exa's search is neural, so a phrase reads better than keywords; the
         * quotes keep a title made of common words together.
         */
        fun of(track: NormalizedTrack): ExaSearchRequest {
            val artists = track.artists.ifEmpty { listOf(track.primary) }.filter { it.isNotBlank() }
            val byline =
                when (artists.size) {
                    0 -> ""
                    1 -> " by ${artists[0]}"
                    else -> " by ${artists.dropLast(1).joinToString(", ")} and ${artists.last()}"
                }
            return ExaSearchRequest(
                query = "\"${track.title}\"$byline, song",
                contents =
                    ExaContents(
                        ExaHighlights(
                            query = (listOf(track.title) + artists).joinToString(" "),
                            maxCharacters = HIGHLIGHT_CHARACTERS,
                        ),
                    ),
            )
        }

        private const val HIGHLIGHT_CHARACTERS = 300
    }
}

@Serializable
internal data class ExaContents(
    val highlights: ExaHighlights,
)

@Serializable
internal data class ExaHighlights(
    val query: String,
    val maxCharacters: Int,
)

/** The parts of Exa's search response the adapter reads; everything else is ignored. */
@Serializable
internal data class ExaSearchResponse(
    val results: List<ExaResult> = emptyList(),
)

@Serializable
internal data class ExaResult(
    val url: String,
    val title: String? = null,
    val highlights: List<String> = emptyList(),
)
