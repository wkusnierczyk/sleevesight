package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbGroupFirst
import app.sleevesight.core.resolution.MbRelease
import app.sleevesight.core.resolution.MbTracklist
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the MusicBrainz client gives the back of Home's cover (#984): a release group's releases with
 * their dates and statuses, and a release's tracklist by medium. The answers are written for the test
 * in the shape of MusicBrainz's `release-group/<id>?inc=releases` and `release/<id>?inc=recordings`
 * (ids made up); not MusicBrainz's data.
 */
class MusicBrainzTracklistTest {
    @Test
    fun `a group's releases, each with its date and status`() =
        runTest {
            val net = ReplayNetClient.ok(GROUP)
            val releases = assertIs<Lookup.Found<List<MbRelease>>>(MusicBrainzClient(net).groupReleases("g1")).value
            assertEquals(listOf("r1", "r2"), releases.map { it.mbid })
            assertEquals(listOf("1959-08-17", null), releases.map { it.date })
            assertEquals(listOf("Official", null), releases.map { it.status })
            val request = net.sent.single()
            assertEquals("https://musicbrainz.org/ws/2/release-group/g1", request.url)
            assertEquals("releases", request.query["inc"])
        }

    @Test
    fun `a release's tracklist, by medium, each track's length its own or its recording's`() =
        runTest {
            val net = ReplayNetClient.ok(RELEASE)
            val tracklist = assertIs<Lookup.Found<MbTracklist>>(MusicBrainzClient(net).tracklist("r1")).value
            assertEquals(listOf(1, 2), tracklist.media.map { it.position })
            val first = tracklist.media.first().tracks
            assertEquals(listOf("So What", "Blue in Green"), first.map { it.title })
            assertEquals(listOf(562_000L, 337_000L), first.map { it.lengthMs })
            assertEquals(listOf("rec1", "rec2"), first.map { it.recordingMbid })
            assertEquals(listOf(null), tracklist.media[1].tracks.map { it.lengthMs })
            val request = net.sent.single()
            assertEquals("https://musicbrainz.org/ws/2/release/r1", request.url)
            assertEquals("recordings", request.query["inc"])
        }

    @Test
    fun `a group's official releases, browsed by status, page after page up to the cap`() =
        runTest {
            val page = (1..100).joinToString(",") { """{"id":"o$it","title":"Kind of Blue","status":"Official"}""" }
            val net = ReplayNetClient.ok("""{"release-count":250,"release-offset":0,"releases":[$page]}""")
            val releases = assertIs<Lookup.Found<List<MbRelease>>>(MusicBrainzClient(net).officialReleases("g1")).value
            assertEquals(300, releases.size)
            assertEquals(listOf("0", "100", "200"), net.sent.map { it.query["offset"] })
            val request = net.sent.first()
            assertEquals("https://musicbrainz.org/ws/2/release", request.url)
            assertEquals("g1", request.query["release-group"])
            assertEquals("official", request.query["status"])
            assertEquals("100", request.query["limit"])
        }

    @Test
    fun `a group whose official releases fit one page is asked once`() =
        runTest {
            val net =
                ReplayNetClient.ok(
                    """{"release-count":1,"releases":[{"id":"o1","title":"K","status":"Official"}]}""",
                )
            val releases = assertIs<Lookup.Found<List<MbRelease>>>(MusicBrainzClient(net).officialReleases("g1")).value
            assertEquals(listOf("o1"), releases.map { it.mbid })
            assertEquals(1, net.sent.size)
            assertEquals(Lookup.NotFound, MusicBrainzClient(ReplayNetClient.status(404, "{}")).officialReleases("x"))
        }

    @Test
    fun `a group's own title and first-release date, for Dates`() =
        runTest {
            // #1099: the group's lookup with no inclusion, its first release over all its releases.
            val net = ReplayNetClient.ok("""{"id":"g1","title":"Kind of Blue","first-release-date":"1959-08-17"}""")
            assertEquals(
                Lookup.Found(MbGroupFirst("g1", "Kind of Blue", "1959-08-17")),
                MusicBrainzClient(net).groupFirst("g1"),
            )
            val request = net.sent.single()
            assertEquals("https://musicbrainz.org/ws/2/release-group/g1", request.url)
            assertEquals(null, request.query["inc"])
            // An empty date is none.
            val undated = ReplayNetClient.ok("""{"id":"g1","title":"Kind of Blue","first-release-date":""}""")
            assertEquals(
                Lookup.Found(MbGroupFirst("g1", "Kind of Blue", null)),
                MusicBrainzClient(undated).groupFirst("g1"),
            )
            assertEquals(Lookup.NotFound, MusicBrainzClient(ReplayNetClient.status(404, "{}")).groupFirst("x"))
        }

    @Test
    fun `an id MusicBrainz does not know is not found`() =
        runTest {
            assertEquals(Lookup.NotFound, MusicBrainzClient(ReplayNetClient.status(404, "{}")).groupReleases("x"))
            assertEquals(Lookup.NotFound, MusicBrainzClient(ReplayNetClient.status(404, "{}")).tracklist("x"))
        }

    private companion object {
        val GROUP =
            """
            {"id":"g1","title":"Kind of Blue","releases":[
              {"id":"r1","title":"Kind of Blue","date":"1959-08-17","status":"Official"},
              {"id":"r2","title":"Kind of Blue","date":"","status":null}
            ]}
            """.trimIndent()

        val RELEASE =
            """
            {"id":"r1","title":"Kind of Blue","media":[
              {"position":1,"tracks":[
                {"position":1,"title":"So What","length":562000,"recording":{"id":"rec1","title":"So What"}},
                {"position":2,"title":"Blue in Green","recording":{"id":"rec2","title":"Blue in Green","length":337000}}
              ]},
              {"position":2,"tracks":[
                {"position":1,"title":"Flamenco Sketches","length":null,"recording":{"id":"rec3"}}
              ]}
            ]}
            """.trimIndent()
    }
}
