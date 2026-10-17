package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End to end, through the real `Pipeline`: the sequences the third and fourth review rounds of
 * PR #155 found, as `PipelineCoordinatorTest` runs them in its simulated world.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostReviewSequencesTest : PipelineHostFixture() {
    @Test
    fun `consecutive rebuilds while the replay gathers write the track once`() =
        runTest {
            started()
            play("Alpha")
            settle()
            host.write()
            settle()
            musicBrainz.byName = 2.seconds
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            advanceTimeBy(500.milliseconds)
            keyChanges.value++
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(
                InsightState.Idle(canWrite = false),
                host.state.value.track
                    ?.insight,
            )
        }

    @Test
    fun `a rebuild in a newer track's debounce while the old track streams waits for the call, then goes on`() =
        runTest {
            provider.streaming = 10.seconds
            started()
            play("Alpha")
            settle()
            host.write()
            advanceTimeBy(2.seconds)
            play("Bravo")
            advanceTimeBy(100.milliseconds)
            keyChanges.value++
            advanceTimeBy(60.seconds)
            play("Charlie")
            advanceTimeBy(60.seconds)

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(listOf("artist|alpha", "artist|bravo", "artist|charlie"), musicBrainz.asked)
            assertEquals(
                "Charlie",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
        }

    @Test
    fun `a stale run made current again while hidden starts no model call`() =
        runTest {
            started()
            play("Zero")
            settle()
            host.write()
            settle()
            musicBrainz.byName = 2.seconds
            play("Alpha")
            advanceTimeBy(DEBOUNCE + 1.8.seconds)
            play("Bravo")
            advanceTimeBy(300.milliseconds)
            host.activityStopped(changingConfigurations = false)
            play("Alpha")
            settle()

            assertEquals(0, provider.requestsFor("Alpha"))
            assertEquals(0, provider.requestsFor("Bravo"))
        }

    @Test
    fun `a new length of a written track keeps its insight, with one call and one lookup`() =
        runTest {
            started()
            play("Alpha", 106.seconds)
            settle()
            host.write()
            settle()
            // YouTube Music's update: the same title, artists and player, a new length.
            play("Alpha", 279.seconds)
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(listOf("artist|alpha"), musicBrainz.asked)
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
        }

    @Test
    fun `a new length while the insight is being written keeps the call, and then the insight`() =
        runTest {
            provider.streaming = 10.seconds
            started()
            play("Alpha", 106.seconds)
            settle()
            host.write()
            advanceTimeBy(2.seconds)
            play("Alpha", 279.seconds)
            advanceTimeBy(30.seconds)

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(1, provider.completed)
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
        }

    @Test
    fun `a stream's next song under one media id is a new track, written after the first action`() =
        runTest {
            provider.streaming = 10.seconds
            started()
            report(NowPlaying(title = "Song one", artists = "Artist", mediaId = "stream", player = Settings.SPOTIFY))
            settle()
            host.write()
            advanceTimeBy(2.seconds)
            report(NowPlaying(title = "Song two", artists = "Artist", mediaId = "stream", player = Settings.SPOTIFY))
            advanceTimeBy(30.seconds)

            assertEquals(1, provider.requestsFor("Song one"))
            assertEquals(1, provider.requestsFor("Song two"))
            assertEquals(
                "Song two",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
        }

    @Test
    fun `a title first, with nothing found, then its artists is written without a tap`() =
        runTest {
            started()
            play("Zero")
            settle()
            host.write()
            settle()
            report(NowPlaying(title = "Alpha", artists = "", player = Settings.SPOTIFY))
            settle()
            assertEquals(
                InsightState.NoEvidence,
                host.state.value.track
                    ?.insight,
            )
            report(NowPlaying(title = "Alpha", artists = "Artist", player = Settings.SPOTIFY))
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
        }

    @Test
    fun `requests are not allowed before the first run is acknowledged, an activity started or not`() =
        runTest {
            acknowledged.value = false
            started()
            settings.value = settings.value.copy(lookUpInBackground = true)
            runCurrent()
            assertEquals(false, host.lookupsAllowed.first())

            acknowledged.value = true
            runCurrent()
            assertEquals(true, host.lookupsAllowed.first())
        }

    // The insight ledger (the sixth review round).

    @Test
    fun `a held new length, then a rebuild changing nothing in the card's key, shows the card`() =
        runTest {
            cache.keepsResults = true
            started()
            play("Alpha", 106.seconds)
            settle()
            host.write()
            settle()
            play("Alpha", 279.seconds)
            settle()
            keyChanges.value++
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(listOf("artist|alpha"), musicBrainz.asked)
            assertEquals(
                true,
                (
                    host.state.value.track
                        ?.insight as? InsightState.Written
                )?.cached,
            )
        }

    @Test
    fun `a rebuild changing the card's key, then a new length, writes nothing without a tap`() =
        runTest {
            cache.keepsResults = true
            started()
            play("Alpha", 106.seconds)
            settle()
            host.write()
            settle()
            // A Last.fm key: new evidence, so the card's key changes.
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()
            play("Alpha", 280.seconds)
            settle()
            host.write()
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
        }

    @Test
    fun `a card kept, shown, then a new length keeps it, with no lookup or call`() =
        runTest {
            cache.keepsResults = true
            started()
            play("Alpha", 106.seconds)
            settle()
            host.write()
            settle()
            play("Bravo")
            settle()
            // Alpha again: a track change, its card served from the cache.
            play("Alpha", 106.seconds)
            settle()
            assertEquals(
                true,
                (
                    host.state.value.track
                        ?.insight as? InsightState.Written
                )?.cached,
            )
            play("Alpha", 279.seconds)
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(listOf("artist|alpha", "artist|bravo"), musicBrainz.asked)
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
        }

    @Test
    fun `the header follows a held report, and the insight stays`() =
        runTest {
            started()
            report(NowPlaying(title = "Alpha", artists = "Artist", mediaId = "m1", player = Settings.SPOTIFY))
            settle()
            host.write()
            settle()
            report(
                NowPlaying(
                    title = "Alpha",
                    artists = "Artist & Guest",
                    album = "Alpha (Deluxe)",
                    mediaId = "m1",
                    player = Settings.SPOTIFY,
                ),
            )
            settle()

            val shown = host.state.value.track
            assertEquals("Artist & Guest", shown?.nowPlaying?.artists)
            assertEquals("Alpha (Deluxe)", shown?.nowPlaying?.album)
            assertTrue(shown?.insight is InsightState.Written)
            assertEquals(1, provider.requestsFor("Alpha"))
        }
}
