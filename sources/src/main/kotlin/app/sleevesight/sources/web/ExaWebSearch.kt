package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.pipeline.songReviewQuery
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.text.HtmlReferences
import app.sleevesight.core.text.SourceText
import app.sleevesight.core.text.WebLinks
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
 * whitespace and the parts that are only page boilerplate ([WebBoilerplate], #905), cut to at most
 * [LINE_CHARACTERS] at a word boundary ([cut]), with the result's URL (architecture, *Source
 * adapters*). A result whose every part is boilerplate, or names only the track in a page's words,
 * gives no line.
 *
 * The query names the song and its credited artists ([ExaSearchRequest.of]); the results are then
 * kept or dropped by [RecordingFilter], at most [MAX_LINES] in Exa's order. The filter judges the
 * snippet as cut, so a result is never kept for words the cut leaves out; it judges it before the
 * boilerplate parts go, since a part naming only the song and the artist shows what the page is
 * about. The adapter does not wait for [Resolution]: a web result has no id to verify against, so
 * the filter is its only check.
 * A kept result's line goes in [SourceResult.evidence] and may be cited, as a line about the track
 * (scope web), never checked against the ids MusicBrainz resolves.
 *
 * Outcomes: [Outcome.SongArticle] when a result is kept with a line; [Outcome.Rejected] when results
 * came back and none passed the filter; [Outcome.None] when none came back, when those that passed
 * were all page boilerplate (their addresses in [SourceResult.pages]), or when the track cannot be
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
        var found = WebLines(emptyList(), emptyList())
        when (fetched) {
            is NetResult.Failure -> {
                outcome = Outcome.Unreachable
            }

            is NetResult.Success -> {
                val response = fetched.value
                if (response == null) {
                    outcome = Outcome.Unreachable
                } else {
                    found = linesFor(track, response, retrievedAt)
                    outcome =
                        when {
                            found.lines.isNotEmpty() -> Outcome.SongArticle
                            // About the recording, but only page boilerplate: nothing found, not a rejection.
                            response.results.isEmpty() || found.emptied.isNotEmpty() -> Outcome.None
                            else -> Outcome.Rejected(REJECTED)
                        }
                }
            }
        }
        return result(outcome, found.lines, start, found.emptied)
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
        pages: List<String> = emptyList(),
    ): SourceResult = SourceResult(outcome, evidence, start.elapsedNow(), trackOnly = true, pages = pages)

    /** The search request for [track]: the key in a header, through [NetSecrets] only. */
    internal fun request(track: NormalizedTrack): NetRequest = request(ExaSearchRequest.of(track), key)

    private fun linesFor(
        track: NormalizedTrack,
        response: ExaSearchResponse,
        retrievedAt: Instant,
    ): WebLines =
        lines(response, retrievedAt, track.subjectNames, filter::fold) { result, snippet ->
            filter.isAbout(track, result.title, snippet, result.url, recording = true)
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
         * [extractionVersion]: raised when what the adapter makes of Exa's results changes. 2: each
         * line cut to [LINE_CHARACTERS] and judged as cut (#634), so a result kept before, with a
         * line up to ten times as long or kept for words past the cut, is missed instead of served.
         * 3: the parts that are only page boilerplate left out before the cut ([WebBoilerplate],
         * #905), so a line kept before, with a video site's footer or a store's rights line as a
         * part of it, is looked up again. 4: each result's title and highlights with their HTML
         * character references decoded ([parse], #1165), so a line kept before with `&#039;` in it is
         * looked up again. The language searches ([ExaLanguageSearch]) share it.
         */
        const val EXTRACTION_VERSION: Int = 4

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
         * [cut] to at most [max], [LINE_CHARACTERS] for a source's line. Cleaned ([SourceText], #1041)
         * before the cut, so the cut counts only the characters a person sees; after [clean], so a
         * chord sheet's zero-width spaces still mark where its chords end ([WebBoilerplate]).
         */
        internal fun snippetOf(
            result: ExaResult,
            max: Int = LINE_CHARACTERS,
            clean: (String) -> String = { it },
        ): String {
            val joined =
                result.highlights
                    .map { it.replace(WHITESPACE, " ").trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(SEPARATOR)
            return cut(SourceText.clean(clean(joined)).replace(WHITESPACE, " ").trim(), max)
        }

        /**
         * A source's line for [result]: its highlights joined as [snippetOf] joins them, without
         * the parts that are only its site's boilerplate, for the [subject] ([WebBoilerplate.clean],
         * #905), then [cut] to [LINE_CHARACTERS], so the room those parts took goes to the page's
         * own text. Empty when no part is left.
         */
        internal fun lineOf(
            result: ExaResult,
            subject: List<String>,
            fold: (String) -> String,
        ): String = snippetOf(result) { WebBoilerplate.clean(it, result.url, subject, fold) }

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

        /** The search request carrying [body], on [key]: the key in a header, through [NetSecrets] only. */
        internal fun request(
            body: ExaSearchRequest,
            key: Secret,
        ): NetRequest =
            NetRequest(
                url = ENDPOINT,
                method = NetMethod.POST,
                secrets = NetSecrets(headers = mapOf(KEY_HEADER to key.reveal())),
                body = NetBody(JSON.encodeToString(body), "application/json"),
            )

        /**
         * The web lines of [response]'s results, once per address, in Exa's order, at most
         * [MAX_LINES]: for the track's search, the album's (`ExaAlbumSearch`, #885) and the artist's
         * (`ExaArtistSearch`, #912). [about]
         * judges each result on its snippet as it came, cut ([snippetOf], #634), before any part
         * goes: a part naming only the song and the artist, which goes as boilerplate, is the
         * surest sign of what the page is about. A kept result's line is [lineOf] it, for the
         * [subject] (the track's title, its work and its artists, the album and its artist, or the artist;
         * folded by [fold]); a kept result with no line left is in [WebLines.emptied].
         */
        internal fun lines(
            response: ExaSearchResponse,
            retrievedAt: Instant,
            subject: List<String>,
            fold: (String) -> String,
            about: (ExaResult, String) -> Boolean,
        ): WebLines {
            val seen = mutableSetOf<String>()
            val lines = mutableListOf<EvidenceDraft>()
            val emptied = mutableListOf<String>()
            for (result in response.results) {
                if (lines.size == MAX_LINES) break
                val snippet = snippetOf(result)
                if (snippet.isNotEmpty() && seen.add(result.url) && about(result, snippet)) {
                    val line = lineOf(result, subject, fold)
                    if (line.isEmpty()) {
                        emptied += result.url
                    } else {
                        lines += EvidenceDraft(SourceId.WEB, Scope.WEB, line, result.url, retrievedAt)
                    }
                }
            }
            return WebLines(lines, emptied)
        }

        /**
         * A 2xx body read as a search response, each result's title and highlights with their HTML
         * character references decoded, once, here where they come in ([HtmlReferences], #1165: a
         * page's `&#039;s` shows as `'s`), and a result whose address is not a web address dropped
         * ([WebLinks], #1040); null for any other status, which has no results.
         */
        internal fun parse(response: NetResponse): ExaSearchResponse? =
            if (response.isSuccess) JSON.decodeFromString<ExaSearchResponse>(response.body).decoded() else null
    }
}

/**
 * What a search's results give ([ExaWebSearch.lines], #905): the web [lines], and the addresses of
 * the results kept as about the subject whose text was all page boilerplate, [emptied], which give
 * no line but are still the subject's pages (`SourceResult.pages`: a lyrics page's link).
 */
internal data class WebLines(
    val lines: List<EvidenceDraft>,
    val emptied: List<String>,
)

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
         *
         * A title that leads with its composer is searched without the lead ([NormalizedTrack.searchTitle],
         * #1179), and with a work part ([NormalizedTrack.searchWork]) only that is quoted, the rest
         * following it, since a page on the music names the work and seldom the movement as the
         * player titles it: `"Piano Sonata No. 16 in C Major, K. 545" "Sonata facile": II. Andante by
         * Wolfgang Amadeus Mozart, Khatia Buniatishvili and Academy of St. Martin in the Fields, song`.
         */
        fun of(track: NormalizedTrack): ExaSearchRequest {
            val artists = track.artists.filter { it.isNotBlank() }
            val byline = " by ${listed(artists)}"
            val from = track.from?.let { " from $it" }.orEmpty()
            val title = track.searchTitle
            val named = track.searchWork?.let { work -> "\"$work\" ${afterWork(title, work)}" } ?: "\"$title\""
            return ExaSearchRequest(
                query = "$named$from$byline, song",
                contents =
                    ExaContents(
                        ExaHighlights(
                            query = (listOfNotNull(title, track.from) + artists).joinToString(" "),
                            maxCharacters = HIGHLIGHT_CHARACTERS,
                        ),
                    ),
            )
        }

        // What [title] says after its [work]: the nickname and the movement, the colon or comma
        // between them and the work left out (`II. Romance` of `…, K.466: II. Romance`).
        private fun afterWork(
            title: String,
            work: String,
        ): String = title.removePrefix(work).trimStart { it == ':' || it == ',' || it.isWhitespace() }

        // [artists] as a sentence lists them: "A", "A and B", "A, B and C".
        private fun listed(artists: List<String>): String =
            if (artists.size == 1) {
                artists[0]
            } else {
                "${artists.dropLast(1).joinToString(", ")} and ${artists.last()}"
            }

        /**
         * The query for an [album] by [artist] (#885, `ExaAlbumSearch`): `"<album>" by <artist>,
         * album`, as [of] words a song's, nothing of the track playing, with highlights steered by
         * the album's title and the artist. With [performers] (a track whose title names its
         * composer, the [artist], #1130), every credited artist, [credited] in the player's order
         * (an arranger the title names too), as [of] lists a song's: `"Mozart: Piano Concertos Nos.
         * 20 & 23" by Wolfgang Amadeus Mozart, Khatia Buniatishvili and Academy of St. Martin in the
         * Fields, album`, and the highlights steered by them too, since a page is kept only when it
         * names a performer; the [artist] and the performers, with no [credited] given.
         */
        fun ofAlbum(
            album: String,
            artist: String,
            performers: List<String> = emptyList(),
            credited: List<String> = emptyList(),
        ): ExaSearchRequest {
            val given = credited.filter { it.isNotBlank() }
            val named =
                when {
                    performers.isEmpty() -> listOf(artist)
                    given.isNotEmpty() -> given
                    else -> listOf(artist) + performers
                }
            val artists = named.filter { it.isNotBlank() }.distinct()
            return ExaSearchRequest(
                query = "\"$album\" by ${listed(artists)}, album",
                contents =
                    ExaContents(ExaHighlights((listOf(album) + artists).joinToString(" "), HIGHLIGHT_CHARACTERS)),
            )
        }

        /**
         * The query for an [artist] the other sources say nothing about (#912, `ExaArtistSearch`):
         * `"<artist>", music artist`, the name quoted to keep a name of common words together, and
         * "music artist" for a person and a band alike, nothing of the track playing; highlights
         * steered toward the artist's biography. Compared live (2026-11-05) on a composer the sources
         * missed (Shashwat Sachdev) and a band named in common words (The National): the artist's own
         * pages, an encyclopedia's, a store's and a magazine's came first, each naming the artist.
         */
        fun ofArtist(artist: String): ExaSearchRequest =
            ExaSearchRequest(
                query = "\"$artist\", music artist",
                contents = ExaContents(ExaHighlights("$artist biography", HIGHLIGHT_CHARACTERS)),
            )

        /**
         * The query for a song the other sources only list (#958, `ExaSongSearch`): `"<title>"
         * <artist> review interview`, the first artist's name only, the words that find a page on the
         * song rather than a shop's or a streaming service's (as `songReviewQuery`, the words the
         * insight's question would use); highlights steered to its sound and making.
         */
        fun ofSongReview(
            title: String,
            artist: String,
        ): ExaSearchRequest =
            ExaSearchRequest(
                query = songReviewQuery(title, artist),
                contents =
                    ExaContents(
                        ExaHighlights("$title $artist sound recording production review", HIGHLIGHT_CHARACTERS),
                    ),
            )

        /**
         * [ofSongReview] in the track's language (#958): `"<title>" <artist>, <word for a song>`, the
         * [title] and [artist] in its script, highlights steered by those names, as
         * [ExaLanguageQuery.of]'s are.
         */
        fun ofSongInLanguage(
            title: String,
            artist: String,
            song: String,
        ): ExaSearchRequest =
            ExaSearchRequest(
                query = "\"$title\" $artist, $song",
                contents = ExaContents(ExaHighlights("$title $artist", HIGHLIGHT_CHARACTERS)),
            )

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
) {
    /**
     * Each result's title and highlights with their HTML character references decoded
     * ([HtmlReferences]); a result whose address is not a web address ([WebLinks], #1040) dropped.
     */
    fun decoded(): ExaSearchResponse =
        copy(
            results =
                results.mapNotNull { result ->
                    WebLinks.of(result.url)?.let { url ->
                        result.copy(
                            url = url,
                            title = result.title?.let(HtmlReferences::decode),
                            highlights = result.highlights.map(HtmlReferences::decode),
                        )
                    }
                },
        )
}

@Serializable
internal data class ExaResult(
    val url: String,
    val title: String? = null,
    val highlights: List<String> = emptyList(),
)
