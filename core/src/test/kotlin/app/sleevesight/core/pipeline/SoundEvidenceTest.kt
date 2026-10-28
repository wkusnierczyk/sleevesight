package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Whether the sources say anything about how the track sounds (#664): decided by each line's source
 * and scope, never its words, so a sound the code cannot read is never called silence.
 */
class SoundEvidenceTest {
    private fun silent(vararg lines: EvidenceDraft) = SoundEvidence.silent(EvidenceSet.of(MALENCZUK + lines, JvmHasher))

    @Test
    fun `the case of the issue, templated lines about the track, says nothing about the sound`() {
        // Maciej Maleńczuk, *Fajnie*: MusicBrainz's writer and performer lines, the player's
        // release; the artist's article and the listeners' counts and tags are not about the track.
        assertTrue(silent())
    }

    @Test
    fun `any free-text line about the track may describe the sound, whatever its words`() {
        // Words no list holds, and another language.
        assertFalse(silent(line(Scope.SONG, "It is a melancholic, upbeat song.", SourceId.WIKIPEDIA)))
        assertFalse(silent(line(Scope.WEB, "Utwór o spokojnym, gitarowym brzmieniu.", SourceId.WEB)))
        // A sentence about something else is free text all the same.
        assertFalse(silent(line(Scope.SONG, "It was released in 2008.", SourceId.WIKIPEDIA)))
        assertFalse(silent(line(Scope.ORIGINAL_SONG, "The original charted in Poland.", SourceId.WIKIPEDIA)))
        // A source the rule does not list counts as free text.
        assertFalse(silent(line(Scope.SONG, "Styles: Indie Rock.", SourceId.DISCOGS)))
    }

    @Test
    fun `Wikidata's genre line speaks of the sound, its other lines do not`() {
        assertFalse(silent(line(Scope.SONG, "The genre of “Fajnie” is alternative rock.", SourceId.WIKIDATA)))
        assertFalse(silent(line(Scope.SONG, "The genres of “Fajnie” include rock and blues.", SourceId.WIKIDATA)))
        assertTrue(silent(line(Scope.SONG, "The record label of “Fajnie” is Universal.", SourceId.WIKIDATA)))
        assertTrue(silent(line(Scope.SONG, "“Fajnie” was first published 2008.", SourceId.WIKIDATA)))
    }

    @Test
    fun `the artist's lines are not read, whatever they say`() {
        assertTrue(silent(line(Scope.ARTIST, "He plays rock and blues on the guitar.", SourceId.WIKIPEDIA)))
        assertTrue(silent(line(Scope.ARTIST, "The genre of “Homo Twist” is rock.", SourceId.WIKIDATA)))
    }

    @Test
    fun `no line about the track at all is silence`() {
        assertTrue(SoundEvidence.silent(EvidenceSet.of(MALENCZUK.filter { it.scope == Scope.ARTIST }, JvmHasher)))
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-10-27T00:00:00Z")

        fun line(
            scope: Scope,
            text: String,
            source: SourceId = SourceId.MUSICBRAINZ,
        ) = EvidenceDraft(source, scope, text, null, AT)

        /** The evidence of #664's case: its lines about the track are all templated. */
        val MALENCZUK: List<EvidenceDraft> =
            listOf(
                line(Scope.SONG, "“Fajnie” was written by Maciej Maleńczuk."),
                line(Scope.SONG, "Maciej Maleńczuk performs on this recording of “Fajnie” (guitar, vocals)."),
                line(Scope.ARTIST, "Maciej Maleńczuk is a Polish singer, poet and guitarist.", SourceId.WIKIPEDIA),
                EvidenceDraft(
                    SourceId.LASTFM,
                    Scope.ARTIST,
                    "Last.fm counts 120,000 listeners for Maciej Maleńczuk.",
                    null,
                    AT,
                    aggregate = true,
                ),
                EvidenceDraft(
                    SourceId.LASTFM,
                    Scope.ARTIST,
                    "Last.fm listeners tag Maciej Maleńczuk as: alternative, polish, rock, blues.",
                    null,
                    AT,
                    aggregate = true,
                ),
                line(Scope.RELEASE, "The player reports the release “Fajnie”.", SourceId.PLAYER),
            )
    }
}
