package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.text.WebLinks

/**
 * Whether a track's own lines only list the song (#958): what sets off the song's web search and,
 * when it finds no better, the song's insight saying so (`ItemInsights`). Decided here, with no paid
 * call, on the lines' source, address and words. A wrong guess costs or misses one search, so it is
 * kept simple, and a host or a phrase is added here when one is found missing.
 *
 * A line is a listing ([isListing]) when it is structured data ([STRUCTURED]: MusicBrainz's and
 * Wikidata's templated lines, the player's), counts what many listeners did
 * ([EvidenceLine.aggregate]), comes from a streaming, shop or chart site ([LISTING_HOSTS],
 * [LISTING_NAMES]), or reads like one ([LISTING_WORDS]: a track list, "released on", "listen on").
 * Such a phrase counts only where little else is said: with [PROSE_WORDS] or more words of prose
 * beside it (lower-case words that are not a listing's own, [METADATA_WORDS]; names, dates and numbers
 * do not count), the line is mixed, and describes the song ("Released on Friday, the song pairs a
 * loping bass line with jagged horns."). A list of tracks, numbered or with little prose beside its
 * lengths, is a listing whatever surrounds it; lengths in prose are moments in the song.
 * Wikipedia's and Last.fm's text describe, whatever they say; so does a web line with no known
 * listing host and no listing shape. The words are English: a page in another language's words
 * counts as describing the song, unless its host says otherwise.
 */
object SongListings {
    /** Whether [lines] have nothing but listings among the song's own ([PromptEvidence.TRACK_SCOPES]), or none. */
    fun listingOnly(lines: List<EvidenceLine>): Boolean =
        lines.filter { it.scope in PromptEvidence.TRACK_SCOPES }.all(::isListing)

    /** Whether [line] only lists the song ([SongListings]). */
    fun isListing(line: EvidenceLine): Boolean =
        when {
            line.aggregate || line.source in STRUCTURED -> true
            line.source in TEXT_SOURCES -> false
            else -> isListingHost(line.url) || readsLikeListing(line.text)
        }

    /** Whether [url] is on a streaming, shop or chart site. */
    fun isListingHost(url: String?): Boolean {
        val host = url?.let(::hostOf) ?: return false
        val labels = host.split('.')
        return LISTING_HOSTS.any { host == it || host.endsWith(".$it") } || labels.any { it in LISTING_NAMES }
    }

    /** Whether [text] has the shape of a listing: a list of tracks, or its release data and no more. */
    fun readsLikeListing(text: String): Boolean {
        val lower = text.lowercase()
        val little = proseWords(text) < PROSE_WORDS
        return isTrackList(text, little) || (little && LISTING_WORDS.any { it in lower })
    }

    // Whether [text] holds a list of tracks: three numbered entries, or two lengths, with the entries'
    // numbers or with [little] prose beside them. Lengths alone may be moments in prose ("At 0:45 the
    // horns enter, and at 2:10 the bass takes over.").
    private fun isTrackList(
        text: String,
        little: Boolean,
    ): Boolean {
        val numbered = NUMBERED.findAll(text).count()
        val timed = TIMES.findAll(text).count() >= TRACK_LIST_TIMES
        return numbered >= TRACK_LIST_NUMBERS || (timed && (little || numbered >= TRACK_LIST_TIMES))
    }

    // How many words of [text] read as prose: in lower case, more than a letter, not a listing's own.
    private fun proseWords(text: String): Int =
        text
            .split(NOT_LETTERS)
            .count { word -> word.length > 1 && word.all(Char::isLowerCase) && word !in METADATA_WORDS }

    // The host of [url] ([WebLinks.host], as a browser reads it), without a leading `www.`; null for none.
    private fun hostOf(url: String): String? = WebLinks.host(url)?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    private val STRUCTURED =
        setOf(SourceId.MUSICBRAINZ, SourceId.WIKIDATA, SourceId.DISCOGS, SourceId.PLAYER)
    private val TEXT_SOURCES = setOf(SourceId.WIKIPEDIA, SourceId.LASTFM)

    // Streaming, shop, download and chart sites, each with its subdomains.
    private val LISTING_HOSTS =
        listOf(
            "spotify.com",
            "music.apple.com",
            "itunes.apple.com",
            "music.youtube.com",
            "deezer.com",
            "tidal.com",
            "qobuz.com",
            "soundcloud.com",
            "bandcamp.com",
            "pandora.com",
            "napster.com",
            "audiomack.com",
            "boomplay.com",
            "anghami.com",
            "jiosaavn.com",
            "gaana.com",
            "wynk.in",
            "joox.com",
            "kkbox.com",
            "music.line.me",
            "melon.com",
            "shazam.com",
            "beatport.com",
            "traxsource.com",
            "junodownload.com",
            "juno.co.uk",
            "hdtracks.com",
            "7digital.com",
            "discogs.com",
            "musicstack.com",
            "roughtrade.com",
            "walmart.com",
            "officialcharts.com",
            "acharts.co",
            "acharts.us",
            "kworb.net",
            "chartmasters.org",
            "tunefind.com",
            "songlink.page",
            "song.link",
            "album.link",
            "odesli.co",
            "ffm.to",
            "linktr.ee",
            "distrokid.com",
            "tunecore.com",
        )

    // Sites of many country domains, by the name in their host (`amazon.de`, `ebay.co.uk`).
    private val LISTING_NAMES = setOf("amazon", "ebay", "rakuten", "hmv")

    private val LISTING_WORDS =
        listOf(
            "released on",
            "release date",
            "released:",
            "listen on",
            "listen to ",
            "stream on",
            "streaming on",
            "available on",
            "add to cart",
            "add to playlist",
            "add to library",
            "buy now",
            "download now",
            "track list",
            "tracklist",
            "track listing",
            "label:",
            "catalog number",
            "catalogue number",
            "isrc",
            "upc:",
            "℗",
        )

    // The words a listing's own sentences are made of, and the few that join them.
    private val METADATA_WORDS =
        setOf(
            "add",
            "album",
            "an",
            "and",
            "as",
            "at",
            "available",
            "buy",
            "by",
            "cart",
            "catalog",
            "catalogue",
            "date",
            "download",
            "first",
            "for",
            "from",
            "in",
            "is",
            "it",
            "label",
            "library",
            "list",
            "listen",
            "listing",
            "music",
            "now",
            "number",
            "of",
            "on",
            "or",
            "part",
            "playlist",
            "recorded",
            "release",
            "released",
            "single",
            "song",
            "stream",
            "streaming",
            "the",
            "to",
            "track",
            "tracklist",
            "was",
        )
    private val NOT_LETTERS = Regex("[^\\p{L}]+")
    private const val PROSE_WORDS = 4

    // Lengths (`3:45`) and entries' numbers (`1. `) in a line, and how many make a list ([isTrackList]).
    private val TIMES = Regex("\\b[0-9]{1,2}:[0-5][0-9]\\b")
    private val NUMBERED = Regex("(?:^|\\s)[0-9]{1,2}[.)]\\s")
    private const val TRACK_LIST_TIMES = 2
    private const val TRACK_LIST_NUMBERS = 3
}
