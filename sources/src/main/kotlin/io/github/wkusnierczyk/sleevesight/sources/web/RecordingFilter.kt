package io.github.wkusnierczyk.sleevesight.sources.web

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.normalizer.AccentFolder

/**
 * Keeps web search results about the right recording (D11; `design/design.md`, *Web search*),
 * whatever the provider. A result is kept when its title or snippet names both the track's title
 * and at least one credited artist, each as a whole phrase after folding case, accents and
 * punctuation. This is what turns away a different song with the same title (the Alphaville
 * "Forever Young" for dearoldfriend's) and a different artist's song (Juice WRLD's "Bad Energy" for
 * Reem's). Results from sources the pipeline already reads, with their own entity checks
 * ([OWN_SOURCE_HOSTS]), are dropped too, so an unverified copy of their text cannot slip in here.
 *
 * Its limit: an artist whose name is a common word (Them) is matched by any text that uses the
 * word, so for such an artist the title does most of the work.
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
        val text = " ${fold("${title.orEmpty()} $snippet")} "

        fun names(phrase: String): Boolean = fold(phrase).let { it.isNotEmpty() && " $it " in text }
        return names(track.title) && track.artists.any(::names)
    }

    /**
     * [text] lowercased, decomposed, without combining marks, with every run of characters other
     * than letters and digits (in any script) made one space, and trimmed.
     */
    internal fun fold(text: String): String {
        val decomposed = accentFolder.decompose(text.lowercase())
        val out = StringBuilder(decomposed.length)
        var space = false
        for (c in decomposed) {
            when {
                c in COMBINING_MARKS -> Unit
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
