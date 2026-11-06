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
 */
data class NormalizedTrack(
    val title: String,
    val artists: List<String>,
    val primary: String,
    val from: String?,
    val variant: String?,
    val notes: List<String>,
    val lookupKey: String,
) {
    /**
     * The names a web page about the track is about: its title, its work and its artists, which a
     * part of a page's passage must say more than to be kept, the rest page boilerplate (#905, #929).
     */
    val subjectNames: List<String>
        get() = listOfNotNull(title, from) + artists
}
