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
