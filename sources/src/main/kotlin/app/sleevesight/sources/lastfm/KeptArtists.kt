package app.sleevesight.sources.lastfm

import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.sources.ArtistResults
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Last.fm's answers for one name, and when they were retrieved. */
internal class Fetched(
    val info: LastFmAnswer<LastFmArtist>,
    val topTags: LastFmAnswer<List<LastFmTag>>,
    val retrievedAt: Instant,
)

/**
 * Last.fm's answers per credited artist's name in [artists], the artist cache (#238,
 * [LastFmAdapter.ARTIST_PART]), by the name folded as the retrieval key folds it
 * ([Normalizer.foldForKey]). Kept: an artist with its top tags, or Last.fm not knowing the name.
 * Not kept: a failed request, another error (a rejected key, the rate limit) or an artist whose top
 * tags did not arrive, which a later lookup may get whole. The bio is kept as its quotable
 * sentences ([LastFmArtist.bio]), never the summary.
 */
internal class KeptArtists(
    private val artists: ArtistResults,
    accentFolder: AccentFolder,
) {
    private val normalizer = Normalizer(accentFolder)

    /** The key of [name]'s entry. */
    fun keyOf(name: String): String = normalizer.foldForKey(name)

    /** What is kept for [key], as answers; null for none, or a value that cannot be read. */
    suspend fun get(key: String): Fetched? {
        val value = artists.get(LastFmAdapter.ARTIST_PART, key)
        return value?.let(::answers)
    }

    /** Keeps [fetched] for [key], if it may be kept. */
    suspend fun keep(
        key: String,
        fetched: Fetched,
    ) {
        val value = storable(fetched)?.let { json.encodeToString(StoredArtist.serializer(), it) }
        if (value != null) artists.keep(LastFmAdapter.ARTIST_PART, key, value)
    }

    private fun answers(value: String): Fetched? {
        val kept =
            try {
                json.decodeFromString(StoredArtist.serializer(), value)
            } catch (_: IllegalArgumentException) {
                null
            }
        val at = kept?.let { Instant.parseOrNull(it.retrievedAt) } ?: return null
        val artist = kept.artist
        return if (artist == null) {
            Fetched(LastFmAnswer.Error(LastFmJson.NOT_FOUND), LastFmAnswer.Error(LastFmJson.NOT_FOUND), at)
        } else {
            Fetched(LastFmAnswer.Found(artist), LastFmAnswer.Found(kept.topTags), at)
        }
    }

    private fun storable(fetched: Fetched): StoredArtist? {
        val info = fetched.info
        val topTags = fetched.topTags
        val at = fetched.retrievedAt.toString()
        return when {
            info is LastFmAnswer.Found && topTags is LastFmAnswer.Found -> StoredArtist(at, info.value, topTags.value)
            info is LastFmAnswer.Error && info.code == LastFmJson.NOT_FOUND -> StoredArtist(at, null)
            else -> null
        }
    }

    /**
     * One name's answers: [artist] with its [topTags], or null for a name Last.fm does not know;
     * [retrievedAt] in ISO 8601, which its lines keep.
     */
    @Serializable
    private class StoredArtist(
        val retrievedAt: String,
        val artist: LastFmArtist?,
        val topTags: List<LastFmTag> = emptyList(),
    )

    private companion object {
        val json = Json { encodeDefaults = false }
    }
}
