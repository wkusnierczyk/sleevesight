package app.sleevesight.core.model

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
 *   classical music ("Mozart: Piano Sonata No. 16 …", Mozart credited): on such a track, the
 *   composer (#1129); null for none. The title keeps it; [searchTitle] leaves it out.
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
) {
    /**
     * The title MusicBrainz is searched by: without a leading [composer] (`Mozart: `), which
     * MusicBrainz's titles do not have and a phrase search would need (#1129); else [title].
     */
    val searchTitle: String
        get() =
            composer
                ?.let { title.removePrefix(it.prefix) }
                ?.takeIf { it.length < title.length && it.isNotBlank() }
                ?: title

    /**
     * The names a web page about the track is about: its title, its work and its artists, which a
     * part of a page's passage must say more than to be kept, the rest page boilerplate (#905, #929).
     */
    val subjectNames: List<String>
        get() = listOfNotNull(title, from) + artists
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
