package app.sleevesight.core.genres

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * A genre Wikidata states (P136) for the track's item, an artist's or the album's (#1098): [name],
 * the genre item's English label (`blue-eyed soul`, `pop music`), at [level], for an artist's the
 * [artist] as the track credits it, and [url], the page of the item that states it, which the label's
 * credit links. Wikidata is CC0.
 */
@Serializable
data class StatedGenre(
    val name: String,
    val level: GenreLevel,
    val artist: String? = null,
    val url: String,
)

/**
 * Wikidata's genre labels (#1098): the genres (P136) Wikidata states for the track's song, its
 * artists and its album, those the [vocabulary] holds, each weighing [WEIGHT]. The song's and the
 * artists' come with Wikipedia's result (`SourceResult.statedGenres`), from the items its identity
 * check accepted, with no request of their own ([labels]); the album's take requests ([albumLookup]),
 * which the pipeline makes after the evidence, as it does Last.fm's album tags.
 *
 * A genre's name is matched to the vocabulary by its letters and digits, as a tag's identity is
 * (`alternative hip-hop` is `alternative hip hop`), and else without a trailing *music*, which
 * Wikidata's labels often carry (`rock music`, `house music`). A genre the vocabulary does not hold
 * gives no label.
 *
 * Statements carry no votes and no order that says which matters more, so each label weighs the same
 * [WEIGHT] ([GenreLabel.weight]), half a source's top tag: Wikidata alone does not outweigh what many
 * listeners' or contributors' tags agree on, and a genre it shares with them weighs their sum
 * ([combineLabels]). Unlike MusicBrainz's tags, Wikidata's genres may reach a prompt: a label
 * Wikidata gives counts as another source's than MusicBrainz's ([TrackLabel.opensCard]).
 *
 * An album's genres are kept by its release group ([KeptTags], under [ALBUM_PART]), so the next
 * track of the album asks nothing about it.
 */
@Suppress("LongParameterList") // The vocabulary, Wikidata, and where the albums' genres are kept; the rest defaulted.
class WikidataLabels(
    private val vocabulary: GenreVocabulary,
    private val wikidata: WikidataLookup? = null,
    clock: Clock = Clock.System,
    lifetime: Duration = DEFAULT_LIFETIME,
    capacity: Int = DEFAULT_CAPACITY,
    artists: ArtistResults = ArtistResults.None,
) {
    private val albums = KeptTags(ALBUM_PART, StatedGenre.serializer(), artists, clock, lifetime, capacity)

    // The vocabulary's tags by their letters and digits, the first of each.
    private val byLetters: Map<String, String> =
        vocabulary.tags
            .groupBy(::letters)
            .mapValues { (_, tags) -> tags.first() }

    /** The labels of [stated], in their order, each at its level, of the genres the vocabulary holds. */
    fun labels(stated: List<StatedGenre>): List<GenreLabel> =
        stated.mapNotNull { genre ->
            tagOf(genre.name)?.let { tag ->
                vocabulary
                    .labelsFor(listOf(tag), genre.url, genre.level, genre.artist, capped = false)
                    .firstOrNull()
                    ?.copy(weight = WEIGHT)
            }
        }

    /** Whether it can ask for an album's genres: with Wikidata to ask. */
    val asksAlbums: Boolean get() = wikidata != null

    /**
     * The labels of the genres Wikidata states for the album whose MusicBrainz release group is
     * [group] (the item whose P436 it is), kept or asked for, and whether a request failed.
     */
    suspend fun albumLookup(group: String): LabelLookup {
        val found = albums.tagsOf(group) { askAlbum(group) }
        return LabelLookup(labels(found.orEmpty()), failed = found == null)
    }

    // The album's item's genres, by their English labels; none for no item, or one with no genre.
    private suspend fun askAlbum(group: String): Lookup<List<StatedGenre>> {
        val lookup = wikidata ?: return Lookup.Found(emptyList())
        return lookup.itemsWithStatement(Wd.MUSICBRAINZ_RELEASE_GROUP, group).then { ids ->
            val id = ids.firstOrNull()
            if (id == null) {
                Lookup.Found(emptyList())
            } else {
                lookup.entities(listOf(id)).then { entities -> genresOf(lookup, id, entities[id]) }
            }
        }
    }

    // The genres [entity], item [id], states, by their English labels.
    private suspend fun genresOf(
        lookup: WikidataLookup,
        id: String,
        entity: WdEntity?,
    ): Lookup<List<StatedGenre>> {
        val genres = entity?.values(Wd.GENRE)?.distinct().orEmpty()
        return if (genres.isEmpty()) {
            Lookup.Found(emptyList())
        } else {
            lookup.labels(genres).then { named ->
                Lookup.Found(
                    genres.mapNotNull { named[it] }.map { StatedGenre(it, GenreLevel.ALBUM, null, Wd.url(id)) },
                )
            }
        }
    }

    // The vocabulary's tag for a genre named [name]: by its letters and digits, else without a
    // trailing *music*.
    private fun tagOf(name: String): String? {
        val words = name.trim().lowercase()
        return byLetters[letters(words)] ?: byLetters[letters(words.removeSuffix(MUSIC))]
    }

    companion object {
        /** A label's weight: half a source's top tag, for each genre alike (see the class). */
        const val WEIGHT: Double = 0.5

        /** As long as Wikidata's retrieval lifetime (D9). */
        val DEFAULT_LIFETIME: Duration = 30.days

        /** A few hundred bytes an album. */
        const val DEFAULT_CAPACITY: Int = 500

        /**
         * An album's Wikidata genres in the artist cache, by its MusicBrainz release group: a JSON
         * array of [StatedGenre], by their English labels, going through the vocabulary when read.
         */
        val ALBUM_PART: ArtistPart = ArtistPart("wikidata.album.genres", 1, 1, setOf(SourceId.WIKIDATA))

        // What Wikidata's labels of a genre often end with (`rock music`), which the vocabulary's tags do not.
        private const val MUSIC = " music"

        private fun letters(tag: String): String = tag.lowercase().filter(Char::isLetterOrDigit)

        // [next] of what was found, none for nothing found, a failure as it is.
        private suspend fun <T, R> Lookup<T>.then(next: suspend (T) -> Lookup<List<R>>): Lookup<List<R>> =
            when (this) {
                is Lookup.Found -> next(value)
                Lookup.NotFound -> Lookup.Found(emptyList())
                is Lookup.Failed -> this
            }
    }
}
