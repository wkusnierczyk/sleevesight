package app.sleevesight.store

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ComposedDate
import app.sleevesight.core.sources.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** When the music was composed (#1132, `SourceResult.composed`) kept with MusicBrainz's and Wikipedia's results. */
class RetrievalComposedTest {
    private val result = SourceResult(Outcome.SongArticle, emptyList(), 120.milliseconds)

    @Test
    fun `the composition date round-trips with its source, and an entry kept without it reads as none`() {
        val work = "https://musicbrainz.org/work/876aa6e9-0a0a-3408-bbfa-0565582cf8e0"
        val musicBrainz = result.copy(composed = ComposedDate("1921", "1924", SourceId.MUSICBRAINZ, work))
        assertEquals(musicBrainz, RetrievalCodec.decode(RetrievalCodec.encode(musicBrainz)))
        val item = "https://www.wikidata.org/wiki/Q1"
        val wikidata = result.copy(composed = ComposedDate("1788", "1788", SourceId.WIKIDATA, item))
        assertEquals(wikidata, RetrievalCodec.decode(RetrievalCodec.encode(wikidata)))
        // None dated, or kept before #1132: no field, read as none.
        val none = RetrievalCodec.encode(result)
        assertFalse("composed" in none)
        assertNull(assertNotNull(RetrievalCodec.decode(none)).composed)
    }
}
