package io.github.wkusnierczyk.sleevesight.core.model

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
)
