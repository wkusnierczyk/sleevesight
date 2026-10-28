package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.net.HostPolicy
import app.sleevesight.net.NetBody
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetMethod
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.net.NetSecrets
import app.sleevesight.net.fetch
import kotlinx.coroutines.Deferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Web search through Exa, the first provider (D11; `design/design.md`, *Web search provider (D11)*), on the
 * user's own key. One evidence line per kept result: Exa's highlight for it, verbatim apart from
 * whitespace and cut to at most [LINE_CHARACTERS] at a word boundary ([cut]), with the result's URL
 * (architecture, *Source adapters*).
 *
 * The query names the song and its credited artists ([ExaSearchRequest.of]); the results are then
 * kept or dropped by [RecordingFilter], at most [MAX_LINES] in Exa's order. The filter judges the
 * line as cut, so a result is never kept for words the line then leaves out. The adapter does not
 * wait for [Resolution]: a web result has no id to verify against, so the filter is its only check.
 * A kept result's line goes in [SourceResult.evidence] and may be cited, as a line about the track
 * (scope web), never checked against the ids MusicBrainz resolves.
 *
 * Outcomes: [Outcome.SongArticle] when a result is kept; [Outcome.Rejected] when results came back
 * and none passed the filter; [Outcome.None] when none came back, or when the track cannot be
 * checked ([RecordingFilter.canCheck]: no credited artist, or a title or artist that folds to
 * nothing, such as "!!!"), since then no result could pass and nothing is sent; [Outcome.Unreachable]
 * for a network error or any answer other than a 2xx (a wrong key, exhausted credits, a bad
 * request), which carries nothing to show.
 *
 * `:wiring`'s list of host policies (`HostPolicies`) registers [HOST_POLICY] under [HOST] in the
 * `NetConfig.hosts` of the app and the CLI; without it `:net` puts no limit on Exa's host.
 */
class ExaWebSearch(
    private val net: NetClient,
    private val key: Secret,
    accentFolder: AccentFolder,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : SourceAdapter {
    override val id: SourceId = SourceId.WEB

    override val extractionVersion: Int = EXTRACTION_VERSION

    private val filter = RecordingFilter(accentFolder)

    override suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult {
        val start = timeSource.markNow()
        if (!filter.canCheck(track)) return result(Outcome.None, emptyList(), start)
        val fetched = net.fetch(request(track), ::parse)
        val retrievedAt = clock.now()
        val outcome: Outcome
        val evidence: List<EvidenceDraft>
        when (fetched) {
            is NetResult.Failure -> {
                outcome = Outcome.Unreachable
                evidence = emptyList()
            }

            is NetResult.Success -> {
                val response = fetched.value
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
        return result(outcome, evidence, start)
    }

    /**
     * Every result this adapter returns, marked as about the track ([SourceResult.trackOnly],
     * #420): web pages about the recording, found by it, so they are kept with *Keep what sources
     * said about each artist* off too, and a replay makes no paid search. A kept page's snippet may
     * still name or describe the recording's artists, which the settings and the privacy policy say.
     */
    private fun result(
        outcome: Outcome,
        evidence: List<EvidenceDraft>,
        start: TimeMark,
    ): SourceResult = SourceResult(outcome, evidence, start.elapsedNow(), trackOnly = true)

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
         * a single client within it. `:wiring`'s list of host policies must hold it under [HOST].
         */
        val HOST_POLICY: HostPolicy = HostPolicy(minInterval = 100.milliseconds)

        /** The most lines kept per track. Fixed, as the Wikipedia quote count is (D1). */
        const val MAX_LINES: Int = 5

        internal const val REJECTED: String = "no result is about this recording"

        internal val JSON: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /**
         * [extractionVersion]. 2: each line cut to [LINE_CHARACTERS] and judged as cut (#634), so a
         * result kept before, with a line up to ten times as long or kept for words past the cut,
         * is missed instead of served.
         */
        const val EXTRACTION_VERSION: Int = 2

        // Unicode's White_Space, listed (no-break spaces, line and paragraph separators, NEL): a
        // JVM `\s` is ASCII only, and `(?U)` is not portable (D2). [cut] then finds every word
        // boundary as a plain space.
        private val WHITESPACE =
            Regex("[\\s\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")

        /**
         * The most characters of a line's text before the ellipsis that marks a cut: the 300 the
         * request asks Exa for, which Exa does not hold to (#608), so the adapter does (#634). D1:
         * an evidence line is a short quote, never a whole passage.
         */
        internal const val LINE_CHARACTERS: Int = ExaSearchRequest.HIGHLIGHT_CHARACTERS

        private const val SEPARATOR = " ... "
        private const val ELLIPSIS = "…"

        /**
         * The result's highlights joined as Exa joins its own fragments, whitespace collapsed, and
         * [cut] to at most [LINE_CHARACTERS].
         */
        internal fun snippetOf(result: ExaResult): String =
            cut(
                result.highlights
                    .map { it.replace(WHITESPACE, " ").trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(SEPARATOR),
            )

        /**
         * [text] (whitespace already collapsed) as it comes when it has at most [max] characters;
         * otherwise its longest start of at most [max] characters that ends at a word boundary,
         * with `…` after it, so the line is still a verbatim quote and the cut shows. A start that
         * ends in the separator between two highlights leaves the separator out. Text with no space
         * in its first [max] characters is cut at the last boundary at or before [max] between two
         * characters as a reader sees them ([joinsPrevious]): never inside a surrogate pair,
         * between a letter and its accent, or inside an emoji sequence or a flag.
         */
        internal fun cut(
            text: String,
            max: Int = LINE_CHARACTERS,
        ): String {
            if (text.length <= max) return text
            val space = if (text[max] == ' ') max else text.lastIndexOf(' ', max - 1)
            val end = if (space > 0) space else characterBoundary(text, max)
            return text
                .substring(0, end)
                .trimEnd()
                .removeSuffix(SEPARATOR.trimEnd())
                .trimEnd() + ELLIPSIS
        }

        // The last boundary between characters as a reader sees them at or before [max] in [text];
        // if the first such character is longer, [max] itself, outside a surrogate pair.
        private fun characterBoundary(
            text: String,
            max: Int,
        ): Int {
            var end = max
            while (end > 0 && joinsPrevious(text, end)) end--
            return when {
                end > 0 -> end
                text[max].isLowSurrogate() -> max - 1
                else -> max
            }
        }

        /**
         * Whether the character at [i] in [text] belongs with the one before it, so a cut at [i]
         * would split what a reader sees as one: the second half of a surrogate pair; a combining
         * mark or a variation selector, in the basic plane or among [SUPPLEMENTARY_MARKS]; either
         * side of a zero-width joiner (emoji sequences); a skin-tone modifier or a tag character
         * (subdivision flags); or the second of a pair of regional indicators (a flag). Unicode's
         * grapheme rules, in the part a quote meets; `java.text.BreakIterator` has them all but is
         * JVM-only (D2). Left out: Hangul syllables spelled as conjoining jamo, other combining
         * marks outside the basic plane, prepended characters and spacing marks of scripts outside
         * the basic plane, which a cut at a word boundary, the usual case, never meets.
         */
        internal fun joinsPrevious(
            text: String,
            i: Int,
        ): Boolean {
            val c = text[i]
            val point = codePointAt(text, i)
            return c.isLowSurrogate() ||
                c.category in MARKS ||
                c == ZERO_WIDTH_JOINER ||
                text[i - 1] == ZERO_WIDTH_JOINER ||
                point in SKIN_TONES ||
                point in TAGS ||
                SUPPLEMENTARY_MARKS.any { point in it } ||
                (point in REGIONAL_INDICATORS && regionalIndicatorsBefore(text, i) % 2 == 1)
        }

        // The number of regional indicators in a row just before [i].
        private fun regionalIndicatorsBefore(
            text: String,
            i: Int,
        ): Int {
            var count = 0
            var j = i
            while (j >= 2 && codePointAt(text, j - 2) in REGIONAL_INDICATORS) {
                count++
                j -= 2
            }
            return count
        }

        // The code point starting at [i]: a surrogate pair's, or the single character's.
        private fun codePointAt(
            text: String,
            i: Int,
        ): Int {
            val high = text[i]
            val low = text.getOrNull(i + 1)
            return if (high.isHighSurrogate() && low != null && low.isLowSurrogate()) {
                SUPPLEMENTARY_BASE + ((high.code - Char.MIN_HIGH_SURROGATE.code) shl SURROGATE_BITS) +
                    (low.code - Char.MIN_LOW_SURROGATE.code)
            } else {
                high.code
            }
        }

        private val MARKS =
            setOf(CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK, CharCategory.COMBINING_SPACING_MARK)
        private const val ZERO_WIDTH_JOINER = '\u200D'
        private const val SUPPLEMENTARY_BASE = 0x10000
        private const val SURROGATE_BITS = 10
        private val SKIN_TONES = 0x1F3FB..0x1F3FF
        private val TAGS = 0xE0020..0xE007F

        /**
         * Marks outside the basic plane that join the character before them, which a [Char]'s
         * category cannot show: the combining marks of musical notation (`Mn` and `Mc`) and the
         * variation selectors supplement (ideographic variants: 葛 and U+E0100).
         */
        private val SUPPLEMENTARY_MARKS =
            listOf(
                0x1D165..0x1D169,
                0x1D16D..0x1D172,
                0x1D17B..0x1D182,
                0x1D185..0x1D18B,
                0x1D1AA..0x1D1AD,
                0xE0100..0xE01EF,
            )
        private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

        /** A 2xx body read as a search response; null for any other status, which has no results. */
        internal fun parse(response: NetResponse): ExaSearchResponse? =
            if (response.isSuccess) JSON.decodeFromString<ExaSearchResponse>(response.body) else null
    }
}

/**
 * The body of Exa's `POST /search` (https://exa.ai/docs/reference/search), as the adapter sends it.
 *
 * - `lite`: the cheapest type ($4 per 1000 against $7 for `auto`), on the same index as `auto`,
 *   served from the index without fetching pages at query time. It replaced `instant` (#584), which
 *   Exa 3.0 made legacy at the same price. In the spike `instant` was as relevant as `auto` on the
 *   fixture's tracks at a tenth of the latency; compared live with `instant` since (#608), `lite`
 *   found the recording on far more of the fixture's tracks, at about the same speed.
 *   `lite` accepts only a restricted set of parameters and rejects a request with any other;
 *   every field here is in that set (`ExaWebSearchTest`).
 * - [numResults] 10, the most the base price covers, so the filter has enough to choose from: it
 *   kept 0 to 4 of 10 on the thin and same-title tracks with `instant` (2026-10-14), and 4 or 5
 *   with `lite` (2026-10-27, #608). Highlights are not billed on top
 *   (`design/design.md`, *Web search provider (D11)*), so a search costs $0.004 by Exa's pricing.
 * - [excludeDomains]: [RecordingFilter.OWN_SOURCE_HOSTS], which the filter drops anyway.
 * - Highlights asked for at 300 characters, steered by the title, the work it is from when there
 *   is one, and the artists: the snippet. Exa's results carry no snippet without a `contents`
 *   option. `maxCharacters` is not a cap: about three in ten came back longer, up to 3,000 (#608),
 *   so the adapter cuts each line to it ([ExaWebSearch.cut], #634).
 * - The results are then judged by [RecordingFilter]: the song (and its work) and a distinctive
 *   credited artist named, and the result's own title not naming the artist without the song.
 */
@Serializable
internal data class ExaSearchRequest(
    val query: String,
    val type: String = "lite",
    val numResults: Int = 10,
    val excludeDomains: List<String> = RecordingFilter.OWN_SOURCE_HOSTS,
    val contents: ExaContents,
) {
    companion object {
        /**
         * The query for [track]: `"<title>" from <work> by <artists>, song`, the `from` part only
         * when the player named the work ([NormalizedTrack.from]), with the artists as a sentence
         * would list them. Exa's search is neural, so a phrase reads better than keywords; the
         * quotes keep a title made of common words together. The track credits an artist: the
         * adapter sends nothing otherwise.
         */
        fun of(track: NormalizedTrack): ExaSearchRequest {
            val artists = track.artists.filter { it.isNotBlank() }
            val byline =
                if (artists.size == 1) {
                    " by ${artists[0]}"
                } else {
                    " by ${artists.dropLast(1).joinToString(", ")} and ${artists.last()}"
                }
            val from = track.from?.let { " from $it" }.orEmpty()
            return ExaSearchRequest(
                query = "\"${track.title}\"$from$byline, song",
                contents =
                    ExaContents(
                        ExaHighlights(
                            query = (listOfNotNull(track.title, track.from) + artists).joinToString(" "),
                            maxCharacters = HIGHLIGHT_CHARACTERS,
                        ),
                    ),
            )
        }

        /** What the request asks Exa for, and what the adapter cuts a line to ([ExaWebSearch.cut]). */
        const val HIGHLIGHT_CHARACTERS: Int = 300
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
