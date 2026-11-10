package app.sleevesight.core.model

import app.sleevesight.core.text.JS_WHITESPACE_CLASS

/**
 * A track as sources name it: the song's plain title and its credited artists, with what
 * normalization removed kept as [from], [variant] and [notes] (`design/design.md`, *Normalizer*).
 *
 * @property primary the first credited artist.
 * @property from the source work, from `(From X)` or ` - From "X"`.
 * @property variant the variant, from `(felt piano)`, `(acoustic)` and the like.
 * @property notes one line per rule applied, for diagnostics.
 * @property lookupKey the lowercased, accent-folded primary artist and title, `artist|title`: the
 *   key of the retrieval cache and of the pipeline's `distinctUntilChangedBy`.
 * @property dropped the version suffix normalization dropped from the title (`2006 Remaster`, `Live`,
 *   `Off Vocal Version`), as the title had it; null for none. LRCLIB is asked only when it names no
 *   other recording than the song's own (#997, `LibraryQuery.of`).
 * @property composer the credited artist the title names first, before a colon, as players file
 *   classical music ("Mozart: Piano Sonata No. 16 …", Mozart credited), with another artist credited
 *   besides: on such a track, the composer (#1129); null for none. The title keeps it; [searchTitle] leaves it out.
 * @property titleArtists with a [composer], the credited artists the title names anywhere, the
 *   composer and an arranger in "(Arr. Busoni)" among them (`ComposerPrefix.namedIn`): no performers;
 *   empty without a composer.
 */
data class NormalizedTrack(
    val title: String,
    val artists: List<String>,
    val primary: String,
    val from: String?,
    val variant: String?,
    val notes: List<String>,
    val lookupKey: String,
    val dropped: String? = null,
    val composer: TitleComposer? = null,
    val titleArtists: List<String> = emptyList(),
) {
    /**
     * The title the sources are searched by: without a leading [composer] (`Mozart: `), which
     * MusicBrainz's titles and the pages on the music do not have and a phrase search would need
     * (#1129, #1179); else [title]. Last.fm and LRCLIB are asked by [title] still: they keep the
     * names the players send, the composer's lead included (`design/classical.md`).
     */
    val searchTitle: String
        get() =
            composer
                ?.let { title.removePrefix(it.prefix) }
                ?.takeIf { it.length < title.length && it.isNotBlank() }
                ?: title

    /**
     * The work a [searchTitle] of the shape `<work>: <movement>` names (#1179): the part before its
     * first colon that whitespace follows, without a nickname in double quotes at its end
     * (`Piano Sonata No. 16 in C Major, K. 545` of `… K. 545 "Sonata facile": II. Andante`), so that
     * a page on the work, which names no movement, can be told to be about the track. Only for a
     * title searched without its [composer] ([searchTitle] is not [title]); null for any other, for
     * one with no such colon, and for a work part that is blank.
     */
    val searchWork: String?
        get() {
            val search = searchTitle
            val work = WORK.find(search)?.takeIf { search != title && it.value.length < search.length }
            return work
                ?.groupValues
                ?.get(1)
                ?.replace(NICKNAME, "")
                ?.trim()
                ?.trimEnd(',')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

    /**
     * The key of what a search by [searchTitle] found and is kept (#1179: a song's web lines): for a
     * title searched without its [composer], the [lookupKey] and a mark of that after a line break,
     * which no lookup key has, so what a search by the whole title found is not served for it; else
     * the lookup key itself, so no other track's kept results change.
     */
    val searchKey: String
        get() = if (searchTitle == title) lookupKey else "$lookupKey\n$SEARCH_TITLE_MARK"

    /**
     * The performers (#1129): with a [composer] the title names, the credited artists the title does
     * not name ([titleArtists]: the composer, an arranger in `(Arr. Busoni)`), when there are any;
     * else every credited artist.
     */
    val performers: List<String>
        get() {
            val named = titleArtists.toSet() + listOfNotNull(composer?.name)
            val performers = artists.filter { it !in named }
            return if (composer != null && performers.isNotEmpty()) performers else artists
        }

    /**
     * The names a web page about the track is about: its title, its work and its artists, which a
     * part of a page's passage must say more than to be kept, the rest page boilerplate (#905, #929).
     */
    val subjectNames: List<String>
        get() = listOfNotNull(title, from) + artists

    private companion object {
        // The work part of a search title: the text before its first colon that whitespace and more
        // title follow. No braces: ICU reads the pattern as the JVM does.
        val WORK = Regex("^([^:]+):$JS_WHITESPACE_CLASS+")

        // A nickname in double quotes, straight or curly, at the end of a work: ` "Sonata facile"`.
        val NICKNAME =
            Regex("$JS_WHITESPACE_CLASS*[\"\u201C\u201E][^\"\u201C\u201D\u201E]*[\"\u201D]$JS_WHITESPACE_CLASS*$")

        const val SEARCH_TITLE_MARK = "search-title"
    }
}

/**
 * A credited artist named at the start of a title, before a colon, whole or by the last words of
 * the name, with initials allowed for the words before the last ("Mozart: " or "J.S. Bach: " for
 * Johann Sebastian Bach), as players file classical music under its composer (#1129).
 *
 * @property name the artist as credited ("Wolfgang Amadeus Mozart").
 * @property prefix the title's lead as written, with the colon and the whitespace after it
 *   ("Mozart: ").
 */
data class TitleComposer(
    val name: String,
    val prefix: String,
)
