package app.sleevesight.store

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            )
        val told = result.copy(roles = roles)
        assertEquals(told, RetrievalCodec.decode(RetrievalCodec.encode(told)))
        // A pop song's: no composer, every artist a performer.
        val pop = result.copy(roles = ArtistRoles(emptyList(), listOf("Writer", "Guest")))
        assertEquals(pop, RetrievalCodec.decode(RetrievalCodec.encode(pop)))
        // None told, or kept before #1180: no field, read as none.
        val none = RetrievalCodec.encode(result)
        assertFalse("roles" in none)
        assertNull(assertNotNull(RetrievalCodec.decode(none)).roles)
    }
}
