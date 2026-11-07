package app.sleevesight.store

import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

class GenreFamilyDroppedTest {
    @Test
    fun `an entry kept with each label's genre family decodes without it`() {
        // Before #1020 each label kept its genre family; the field is gone, and such an entry is
        // still read, its labels as they were, rather than a miss.
        val labels =
            listOf(
                GenreLabel("folk rock", "Folk Rock", "folkrock", "https://www.last.fm/music/A", artist = "A"),
                GenreLabel("instrumental", "Instrumental", "instrumental", beyondCap = true),
            )
        val result = SourceResult(Outcome.SongArticle, emptyList(), 12.milliseconds, genres = labels)
        val now = RetrievalCodec.encode(result)
        assertFalse("family" in now)
        val families = listOf("folk", "score").iterator()
        val before = Regex("\"tag\":\"[^\"]*\",").replace(now) { "${it.value}\"family\":\"${families.next()}\"," }
        assertFalse(families.hasNext())
        assertEquals(result, RetrievalCodec.decode(before))
    }
}
