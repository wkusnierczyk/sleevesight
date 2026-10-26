package app.sleevesight.core.resolution

import app.sleevesight.core.pipeline.ClearEpoch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * MusicBrainz's answers kept for a short time (D13, #20): the question asked ahead, by name, is
 * not asked again when the track plays, and the recording is still chosen by the player's length.
 */
class RememberedMusicBrainzTest {
    // MusicBrainz as the fake answers it, counting each question it is asked.
    private class Counting(
        private val inner: MusicBrainzLookup,
    ) : MusicBrainzLookup {
        val asked = mutableListOf<String>()
        var failing = false

        /** While set, a search waits for it before it answers. */
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun searchRecordings(
            title: String,
            artist: String,
        ): Lookup<List<MbRecording>> {
            asked += "search $title"
            hold?.await()
            return if (failing) Lookup.Failed("unreachable") else inner.searchRecordings(title, artist)
        }

        override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> {
            asked += "recording $mbid"
            return inner.recording(mbid)
        }

        override suspend fun work(mbid: String): Lookup<MbWork> {
            asked += "work $mbid"
            return inner.work(mbid)
        }

        override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> {
            asked += "artists $name"
            return Lookup.Found(MbArtistSearch(1, listOf(MbArtist("mbid-$name", name))))
        }
    }

    // Two recordings of the song: the earlier at 400 s, a later one at 200 s.
    private val search =
        Lookup.Found(
            listOf(
                recording("long", "Song", "Artist", lengthSeconds = 400, date = "1970"),
                recording("short", "Song", "Artist", lengthSeconds = 200, date = "1980"),
            ),
        )
    private val time = TestTimeSource()
    private val counting = Counting(FakeMusicBrainz(search))
    private val remembered = RememberedMusicBrainz(counting, time, lifetime = 30.minutes, capacity = 2)

    @Test
    fun `a question asked again soon is answered from memory`() =
        runTest {
            remembered.searchRecordings("Song", "Artist")
            remembered.searchRecordings("Song", "Artist")
            remembered.searchArtists("Artist")
            remembered.searchArtists("Artist")
            assertEquals(listOf("search Song", "artists Artist"), counting.asked)
        }

    @Test
    fun `an answer to work started before a clear is not kept when it arrives`() =
        runTest {
            val epoch = ClearEpoch()
            val gated = RememberedMusicBrainz(counting, time, lifetime = 30.minutes, capacity = 2, epoch = epoch)
            val hold = CompletableDeferred<Unit>()
            counting.hold = hold
            val asking = async(epoch.mark()) { gated.searchRecordings("Song", "Artist") }
            runCurrent()

            epoch.advance()
            gated.forget()
            hold.complete(Unit)
            asking.await()
            counting.hold = null
            gated.searchRecordings("Song", "Artist")

            assertEquals(listOf("search Song", "search Song"), counting.asked)
        }

    @Test
    fun `forgetting asks every question again`() =
        runTest {
            remembered.searchRecordings("Song", "Artist")
            remembered.searchArtists("Artist")
            remembered.forget()
            remembered.searchRecordings("Song", "Artist")
            remembered.searchArtists("Artist")
            assertEquals(listOf("search Song", "artists Artist", "search Song", "artists Artist"), counting.asked)
        }

    @Test
    fun `an answer not obtained is not kept, and one MusicBrainz gave is, found or not`() =
        runTest {
            counting.failing = true
            remembered.searchRecordings("Song", "Artist")
            counting.failing = false
            remembered.searchRecordings("Song", "Artist")
            remembered.recording("unknown")
            remembered.recording("unknown")
            assertEquals(listOf("search Song", "search Song", "recording unknown"), counting.asked)
        }

    @Test
    fun `a recording kept without its tags is not served once they are wanted`() =
        runTest {
            var tags = false
            val byTags = RememberedMusicBrainz(counting, time, lifetime = 30.minutes, capacity = 2) { tags }
            byTags.recording("short")
            byTags.recording("short")
            tags = true
            byTags.recording("short")
            byTags.recording("short")
            assertEquals(listOf("recording short", "recording short"), counting.asked)
        }

    @Test
    fun `an answer is kept for its lifetime only`() =
        runTest {
            remembered.searchRecordings("Song", "Artist")
            time += 29.minutes
            remembered.searchRecordings("Song", "Artist")
            time += 1.minutes
            remembered.searchRecordings("Song", "Artist")
            assertEquals(listOf("search Song", "search Song"), counting.asked)
        }

    @Test
    fun `past its capacity, the least recently used answer goes first`() =
        runTest {
            remembered.recording("a")
            remembered.recording("b")
            remembered.recording("a")
            remembered.recording("c")
            remembered.recording("a")
            remembered.recording("b")
            assertEquals(listOf("recording a", "recording b", "recording c", "recording b"), counting.asked)
        }

    @Test
    fun `a track resolved ahead by name is resolved again by its length, with no new search`() =
        runTest {
            val track = track("Song", "Artist")
            val ahead = resolve(remembered, track, seconds = null)
            val playing = resolve(remembered, track, seconds = 200)
            assertEquals("long", ahead.recordingMbid)
            assertEquals("short", playing.recordingMbid)
            assertEquals(1, counting.asked.count { it.startsWith("search") })
            assertEquals(resolve(FakeMusicBrainz(search), track, seconds = 200), playing)
        }
}
