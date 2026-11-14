// Lengths and dates are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.ArtistRoles
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The composer told from the performers once resolved (#1180, `design/classical.md`, section 2): a
 * credited artist the recording's work names its composer, and whom the recording does not have, is the
 * composer; the others are performers. Where the work tells nothing (none, a failed lookup, no composer
 * relationship), the title's composer lead stands ([ArtistRoles.of]).
 */
class RecordingRolesTest {
    private val puccini = "Giacomo Puccini"
    private val pavarotti = "Luciano Pavarotti"
    private val mehta = "Zubin Mehta"
    private val orchestra = "Los Angeles Philharmonic"
    private val nessunDorma = "Turandot: \"Nessun dorma!\""
    private val aria = track(nessunDorma, "$puccini, $pavarotti, $mehta, $orchestra")

    // MusicBrainz's: credited to the tenor alone, the conductor and the orchestra in its relationships.
    private val tenor = recording("tenor", "Turandot: “Nessun dorma!”", pavarotti, 204, "1994-08-29")

    private fun detail(
        recording: MbRecording,
        relations: List<MbArtistRelation> = emptyList(),
        work: String? = "w",
    ) = MbRecordingDetail(
        recording.mbid,
        recording.title,
        recording.credits,
        recording.firstReleaseDate,
        listOfNotNull(work?.let { MbWorkLink(it, recording.title, cover = false) }),
        relations,
        emptyList(),
    )

    private fun work(
        vararg composers: String,
        type: String? = "Aria",
    ) = MbWork(
        mbid = "w",
        title = "Nessun dorma",
        artistRelations = composers.map { MbArtistRelation("composer", "mbid-$it", it, emptyList()) },
        wikidataIds = emptyList(),
        performances = emptyList(),
        type = type,
    )

    private fun relation(
        type: String,
        name: String,
    ) = MbArtistRelation(type, "mbid-$name", name, emptyList())

    @Test
    fun `the work's composer, not on the recording, is the composer, the other three the performers`() =
        runTest {
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(listOf(tenor)),
                    mapOf(
                        "tenor" to
                            detail(
                                tenor,
                                listOf(relation("conductor", mehta), relation("performing orchestra", orchestra)),
                            ),
                    ),
                    mapOf("w" to work(puccini)),
                )
            val resolution = resolve(musicBrainz, aria, 204)
            assertEquals("tenor", resolution.recordingMbid)
            assertEquals(ArtistRoles(listOf(puccini), listOf(pavarotti, mehta, orchestra)), resolution.roles)
            assertEquals(pavarotti, ArtistRoles.of(aria, resolution.roles).lead(aria))
        }

    @Test
    fun `a pop song's writer who sings it is a performer, and no one a composer`() =
        runTest {
            val song = track("Song", "Writer, Guest")
            val both =
                recording(
                    "both",
                    "Song",
                    "Writer",
                    200,
                    "1990",
                ).copy(credits = listOf(credit("Writer"), credit("Guest")))
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(listOf(both)),
                    mapOf("both" to detail(both)),
                    mapOf(
                        "w" to work("Writer", type = "Song"),
                    ),
                )
            val roles = resolve(musicBrainz, song).roles
            assertEquals(ArtistRoles(emptyList(), listOf("Writer", "Guest")), roles)
            assertEquals("Writer", ArtistRoles.of(song, roles).lead(song))
        }

    @Test
    fun `a title's composer whose resolution has no usable work keeps the title's roles`() =
        runTest {
            val mozart = "Wolfgang Amadeus Mozart"
            val k545 = track("Mozart: Andante", "$mozart, Pianist, Orchestra")
            val ours =
                recording(
                    "ours",
                    "Andante",
                    "Pianist",
                    200,
                    "2024",
                ).copy(credits = listOf(credit("Pianist"), credit("Orchestra")))
            val title = ArtistRoles(listOf(mozart), listOf("Pianist", "Orchestra"))
            // No work linked; the work's lookup failed; a work with no composer relationship.
            val cases =
                listOf(
                    FakeMusicBrainz(Lookup.Found(listOf(ours)), mapOf("ours" to detail(ours, work = null))),
                    FakeMusicBrainz(Lookup.Found(listOf(ours)), mapOf("ours" to detail(ours)), workFailure = "Timeout"),
                    FakeMusicBrainz(Lookup.Found(listOf(ours)), mapOf("ours" to detail(ours)), mapOf("w" to work())),
                )
            for (musicBrainz in cases) {
                val resolution = resolve(musicBrainz, k545)
                assertEquals("ours", resolution.recordingMbid)
                assertNull(resolution.roles)
                // The composer is not made a performer, nor the performers all the credited artists.
                assertEquals(title, ArtistRoles.of(k545, resolution.roles))
            }
        }

    @Test
    fun `a title's composer on the recording performs, and one the work does not name still composed`() =
        runTest {
            // `Band: Song` by Band and Guest, Band on the recording and the work's writer: no composer.
            val band = track("Band: Song", "Band, Guest")
            val withGuest =
                recording(
                    "g",
                    "Band: Song",
                    "Band",
                    200,
                    "1990",
                ).copy(credits = listOf(credit("Band"), credit("Guest")))
            val bandWrote =
                FakeMusicBrainz(
                    Lookup.Found(listOf(withGuest)),
                    mapOf("g" to detail(withGuest)),
                    mapOf(
                        "w" to work("Band", type = "Song"),
                    ),
                )
            assertEquals(ArtistRoles(emptyList(), listOf("Band", "Guest")), resolve(bandWrote, band).roles)
            // `Liszt: …` whose work MusicBrainz credits to its arranger alone: Liszt, not on the recording,
            // stays the composer beside the arranger.
            val liszt = track("Liszt: Etude", "Franz Liszt, Pianist")
            val ours = recording("p", "Etude", "Pianist", 200, "2000")
            val arranged =
                FakeMusicBrainz(Lookup.Found(listOf(ours)), mapOf("p" to detail(ours)), mapOf("w" to work("Arranger")))
            assertEquals(ArtistRoles(listOf("Franz Liszt"), listOf("Pianist")), resolve(arranged, liszt).roles)
        }

    @Test
    fun `nothing that fits by the composer credited first, a performer's search finds the recording`() =
        runTest {
            // Puccini's search gives other recordings of the aria, none of this length.
            val other = recording("other", "Turandot: “Nessun dorma!”", puccini, 170, "1960")
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(listOf(other)),
                    mapOf("tenor" to detail(tenor)),
                    mapOf("w" to work(puccini)),
                ).apply { searchesBy[pavarotti] = Lookup.Found(listOf(tenor)) }
            val resolution = resolve(musicBrainz, aria, 204)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("tenor", resolution.recordingMbid)
            assertEquals(listOf(nessunDorma to puccini, nessunDorma to pavarotti), musicBrainz.searched)
            assertEquals(listOf(puccini), resolution.roles?.composers)
        }

    @Test
    fun `a performer's search takes only recordings crediting no one but the track's artists`() =
        runTest {
            // The tenor's recording with another orchestra and conductor is not this one.
            val elsewhere =
                recording("elsewhere", tenor.title, pavarotti, 201, "1991")
                    .copy(credits = listOf(credit(pavarotti), credit("Royal Philharmonic Orchestra")))
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(emptyList()),
                    mapOf("tenor" to detail(tenor)),
                    mapOf("w" to work(puccini)),
                ).apply { searchesBy[pavarotti] = Lookup.Found(listOf(elsewhere, tenor)) }
            assertEquals("tenor", resolve(musicBrainz, aria, 204).recordingMbid)
        }

    @Test
    fun `a failed search by a later artist is MusicBrainz unreachable`() =
        runTest {
            val musicBrainz =
                FakeMusicBrainz(Lookup.Found(emptyList())).apply { searchesBy[pavarotti] = Lookup.Failed("Timeout") }
            assertEquals(Outcome.Unreachable, resolve(musicBrainz, aria, 204).outcome)
            assertEquals(listOf(nessunDorma to puccini, nessunDorma to pavarotti), musicBrainz.searched)
        }

    @Test
    fun `a classical work's composer the recording credits, with no performer relationship, is the composer`() =
        runTest {
            // MusicBrainz often credits the composer on a classical recording (a release imported from a
            // streaming service's artists): only a performer relationship shows that they play.
            val mozart = "Wolfgang Amadeus Mozart"
            val k545 = track("Mozart: Andante", "$mozart, Pianist")
            val both =
                recording(
                    "both",
                    "Andante",
                    mozart,
                    200,
                    "2024",
                ).copy(credits = listOf(credit(mozart), credit("Pianist")))
            val sonata =
                FakeMusicBrainz(
                    Lookup.Found(listOf(both)),
                    mapOf("both" to detail(both, listOf(relation("instrument", "Pianist")))),
                    mapOf("w" to work(mozart, type = "Sonata")),
                )
            assertEquals(ArtistRoles(listOf(mozart), listOf("Pianist")), resolve(sonata, k545).roles)
            // With no lead in the title: *Nessun dorma* credited to Puccini and the tenor on MusicBrainz.
            val credited =
                tenor.copy(mbid = "credited", credits = listOf(credit(puccini), credit(pavarotti)))
            val composerCredited =
                FakeMusicBrainz(
                    Lookup.Found(listOf(credited)),
                    mapOf(
                        "credited" to
                            detail(
                                credited,
                                listOf(relation("conductor", mehta), relation("performing orchestra", orchestra)),
                            ),
                    ),
                    mapOf("w" to work(puccini)),
                )
            assertEquals(
                ArtistRoles(listOf(puccini), listOf(pavarotti, mehta, orchestra)),
                resolve(composerCredited, aria, 204).roles,
            )
        }

    @Test
    fun `after a later artist's search, the recording crediting the most of the track's artists is chosen`() =
        runTest {
            // The tenor's 1980 recording credited to him alone is earlier, but the 1994 one credits the
            // conductor and the orchestra the player credits too.
            val alone = tenor.copy(mbid = "alone", firstReleaseDate = "1980", releases = emptyList())
            val all = tenor.copy(credits = listOf(credit(pavarotti), credit(mehta), credit(orchestra)))
            val musicBrainz =
                FakeMusicBrainz(Lookup.Found(emptyList()), mapOf("tenor" to detail(all)), mapOf("w" to work(puccini)))
                    .apply { searchesBy[pavarotti] = Lookup.Found(listOf(alone, all)) }
            assertEquals("tenor", resolve(musicBrainz, aria, 204).recordingMbid)
        }

    @Test
    fun `after a later artist's search, of recordings crediting the soloist alone, the one naming the others plays`() =
        runTest {
            // MusicBrainz credits both to the tenor alone; only the 1994 one's relationships name the
            // conductor and the orchestra the player credits.
            val alone = tenor.copy(mbid = "alone", firstReleaseDate = "1980", releases = emptyList())
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(emptyList()),
                    mapOf(
                        "alone" to detail(alone, listOf(relation("conductor", "Another Conductor"))),
                        "tenor" to
                            detail(
                                tenor,
                                listOf(relation("conductor", mehta), relation("performing orchestra", orchestra)),
                            ),
                    ),
                    mapOf("w" to work(puccini)),
                ).apply { searchesBy[pavarotti] = Lookup.Found(listOf(alone, tenor)) }
            assertEquals("tenor", resolve(musicBrainz, aria, 204).recordingMbid)
            // Without a later artist's search the earliest stays chosen, as before: by the tenor alone.
            val first = FakeMusicBrainz(Lookup.Found(listOf(alone, tenor)), mapOf("alone" to detail(alone)))
            assertEquals("alone", resolve(first, track(nessunDorma, "$pavarotti, $mehta"), 204).recordingMbid)
        }
}
