package app.sleevesight.core.genres

/**
 * For the Phase 0.5 gate's judgement of MusicBrainz tags (D12, #46) only: how the labels from one
 * artist's Last.fm tags and from its MusicBrainz tags compare, as counts. Counts, not tags, so the
 * gate's output can be committed or posted: anything that lists MusicBrainz tags is CC BY-NC-SA 3.0
 * (`design/architecture.md`, *MusicBrainz tags and genres (#9)*, *Conditions*). Nothing here is
 * shown in the app or sent in a prompt.
 *
 * @property lastFm labels from the Last.fm tags.
 * @property musicBrainz labels from the MusicBrainz tags.
 * @property both labels from both.
 */
data class GenreAgreement(
    val lastFm: Int,
    val musicBrainz: Int,
    val both: Int,
) {
    /** MusicBrainz gives labels where Last.fm gives none: the gap D12 asks about. */
    val fillsGap: Boolean get() = lastFm == 0 && musicBrainz > 0
}

/**
 * How [lastFmTags] and [musicBrainzTags], each in its source's order, compare through this
 * vocabulary. Labels compare by [GenreVocabulary.identity], so `hip-hop` from one source and
 * `hip hop` from the other agree.
 */
fun GenreVocabulary.agreement(
    lastFmTags: List<String>,
    musicBrainzTags: List<String>,
): GenreAgreement {
    val fromLastFm = labelsFor(lastFmTags).mapNotNull { identity(it.tag) }.toSet()
    val fromMusicBrainz = labelsFor(musicBrainzTags).mapNotNull { identity(it.tag) }.toSet()
    return GenreAgreement(fromLastFm.size, fromMusicBrainz.size, (fromLastFm intersect fromMusicBrainz).size)
}
