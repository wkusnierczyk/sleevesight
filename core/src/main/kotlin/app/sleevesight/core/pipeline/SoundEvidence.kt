package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.WikidataEvidence

/**
 * Whether the sources say anything about how the track sounds (#664): when they say nothing, a
 * genre's reason opens with the app's own line saying so, and where the label came from, so the
 * model need not write about the evidence or its gaps (`GenreReasonPrompt`), a sentence nothing can
 * back. Decided in code, the same for the same evidence, from the evidence the reason is written
 * from, and in the safe direction: the line says the sources are silent only when that is certain,
 * so no prose is read for words of sound, which a word list would miss ("melancholic", a line in
 * Polish).
 *
 * Silent when every line about the track itself ([PromptEvidence.TRACK_SCOPES], the song, its
 * original and web results, and the player's release, [Scope.RELEASE]) is a templated fact that
 * says nothing about the sound. A line is identified by its source and scope, never by its words:
 * - templated, and silent on the sound: MusicBrainz's lines ([SourceId.MUSICBRAINZ]: the first
 *   release, the cover and its original, writers, producers and performers), the player's release
 *   line ([SourceId.PLAYER]), Wikidata's lines ([SourceId.WIKIDATA]: label, awards, dates) but its
 *   genre line, and a line of counts or lists of what listeners did ([EvidenceLine.aggregate]);
 * - speaking of the sound: Wikidata's genre line ("The genre of “…” is …", known by its template's
 *   opening, [WikidataEvidence.namesGenre]), whose genre says how the track sounds;
 * - free text, which may: any other line, whatever its words: a Wikipedia article's sentence, a web
 *   result, a Last.fm biography, and a source added later until it is listed above.
 *
 * Lines about the artist are not read: the artist's other work is another matter than how this
 * track sounds, and the reason may still cite them.
 */
object SoundEvidence {
    /** Whether no line of [evidence] about the track itself may speak of how it sounds. */
    fun silent(evidence: EvidenceSet): Boolean = evidence.lines.filter(::aboutTheTrack).all(::templated)

    private fun aboutTheTrack(line: EvidenceLine): Boolean =
        line.scope in PromptEvidence.TRACK_SCOPES || line.scope == Scope.RELEASE

    // A templated fact that says nothing about the sound.
    private fun templated(line: EvidenceLine): Boolean =
        line.aggregate ||
            when (line.source) {
                SourceId.MUSICBRAINZ, SourceId.PLAYER -> true
                SourceId.WIKIDATA -> !WikidataEvidence.namesGenre(line.text)
                SourceId.WIKIPEDIA, SourceId.LASTFM, SourceId.DISCOGS, SourceId.WEB, SourceId.MODEL -> false
            }
}
