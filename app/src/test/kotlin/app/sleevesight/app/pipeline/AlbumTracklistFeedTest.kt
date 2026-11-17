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
    private val g = TracklistKey("g", "rec")
    private val asked = mutableListOf<String?>()
    private var answer: Lookup<MbTracklist>? = Lookup.Found(tracklist)
    private val allowed = MutableStateFlow(true)
    private var on = true

    private val albumsAsked = mutableListOf<AlbumsKey>()
    private var albumsAnswer: Lookup<List<NamedAlbum>> = Lookup.Found(listOf(NamedAlbum("g-a", tracklist)))
    private val store = AlbumChoiceStore.InMemory(mapOf("kept" to AlbumChoice("g-kept", listOf("g-kept", "g-other"))))

    private fun TestScope.feed(
        epoch: ClearEpoch = ClearEpoch(),
        hold: CompletableDeferred<Unit>? = null,
    ) = AlbumTracklistFeed(
        lookup = { key ->
            asked += key.group
            hold?.await()
            answer
        },
        scope = backgroundScope,
        musicBrainzOn = { on },
        allowed = allowed,
        epoch = epoch,
        albumsLookup = { key ->
            albumsAsked += key
            albumsAnswer
        },
        choiceStore = store,
    )

    @Test
    fun aLookupAheadSupersededIsCancelledAndForgottenSoTheNextTracksIsNotHeldBehindIt() =
        runTest {
            // #729: a skip while the skipped track's release is still asked.
            val hold = CompletableDeferred<Unit>()
            val feed = feed(hold = hold)
            val skipped = TracklistKey("g-skipped", "rec-1")
            feed.lookAhead(skipped)
            runCurrent()
            assertEquals(TracklistState.Looking, feed.states.value[skipped])
            hold.complete(Unit)
            // The next track's look ahead gives it up before its answer lands.
            feed.lookAhead(g)
            runCurrent()
            assertNull(feed.states.value[skipped])
            assertEquals(TracklistState.Found(tracklist), feed.states.value[g])
            // Asked again later, it is asked anew.
            feed.lookAhead(skipped)
            runCurrent()
            assertEquals(TracklistState.Found(tracklist), feed.states.value[skipped])
            assertEquals(listOf("g-skipped", "g", "g-skipped"), asked)
        }

    @Test
    fun aLookupAheadTheCoverTurnedToIsNotGivenUp() =
        runTest {
            val hold = CompletableDeferred<Unit>()
            val feed = feed(hold = hold)
            feed.lookAhead(g)
            runCurrent()
            feed.look(g)
            val next = TracklistKey("g-next", "rec-2")
            feed.lookAhead(next)
            hold.complete(Unit)
            runCurrent()
            assertEquals(TracklistState.Found(tracklist), feed.states.value[g])
            assertEquals(listOf("g", "g-next"), asked)
        }

    @Test
    fun aLookupAheadThatEndedIsKept() =
        runTest {
            val feed = feed()
            feed.lookAhead(g)
            runCurrent()
            feed.lookAhead(TracklistKey("g-next", "rec-2"))
            runCurrent()
            assertEquals(TracklistState.Found(tracklist), feed.states.value[g])
        }

    @Test
    fun aLookupAheadAsksAgainAfterAFailure() =
        runTest {
            answer = Lookup.Failed("Offline")
            val feed = feed()
            feed.lookAhead(g)
            runCurrent()
            assertEquals(TracklistState.Failed, feed.states.value[g])
            answer = Lookup.Found(tracklist)
            feed.lookAhead(g)
            runCurrent()
            assertEquals(TracklistState.Found(tracklist), feed.states.value[g])
            assertEquals(listOf("g", "g"), asked)
        }

    @Test
    fun theAlbumsOfOneNameAreAskedOnceAndAgainOnlyOnRetryAfterAFailure() =
        runTest {
            // #1080: on the listener's tap, all of them in one lookup.
            val key = AlbumsKey(listOf("g-a", "g-b"), "rec")
            albumsAnswer = Lookup.Failed("Offline")
            val feed = feed()
            feed.lookAlbums(key)
            runCurrent()
            assertEquals(AlbumsState.Failed, feed.albums.value[key])
            feed.lookAlbums(key)
            runCurrent()
            assertEquals(1, albumsAsked.size)
            albumsAnswer = Lookup.Found(listOf(NamedAlbum("g-a", tracklist)))
            feed.retryAlbums(key)
            runCurrent()
            assertEquals(AlbumsState.Found(listOf(NamedAlbum("g-a", tracklist))), feed.albums.value[key])
            feed.retryAlbums(key)
            runCurrent()
            assertEquals(2, albumsAsked.size)
        }

    @Test
    fun aChoiceIsKeptOnThePhoneUntilChangedAndClearHistoryForgetsIt() =
        runTest {
            val feed = feed()
            runCurrent()
            // Those kept before are read at the start.
            assertEquals(mapOf("kept" to AlbumChoice("g-kept", listOf("g-kept", "g-other"))), feed.choices.value)
            val choice = AlbumChoice("g-2006", listOf("g-2001", "g-2006"))
            feed.choose("greatest hits", choice)
            assertEquals(choice, feed.choices.value["greatest hits"])
            assertEquals(choice, store.kept["greatest hits"])
            // *Let Sleevesight decide*.
            feed.choose("greatest hits", null)
            assertNull(feed.choices.value["greatest hits"])
            assertNull(store.kept["greatest hits"])
            feed.choose("greatest hits", choice.copy(group = "g-2001"))
            feed.lookAlbums(AlbumsKey(listOf("g-a", "g-b"), null))
            runCurrent()
            feed.forget()
            assertEquals(emptyMap<String, AlbumChoice>(), feed.choices.value)
            assertEquals(emptyMap<String, AlbumChoice>(), store.kept)
            assertEquals(emptyMap<AlbumsKey, AlbumsState>(), feed.albums.value)
        }

    @Test
    fun anAlbumIsAskedOnceAndItsTracklistKept() =
        runTest {
            val feed = feed()
            feed.look(g)
            feed.look(g)
            runCurrent()
            feed.look(g)
            runCurrent()
            assertEquals(listOf("g"), asked)
            assertEquals(TracklistState.Found(tracklist), feed.states.value[g])
        }

    @Test
    fun nothingIsAskedUntilLookupsAreAllowed() =
        runTest {
            allowed.value = false
            val feed = feed()
            feed.look(g)
            runCurrent()
            assertEquals(emptyList<String>(), asked)
            assertEquals(TracklistState.Looking, feed.states.value[g])
            allowed.value = true
            runCurrent()
            assertEquals(listOf("g"), asked)
        }

    @Test
    fun withMusicBrainzOffNothingIsAskedOrKept() =
        runTest {
            on = false
            val feed = feed()
            feed.look(g)
            runCurrent()
            assertEquals(emptyList<String>(), asked)
            assertNull(feed.states.value[g])
            on = true
            feed.look(g)
            runCurrent()
            assertEquals(listOf("g"), asked)
        }

    @Test
    fun aFailureIsAskedAgainOnlyOnRetry() =
        runTest {
            answer = Lookup.Failed("Offline")
            val feed = feed()
            feed.look(g)
            runCurrent()
            assertEquals(TracklistState.Failed, feed.states.value[g])
            feed.look(g)
            runCurrent()
            assertEquals(1, asked.size)
            answer = Lookup.NotFound
            feed.retry(g)
            runCurrent()
            assertEquals(2, asked.size)
            assertEquals(TracklistState.NotFound, feed.states.value[g])
            feed.retry(g)
            runCurrent()
            assertEquals(2, asked.size)
        }

    @Test
    fun anAlbumOfSeveralThatTheRecordingTellsNotApartIsUnclear() =
        runTest {
            // #1078: the lookup answers none of the tied groups.
            answer = null
            val tied = TracklistKey(null, "rec", listOf("g-a", "g-b"))
            val feed = feed()
            feed.look(tied)
            runCurrent()
            assertEquals(TracklistState.Unclear, feed.states.value[tied])
            feed.look(tied)
            runCurrent()
            assertEquals(1, asked.size)
        }

    @Test
    fun clearHistoryDropsEveryTracklistAndOneUnderWayKeepsNothing() =
        runTest {
            val epoch = ClearEpoch()
            val hold = CompletableDeferred<Unit>()
            val feed = feed(epoch, hold)
            feed.look(g)
            runCurrent()
            epoch.advance()
            feed.forget()
            hold.complete(Unit)
            runCurrent()
            assertEquals(emptyMap<TracklistKey, TracklistState>(), feed.states.value)
        }

    @Test
    fun theLeastRecentlyAskedAlbumIsDroppedPastTheCapacity() =
        runTest {
            val feed = feed()
            repeat(AlbumTracklistFeed.CAPACITY + 1) { feed.look(TracklistKey("g$it")) }
            runCurrent()
            assertNull(feed.states.value[TracklistKey("g0")])
            assertEquals(AlbumTracklistFeed.CAPACITY, feed.states.value.size)
        }
}
