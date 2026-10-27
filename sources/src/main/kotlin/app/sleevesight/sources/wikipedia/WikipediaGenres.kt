package app.sleevesight.sources.wikipedia

import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.sources.lastfm.LastFmBio

/**
 * A MusicBrainz genre's English Wikipedia article, as a genre's panel quotes it (#523): the
 * Wikidata items holding the genre's id ([MUSICBRAINZ_GENRE], `P8052`), the first of them, by
 * their number (`Q181010`), with an English article (its `enwiki` sitelink), and that article's
 * first [SENTENCES] sentences, from its lead's plain text, tidied and split as the Wikipedia adapter splits it
 * ([WikipediaSource.split], else, for a paragraph it gives none of, [LastFmBio.split]). Three
 * requests at most, all to Wikimedia, which needs no key; the text is CC BY-SA 4.0, credited with a
 * link to the article ([articleUrl]).
 */
class WikipediaGenres(
    private val wikidata: WikidataLookup,
    private val wikipedia: WikipediaClient,
) : GenreArticles {
    override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> =
        wikidata.itemsWithStatement(MUSICBRAINZ_GENRE, musicBrainzGenre).then { found ->
            // The lowest-numbered item first, so several holding one id always give the same article.
            val items = found.sortedBy { it.removePrefix("Q").toLongOrNull() ?: Long.MAX_VALUE }
            if (items.isEmpty()) Lookup.NotFound else article(items)
        }

    // The first of [items] with an English article, and its opening sentences.
    private suspend fun article(items: List<String>): Lookup<GenreDefinition> =
        wikidata.entities(items).then { entities ->
            val title = items.firstNotNullOfOrNull { entities[it]?.enwiki }
            if (title == null) {
                Lookup.NotFound
            } else {
                wikipedia.lead(title).then { lead ->
                    lead
                        ?.let(::opening)
                        ?.let { Lookup.Found(GenreDefinition(it, title, articleUrl(title))) }
                        ?: Lookup.NotFound
                }
            }
        }

    // [next] of what was found; not found and failed as they are.
    private inline fun <T, R> Lookup<T>.then(next: (T) -> Lookup<R>): Lookup<R> =
        when (this) {
            is Lookup.Found -> next(value)
            Lookup.NotFound -> Lookup.NotFound
            is Lookup.Failed -> this
        }

    companion object {
        /** Wikidata's *MusicBrainz genre ID*. */
        const val MUSICBRAINZ_GENRE: String = "P8052"

        /**
         * How many sentences: the first says what the genre is for most genres, the second helps
         * where the first is thin (*Hard bop*, *K-pop*; checked on 30 common genres, #523).
         */
        const val SENTENCES: Int = 2

        /** The first [SENTENCES] sentences of [lead], joined by a space; null when it has none. */
        internal fun opening(lead: String): String? =
            lead
                .split('\n')
                .map { WikipediaSource.tidy(it) }
                .filter { it.isNotEmpty() }
                .flatMap { paragraph -> WikipediaSource.split(paragraph).ifEmpty { LastFmBio.split(paragraph) } }
                .take(SENTENCES)
                .joinToString(" ")
                .ifEmpty { null }
    }
}
