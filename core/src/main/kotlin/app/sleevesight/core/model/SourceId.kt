package app.sleevesight.core.model

/**
 * Where an evidence line comes from. [tag] prefixes its id (`W1`, `L3`, `S2`, `P1`).
 *
 * Two are pseudo-sources that retrieve nothing: [PLAYER], the player's own metadata (the
 * release), and [MODEL], the model's own knowledge (*AI service knowledge*), which may add at most
 * one unverified claim, cited `[AI]` (`[M]` until 0.1.8, #668: `citationId`).
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

    /** Web search, `S` for *Search* (`G` until 0.1.12, #769: [LEGACY_WEB_TAG]). */
    WEB("S"),
    MODEL("AI"),
    PLAYER("P"),
    ;

    companion object {
        /**
         * Web search's tag until 0.1.12, `G` (#769). Insights kept in the synthesis cache cite its
         * lines `[G1]`, `[G2]`; `citationId` reads such an id as [WEB]'s (`S1`, `S2`), and the evidence
         * fingerprint still spells [WEB]'s ids with it, so those insights are still found. No source
         * takes it: after 0.1.12, a `G<n>` is only ever an old web search id.
         */
        const val LEGACY_WEB_TAG: String = "G"

        /** The source with [tag], or null. */
        fun ofTag(tag: String): SourceId? = entries.firstOrNull { it.tag == tag }
    }
}
