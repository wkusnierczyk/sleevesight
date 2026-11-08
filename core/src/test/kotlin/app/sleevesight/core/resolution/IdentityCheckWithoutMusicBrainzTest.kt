package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.IdentityCheck.Route
import app.sleevesight.core.resolution.IdentityCheck.Verdict
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which rejections are only for want of what MusicBrainz resolves (#1069,
 * [Verdict.Rejected.withoutMusicBrainz]): *Take California* by Propellerheads, with MusicBrainz
 * unreachable, over a hand-written Wikidata.
 */
class IdentityCheckWithoutMusicBrainzTest {
    private val track = Normalizer(JvmAccentFolder).normalize("Take California", "Propellerheads")

    private class FakeWikidata(
        val entities: Map<String, WdEntity>,
        val claims: Map<String, List<String>>,
    ) : WikidataLookup {
        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            Lookup.Found(entities.filterKeys { it in ids })

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> = Lookup.Found(emptyMap())

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = Lookup.Found(claims[id].orEmpty())

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> = Lookup.Found(emptyList())
    }

    // The song's item: a musical work naming no performer, so nothing ties it to the band by name.
    private val song =
        WdEntity("Q135926731", "Take California", "Take California", mapOf(Wd.INSTANCE_OF to listOf("Q105543609")))
    private val band = WdEntity("Q1", "Propellerheads", "Propellerheads", mapOf(Wd.INSTANCE_OF to listOf("Q215380")))
    private val list = WdEntity("Q2", "Propellerheads", null, mapOf(Wd.INSTANCE_OF to listOf(Wd.DISAMBIGUATION)))
    private val album = WdEntity("Q3", "Decksandrumsandrockandroll", null, mapOf(Wd.INSTANCE_OF to listOf("Q482994")))
    private val check =
        IdentityCheck(
            FakeWikidata(
                listOf(song, band, list, album).associateBy { it.id },
                mapOf("Q1" to listOf("mbid-band")),
            ),
            NameMatcher(JvmAccentFolder),
        )

    private val unreached = Resolution(outcome = Outcome.Unreachable)

    @Test
    fun `an artist with no MusicBrainz id to confirm its article is rejected for want of MusicBrainz`() =
        runTest {
            assertEquals(
                Verdict.Rejected(
                    "Q1",
                    "no MusicBrainz id for Propellerheads to confirm “Propellerheads”",
                    withoutMusicBrainz = true,
                ),
                check.artist("Q1", ResolvedArtist("Propellerheads", null)),
            )
        }

    @Test
    fun `an artist rejected against its MusicBrainz id, or a disambiguation page, is not for want of MusicBrainz`() =
        runTest {
            val other = check.artist("Q1", ResolvedArtist("Propellerheads", "mbid-other"))
            assertEquals(false, (other as Verdict.Rejected).withoutMusicBrainz)
            val disambiguation = check.artist("Q2", ResolvedArtist("Propellerheads", null))
            assertEquals(false, (disambiguation as Verdict.Rejected).withoutMusicBrainz)
        }

    @Test
    fun `a song found by name with no recording from MusicBrainz is rejected for want of MusicBrainz`() =
        runTest {
            val verdict = check.song(song.id, track, unreached, Route.NAME)
            assertEquals(
                Verdict.Rejected(
                    song.id,
                    "“Take California” names no performer or writer credited on the track",
                    withoutMusicBrainz = true,
                ),
                verdict,
            )
        }

    @Test
    fun `a song rejected with a MusicBrainz recording, or an item that is no song, is not for want of it`() =
        runTest {
            val resolved =
                Resolution(
                    outcome = Outcome.SongArticle,
                    recordingMbid = "r",
                    artists = listOf(ResolvedArtist("Propellerheads", "mbid-band")),
                )
            val withRecording = check.song(song.id, track, resolved, Route.NAME)
            assertEquals(false, (withRecording as Verdict.Rejected).withoutMusicBrainz)
            val notSong = check.song(album.id, track, unreached, Route.NAME)
            assertEquals(false, (notSong as Verdict.Rejected).withoutMusicBrainz)
        }
}
