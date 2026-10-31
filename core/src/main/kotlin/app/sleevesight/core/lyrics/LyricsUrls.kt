package app.sleevesight.core.lyrics

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.normalizer.AccentFolder

// The lyrics links' URLs (#728): reading a lyrics page's host and path, the slugs that tell which
// artist a page is for, and the Genius search. Portable code (D2): no `java.net`.

/**
 * Whether [path] names one of the artists of [artistSlugs]: its slug holds one whole, between
 * hyphens or at an end (`/Gerry-rafferty-right-down-the-line-lyrics` names `gerry-rafferty`).
 * Percent-encoded characters are read as what they encode (a Musixmatch path names `Beyonc%C3%A9`).
 */
internal fun namesArtist(
    path: String,
    artistSlugs: List<String>,
    accentFolder: AccentFolder,
): Boolean {
    val pathSlug = "-${slug(percentDecoded(path), accentFolder)}-"
    return artistSlugs.any { "-$it-" in pathSlug }
}

/**
 * [artist]'s slugs ([slug]): as written, and with `&` as `and`, as Genius writes "Simon & Garfunkel"
 * (`Simon-and-garfunkel`); none that is empty.
 */
internal fun artistSlugs(
    artist: String,
    accentFolder: AccentFolder,
): List<String> =
    listOf(slug(artist, accentFolder), slug(artist.replace("&", " and "), accentFolder))
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * [text] as a slug, as lyrics sites write names in their paths: lowercase, accents folded,
 * apostrophes dropped (`Don’t` is `dont`, `O’Connor` `oconnor`), every other run of characters that
 * are not letters or digits one `-`, none at either end.
 */
fun slug(
    text: String,
    accentFolder: AccentFolder,
): String {
    val folded = accentFolder.decompose(text.lowercase()).filterNot { it.category == CharCategory.NON_SPACING_MARK }
    val out = StringBuilder(folded.length)
    var gap = false
    for (c in folded) {
        when {
            c in APOSTROPHES -> Unit
            c.isLetterOrDigit() -> {
                if (gap && out.isNotEmpty()) out.append('-')
                gap = false
                out.append(c)
            }
            else -> gap = true
        }
    }
    return out.toString()
}

/**
 * The Genius search for [track]'s title and primary artist, as the sources name them (#728:
 * "2006 Remaster" and the like already removed by the normalizer): `https://genius.com/search?q=`
 * and the two, a space between, percent-encoded ([percentEncoded]).
 */
fun searchUrl(track: NormalizedTrack): String = GENIUS_SEARCH + percentEncoded("${track.title} ${track.primary}".trim())

/**
 * [text] percent-encoded as a URL's query value: every character but the unreserved ones of RFC 3986
 * (ASCII letters and digits, `-`, `.`, `_`, `~`) as its UTF-8 bytes, a space as `%20`.
 */
internal fun percentEncoded(text: String): String {
    val out = StringBuilder(text.length)
    for (byte in text.encodeToByteArray()) {
        val code = byte.toInt() and BYTE
        if (code.toChar() in UNRESERVED) {
            out.append(code.toChar())
        } else {
            out.append('%').append(HEX[code shr NIBBLE]).append(HEX[code and LOW])
        }
    }
    return out.toString()
}

// [path] with each run of percent-encoded bytes decoded as UTF-8; a `%` not followed by two hex
// digits is left as it is.
private fun percentDecoded(path: String): String =
    ESCAPES.replace(path) { run ->
        val escapes = run.value
        ByteArray(escapes.length / ESCAPE) { i ->
            escapes.substring(i * ESCAPE + 1, (i + 1) * ESCAPE).toInt(HEX_RADIX).toByte()
        }.decodeToString()
    }

/**
 * The lowercase host and the path of [url], when it is an `https` URL with a host, no user
 * information, and no space, control character or backslash; null otherwise. A port and the root's
 * trailing dot are left off the host. Not a full URL parser: enough to tell a whitelisted host from a
 * look-alike (`notgenius.com`, `genius.com.evil.example`, `genius.com@evil.example`).
 */
internal fun parse(url: String): Pair<String, String>? {
    val https = url.startsWith(HTTPS, ignoreCase = true) && url.none { it <= ' ' || it == '\\' }
    val rest = if (https) url.substring(HTTPS.length) else ""
    val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    val host = authority.substringBefore(':').lowercase().removeSuffix(".")
    val pathEnd = rest.indexOfAny(charArrayOf('?', '#'), authorityEnd).let { if (it < 0) rest.length else it }
    return if (!https || '@' in authority || host.isEmpty()) null else host to rest.substring(authorityEnd, pathEnd)
}

private const val GENIUS_SEARCH = "https://genius.com/search?q="
private const val HTTPS = "https://"
private val APOSTROPHES = setOf('\'', '’', '‘', 'ʼ')
private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
private val ESCAPES = Regex("(?:%[0-9A-Fa-f]{2})+")
private const val HEX = "0123456789ABCDEF"
private const val HEX_RADIX = 16
private const val ESCAPE = 3
private const val BYTE = 0xFF
private const val NIBBLE = 4
private const val LOW = 0x0F
