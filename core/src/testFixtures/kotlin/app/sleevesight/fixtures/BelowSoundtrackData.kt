package app.sleevesight.fixtures

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `playlist.json`: the playlist as the player shows it, in playlist order, duplicates included. */
@Serializable
data class FixturePlaylist(
    val name: String,
    val owner: String,
    val uri: String,
    val tracks: List<FixtureTrack>,
)

/** One playlist entry: `n` is its position, from 1; `artist` is the player's artist string. */
@Serializable
data class FixtureTrack(
    val n: Int,
    val title: String,
    val artist: String,
    @SerialName("dur") val duration: String,
    val explicit: Boolean = false,
)

/**
 * `facts.json`: the simulator's frozen lookups for one unique track (the first occurrence of a
 * duplicate), with the outcome each source gave and the fact lines written in advance. The
 * outcomes are the expected outcomes of the resolution tests; the fact lines are reference facts.
 */
@Serializable
data class FixtureFacts(
    val n: Int,
    val title: String,
    val artist: String,
    val wikipedia: FixtureWikipedia,
    @SerialName("lastfm") val lastFm: FixtureLastFm,
    val web: FixtureWeb,
    val notes: String,
)

/** `status`: `song_article`, `artist_article`, `original_song`, `mismatch` or `none`. */
@Serializable
data class FixtureWikipedia(
    val status: String,
    val url: String?,
    val about: String,
    val facts: List<String>,
)

/** `status`: `found`, `no_bio` or `not_found`. */
@Serializable
data class FixtureLastFm(
    val status: String,
    val url: String,
    val listeners: String?,
    val tags: List<String>,
    val similar: List<String>,
    val facts: List<String>,
)

@Serializable
data class FixtureWeb(
    val query: String,
    val results: List<FixtureWebResult>,
)

@Serializable
data class FixtureWebResult(
    val url: String,
    val title: String,
    val facts: List<String>,
)

/** `albums.json`: the release and Spotify links for one unique track. */
@Serializable
data class FixtureAlbum(
    val n: Int,
    val album: String?,
    val year: String?,
    @SerialName("album_source") val albumSource: String?,
    @SerialName("track_url") val trackUrl: String?,
    @SerialName("album_url") val albumUrl: String?,
    @SerialName("artist_urls") val artistUrls: Map<String, String>,
)
