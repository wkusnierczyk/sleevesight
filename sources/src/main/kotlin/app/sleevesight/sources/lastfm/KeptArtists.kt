package app.sleevesight.sources.lastfm

import app.sleevesight.core.sources.ArtistResults
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Last.fm's answers for one name, and when they were retrieved; for answers the artist cache kept,
 * when they stop being fresh.
 */
internal class Fetched(
    val info: LastFmAnswer<LastFmArtist>,
    val topTags: LastFmAnswer<List<LastFmTag>>,
    val retrievedAt: Instant,
    val freshUntil: Instant? = null,
)

/**
 * Last.fm's answers per credited artist's name in [artists], the artist cache (#238,
 * [LastFmAdapter.ARTIST_PART]), by the name exactly as sent: Last.fm answers each spelling on its
 * own (an accent or none, `Björk` and `Bjork`, or other punctuation, `AC/DC` and `AC DC`, may be
 * different pages, or one may be unknown), so names that fold alike do not share an entry. Kept:
 * an artist with its top tags, or Last.fm not knowing the name.
 * Not kept: a failed request, another error (a rejected key, the rate limit) or an artist whose top
 * tags did not arrive, which a later lookup may get whole. The bio is kept as its quotable
 * sentences ([LastFmArtist.bio]), never the summary.
 */
internal class KeptArtists(
    private val artists: ArtistResults,
) {
    /** What is kept for [name], as answers; null for none, or a value that cannot be read. */
    suspend fun get(name: String): Fetched? {
        val part = artists.get(LastFmAdapter.ARTIST_PART, name)
        return part?.let { answers(it.value, it.freshUntil) }
    }

    /** Keeps [fetched] for [name], if it may be kept. */
    suspend fun keep(
        name: String,
        fetched: Fetched,
    ) {
        val value = storable(fetched)?.let { json.encodeToString(StoredArtist.serializer(), it) }
        if (value != null) artists.keep(LastFmAdapter.ARTIST_PART, name, value)
    }

    private fun answers(
        value: String,
        freshUntil: Instant,
    ): Fetched? {
        val kept =
            try {
                json.decodeFromString(StoredArtist.serializer(), value)
            } catch (_: IllegalArgumentException) {
                null
            }
        val at = kept?.let { Instant.parseOrNull(it.retrievedAt) } ?: return null
        val artist = kept.artist
        return if (artist == null) {
            Fetched(LastFmAnswer.Error(LastFmJson.NOT_FOUND), LastFmAnswer.Error(LastFmJson.NOT_FOUND), at, freshUntil)
        } else {
            Fetched(LastFmAnswer.Found(artist), LastFmAnswer.Found(kept.topTags), at, freshUntil)
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
