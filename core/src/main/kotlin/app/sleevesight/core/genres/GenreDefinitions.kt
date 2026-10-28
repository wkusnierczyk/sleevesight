package app.sleevesight.core.genres

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * What a genre is (#523): the first sentences of its English Wikipedia article ([text], CC BY-SA
 * 4.0, quoted as written), the article's [title], and its address ([url]), which the credit ("From
 * Wikipedia") links.
 */
data class GenreDefinition(
    val text: String,
    val title: String,
    val url: String,
)

/**
 * The English Wikipedia article of a MusicBrainz genre (#523): `WikipediaGenres` in `:sources`
 * (the Wikidata item holding the genre's id, `P8052`; the item's `enwiki` sitelink; the article's
 * lead), a fake in tests. Not found when no item holds the id, the item has no English article, or
 * its lead gives no sentence; failed when a request failed.
 */
fun interface GenreArticles {
    suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition>
}

/**
 * The definitions a genre's panel shows (#523; `design/design.md`, *Genre labels*): a genre's own,
 * the same for every track, so each is found once and kept per genre, by its MusicBrainz genre
 * ([GenreVocabulary.musicBrainzGenre]). A genre that is not one of MusicBrainz's has none, nor one
 * whose genre has no English article, and its panel says so; *Why is this …?* is offered either way.
 *
 * Kept in [kept], the artist-level cache's table (#238), under [PART], for Wikipedia's lifetime
 * (*How long each source's findings are kept*, D9), whatever *Keep what sources said about each
 * artist* says: a definition says nothing about what was played. A genre whose article is not found
 * is kept as having none, so it is not asked again meanwhile; a failed request is not kept. With no
 * store that persists (the CLI without a cache, tests), they are kept in memory instead, for
 * [lifetime], at most [capacity] genres.
 */
class GenreDefinitions(
    private val articles: GenreArticles,
    private val vocabulary: GenreVocabulary,
    clock: Clock = Clock.System,
    lifetime: Duration = DEFAULT_LIFETIME,
    capacity: Int = DEFAULT_CAPACITY,
    kept: ArtistResults = ArtistResults.None,
) {
    private val kept = KeptTags(PART, kept, clock, lifetime, capacity)

    /**
     * The definition of the genre [identity] stands for ([GenreLabel.identity]), kept or looked up:
     * not found when it has none (it is not one of MusicBrainz's genres, or its genre has no English
     * article), which its panel says (#663); failed when the lookup failed, and nothing is kept.
     */
    suspend fun lookup(identity: String): Lookup<GenreDefinition> {
        val genre = vocabulary.musicBrainzGenre(identity) ?: return Lookup.NotFound
        val found =
            kept.tagsOf(genre) {
                when (val answer = articles.definition(genre)) {
                    is Lookup.Found -> answer.value.let { Lookup.Found(listOf(it.text, it.title, it.url)) }
                    Lookup.NotFound -> Lookup.NotFound
                    is Lookup.Failed -> answer
                }
            }
        return when {
            found == null -> Lookup.Failed(FAILED)
            found.size == FIELDS -> found.let { (text, title, url) -> Lookup.Found(GenreDefinition(text, title, url)) }
            else -> Lookup.NotFound
        }
    }

    companion object {
        /**
         * A genre's definition in the artist-level cache, by its MusicBrainz genre: a JSON array of
         * its text, the article's title and its address, or an empty one for a genre with no article.
         */
        val PART: ArtistPart = ArtistPart("wikipedia.genre", 1, 1, setOf(SourceId.WIKIPEDIA))

        /** In memory, without a store: Wikipedia's default lifetime. */
        val DEFAULT_LIFETIME: Duration = Settings.DEFAULT_CACHE_LIFETIMES.getValue(SourceId.WIKIPEDIA)

        /** A few hundred bytes a genre; the vocabulary has fewer than a hundred. */
        const val DEFAULT_CAPACITY: Int = 200

        private const val FIELDS = 3

        /** Why a lookup failed: the request's own reason is not kept past it. */
        const val FAILED: String = "lookup failed"
    }
}
