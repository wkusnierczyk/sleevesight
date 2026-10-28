package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Edition
import app.sleevesight.core.resolution.MusicBrainzEvidence

/**
 * The evidence an insight's prompt is written from (#239, `design/design.md`, *Synthesis*): the
 * track's, less artist-level lines ([Scope.ARTIST]) the insight should not dwell on, each line kept
 * keeping its id, so the reply cites the track's whole evidence (`InsightWriter` checks a fresh reply
 * against what was sent).
 *
 * - A track with enough evidence of its own ([ENOUGH_OF_ITS_OWN] lines about the song, its original
 *   or the web) gets no artist-level line. Measured on three albums and a one-artist playlist (#239),
 *   insights of such tracks hardly narrated the artist's lines (3 of 131 sentences cited artist
 *   lines only, against 14 of 29 on the tracks with fewer), which were a quarter of each prompt.
 * - A track with less gets the artist-level lines no other track's insight in the listening session
 *   cited ([SessionFacts]): on an album whose songs are thinly documented, the model filled its
 *   sentences with the artist's lines, the same ones track after track; now each fact is told
 *   once a session, and a later track's insight is shorter rather than repeated.
 * - All of the evidence when leaving lines out would leave nothing retrieved to narrate
 *   ([EvidenceSet.nothingToNarrate] with model knowledge off, whatever its switch): an insight that
 *   repeats an artist fact is better than none, and than one in which the model's own knowledge
 *   (`[AI]`, unverified) stands in for the verified artist lines left out.
 *
 * Web lines count as the track's own, as a web search is about the track; the measurement ran with
 * web search off, so how insights use the artist lines on tracks whose own lines are mostly web
 * results was not measured. With web search on, most tracks reach [ENOUGH_OF_ITS_OWN].
 *
 * The player's own release line (`P`, [SourceId.PLAYER]) is left out too when MusicBrainz's
 * first-release line names that release as the album the song first came out on
 * ([restatesRelease], #424): "first released ..., on the album “Sundown”". On thinly documented
 * tracks the model padded the insight with a sentence citing the `P` line alone ("This recording
 * is on that same "Sundown" release [P1]"), cited and true but telling nothing the line before it
 * had not; with no `P` line there is nothing to restate. Where the song first came out elsewhere,
 * as for a compilation or a soundtrack, the `P` line stays, and a sentence citing it tells the
 * listener where this recording comes from. The release stays in the prompt's track block either
 * way.
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
     * What to send of [evidence], given the facts [citedByOthers] cited in the session and the
     * [release] the player reports (`NowPlaying.album`, the `P` line's), null when it reports none.
     * With [artistFactsOnce] off (*Don't repeat artist facts*, #407), every
     * artist-level line is sent, and only a restated release line is left out (#424).
     */
    fun of(
        evidence: EvidenceSet,
        release: String?,
        citedByOthers: Set<ArtistFact>,
        hasher: Hasher,
        artistFactsOnce: Boolean = true,
    ): EvidenceSet {
        val own = evidence.lines.count { it.scope in TRACK_SCOPES }
        val restated = restatesRelease(evidence, release)
        val sent =
            when {
                !artistFactsOnce -> evidence.without(hasher, restated)
                own >= ENOUGH_OF_ITS_OWN -> evidence.without(hasher) { it.scope == Scope.ARTIST || restated(it) }
                else ->
                    evidence.without(hasher) {
                        (it.scope == Scope.ARTIST && ArtistFact.of(it) in citedByOthers) || restated(it)
                    }
            }
        // Decided on the retrieved lines alone: the artists' report does not matter with model knowledge off.
        return if (sent.nothingToNarrate(allowModel = false, artistsKnown = true)) evidence else sent
    }

    /**
     * Which lines of [evidence] only restate the player's [release]: its `P` line, when MusicBrainz's
     * first-release line says the song first came out on an album of the same name
     * ([MusicBrainzEvidence.firstReleaseAlbum]). The names are compared whole, folded as an
     * [ArtistFact]'s text is ([ArtistFact.matchKey]: case, spacing, quotation marks, apostrophes,
     * dashes and a closing full stop), the player's less its closing bracketed parts ([Edition.strip]: its
     * "Rubber Soul (Remastered)" is MusicBrainz's “Rubber Soul”). MusicBrainz's name is taken as it
     * is: a song first released on “Sundown (Live)” came out on another edition than the studio
     * "Sundown" the player reports, so its line stays.
     *
     * So the album "Greatest Hits" is not “Greatest Hits II”, and the track's title or the artist
     * credit, which the line quotes too, never counts: on a title track first released as a single
     * (“Sundown” ... on the single “Sundown”) the line stays, since the album it is on is another
     * release. The other sources' lines do not count either, being free text: Wikipedia's "from his
     * 1974 album Sundown" may name the release, but a name found inside a sentence may be part of a
     * longer one, or another recording's. A player's " - Single" or " - EP" is kept in its name, so
     * its single is not taken for the album.
     */
    internal fun restatesRelease(
        evidence: EvidenceSet,
        release: String?,
    ): (EvidenceLine) -> Boolean {
        val wanted = release?.let { ArtistFact.matchKey(Edition.strip(it)) }?.takeIf { it.isNotEmpty() }
        val named =
            wanted != null &&
                evidence.lines.any { line ->
                    line.source == SourceId.MUSICBRAINZ &&
                        line.scope == Scope.SONG &&
                        MusicBrainzEvidence.firstReleaseAlbum(line.text)?.let { ArtistFact.matchKey(it) } == wanted
                }
        return { line -> named && line.source == SourceId.PLAYER }
    }
}
