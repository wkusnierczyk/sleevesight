package app.sleevesight.core.resolution

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * An album's tracklist for the back of Home's cover (#984): the release group's earliest official
 * release, looked up with its tracks; a group id that is a release's own looked up as the release;
 * and both questions answered once by the remembered MusicBrainz answers.
 */
class AlbumTracklistsTest {
    // MusicBrainz with [groups]' releases and [releases]' tracklists, each question counted.
    private class Fake(
        val groups: Map<String, Lookup<List<MbRelease>>>,
        val releases: Map<String, MbTracklist>,
        val official: Map<String, Lookup<List<MbRelease>>> = emptyMap(),
    ) : MusicBrainzLookup {
        val asked = mutableListOf<String>()

        override suspend fun officialReleases(mbid: String): Lookup<List<MbRelease>> {
            asked += "official $mbid"
            return official[mbid] ?: Lookup.Found(emptyList())
        }

        override suspend fun groupReleases(mbid: String): Lookup<List<MbRelease>> {
            asked += "group $mbid"
            return groups[mbid] ?: Lookup.NotFound
        }

        override suspend fun tracklist(mbid: String): Lookup<MbTracklist> {
            asked += "release $mbid"
            return releases[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound
        }

        override suspend fun searchRecordings(
            title: String,
            artist: String,
        ): Lookup<List<MbRecording>> = Lookup.NotFound

        override suspend fun searchRecordingsBefore(
            title: String,
            artist: String,
            date: String,
        ): Lookup<List<MbRecording>> = Lookup.NotFound

        override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> = Lookup.NotFound

        override suspend fun recordingReleases(mbid: String): Lookup<MbRecording> = Lookup.NotFound

        override suspend fun work(mbid: String): Lookup<MbWork> = Lookup.NotFound

        override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> = Lookup.NotFound
    }

    private fun release(
        mbid: String,
        date: String?,
        status: String?,
    ) = MbRelease(mbid, "Kind of Blue", date, null, status = status)

    private fun tracks(
        mbid: String,
        vararg titles: String,
    ) = MbTracklist(
        mbid,
        listOf(MbMedium(1, titles.mapIndexed { i, title -> MbTrack(i + 1, title, null, "rec-$title") })),
    )

    @Test
    fun `the earliest official release is chosen, an undated one after every dated one`() {
        val chosen =
            AlbumTracklists.choose(
                listOf(
                    release("undated", null, "Official"),
                    release("bootleg", "1958", "Bootleg"),
                    release("reissue", "1997-03", "Official"),
                    release("first", "1959-08-17", "Official"),
                    release("promo", "1959-01", "Promotion"),
                ),
            )
        assertEquals("first", chosen?.mbid)
    }

    @Test
    fun `with no official release, the earliest with no status, never a bootleg or promotion`() {
        assertEquals(
            "unset",
            AlbumTracklists
                .choose(
                    listOf(release("bootleg", "1958", "Bootleg"), release("unset", "1960", null)),
                )?.mbid,
        )
        assertNull(AlbumTracklists.choose(listOf(release("promo", "1959", "Promotion"))))
    }

    @Test
    fun `a group's tracklist is its chosen release's`() =
        runTest {
            val fake =
                Fake(
                    mapOf(
                        "group" to
                            Lookup.Found(
                                listOf(release("later", "1997", "Official"), release("first", "1959", "Official")),
                            ),
                    ),
                    mapOf("first" to tracks("first", "So What", "Freddie Freeloader")),
                )
            val found = assertIs<Lookup.Found<MbTracklist>>(AlbumTracklists(fake).of("group"))
            assertEquals(
                listOf("So What", "Freddie Freeloader"),
                found.value.media
                    .single()
                    .tracks
                    .map { it.title },
            )
            assertEquals(listOf("group group", "release first"), fake.asked)
        }

    @Test
    fun `with none of the 25 listed official, the earliest official release beyond them`() =
        runTest {
            val listed = (1..25).map { release("unset$it", "1960-01-${it.toString().padStart(2, '0')}", null) }
            val fake =
                Fake(
                    mapOf("group" to Lookup.Found(listed)),
                    mapOf("official" to tracks("official", "So What")),
                    mapOf(
                        "group" to
                            Lookup.Found(
                                listOf(release("later", "1999", "Official"), release("official", "1961", "Official")),
                            ),
                    ),
                )
            assertIs<Lookup.Found<MbTracklist>>(AlbumTracklists(fake).of("group"))
            assertEquals(listOf("group group", "official group", "release official"), fake.asked)
        }

    @Test
    fun `with no official release anywhere, the earliest listed with no status`() =
        runTest {
            val fake =
                Fake(
                    mapOf(
                        "group" to
                            Lookup.Found(
                                listOf(release("unset", "1960", null), release("b", "1958", "Bootleg")),
                            ),
                    ),
                    mapOf("unset" to tracks("unset", "So What")),
                )
            assertIs<Lookup.Found<MbTracklist>>(AlbumTracklists(fake).of("group"))
            assertEquals(listOf("group group", "official group", "release unset"), fake.asked)
            val failing =
                Fake(
                    mapOf("group" to Lookup.Found(listOf(release("unset", "1960", null)))),
                    emptyMap(),
                    mapOf("group" to Lookup.Failed("Offline")),
                )
            assertEquals(Lookup.Failed("Offline"), AlbumTracklists(failing).of("group"))
        }

    @Test
    fun `an id that is no group's is looked up as a release's own`() =
        runTest {
            val fake = Fake(emptyMap(), mapOf("release" to tracks("release", "Airbag")))
            assertIs<Lookup.Found<MbTracklist>>(AlbumTracklists(fake).of("release"))
            assertEquals(listOf("group release", "release release"), fake.asked)
        }

    @Test
    fun `no release to show, or one with no track, is not found, and a failure stays one`() =
        runTest {
            val none = Fake(mapOf("group" to Lookup.Found(listOf(release("b", "1959", "Bootleg")))), emptyMap())
            assertEquals(Lookup.NotFound, AlbumTracklists(none).of("group"))
            val empty =
                Fake(
                    mapOf("group" to Lookup.Found(listOf(release("r", "1959", "Official")))),
                    mapOf("r" to MbTracklist("r", listOf(MbMedium(1, emptyList())))),
                )
            assertEquals(Lookup.NotFound, AlbumTracklists(empty).of("group"))
            val failing = Fake(mapOf("group" to Lookup.Failed("Offline")), emptyMap())
            assertEquals(Lookup.Failed("Offline"), AlbumTracklists(failing).of("group"))
            assertEquals(listOf("group group"), failing.asked)
        }

    @Test
    fun `the remembered answers ask each question once, and again after Clear history`() =
        runTest {
            val fake =
                Fake(
                    mapOf("group" to Lookup.Found(listOf(release("first", "1959", "Official")))),
                    mapOf("first" to tracks("first", "So What")),
                )
            val remembered = RememberedMusicBrainz(fake)
            val tracklists = AlbumTracklists(remembered)
            tracklists.of("group")
            tracklists.of("group")
            assertEquals(listOf("group group", "release first"), fake.asked)
            remembered.forget()
            tracklists.of("group")
            assertEquals(4, fake.asked.size)
        }
}
