package app.sleevesight.core.resolution

import app.sleevesight.core.sources.ArtistRoles
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A classical work's composers the player does not credit (#1298): Spotify credits *Nessun Dorma* to
 * Pavarotti alone, and the aria's work names Puccini its composer. The roles keep him apart from the
 * credited composers ([ArtistRoles.uncredited]), only on a work of a classical form, from the work's
 * composer relationships alone (MusicBrainz's CC0 core data).
 */
class UncreditedComposerTest {
    private val puccini = "Giacomo Puccini"
    private val pavarotti = "Luciano Pavarotti"
    private val tenor = recording("tenor", "Nessun dorma", pavarotti, 180, "1990-07-01")

    private fun work(
        type: String?,
        vararg composers: String,
    ) = MbWork(
        mbid = "w",
        title = "Nessun dorma",
        artistRelations =
            composers.map { MbArtistRelation("composer", "mbid-$it", it, emptyList()) } +
                listOf(
                    MbArtistRelation("librettist", "mbid-adami", "Giuseppe Adami", emptyList()),
                    MbArtistRelation("composer", "mbid-[unknown]", "[unknown]", emptyList()),
                ),
        wikidataIds = emptyList(),
        performances = emptyList(),
        type = type,
    )

    private fun detail(recording: MbRecording) =
        MbRecordingDetail(
            recording.mbid,
            recording.title,
            recording.credits,
            recording.firstReleaseDate,
            listOf(MbWorkLink("w", recording.title, cover = false)),
            emptyList(),
            emptyList(),
        )

    private suspend fun rolesOf(
        artists: String,
        work: MbWork,
    ): ArtistRoles? {
        val musicBrainz =
            FakeMusicBrainz(Lookup.Found(listOf(tenor)), mapOf("tenor" to detail(tenor)), mapOf("w" to work))
        return resolve(musicBrainz, track("Nessun Dorma", artists), 180).roles
    }

    @Test
    fun `an aria credited to its tenor alone has its work's composer, uncredited, apart from the credited`() =
        runTest {
            val roles = rolesOf(pavarotti, work("Aria", puccini))
            val expected = ArtistRoles(emptyList(), listOf(pavarotti), classical = true, uncredited = listOf(puccini))
            assertEquals(expected, roles)
        }

    @Test
    fun `a song's composer the player does not credit is not added, nor one the player credits`() =
        runTest {
            // A song of no song cycle is no classical work (#1199): only the credited count.
            assertEquals(emptyList(), rolesOf(pavarotti, work("Song", puccini))?.uncredited)
            // Credited, Puccini is a composer of the track's, not an uncredited one.
            val credited = rolesOf("$puccini, $pavarotti", work("Aria", puccini))
            assertEquals(listOf(puccini), credited?.composers)
            assertEquals(emptyList(), credited?.uncredited)
        }

    @Test
    fun `a second composer the work names beside the credited one is uncredited, by MusicBrainz's name`() =
        runTest {
            val roles = rolesOf("$puccini, $pavarotti", work("Aria", puccini, "Franco Alfano"))
            assertEquals(listOf(puccini), roles?.composers)
            assertEquals(listOf("Franco Alfano"), roles?.uncredited)
        }

    @Test
    fun `a credit that may name the composer, unresolved, keeps them from being named twice`() =
        runTest {
            // No composer lead in the title: "Puccini" is not MusicBrainz's "Giacomo Puccini" by name, nor
            // resolved to his id, but its words are all his.
            assertEquals(emptyList(), rolesOf("Puccini, $pavarotti", work("Aria", puccini))?.uncredited)
            assertEquals(emptyList(), rolesOf("PUCCÌNI, $pavarotti", work("Aria", puccini))?.uncredited)
            // Words of another's: still uncredited.
            assertEquals(listOf(puccini), rolesOf("Giacomo Rossi, $pavarotti", work("Aria", puccini))?.uncredited)
        }

    @Test
    fun `a composer among the artists of the joint credit the track was accepted under is credited`() =
        runTest {
            // The player's "G. Puccini & Luciano Pavarotti" is MusicBrainz's two artists joined: no one id is
            // the pair's, and its words are not all Puccini's; the joint credit's ids hold his.
            val joint =
                tenor.copy(
                    credits =
                        listOf(
                            MbCredit("G. Puccini", "mbid-$puccini", puccini, " & "),
                            credit(pavarotti),
                        ),
                )
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(listOf(joint)),
                    mapOf("tenor" to detail(joint)),
                    mapOf("w" to work("Aria", puccini)),
                )
            val resolution = resolve(musicBrainz, track("Nessun Dorma", "G. Puccini & $pavarotti"), 180)
            assertEquals("tenor", resolution.recordingMbid)
            assertEquals(listOf("mbid-$puccini", "mbid-$pavarotti"), resolution.jointArtists.map { it.mbid })
            assertEquals(emptyList(), resolution.roles?.uncredited)
        }

    @Test
    fun `a member of an unresolved joint credit that may name the composer keeps them from being named twice`() =
        runTest {
            // "Puccini & Adami" resolves to no one; the recording is found through Pavarotti. Its member
            // "Puccini" is all Giacomo Puccini's words, so he is not added beside it.
            assertEquals(emptyList(), rolesOf("Puccini & Adami, $pavarotti", work("Aria", puccini))?.uncredited)
            assertEquals(emptyList(), rolesOf("Adami / Puccini, $pavarotti", work("Aria", puccini))?.uncredited)
        }
}
