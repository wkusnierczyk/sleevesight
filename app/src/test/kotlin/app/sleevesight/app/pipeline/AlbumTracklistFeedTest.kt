package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbTracklist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The albums' tracklists for the back of Home's cover (#984): asked once per album, only while
 * lookups are allowed and MusicBrainz is on, again on *Retry* after a failure, and dropped by
 * *Clear history*.
 */
class AlbumTracklistFeedTest {
    private val tracklist = MbTracklist("r", emptyList())
    private val asked = mutableListOf<String>()
    private var answer: Lookup<MbTracklist> = Lookup.Found(tracklist)
    private val allowed = MutableStateFlow(true)
    private var on = true

    private fun TestScope.feed(
        epoch: ClearEpoch = ClearEpoch(),
        hold: CompletableDeferred<Unit>? = null,
    ) = AlbumTracklistFeed(
        lookup = { group ->
            asked += group
            hold?.await()
            answer
        },
        scope = backgroundScope,
        musicBrainzOn = { on },
        allowed = allowed,
        epoch = epoch,
    )

    @Test
    fun anAlbumIsAskedOnceAndItsTracklistKept() =
        runTest {
            val feed = feed()
            feed.look("g")
            feed.look("g")
            runCurrent()
            feed.look("g")
            runCurrent()
            assertEquals(listOf("g"), asked)
            assertEquals(TracklistState.Found(tracklist), feed.states.value["g"])
        }

    @Test
    fun nothingIsAskedUntilLookupsAreAllowed() =
        runTest {
            allowed.value = false
            val feed = feed()
            feed.look("g")
            runCurrent()
            assertEquals(emptyList<String>(), asked)
            assertEquals(TracklistState.Looking, feed.states.value["g"])
            allowed.value = true
            runCurrent()
            assertEquals(listOf("g"), asked)
        }

    @Test
    fun withMusicBrainzOffNothingIsAskedOrKept() =
        runTest {
            on = false
            val feed = feed()
            feed.look("g")
            runCurrent()
            assertEquals(emptyList<String>(), asked)
            assertNull(feed.states.value["g"])
            on = true
            feed.look("g")
            runCurrent()
            assertEquals(listOf("g"), asked)
        }

    @Test
    fun aFailureIsAskedAgainOnlyOnRetry() =
        runTest {
            answer = Lookup.Failed("Offline")
            val feed = feed()
            feed.look("g")
            runCurrent()
            assertEquals(TracklistState.Failed, feed.states.value["g"])
            feed.look("g")
            runCurrent()
            assertEquals(1, asked.size)
            answer = Lookup.NotFound
            feed.retry("g")
            runCurrent()
            assertEquals(2, asked.size)
            assertEquals(TracklistState.NotFound, feed.states.value["g"])
            feed.retry("g")
            runCurrent()
            assertEquals(2, asked.size)
        }

    @Test
    fun clearHistoryDropsEveryTracklistAndOneUnderWayKeepsNothing() =
        runTest {
            val epoch = ClearEpoch()
            val hold = CompletableDeferred<Unit>()
            val feed = feed(epoch, hold)
            feed.look("g")
            runCurrent()
            epoch.advance()
            feed.forget()
            hold.complete(Unit)
            runCurrent()
            assertEquals(emptyMap<String, TracklistState>(), feed.states.value)
        }

    @Test
    fun theLeastRecentlyAskedAlbumIsDroppedPastTheCapacity() =
        runTest {
            val feed = feed()
            repeat(AlbumTracklistFeed.CAPACITY + 1) { feed.look("g$it") }
            runCurrent()
            assertNull(feed.states.value["g0"])
            assertEquals(AlbumTracklistFeed.CAPACITY, feed.states.value.size)
        }
}
