package app.sleevesight.sources.lastfm

import app.sleevesight.core.Secret
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.net.NetClient

/** Last.fm's album, by the artist's name and the album's (#885): `LastFmAlbums`; a fake in tests. */
fun interface LastFmAlbumInfo {
    suspend fun albumInfo(
        artist: String,
        album: String,
    ): Lookup<LastFmAlbum>
}

/**
 * Last.fm's album (`album.getInfo`, #885), by the artist's name and the album's, with
 * `autocorrect=1` as the other lookups have it, for an album's insight (`AlbumSource`, which checks
 * the answer is the album asked for). Last.fm's error 6 is an album it does not know.
 *
 * The user's API key goes in `NetSecrets` as `api_key`, never in the URL.
 */
class LastFmAlbums(
    private val net: NetClient,
    private val apiKey: Secret,
) : LastFmAlbumInfo {
    override suspend fun albumInfo(
        artist: String,
        album: String,
    ): Lookup<LastFmAlbum> = net.lastFmLookup(apiKey, "album.getinfo", artist, "album" to album, LastFmJson::albumInfo)
}
