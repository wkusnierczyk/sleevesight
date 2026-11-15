package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbFoundWork
import app.sleevesight.core.resolution.canonicalWork
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.testing.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The work searches of #1299 as MusicBrainz answers them, replayed from `fixtures/http/musicbrainz-1299/`
 * (and Barber's from `musicbrainz-1289/`), each weighed for the piece itself ([canonicalWork]): of the many
 * works of a much-played piece's title, its arrangements, versions and catch-alls are never taken.
 */
class MusicBrainzWorkSearchTest {
    private val client = MusicBrainzClient(ReplayNetClient("musicbrainz-1299", "musicbrainz-1289"))
    private val names = RecordedTracks.names

    private suspend fun found(
        title: String,
        composer: String,
    ): List<MbFoundWork> = assertIs<Lookup.Found<List<MbFoundWork>>>(client.searchWorks(title, composer)).value

    private fun List<MbFoundWork>.only(mbid: String) = filter { it.mbid.startsWith(mbid) }

    @Test
    fun `Barber's Adagio for Strings is the piece, not its catch-all or arrangements`() =
        runTest {
            val works = found("Adagio for Strings", "Samuel Barber")
            assertEquals(5, works.size)
            assertEquals(12, works.single { it.mbid.startsWith("1ebe1abf") }.aliases)
            assertEquals(
                "1ebe1abf-daf2-3e5e-b3ad-b6c5a4ec5727",
                names.canonicalWork("Adagio for Strings", "Samuel Barber", works)?.mbid,
            )
        }

    @Test
    fun `Fur Elise takes no work - Rasquin's production music version and Gray's arrangement are none`() =
        runTest {
            val works = found("Für Elise", "Ludwig van Beethoven")
            // The piece is titled `Bagatelle for Piano in A minor, WoO 59 “Für Elise”: Poco moto`, which the
            // track's title does not fit; of the works titled `Für Elise`, none is the piece.
            assertNull(names.canonicalWork("Für Elise", "Ludwig van Beethoven", works))
            // Rasquin composed his too, and Gray arranged his.
            assertNull(names.canonicalWork("Für Elise", "Ludwig van Beethoven", works.only("32d34921")))
            assertNull(names.canonicalWork("Für Elise", "Ludwig van Beethoven", works.only("3d9e2225")))
        }

    @Test
    fun `Eine kleine Nachtmusik takes no work - Kreisler's song with his lyrics is none`() =
        runTest {
            val works = found("Eine kleine Nachtmusik", "Wolfgang Amadeus Mozart")
            assertNull(names.canonicalWork("Eine kleine Nachtmusik", "Wolfgang Amadeus Mozart", works))
            val kreisler = works.only("4e997d06")
            assertEquals("with lyrics by Georg Kreisler", kreisler.single().disambiguation)
            assertNull(names.canonicalWork("Eine kleine Nachtmusik", "Wolfgang Amadeus Mozart", kreisler))
        }

    @Test
    fun `Bolero is the ballet, not Ravel's own arrangement for two pianos`() =
        runTest {
            val works = found("Boléro", "Maurice Ravel")
            // The ballet (13 aliases) is the one left: the catch-all, the arrangements, the other versions and
            // the editions are known by two names at most, or credit an arranger or another composer.
            assertEquals(BOLERO, names.canonicalWork("Boléro", "Maurice Ravel", works)?.mbid)
            assertEquals(BOLERO, names.canonicalWork("Boléro", "Maurice Ravel", works.only("487527b9"))?.mbid)
            // Arranged by Ravel himself, known by two names.
            assertNull(names.canonicalWork("Boléro", "Maurice Ravel", works.only("0d202827")))
        }

    private companion object {
        const val BOLERO = "487527b9-a525-33f4-b948-ea41b9a2672f"
    }
}
