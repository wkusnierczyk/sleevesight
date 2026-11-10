package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.language.Language
import app.sleevesight.core.language.Script
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.sources.LanguageAdapter
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetResult
import app.sleevesight.net.fetch
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * The web search in the track's language (#902; `design/architecture.md`, *Search in the track's
 * language*), through Exa on the user's key: one more search, beside the English one
 * ([ExaWebSearch]), with the same type, size and excluded hosts (every Wikipedia among them: the
 * language's Wikipedia is read by its own client), and its lines made the same way (`S`, numbered
 * after the English ones).
 *
 * The query is in the language ([ExaLanguageQuery.of]): the title and the artists in the language's
 * script where Wikidata or the track names them so, the work it comes from (a film's song is found
 * by the film's name too), and the language's word for a song. A result is kept when it is about the
 * recording under its names in the language or under the track's own ([RecordingFilter]), names
 * found anywhere in the text for a script without spaces between words. A line is marked with the
 * language (`EvidenceDraft.language`) when its text is written in one of the language's scripts, or
 * always for a language written in Latin letters, which cannot be told from English by its letters;
 * a line in another script (an English page the search found) is not.
 */
class ExaLanguageSearch(
    private val net: NetClient,
    private val key: Secret,
    accentFolder: AccentFolder,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : LanguageAdapter {
    override val id: SourceId = SourceId.WEB

    override val extractionVersion: Int = ExaWebSearch.EXTRACTION_VERSION

    private val filter = RecordingFilter(accentFolder)

    override suspend fun lookup(
        query: LanguageQuery,
        items: LanguageItems,
    ): SourceResult {
        val start = timeSource.markNow()
        val native = ExaLanguageQuery.nativeTrack(query, items)
        val checkable = filter.canCheck(native) || filter.canCheck(query.track)
        val fetched =
            if (checkable) {
                net.fetch(
                    ExaWebSearch.request(ExaLanguageQuery.of(query, items), key),
                    ExaWebSearch::parse,
                )
            } else {
                null
            }
        val response =
            when (fetched) {
                is NetResult.Success -> fetched.value
                is NetResult.Failure, null -> null
            }
        val found = response?.let { linesOf(query, native, it) }
        val lines = found?.lines.orEmpty().map { it.marked(query.language) }
        val outcome =
            when {
                !checkable -> Outcome.None
                response == null -> Outcome.Unreachable
                lines.isNotEmpty() -> Outcome.SongArticle
                response.results.isEmpty() || found?.emptied?.isNotEmpty() == true -> Outcome.None
                else -> Outcome.Rejected(ExaWebSearch.REJECTED)
            }
        // Made with fallback names when Wikidata's in the language could not be read: shown, not kept,
        // so a later play searches with them.
        return SourceResult(
            outcome,
            lines,
            start.elapsedNow(),
            provisional = items.unreachable,
            trackOnly = true,
            pages = found?.emptied.orEmpty(),
        )
    }

    // The lines of [response]'s results about the recording, under the [native] names or the track's own.
    private fun linesOf(
        query: LanguageQuery,
        native: NormalizedTrack,
        response: ExaSearchResponse,
    ): WebLines {
        val track = query.track
        val unspaced = query.language.scripts.any { it.unspaced }
        val subject = listOfNotNull(native.title, native.from, track.title, track.from) + native.artists + track.artists
        return ExaWebSearch.lines(response, clock.now(), subject.distinct(), filter::fold) { result, snippet ->
            filter.isAbout(native, result.title, snippet, result.url, wholeWords = !unspaced) ||
                filter.isAbout(track, result.title, snippet, result.url)
        }
    }
}

/**
 * [this] line marked with [language] when its text is written in it, as far as its letters tell: always
 * for a language written in Latin letters, which cannot be told from English by its letters.
 */
internal fun EvidenceDraft.marked(language: Language): EvidenceDraft {
    val scripts = language.scripts - Script.LATIN
    val inIt = scripts.isEmpty() || Script.of(text) in scripts
    return if (inIt) copy(language = language.code) else this
}

/** The web search's query in the track's language (#902, [ExaLanguageSearch]). */
internal object ExaLanguageQuery {
    /**
     * The request for [query]: `"<title>" <work> <artists>, <song>`, the title and artists as
     * [nativeTrack] names them, the work it comes from as Wikidata names it in the language (or the
     * one the player named), and the language's word for a song ([Language.song]), so the search
     * leans to pages in the language. Highlights are steered by the same names, as the English
     * search's are.
     */
    fun of(
        query: LanguageQuery,
        items: LanguageItems,
    ): ExaSearchRequest {
        val native = nativeTrack(query, items)
        val work = items.comes.firstNotNullOfOrNull { it.label?.takeIf(String::isNotBlank) } ?: query.track.from
        val artists = native.artists.filter { it.isNotBlank() }
        val names = (listOf("\"${native.searchTitle}\"") + listOfNotNull(work) + artists).joinToString(" ")
        return ExaSearchRequest(
            query = "$names, ${query.language.song}",
            contents =
                ExaContents(
                    ExaHighlights(
                        query = (listOfNotNull(native.searchTitle, work) + artists).joinToString(" "),
                        maxCharacters = ExaSearchRequest.HIGHLIGHT_CHARACTERS,
                    ),
                ),
        )
    }

    /**
     * [query]'s track as the language names it: its title in the language's script (the track's own
     * when written in a script other than Latin, else Wikidata's label in the language), each
     * credited artist likewise, and the work it is from, when the player named one, as Wikidata names
     * it in the language; each as the track has it where neither does.
     */
    fun nativeTrack(
        query: LanguageQuery,
        items: LanguageItems,
    ): NormalizedTrack {
        val track = query.track

        fun native(
            name: String,
            label: String?,
        ): String = if (Script.of(name) != null) name else label?.takeIf { it.isNotBlank() } ?: name
        val artists = track.artists.map { native(it, items.artists[it]?.label) }
        return track.copy(
            title = native(track.title, items.song?.label),
            artists = artists,
            primary = native(track.primary, items.artists[track.primary]?.label),
            from = track.from?.let { from -> native(from, items.comes.firstOrNull()?.label) },
        )
    }
}
