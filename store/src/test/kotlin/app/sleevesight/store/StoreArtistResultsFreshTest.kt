package app.sleevesight.store

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.FreshLookups
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The artist cache under work that looks its sources up afresh (*Look again*, #617,
 * [FreshLookups]): it reads nothing, so the adapters ask their sources, and keeps what they find.
 */
class StoreArtistResultsFreshTest {
    private val fixture = CacheFixture()
    private val keeping = ArtistKeeping.over({ fixture.caches }, EmptyCoroutineContext) { true }
    private val artists = StoreArtistResults({ fixture.caches }, EmptyCoroutineContext, keeping)

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `work that looks afresh reads nothing, and what it keeps is read after`() =
        runBlocking {
            artists.keep(PART, "gordon lightfoot", "Bio.")
            assertEquals("Bio.", artists.get(PART, "gordon lightfoot")?.value)

            withContext(FreshLookups) {
                assertNull(artists.get(PART, "gordon lightfoot"))
                artists.keep(PART, "gordon lightfoot", "A new bio.")
            }

            assertEquals("A new bio.", artists.get(PART, "gordon lightfoot")?.value)
        }

    private companion object {
        val PART = ArtistPart("lastfm.artist", 1, 1, setOf(SourceId.LASTFM))
    }
}
