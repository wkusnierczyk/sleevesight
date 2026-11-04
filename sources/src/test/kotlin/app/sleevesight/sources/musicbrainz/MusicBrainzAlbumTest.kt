package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.resolution.Lookup
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the MusicBrainz client gives an album's insight (#522, #885): a release group with its
 * releases' ids, and the group a release is of. The answers are written for the test in the shape
 * of MusicBrainz's `release-group/<id>?inc=artist-credits+url-rels+releases` and
 * `release/<id>?inc=release-groups` (ids made up, `00000000-…`); not MusicBrainz's data.
 */
class MusicBrainzAlbumTest {
    @Test
    fun `a release group keeps its releases' ids, asked for with its credit and links`() =
        runTest {
            val net = ReplayNetClient.ok(GROUP)
            val album = assertIs<Lookup.Found<MbAlbum>>(MusicBrainzClient(net).releaseGroup(GROUP_ID)).value
            assertEquals(listOf(RELEASE_1, RELEASE_2), album.releaseIds)
            assertEquals("Sundown", album.title)
            val request = net.sent.single()
            assertEquals("https://musicbrainz.org/ws/2/release-group/$GROUP_ID", request.url)
            assertEquals(MusicBrainzClient.ALBUM_INC, request.query["inc"])
            assertEquals("artist-credits+url-rels+releases", MusicBrainzClient.ALBUM_INC)
        }

    @Test
    fun `a release group with no releases listed has none`() =
        runTest {
            val body = """{"id":"$GROUP_ID","title":"Sundown","primary-type":"Album","secondary-types":[]}"""
            val album = assertIs<Lookup.Found<MbAlbum>>(MusicBrainzClient(ReplayNetClient.ok(body)).releaseGroup("g"))
            assertEquals(emptyList(), album.value.releaseIds)
        }

    @Test
    fun `a release's group, by the release's id`() =
        runTest {
            val net = ReplayNetClient.ok(RELEASE)
            assertEquals(Lookup.Found(GROUP_ID), MusicBrainzClient(net).groupOfRelease(RELEASE_2))
            val request = net.sent.single()
            assertEquals("https://musicbrainz.org/ws/2/release/$RELEASE_2", request.url)
            assertEquals("release-groups", request.query["inc"])
        }

    @Test
    fun `a release MusicBrainz does not know is not found, and one with no group failed`() =
        runTest {
            val client = { net: ReplayNetClient -> MusicBrainzClient(net) }
            assertEquals(Lookup.NotFound, client(ReplayNetClient.status(404, "{}")).groupOfRelease("r"))
            assertIs<Lookup.Failed>(
                client(ReplayNetClient.ok("""{"id":"$RELEASE_2","title":"Sundown"}""")).groupOfRelease("r"),
            )
            assertIs<Lookup.Failed>(client(ReplayNetClient.status(503, "{}")).groupOfRelease("r"))
        }

    private companion object {
        const val GROUP_ID = "00000000-0000-4000-8000-0000000000a1"
        const val RELEASE_1 = "00000000-0000-4000-8000-0000000000b1"
        const val RELEASE_2 = "00000000-0000-4000-8000-0000000000b2"

        const val GROUP =
            """{"id":"$GROUP_ID","title":"Sundown","primary-type":"Album","secondary-types":[],
              "first-release-date":"1974-01",
              "artist-credit":[{"name":"Gordon Lightfoot","joinphrase":"",
                "artist":{"id":"00000000-0000-4000-8000-0000000000c1","name":"Gordon Lightfoot"}}],
              "releases":[{"id":"$RELEASE_1","title":"Sundown"},{"id":"$RELEASE_2","title":"Sundown"}]}"""

        const val RELEASE =
            """{"id":"$RELEASE_2","title":"Sundown",
              "release-group":{"id":"$GROUP_ID","title":"Sundown","primary-type":"Album"}}"""
    }
}
