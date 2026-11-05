package app.sleevesight.core.evidence

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant

/**
 * A line's language (#902, `EvidenceDraft.language`): carried from draft to line, and part of the
 * fingerprint only when set, so the lines found before it keep their fingerprints and a line in
 * Hindi is never taken for the same text unmarked.
 */
class EvidenceLanguageTest {
    private val at = Instant.parse("2026-11-05T12:00:00Z")

    private fun draft(
        source: SourceId,
        text: String,
        language: String? = null,
        url: String? = null,
    ) = EvidenceDraft(source, Scope.SONG, text, url, at, language = language)

    private fun set(vararg drafts: EvidenceDraft) = EvidenceSet.of(drafts.toList(), JvmHasher)

    // The documented encoding of [lines], each its fields, as `EvidenceSet` spells it.
    private fun encoded(vararg lines: List<String>): String {
        val text =
            lines.joinToString("") { fields -> fields.joinToString("") { "${it.encodeToByteArray().size}:$it" } + "\n" }
        return JvmHasher.sha256(text.encodeToByteArray()).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    @Test
    fun `a line carries its draft's language, and a set made again keeps it`() {
        val evidence = set(draft(SourceId.WIKIPEDIA, "Lead."), draft(SourceId.WIKIPEDIA, "तुम ही हो गाना है।", "hi"))
        assertEquals(listOf(null, "hi"), evidence.lines.map { it.language })
        val more = evidence.with(listOf(draft(SourceId.WEB, "A page.", "hi")), JvmHasher)
        assertEquals(listOf(null, "hi", "hi"), more.lines.map { it.language })
        assertEquals("hi", draft(SourceId.WEB, "x", "hi").copy(text = "y").language)
    }

    @Test
    fun `a line without a language is fingerprinted as before, one with it with its code after the text`() {
        val evidence = set(draft(SourceId.WIKIPEDIA, "Lead."), draft(SourceId.WIKIPEDIA, "तुम ही हो गाना है।", "hi"))
        assertEquals(
            encoded(listOf("W1", "song", "Lead."), listOf("W2", "song", "तुम ही हो गाना है।", "hi")),
            evidence.fingerprint,
        )
        assertEquals(encoded(listOf("W1", "song", "Lead.")), set(draft(SourceId.WIKIPEDIA, "Lead.")).fingerprint)
    }

    @Test
    fun `lines that differ only by their language are different evidence`() {
        val english = set(draft(SourceId.WEB, "Evidências is a song.", url = "https://example.org"))
        val portuguese = set(draft(SourceId.WEB, "Evidências is a song.", "pt", "https://example.org"))
        val spanish = set(draft(SourceId.WEB, "Evidências is a song.", "es", "https://example.org"))
        assertNotEquals(english.fingerprint, portuguese.fingerprint)
        assertNotEquals(portuguese.fingerprint, spanish.fingerprint)
        assertNotEquals(english, portuguese)
    }
}
