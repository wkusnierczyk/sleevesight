package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Whether the sources say anything about how the track sounds (#664), decided in code. */
class SoundEvidenceTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private fun silent(vararg lines: EvidenceDraft) =
        SoundEvidence.silent(EvidenceSet.of(MALENCZUK + lines, JvmHasher), vocabulary)

    @Test
    fun `the case of the issue says nothing about the sound`() {
        // Maciej Maleńczuk, *Fajnie*: the writer, the listeners' counts and tags, the artist's
        // article (a guitarist, which is the artist's, not the track's), the player's release.
        assertTrue(silent())
    }

    @Test
    fun `a line about the track that names an instrument or a genre speaks of the sound`() {
        assertFalse(silent(line(Scope.SONG, "Maciej Maleńczuk performs on this recording of “Fajnie” (guitar).")))
        assertFalse(silent(line(Scope.SONG, "It is a blues song with a slow groove.", SourceId.WIKIPEDIA)))
        assertFalse(silent(line(Scope.WEB, "A hip-hop track from the Kraków scene.", SourceId.WEB)))
        assertFalse(silent(line(Scope.ORIGINAL_SONG, "The original was an acoustic ballad.", SourceId.WIKIPEDIA)))
    }

    @Test
    fun `a title, the artist's lines and the listeners' tags do not`() {
        // A title in quotation marks names a genre, but says nothing of the sound.
        assertTrue(silent(line(Scope.SONG, "“Rock and Roll Blues” was written by Maciej Maleńczuk.")))
        // The artist's other work is another matter than how this track sounds.
        assertTrue(silent(line(Scope.ARTIST, "He plays rock and blues on the guitar.", SourceId.WIKIPEDIA)))
        // The tags are where the label itself comes from.
        assertTrue(
            silent(
                EvidenceDraft(SourceId.LASTFM, Scope.SONG, "Last.fm listeners tag it as: rock.", null, AT, true),
            ),
        )
    }

    @Test
    fun `a word is matched whole, not inside another`() {
        // "bassoon" is no bass, "Sounders" no sound, "rocket" no rock.
        assertTrue(silent(line(Scope.SONG, "It was played at a Sounders match, before a rocket launch and a bassoon.")))
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-10-27T00:00:00Z")

        fun line(
            scope: Scope,
            text: String,
            source: SourceId = SourceId.MUSICBRAINZ,
        ) = EvidenceDraft(source, scope, text, null, AT)

        /** The evidence of #664's case, as its lines' kinds came: no line about the track's sound. */
        val MALENCZUK: List<EvidenceDraft> =
            listOf(
                line(Scope.SONG, "“Fajnie” was written by Maciej Maleńczuk."),
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
