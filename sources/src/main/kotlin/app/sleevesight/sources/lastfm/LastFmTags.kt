package app.sleevesight.sources.lastfm

import app.sleevesight.core.Secret
import app.sleevesight.core.genres.ReleaseTags
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResult
import app.sleevesight.net.NetSecrets
import app.sleevesight.net.fetch

/**
 * Last.fm's top tags for a track (`track.getTopTags`) and an album (`album.getTopTags`), by the
 * artist's name and the track's or album's, with `autocorrect=1` as the artist lookups have it, for
 * the track's and album's genre labels (#439). Tags weighted below [LastFmAdapter.MIN_TAG_COUNT] are
 * left out, as for an artist's. Last.fm's error 6 is a track or album it does not know.
 *
 * The user's API key goes in [NetSecrets] as `api_key`, never in the URL.
 */
class LastFmTags(
    private val net: NetClient,
    private val apiKey: Secret,
) : ReleaseTags {
    override suspend fun trackTags(
        artist: String,
        title: String,
    ): Lookup<List<String>> = call("track.gettoptags", artist, "track" to title)

    override suspend fun albumTags(
        artist: String,
        album: String,
    ): Lookup<List<String>> = call("album.gettoptags", artist, "album" to album)

    override fun trackPage(
        artist: String,
        title: String,
    ): String = "$PAGES${encode(artist)}/_/${encode(title)}"

    override fun albumPage(
        artist: String,
        album: String,
    ): String = "$PAGES${encode(artist)}/${encode(album)}"

    // Detekt does not see `NetClient.fetch`, from `:net`, as suspending; it is.
    @Suppress("RedundantSuspendModifier")
    private suspend fun call(
        method: String,
        artist: String,
        named: Pair<String, String>,
    ): Lookup<List<String>> {
        val request =
            NetRequest(
                url = LastFmAdapter.API_URL,
                query =
                    mapOf(
                        "method" to method,
                        "artist" to artist,
                        named,
                        "autocorrect" to "1",
                        "format" to "json",
                    ),
                secrets = NetSecrets(query = mapOf("api_key" to apiKey.reveal())),
            )
        val answer =
            net.fetch(request) { response ->
                val parsed = LastFmJson.topTags(response.body)
                check(response.isSuccess || parsed is LastFmAnswer.Error) { "status ${response.status}" }
                parsed
            }
        return when (answer) {
            is NetResult.Failure -> Lookup.Failed(answer.error.toString())
            is NetResult.Success ->
                when (val parsed = answer.value) {
                    is LastFmAnswer.Found ->
                        Lookup.Found(parsed.value.filter { it.count >= LastFmAdapter.MIN_TAG_COUNT }.map { it.name })
                    is LastFmAnswer.Error ->
                        if (parsed.code ==
                            LastFmJson.NOT_FOUND
                        ) {
                            Lookup.NotFound
                        } else {
                            Lookup.Failed("Last.fm error ${parsed.code}")
                        }
                    is LastFmAnswer.Unreachable -> Lookup.Failed(parsed.error.toString())
                }
        }
    }

    private companion object {
        const val PAGES = "https://www.last.fm/music/"
        const val SAFE = "-._~"
        const val HEX = "0123456789ABCDEF"
        const val BYTE = 0xFF
        const val ASCII = 0x80
        const val NIBBLE = 4
        const val LOW_NIBBLE = 0x0F

        // As Last.fm's own links have it: a space as `+`, letters, digits and `-._~` as they are, the
        // rest percent-encoded as UTF-8 (`Simon+%26+Garfunkel`, `AC%2FDC`).
        fun encode(name: String): String =
            buildString {
                for (byte in name.encodeToByteArray()) {
                    val c = byte.toInt() and BYTE
                    when {
                        c == ' '.code -> append('+')
                        c < ASCII && c.toChar().let { it.isLetterOrDigit() || it in SAFE } -> append(c.toChar())
                        else -> append('%').append(HEX[c shr NIBBLE]).append(HEX[c and LOW_NIBBLE])
                    }
                }
            }
    }
}
