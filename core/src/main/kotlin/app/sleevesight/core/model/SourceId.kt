package app.sleevesight.core.model

/**
 * Where an evidence line comes from. [tag] prefixes its id (`W1`, `L3`, `G2`, `P1`).
 *
 * Two are pseudo-sources that retrieve nothing: [PLAYER], the player's own metadata (the
 * release), and [MODEL], the model's own knowledge, which may add at most one unverified claim.
 * [WIKIDATA] is separate from [WIKIPEDIA] because its lines are templated from structured data
 * and its retrieval cache lifetime differs (D9); both sit behind the user's one Wikipedia switch.
 */
enum class SourceId(
    val tag: String,
) {
    MUSICBRAINZ("MB"),
    WIKIPEDIA("W"),
    WIKIDATA("WD"),
    LASTFM("L"),
    DISCOGS("D"),
    WEB("G"),
    MODEL("M"),
    PLAYER("P"),
    ;

    companion object {
        /** The source with [tag], or null. */
        fun ofTag(tag: String): SourceId? = entries.firstOrNull { it.tag == tag }
    }
}
