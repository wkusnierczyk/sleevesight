package app.sleevesight.store

import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.StatedGenre
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** The genres Wikidata states (#1098, `SourceResult.statedGenres`) kept with Wikipedia's result. */
class RetrievalStatedGenresTest {
    private val result = SourceResult(Outcome.SongArticle, emptyList(), 120.milliseconds)

    @Test
    fun `the stated genres round-trip with their levels, and an entry kept without them reads as none`() {
        val stated =
            result.copy(
                statedGenres =
                    listOf(
                        StatedGenre("blue-eyed soul", GenreLevel.TRACK, null, "https://www.wikidata.org/wiki/Q1"),
                        StatedGenre(
                            "soul music",
                            GenreLevel.ARTIST,
                            "Hall & Oates",
                            "https://www.wikidata.org/wiki/Q2",
                        ),
                    ),
            )
        assertEquals(stated, RetrievalCodec.decode(RetrievalCodec.encode(stated)))
        val none = RetrievalCodec.encode(result)
        assertFalse("statedGenres" in none)
        assertEquals(emptyList(), assertNotNull(RetrievalCodec.decode(none)).statedGenres)
        // A level this version does not know: the entry cannot be read, a miss.
        val unknown = RetrievalCodec.encode(stated).replace("\"TRACK\"", "\"SIDE\"")
        assertNull(RetrievalCodec.decode(unknown))
    }
}
