package app.sleevesight.sources.lastfm

import app.sleevesight.net.NetError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * An artist as Last.fm's `artist.getInfo` gives it (format `json`).
 *
 * @property name the artist Last.fm answered with: with `autocorrect=1`, its correction of the name
 *   asked for, which may be another artist altogether (the fixture's track 61).
 * @property mbid Last.fm's MusicBrainz artist id, when it has one.
 * @property tags the top tags, at most five, in Last.fm's order.
 * @property bio the sentences of the bio's summary that may be quoted ([LastFmBio.sentences]): the
 *   summary itself is not kept, so a cached answer holds no more of the bio than is shown.
 * @property sharedPage the bio says several artists share this page ([LastFmBio.sharedPage]): its
 *   listeners, tags and similar artists mix them, so the match is never verified. The bio is one of
 *   two signals and need not say so: [ArtistCheck] also asks MusicBrainz whether another artist has
 *   the name.
 * @property bioUrl the bio's own page (`…/+wiki`), when given.
 *
 * Serializable, as the artist cache keeps it (`LastFmAdapter.ARTIST_PART`): a change to its fields
 * raises that part's version.
 */
@Serializable
data class LastFmArtist(
    val name: String,
    val mbid: String?,
    val url: String?,
    val listeners: Long?,
    val tags: List<String>,
    val similar: List<LastFmSimilar>,
    val bio: List<String>,
    val sharedPage: Boolean,
    val bioUrl: String?,
)

/** A similar artist: by listening overlap, not by sound. */
@Serializable
data class LastFmSimilar(
    val name: String,
    val url: String?,
)

/** A tag with its weight, `count`, which Last.fm scales so the artist's top tag has 100. */
@Serializable
data class LastFmTag(
    val name: String,
    val count: Int,
)

/** What a Last.fm call answered. */
internal sealed interface LastFmAnswer<out T> {
    data class Found<T>(
        val value: T,
    ) : LastFmAnswer<T>

    /** An API error: [code] is Last.fm's (6: not found; 10, 26: the key; 29: the rate limit). */
    data class Error(
        val code: Int,
    ) : LastFmAnswer<Nothing>

    /** No answer: the request failed. */
    data class Unreachable(
        val error: NetError,
    ) : LastFmAnswer<Nothing>
}

/**
 * Reads Last.fm's JSON, which is converted from its XML: a list of one comes as a bare object, an
 * empty list may come as an empty string, and numbers come as strings. Each reader takes all forms.
 */
internal object LastFmJson {
    /** Last.fm's code for an artist (or other entity) it does not know. */
    const val NOT_FOUND: Int = 6

    private val json = Json

    fun artistInfo(body: String): LastFmAnswer<LastFmArtist> {
        val root = json.parseToJsonElement(body).jsonObject
        root.error()?.let { return LastFmAnswer.Error(it) }
        val artist = root.obj("artist") ?: error("no artist")
        val summary = artist.obj("bio")?.string("summary")
        return LastFmAnswer.Found(
            LastFmArtist(
                name = artist.string("name") ?: error("no artist name"),
                mbid = artist.string("mbid")?.takeIf { it.isNotBlank() },
                url = artist.string("url")?.takeIf { it.isNotBlank() },
                listeners = artist.obj("stats")?.string("listeners")?.toLongOrNull(),
                tags = artist.obj("tags").items("tag").mapNotNull { it.string("name") },
                similar =
                    artist.obj("similar").items("artist").mapNotNull { similar ->
                        similar.string("name")?.let { LastFmSimilar(it, similar.string("url")) }
                    },
                bio = LastFmBio.sentences(summary),
                sharedPage = LastFmBio.sharedPage(summary),
                bioUrl =
                    artist
                        .obj("bio")
                        ?.obj("links")
                        .items("link")
                        .firstNotNullOfOrNull { it.string("href") },
            ),
        )
    }

    fun topTags(body: String): LastFmAnswer<List<LastFmTag>> {
        val root = json.parseToJsonElement(body).jsonObject
        root.error()?.let { return LastFmAnswer.Error(it) }
        val tags =
            root.obj("toptags").items("tag").mapNotNull { tag ->
                tag.string("name")?.let { LastFmTag(it, tag.string("count")?.toIntOrNull() ?: 0) }
            }
        return LastFmAnswer.Found(tags)
    }

    /** An error answer's code; an `error` that is not a number is still an error, of an unknown code. */
    private fun JsonObject.error(): Int? = if ("error" in this) string("error")?.toIntOrNull() ?: -1 else null

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    /** A string or a number, as text; null for anything else. */
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /** The objects under [name]: a list, a lone object, or nothing (absent, an empty string). */
    private fun JsonObject?.items(name: String): List<JsonObject> =
        when (val element: JsonElement? = this?.get(name)) {
            is JsonArray -> element.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
}
