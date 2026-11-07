package app.sleevesight.sources.lrclib

import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.lyrics.LyricsLibrary
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.net.HostPolicy
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.sources.lookup
import app.sleevesight.sources.parseObject
import app.sleevesight.sources.string
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * LRCLIB's `/api/get` (#997; lrclib.net, free, open, no key): the record of a track by its title,
 * artist, album (when the player names one) and length, which LRCLIB matches within 2 seconds. Its
 * `instrumental` mark is [LibraryLyrics.INSTRUMENTAL]; plain or synced lyrics not blank,
 * [LibraryLyrics.LYRICS]; a record with neither, [LibraryLyrics.NONE]. A 404 is a track it does not
 * know ([Lookup.NotFound]), not one without lyrics.
 *
 * LRCLIB asks clients to name themselves, their version and a homepage in the User-Agent, and to
 * honour a 429's `Retry-After` with jitter: [HOST_POLICY], which `:wiring`'s list of host policies
 * registers under [HOST], gives both.
 *
 * `/api/get`, not `/api/get-cached`: `/api/get` is the endpoint LRCLIB's documentation describes
 * (lrclib.net/docs, read 2026-11-07), with a 404 for a track it does not know, which "can be picked up
 * by the background lyrics fetching service" for a later request, so a miss does not wait on that
 * fetch. `/api/get-cached`, which keeps to LRCLIB's own database, is not in the documentation now.
 * Checked live on 2026-11-07, one request each: a request with no `album_name` is answered (200, the
 * record found), so a track the player names no album for is asked without one; a miss is a 404 from
 * both endpoints in about 0.1 s. Should `/api/get` ever wait on an outside lookup, [REQUEST_TIMEOUT]
 * bounds it, and the request comes after the evidence, so the insight never waits for it.
 */
class LrclibClient(
    private val net: NetClient,
) : LyricsLibrary {
    override suspend fun lookUp(query: LibraryQuery): Lookup<LibraryLyrics> =
        net.lookup(request(query)) { response -> answer(response.body) }

    companion object {
        const val HOST: String = "lrclib.net"
        const val ENDPOINT: String = "https://$HOST/api/get"

        /**
         * LRCLIB's terms for clients (lrclib.net/docs, read 2026-11-06): the User-Agent names the app,
         * its version and a homepage, Sleevesight's site; a 429's `Retry-After` honoured with random
         * jitter, here up to a second; requests one after another, about 90 a minute for a client
         * that names itself. One a second keeps well under it, and a track asks once at most.
         */
        val HOST_POLICY: HostPolicy =
            HostPolicy(minInterval = 1.seconds, contact = "https://sleevesight.app", retryJitter = 1.seconds)

        /**
         * How long one request may take, in place of the client's minute: a lookup of the record
         * answers in well under a second, and the Instrumental chip stays as it was meanwhile.
         */
        val REQUEST_TIMEOUT: Duration = 10.seconds

        /** The request for [query]: its title, artist, album if any, and length in seconds. */
        internal fun request(query: LibraryQuery): NetRequest =
            NetRequest(
                url = ENDPOINT,
                query =
                    listOfNotNull(
                        "track_name" to query.title,
                        "artist_name" to query.artist,
                        query.album?.let { "album_name" to it },
                        "duration" to query.seconds.toString(),
                    ).toMap(),
                timeout = REQUEST_TIMEOUT,
            )

        /** What a record says of the track: its instrumental mark first, then whether it has lyrics. */
        internal fun answer(body: String): LibraryLyrics {
            val record = parseObject(body)
            val marked = (record["instrumental"] as? JsonPrimitive)?.booleanOrNull == true
            val lyrics = listOf("plainLyrics", "syncedLyrics").any { !record.string(it).isNullOrBlank() }
            return when {
                marked -> LibraryLyrics.INSTRUMENTAL
                lyrics -> LibraryLyrics.LYRICS
                else -> LibraryLyrics.NONE
            }
        }
    }
}
