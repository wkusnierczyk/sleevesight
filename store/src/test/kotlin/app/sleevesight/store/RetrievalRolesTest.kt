package app.sleevesight.store

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** MusicBrainz's roles of the credited artists (#1180, `SourceResult.roles`) kept with its result. */
class RetrievalRolesTest {
    private val result = SourceResult(Outcome.SongArticle, emptyList(), 120.milliseconds)

    @Test
    fun `the roles round-trip, and an entry kept without them reads as none told`() {
        val roles =
            ArtistRoles(
                listOf("Giacomo Puccini"),
                listOf("Luciano Pavarotti", "Zubin Mehta", "Los Angeles Philharmonic"),
                classical = true,
            )
        val told = result.copy(roles = roles)
        // Whether the work is a classical one too (#1199).
        assertEquals(told, RetrievalCodec.decode(RetrievalCodec.encode(told)))
        // A pop song's: no composer, every artist a performer.
        val pop = result.copy(roles = ArtistRoles(emptyList(), listOf("Writer", "Guest")))
        assertEquals(pop, RetrievalCodec.decode(RetrievalCodec.encode(pop)))
        // Kept before #1199: no classical field, read as not classical.
        val encoded = RetrievalCodec.encode(told)
        assertTrue("\"classical\":true" in encoded)
        val before = encoded.replace(",\"classical\":true", "")
        assertEquals(false, assertNotNull(RetrievalCodec.decode(before)).roles?.classical)
        // None told, or kept before #1180: no field, read as none.
        val none = RetrievalCodec.encode(result)
        assertFalse("roles" in none)
        assertNull(assertNotNull(RetrievalCodec.decode(none)).roles)
    }
}
