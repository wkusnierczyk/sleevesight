package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SongWebQuery
import app.sleevesight.core.sources.WebPage
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetResult
import app.sleevesight.net.fetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/** A web search for the pages that describe a song (#958): `ExaSongSearch`; a fake in tests. */
interface SongWebSearch {
    /**
     * The web lines about the [song], at most [ExaWebSearch.MAX_LINES], none when nothing found is
     * about it; failed when the engine could not be reached or refused the request, or [mayPay], asked
     * after any wait, right before the request, said no. [sent] is told right before the one request
     * goes out, the paid part, and not when none is sent.
     */
    suspend fun search(
        song: SongWebQuery,
        sent: () -> Unit = {},
        mayPay: () -> Boolean = { true },
    ): Lookup<List<EvidenceDraft>>

    /**
     * How a page the AI service's own search found for the [song] gives its line, retrieved at the
     * time given, by [search]'s rules; null for no line.
     */
    suspend fun pageLines(song: SongWebQuery): (WebPage, Instant) -> EvidenceDraft?
}

/**
 * Exa's search for what a song sounds like and how it was made (#958), for the song's insight when the
 * other sources only list it (`SongWebSource`), on the user's own key: the track's search's request but
 * for the query, its host and policy ([ExaWebSearch.HOST_POLICY]), its lines ([ExaWebSearch.lines]), and
 * its filter ([RecordingFilter.isAbout]). One call, whichever way it is asked:
 *
 * - in English ([ExaSearchRequest.ofSongReview]): the title and the first artist, with `review
 *   interview`, the highlights steered to the song's sound and making;
 * - for a track in another language ([SongWebQuery.language]), in that language instead
 *   ([ExaSearchRequest.ofSongInLanguage], [ExaLanguageQuery.nativeTrack]): the title and the first artist
 *   in its script, and the language's word for a song; its lines marked with the language
 *   ([ExaLanguageSearch.marked]), judged as the search in the language judges them. Wikidata's names in
 *   the language are asked with [items], free, by the play's resolution; with none, or none found, the
 *   track's own names.
 *
 * Each line is a web line about the track (scope web). A track that cannot be checked
 * ([RecordingFilter.canCheck]) is not searched: nothing is sent, and nothing found.
 */
class ExaSongSearch(
    private val net: NetClient,
    private val key: Secret,
    accentFolder: AccentFolder,
    private val items: suspend (LanguageQuery) -> LanguageItems = { LanguageItems.NONE },
    private val clock: Clock = Clock.System,
) : SongWebSearch {
    private val filter = RecordingFilter(accentFolder)

    override suspend fun search(
        song: SongWebQuery,
        sent: () -> Unit,
        mayPay: () -> Boolean,
    ): Lookup<List<EvidenceDraft>> {
        val native = nativeOf(song)
        return when {
            !filter.canCheck(native) && !filter.canCheck(song.track) -> Lookup.Found(emptyList())
            !mayPay() -> Lookup.Failed(NOT_PAID)
            else -> searched(song, native, sent)
        }
    }

    // The one request, told to [sent], and what it found.
    private suspend fun searched(
        song: SongWebQuery,
        native: NormalizedTrack,
        sent: () -> Unit,
    ): Lookup<List<EvidenceDraft>> {
        val track = song.track
        val language = song.language
        val body =
            if (language == null) {
                ExaSearchRequest.ofSongReview(track.title, track.primary)
            } else {
                ExaSearchRequest.ofSongInLanguage(native.title, native.primary, language.song)
            }
        val request = ExaWebSearch.request(body, key)
        sent()
        return when (val fetched = net.fetch(request, ExaWebSearch::parse)) {
            is NetResult.Failure -> Lookup.Failed(UNREACHABLE)
            is NetResult.Success ->
                fetched.value?.let { Lookup.Found(linesOf(song, native, it)) } ?: Lookup.Failed(REFUSED)
        }
    }

    override suspend fun pageLines(song: SongWebQuery): (WebPage, Instant) -> EvidenceDraft? {
        val native = nativeOf(song)
        val subject = subjectOf(song, native)
        return { page, at ->
            val snippet = ExaWebSearch.cut(page.text)
            if (about(song, native, page.title, snippet, page.url)) {
                ExaWebSearch
                    .cut(WebBoilerplate.clean(page.text, page.url, subject, filter::fold))
                    .takeIf { it.isNotEmpty() }
                    ?.let { EvidenceDraft(SourceId.WEB, Scope.WEB, it, page.url, at) }
                    ?.let { draft -> song.language?.let { draft.marked(it) } ?: draft }
            } else {
                null
            }
        }
    }

    // [song]'s track as its language names it, Wikidata asked when it can be; the track's own otherwise.
    private suspend fun nativeOf(song: SongWebQuery): NormalizedTrack {
        val language = song.language ?: return song.track
        val resolution = song.resolution
        val query = LanguageQuery(song.track, resolution ?: Resolution.NONE, language)
        val named =
            try {
                if (resolution == null) LanguageItems.NONE else items(query)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                LanguageItems.NONE
            }
        return ExaLanguageQuery.nativeTrack(query, named)
    }

    // The names a part of a page must say more than, the track's in either form.
    private fun subjectOf(
        song: SongWebQuery,
        native: NormalizedTrack,
    ): List<String> {
        val track = song.track
        return (listOfNotNull(native.title, native.from, track.title, track.from) + native.artists + track.artists)
            .distinct()
    }

    private fun linesOf(
        song: SongWebQuery,
        native: NormalizedTrack,
        response: ExaSearchResponse,
    ): List<EvidenceDraft> {
        val lines =
            ExaWebSearch
                .lines(response, clock.now(), subjectOf(song, native), filter::fold) { result, snippet ->
                    about(song, native, result.title, snippet, result.url)
                }.lines
        return song.language?.let { language -> lines.map { it.marked(language) } } ?: lines
    }

    // Whether a result is about [song], under the [native] names or the track's own, as the search in
    // the language judges one: a name anywhere in the text for a script without spaces between words.
    private fun about(
        song: SongWebQuery,
        native: NormalizedTrack,
        title: String?,
        snippet: String,
        url: String,
    ): Boolean {
        val unspaced = song.language?.scripts?.any { it.unspaced } == true
        return filter.isAbout(native, title, snippet, url, wholeWords = !unspaced) ||
            filter.isAbout(song.track, title, snippet, url)
    }

    private companion object {
        const val UNREACHABLE = "Exa could not be reached"
        const val REFUSED = "Exa refused the search"
        const val NOT_PAID = "not paid"
    }
}
