package app.sleevesight.core.genres

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Last.fm's top tags for a track and for an album (`track.getTopTags`, `album.getTopTags`), each
 * by the artist's name and the track's or album's, those weighted too low left out, most applied
 * first: `LastFmTags` in `:sources`, on the user's key; a fake in tests. Null page: none to link.
 */
interface ReleaseTags {
    suspend fun trackTags(
        artist: String,
        title: String,
    ): Lookup<List<String>>

    suspend fun albumTags(
        artist: String,
        album: String,
    ): Lookup<List<String>>

    /** The track's page on Last.fm, which a label from its tags links. */
    fun trackPage(
        artist: String,
        title: String,
    ): String?

    /** The album's page on Last.fm, which a label from its tags links. */
    fun albumPage(
        artist: String,
        album: String,
    ): String?
}

/**
 * Last.fm's genre labels for the track and its album (#439), through the [vocabulary]: an artist's
 * tags describe a career, the track's and album's what is playing. The pipeline asks for them after
 * the evidence is assembled, as it does MusicBrainz's artist tags, so the insight never waits for
 * them; they are labels only, never evidence.
 *
 * One request each per track. An album's tags are kept by the artist's name and the album's,
 * each folded as a lookup key is ([fold], `Normalizer.foldForKey`) ([KeptTags], under
 * [ALBUM_PART]), so the next track of the album asks nothing about it. With *Keep what sources
 * said about each artist* off (#420, the default), nothing is kept, and each track asks for its
 * album's tags again. The track's are not kept, since the
 * retrieval cache does not see them and a track seldom plays twice soon. A failed request is not
 * kept, and gives no labels meanwhile.
 */
@Suppress("LongParameterList") // The tags, the vocabulary and the key's folding; the rest defaulted, for tests.
class LastFmLabels(
    private val tags: ReleaseTags,
    private val vocabulary: GenreVocabulary,
    private val fold: (String) -> String = { it },
    clock: Clock = Clock.System,
    lifetime: Duration = DEFAULT_LIFETIME,
    capacity: Int = DEFAULT_CAPACITY,
    artists: ArtistResults = ArtistResults.None,
) {
    private val albums = KeptTags(ALBUM_PART, artists, clock, lifetime, capacity)

    /** The labels from the tags of [title] by [artist]; none when the request failed. */
    suspend fun trackLabels(
        artist: String,
        title: String,
    ): List<GenreLabel> = trackLookup(artist, title).labels

    /** As [trackLabels], and whether the request failed ([LabelLookup.failed], #772). */
    suspend fun trackLookup(
        artist: String,
        title: String,
    ): LabelLookup {
        val answer = tags.trackTags(artist, title)
        val found = (answer as? Lookup.Found)?.value.orEmpty()
        return LabelLookup(
            vocabulary.labelsFor(found, tags.trackPage(artist, title), GenreLevel.TRACK),
            failed = answer is Lookup.Failed,
        )
    }

    /** The labels from the tags of [album] by [artist], kept or asked for; none when the request failed. */
    suspend fun albumLabels(
        artist: String,
        album: String,
    ): List<GenreLabel> = albumLookup(artist, album).labels

    /** As [albumLabels], and whether the request failed ([LabelLookup.failed], #772). */
    suspend fun albumLookup(
        artist: String,
        album: String,
    ): LabelLookup {
        val found = albums.tagsOf(albumKey(fold(artist), fold(album))) { tags.albumTags(artist, album) }
        return LabelLookup(
            vocabulary.labelsFor(found.orEmpty(), tags.albumPage(artist, album), GenreLevel.ALBUM),
            failed = found == null,
        )
    }

    companion object {
        /** As long as Last.fm's retrieval lifetime (D9). */
        val DEFAULT_LIFETIME: Duration = 7.days

        /** A few hundred bytes an album. */
        const val DEFAULT_CAPACITY: Int = 500

        /**
         * An album's Last.fm tags in the artist cache, by [albumKey]: a JSON array of the tags, most
         * applied first, as [ReleaseTags] gave them, going through the vocabulary when read.
         */
        val ALBUM_PART: ArtistPart = ArtistPart("lastfm.album.tags", 1, 1, setOf(SourceId.LASTFM))

        /** The artist's name and the album's, each folded, as a track's lookup key joins its parts. */
        internal fun albumKey(
            artist: String,
            album: String,
        ): String = "$artist|$album"
    }
}
