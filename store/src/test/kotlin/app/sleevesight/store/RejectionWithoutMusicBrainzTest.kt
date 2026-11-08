package app.sleevesight.store

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A rejection only for want of what MusicBrainz resolves (#1069), as the retrieval cache keeps it. */
class RejectionWithoutMusicBrainzTest {
    private val reason = "no MusicBrainz id for Propellerheads to confirm “Propellerheads”"

    private fun result(withoutMusicBrainz: Boolean) =
        SourceResult(
            Outcome.Rejected(reason),
            emptyList(),
            12.milliseconds,
            rejections = listOf(Rejection("Propellerheads", reason, Scope.ARTIST, withoutMusicBrainz)),
        )

    @Test
    fun `a rejection for want of MusicBrainz round-trips with its mark`() {
        val marked = result(withoutMusicBrainz = true)
        val encoded = RetrievalCodec.encode(marked)
        assertTrue("\"withoutMusicBrainz\":true" in encoded)
        assertEquals(marked, RetrievalCodec.decode(encoded))
    }

    @Test
    fun `an entry kept before the mark decodes as before, unmarked`() {
        // An entry as kept before #1069, with no such field.
        val before =
            "{\"outcome\":\"rejected\",\"reason\":\"$reason\",\"latency\":\"PT0.012S\"," +
                "\"rejections\":[{\"subject\":\"Propellerheads\",\"reason\":\"$reason\",\"scope\":\"artist\"}]}"
        val decoded = RetrievalCodec.decode(before)
        assertEquals(result(withoutMusicBrainz = false), decoded)
        assertFalse(decoded!!.rejections.single().withoutMusicBrainz)
        // One encoded unmarked leaves the field out, as before.
        assertEquals(before, RetrievalCodec.encode(result(withoutMusicBrainz = false)))
    }
}
