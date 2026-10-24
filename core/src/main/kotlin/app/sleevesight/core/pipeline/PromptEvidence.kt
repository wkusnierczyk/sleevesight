package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId

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
 *   (`[M]`, unverified) stands in for the verified artist lines left out.
 *
 * Web lines count as the track's own, as a web search is about the track; the measurement ran with
 * web search off, so how insights use the artist lines on tracks whose own lines are mostly web
 * results was not measured. With web search on, most tracks reach [ENOUGH_OF_ITS_OWN].
 *
 * The player's own release line (`P`, [SourceId.PLAYER]) is left out too when a line about the song
 * names that release ([restatesRelease], #424): MusicBrainz's "first released ..., on the album
 * “Sundown”", say. On thinly documented tracks the model padded the insight with a sentence citing
 * the `P` line alone ("This recording is on that same "Sundown" release [P1]"), cited and true but
 * telling nothing the line before it had not; with no `P` line there is nothing to restate. Where no
 * line about the song names the release, as on a compilation or a soundtrack, the `P` line stays,
 * and a sentence citing it tells the listener where this recording comes from. The release stays in
 * the prompt's track block either way.
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
     */
    fun of(
        evidence: EvidenceSet,
        release: String?,
        citedByOthers: Set<ArtistFact>,
        hasher: Hasher,
    ): EvidenceSet {
        val own = evidence.lines.count { it.scope in TRACK_SCOPES }
        val restated = restatesRelease(evidence, release)
        val sent =
            if (own >= ENOUGH_OF_ITS_OWN) {
                evidence.without(hasher) { it.scope == Scope.ARTIST || restated(it) }
            } else {
                evidence.without(hasher) {
                    (it.scope == Scope.ARTIST && ArtistFact.of(it) in citedByOthers) || restated(it)
                }
            }
        // Decided on the retrieved lines alone: the artists' report does not matter with model knowledge off.
        return if (sent.nothingToNarrate(allowModel = false, artistsKnown = true)) evidence else sent
    }

    /**
     * Which lines of [evidence] only restate the player's [release]: its `P` line, when a line about
     * the song ([Scope.SONG], from a source other than the player) names the release. A line names
     * it when it holds the release's name, or the name less one closing bracketed part (the player's
     * "Rubber Soul (Remastered)" is MusicBrainz's “Rubber Soul”), as a whole: case, spacing,
     * quotation marks, apostrophes and dashes folded as an [ArtistFact]'s are, and no letter or digit
     * right before or after it. Lines about an original song, the artist or a web page do not count:
     * they may name another recording's release. The cost: a release whose name is a common word
     * ("Love") is taken as named by a song line that uses the word, and its `P` line goes; the
     * prompt's track block still gives the release, uncitable.
     */
    internal fun restatesRelease(
        evidence: EvidenceSet,
        release: String?,
    ): (EvidenceLine) -> Boolean {
        val names = release?.let(::releaseNames).orEmpty()
        val named =
            names.isNotEmpty() &&
                evidence.lines.any { line ->
                    line.scope == Scope.SONG && line.source != SourceId.PLAYER && names.any { holds(line.text, it) }
                }
        return { line -> named && line.source == SourceId.PLAYER }
    }

    // The release's name and the name less one closing bracketed part, folded; none blank.
    private fun releaseNames(release: String): Set<String> =
        listOf(release, release.replace(CLOSING_BRACKETS, ""))
            .map { ArtistFact.matchKey(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    // Whether [text], folded, holds [name] with no letter or digit right before or after it.
    private fun holds(
        text: String,
        name: String,
    ): Boolean {
        val folded = ArtistFact.matchKey(text)
        var from = folded.indexOf(name)
        while (from >= 0) {
            val end = from + name.length
            val before = from == 0 || !folded[from - 1].isLetterOrDigit()
            val after = end == folded.length || !folded[end].isLetterOrDigit()
            if (before && after) return true
            from = folded.indexOf(name, from + 1)
        }
        return false
    }

    // One closing `(…)` or `[…]` part, and the white space before it.
    private val CLOSING_BRACKETS = Regex("""\s*[(\[][^()\[\]]*[)\]]\s*$""")
}
