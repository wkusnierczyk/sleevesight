package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.evidence.Scope

/**
 * The evidence an insight's prompt is written from (#239, `design/design.md`, *Synthesis*): the
 * track's, less artist-level lines ([Scope.ARTIST]) the insight should not dwell on, each line kept
 * keeping its id, so the reply cites, and is checked against, the track's whole evidence.
 *
 * - A track with enough evidence of its own ([ENOUGH_OF_ITS_OWN] lines about the song, its original
 *   or the web) gets no artist-level line. Measured on three albums and a one-artist playlist (#239),
 *   insights of such tracks hardly narrated the artist's lines (3 of 131 sentences cited artist
 *   lines only, against 14 of 29 on the tracks with fewer), which were a quarter of each prompt.
 * - A track with less gets the artist-level lines no other track's insight in the listening session
 *   cited ([SessionFacts]): on an album whose songs are thinly documented, the model filled its
 *   sentences with the artist's lines, the same ones track after track; now each fact is told
 *   once a session, and a later track's insight is shorter rather than repeated.
 * - All of the evidence when leaving lines out would leave nothing to narrate
 *   ([EvidenceSet.nothingToNarrate]): an insight that repeats an artist fact is better than none.
 *
 * Which lines are left out depends on the session, so the synthesis cache keys a card by the whole
 * evidence's fingerprint, not the one sent: a track played again finds the card it was given.
 */
object PromptEvidence {
    /**
     * How many lines about the track itself make the artist's lines unneeded: below it, the
     * measured insights used artist lines in one or two of their sentences; from it, almost never.
     */
    const val ENOUGH_OF_ITS_OWN: Int = 4

    /** The scopes of lines about the track itself, not its artist or the player's release. */
    val TRACK_SCOPES: Set<Scope> = setOf(Scope.SONG, Scope.ORIGINAL_SONG, Scope.WEB)

    /**
     * What to send of [evidence], given the facts [citedByOthers] cited in the session, with
     * [allowModel] and [artistsKnown] as [EvidenceSet.nothingToNarrate] takes them.
     */
    fun of(
        evidence: EvidenceSet,
        citedByOthers: Set<ArtistFact>,
        hasher: Hasher,
        allowModel: Boolean,
        artistsKnown: Boolean,
    ): EvidenceSet {
        val own = evidence.lines.count { it.scope in TRACK_SCOPES }
        val sent =
            if (own >= ENOUGH_OF_ITS_OWN) {
                evidence.without(hasher) { it.scope == Scope.ARTIST }
            } else {
                evidence.without(hasher) { it.scope == Scope.ARTIST && ArtistFact.of(it) in citedByOthers }
            }
        return if (sent.nothingToNarrate(allowModel, artistsKnown)) evidence else sent
    }
}
