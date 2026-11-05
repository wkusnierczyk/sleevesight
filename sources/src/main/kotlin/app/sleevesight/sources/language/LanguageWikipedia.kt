package app.sleevesight.sources.language

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.language.Language
import app.sleevesight.core.language.Script
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.IdentityCheck.Route
import app.sleevesight.core.resolution.IdentityCheck.Verdict
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.LanguageAdapter
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.net.NetClient
import app.sleevesight.sources.wikipedia.WikipediaClient
import app.sleevesight.sources.wikipedia.articleUrl
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * The Wikipedia in the track's language (#902; `design/architecture.md`, *Search in the track's
 * language*), `<code>.wikipedia.org`, as a lookup beside the English one: the leads of the articles
 * Wikidata's sitelinks in the language give ([LanguageItems]) for the song the identity check
 * confirmed, the works it comes from (a film, an album) and each artist it confirmed; for the song,
 * when its item has no article there, the article that language's Wikipedia has by the song's title
 * in its own script, if the identity check confirms that article's item.
 *
 * Lines (D1), as the English adapter quotes them: at most three sentences per article, verbatim, cut
 * at its script's sentence ends ([LanguageSentences]), each linking its article on the language's
 * host and marked with the language (`EvidenceDraft.language`); Wikipedia's lines (`W`), numbered
 * after the English ones. In order: the song's (its scope as the check gave it), the works' (release,
 * about the work by its English name, the sentences chosen by the work's name alone, so they are the
 * same whichever of its songs plays), then each artist's (artist, about the artist the track
 * credits). No Wikidata line: the English lookup templates those already.
 *
 * Outcomes: the song's scope when its article gave lines, [Outcome.SongArticle] for a work's alone,
 * [Outcome.ArtistArticle] for an artist's alone; [Outcome.Unreachable] when a lead could not be read
 * and none gave a line; else [Outcome.None]. A result missing a part because Wikidata or Wikipedia
 * did not answer is provisional, so it is not kept.
 */
class LanguageWikipedia(
    private val net: NetClient,
    private val check: IdentityCheck,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : LanguageAdapter {
    override val id: SourceId = SourceId.WIKIPEDIA

    override val extractionVersion: Int = EXTRACTION_VERSION

    override val consults: Set<SourceId> = SourceSwitch.WIKIPEDIA.sources

    override suspend fun lookup(
        query: LanguageQuery,
        items: LanguageItems,
    ): SourceResult {
        val start = timeSource.markNow()
        val language = query.language
        val wikipedia = WikipediaClient(net, language.wiki)
        val title = nativeTitle(query, items)
        val found =
            items.song?.article?.let { ByName(it to items.songScope, failed = false) }
                ?: byName(wikipedia, query, title)
        val retrievedAt = clock.now()
        val lines = mutableListOf<EvidenceDraft>()
        var leadFailed = false
        for (page in pages(items, found.page, title).distinctBy { it.article }) {
            when (val lead = wikipedia.lead(page.article)) {
                is Lookup.Found -> lines += page.lines(lead.value, language, retrievedAt)
                is Lookup.NotFound -> Unit
                is Lookup.Failed -> leadFailed = true
            }
        }
        return SourceResult(
            outcome(lines, leadFailed),
            lines,
            start.elapsedNow(),
            provisional = items.unreachable || found.failed || leadFailed,
            // An artist checked, found or not, is something said about the artist (#420).
            trackOnly = query.resolution.artists.none { it.mbid != null },
        )
    }

    // The articles to quote: the [song]'s, then the works', then each artist's, as their sitelinks name them.
    private fun pages(
        items: LanguageItems,
        song: Pair<String, Scope>?,
        title: String?,
    ): List<Page> {
        val songPage = song?.let { (article, scope) -> Page(article, scope, listOfNotNull(items.song?.label, title)) }
        val works =
            items.comes.mapNotNull { work ->
                // By the work's name alone, never the song's: the album's insight takes these lines, the
                // same whichever of its tracks is playing.
                work.article?.let { Page(it, Scope.RELEASE, listOfNotNull(work.label), about = work.name) }
            }
        val artists =
            items.artists.mapNotNull { (credited, artist) ->
                artist.article?.let { Page(it, Scope.ARTIST, listOfNotNull(artist.label, credited), about = credited) }
            }
        return listOfNotNull(songPage) + works + artists
    }

    /** The song's article found by name, with its scope, or none; [failed] when a request for it failed. */
    private class ByName(
        val page: Pair<String, Scope>?,
        val failed: Boolean,
    )

    // The song's article by its title in the language's script, when the identity check confirms its item.
    private suspend fun byName(
        wikipedia: WikipediaClient,
        query: LanguageQuery,
        title: String?,
    ): ByName {
        val found = title?.let { wikipedia.pages(listOf(it)) }
        val page = (found as? Lookup.Found)?.value?.get(title)?.takeUnless { it.disambiguation }
        val verdict = page?.item?.let { check.song(it, query.track, query.resolution, Route.NAME) }
        return ByName(
            page = (verdict as? Verdict.Accepted)?.let { page.title to it.scope },
            failed = found is Lookup.Failed || verdict is Verdict.Unreachable,
        )
    }

    /** One article to quote: its title, the scope of its lines, what they must name, and the artist they are about. */
    private class Page(
        val article: String,
        val scope: Scope,
        val subjects: List<String>,
        val about: String? = null,
    ) {
        fun lines(
            lead: String?,
            language: Language,
            retrievedAt: Instant,
        ): List<EvidenceDraft> =
            LanguageSentences.of(lead.orEmpty(), subjects, stops = language.scripts.all { it.stops }).map {
                EvidenceDraft(
                    SourceId.WIKIPEDIA,
                    scope,
                    it,
                    articleUrl(article, language.wiki),
                    retrievedAt,
                    about = about,
                    language = language.code,
                )
            }
    }

    companion object {
        // The song's scope when its article gave lines; a work's alone is the track's release, so a
        // song article's outcome; an artist's alone, the artist's.
        private fun outcome(
            lines: List<EvidenceDraft>,
            leadFailed: Boolean,
        ): Outcome =
            when {
                lines.any { it.scope == Scope.SONG } -> Outcome.SongArticle
                lines.any { it.scope == Scope.ORIGINAL_SONG } -> Outcome.OriginalSong
                lines.any { it.scope == Scope.RELEASE } -> Outcome.SongArticle
                lines.isNotEmpty() -> Outcome.ArtistArticle
                leadFailed -> Outcome.Unreachable
                else -> Outcome.None
            }

        /** [extractionVersion]: raised when what the adapter makes of the articles changes. */
        const val EXTRACTION_VERSION: Int = 1

        /**
         * The song's title in the language's script: the track's own, when written in a script other
         * than Latin; else Wikidata's label for the song in the language; null when neither.
         */
        fun nativeTitle(
            query: LanguageQuery,
            items: LanguageItems,
        ): String? = query.track.title.takeIf { Script.of(it) != null } ?: items.song?.label?.takeIf { it.isNotBlank() }
    }
}
