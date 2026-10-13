package io.github.wkusnierczyk.sleevesight.sources.web

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.normalizer.AccentFolder

/**
 * Keeps web search results about the right recording (D11; `design/design.md`, *Web search
 * provider (D11)*), whatever the provider. Phrases are matched whole, after [fold]ing case, accents
 * and punctuation, in the result's title and snippet together. A result is kept when:
 *
 * - it names the song: the track's title, and the work it is from when the player gave one
 *   ([NormalizedTrack.from]: `Theme` from Coronation Street, not any London Music Works theme);
 * - it names a credited artist, a *distinctive* one when the track has one ([requiredArtists]:
 *   Van Morrison for Them and Van Morrison, since "them" is in any text);
 * - its own title does not name such an artist without the song. A page titled after the artist
 *   alone is about the artist or another of their recordings (Yanjoco's "Caravan", whose page
 *   lists "I Will Follow You Into The Dark" among the artist's other songs), so a mention in its
 *   snippet does not make it about this recording.
 *
 * This is what turns away a different song with the same title (the Alphaville "Forever Young"
 * for dearoldfriend's) and a different artist's song (Juice WRLD's "Bad Energy" for Reem's).
 * Results from sources the pipeline already reads, with their own entity checks
 * ([OWN_SOURCE_HOSTS]), are dropped too, so an unverified copy of their text cannot slip in here.
 *
 * Its limits: an artist whose every word is common ("To Twelve", "a balladeer") is matched by any
 * text with those words, so for such an artist the title does most of the work; and a page on
 * another recording that names the song and the artist in passing still passes.
 */
class RecordingFilter(
    private val accentFolder: AccentFolder,
) {
    /** Whether a result with [title], [snippet] and [url] is about [track]. */
    fun isAbout(
        track: NormalizedTrack,
        title: String?,
        snippet: String,
        url: String,
    ): Boolean {
        if (isOwnSource(url)) return false
        val heading = padded(title.orEmpty())
        val text = padded("${title.orEmpty()} $snippet")
        val song = listOfNotNull(track.title, track.from).map(::padded)
        val artists = requiredArtists(track).map(::padded)
        val namesSong = song.none { it.isBlank() } && song.all { it in text }
        val namesArtist = artists.any { it in text }
        val titledAfterOther = artists.any { it in heading } && !song.all { it in heading }
        return namesSong && namesArtist && !titledAfterOther
    }

    /**
     * The credited artists a result must name one of, folded: the distinctive ones, those with a
     * word outside [COMMON_WORDS], or all of them when none is distinctive.
     */
    internal fun requiredArtists(track: NormalizedTrack): List<String> {
        val all = track.artists.map(::fold).filter { it.isNotEmpty() }
        return all.filter { name -> name.split(' ').any { it !in COMMON_WORDS } }.ifEmpty { all }
    }

    // [phrase] folded, with a space either side, so `in` matches whole words only.
    private fun padded(phrase: String): String = " ${fold(phrase)} "

    /**
     * [text] lowercased, decomposed, without combining marks, with `&` read as `and` and every
     * other run of characters other than letters and digits (in any script) made one space, and
     * trimmed.
     */
    internal fun fold(text: String): String {
        val decomposed = accentFolder.decompose(text.lowercase())
        val out = StringBuilder(decomposed.length)
        var space = false
        for (c in decomposed) {
            when {
                c in COMBINING_MARKS -> {
                    continue
                }

                c == '&' -> {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append("and")
                    space = true
                }

                c.isLetterOrDigit() -> {
                    if (space && out.isNotEmpty()) out.append(' ')
                    out.append(c)
                    space = false
                }
                else -> space = true
            }
        }
        return out.toString()
    }

    companion object {
        /**
         * Hosts of sources the pipeline reads through their own adapters, which verify their
         * matches (architecture, *Entity resolution*). A host here matches itself and its
         * subdomains. Wikiwand is a mirror of Wikipedia. The same list goes to the provider as an
         * exclusion where it takes one.
         */
        val OWN_SOURCE_HOSTS: List<String> =
            listOf("wikipedia.org", "wikiwand.com", "wikidata.org", "musicbrainz.org", "last.fm", "discogs.com")

        private val COMBINING_MARKS = '\u0300'..'\u036F'

        /**
         * Words too common to identify an artist on their own: articles, pronouns, prepositions,
         * conjunctions and small numbers. An artist named only with these ("Them", "To Twelve")
         * is not distinctive.
         */
        internal val COMMON_WORDS: Set<String> =
            (
                "a an the and or of to in on at by for with from into over under up down out off " +
                    "i me my we us our you your he him his she her it its they them their " +
                    "this that these those is are be been no not all so as one two three four five " +
                    "six seven eight nine ten eleven twelve"
            ).split(' ').toSet()

        /** Whether [url]'s host is one of [OWN_SOURCE_HOSTS] or below one. */
        internal fun isOwnSource(url: String): Boolean {
            val host = hostOf(url) ?: return false
            return OWN_SOURCE_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        // The authority of an absolute URL, without user info or port, lowercased; null if none.
        private fun hostOf(url: String): String? {
            val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
            if (afterScheme.isEmpty()) return null
            val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
            val host =
                authority
                    .substringAfterLast('@')
                    .substringBefore(':')
                    .trimEnd('.')
                    .lowercase()
            return host.ifEmpty { null }
        }
    }
}
