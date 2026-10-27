package app.sleevesight.core.evidence

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What *Look again* counts as new (#617): a line that says what no earlier line says, by source,
 * scope and text with its spacing evened out; never the order, the ids, or lines missing.
 */
class LineContentTest {
    private val at = Instant.parse("2026-10-27T12:00:00Z")

    private fun set(vararg lines: Pair<SourceId, String>): EvidenceSet =
        EvidenceSet.of(lines.map { (source, text) -> EvidenceDraft(source, Scope.SONG, text, null, at) }, JvmHasher)

    private val earlier =
        set(SourceId.MUSICBRAINZ to "Recorded in 1973.", SourceId.WIKIPEDIA to "It topped the charts.")

    @Test
    fun `the same lines in another order or spacing bring nothing new`() {
        val again =
            set(SourceId.WIKIPEDIA to "  It topped\n the  charts. ", SourceId.MUSICBRAINZ to "Recorded in 1973.")
        assertFalse(again.bringsNewSince(earlier))
    }

    @Test
    fun `case, curly quotes, dashes and Unicode spaces bring nothing new`() {
        val earlier = set(SourceId.WIKIPEDIA to "It is \"Sundown\" - a hit.")
        val again = set(SourceId.WIKIPEDIA to "it\u00A0is \u201CSundown\u201D \u2013 a hit.")
        assertFalse(again.bringsNewSince(earlier))
    }

    @Test
    fun `counts and lists of listeners bring nothing new when only their numbers or order change`() {
        fun counted(vararg texts: String) =
            EvidenceSet.of(
                texts.map { EvidenceDraft(SourceId.LASTFM, Scope.ARTIST, it, null, at, aggregate = true) },
                JvmHasher,
            )
        val earlier = counted("Last.fm counts 683,942 listeners for X.", "Last.fm listeners tag X as: folk, rock.")
        assertFalse(
            counted(
                "Last.fm counts 690,001 listeners for X.",
                "Last.fm listeners tag X as: rock, folk.",
            ).bringsNewSince(earlier),
        )
        assertTrue(counted("Last.fm listeners tag X as: rock, jazz.").bringsNewSince(earlier))
        // Unmarked, the same count would be new.
        assertTrue(
            set(SourceId.LASTFM to "Last.fm counts 690,001 listeners for X.").bringsNewSince(
                set(
                    SourceId.LASTFM to "Last.fm counts 683,942 listeners for X.",
                ),
            ),
        )
    }

    @Test
    fun `fewer lines bring nothing new`() {
        assertFalse(set(SourceId.MUSICBRAINZ to "Recorded in 1973.").bringsNewSince(earlier))
        assertFalse(set().bringsNewSince(earlier))
    }

    @Test
    fun `a line with other words, or from another source, is new`() {
        assertTrue(set(SourceId.MUSICBRAINZ to "Recorded in 1974.").bringsNewSince(earlier))
        assertTrue(set(SourceId.LASTFM to "Recorded in 1973.").bringsNewSince(earlier))
    }
}
