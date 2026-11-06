package app.sleevesight.sources

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Evidence lines as the artist-level table keeps them: what a line is about and its language only when set (#939). */
class KeptDraftsTest {
    private val at = Instant.parse("2026-11-05T12:00:00Z")

    @Test
    fun `a line with neither what it is about nor a language is encoded as before`() {
        val line = EvidenceDraft(SourceId.WEB, Scope.WEB, "A page.", "https://example.com", at)
        assertEquals(
            """[{"s":"WEB","c":"web","t":"A page.","u":"https://example.com","a":${at.toEpochMilliseconds()}}]""",
            KeptDrafts.encode(listOf(line)),
        )
        assertEquals(listOf(line), KeptDrafts.decode(KeptDrafts.encode(listOf(line))))
    }

    @Test
    fun `what a line is about and its language are kept with it`() {
        val line =
            EvidenceDraft(SourceId.WIKIPEDIA, Scope.RELEASE, "《BE》는 앨범이다.", null, at, about = "BE", language = "ko")
        assertEquals(listOf(line), KeptDrafts.decode(KeptDrafts.encode(listOf(line))))
    }
}
