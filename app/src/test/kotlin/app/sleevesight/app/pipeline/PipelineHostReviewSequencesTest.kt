package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
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
            // The card paid for stays, with Write beside it to rewrite it from the new evidence.
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
            assertTrue(host.state.value.rewrite)
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
    fun `a rebuild changing the card's key, then a new length, keeps the card, and rewrites only on a tap`() =
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
            assertEquals(1, provider.requestsFor("Alpha"))
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
            assertTrue(host.state.value.rewrite)

            // A tap is the user asking: exactly one more call.
            host.write()
            settle()
            assertEquals(2, provider.requestsFor("Alpha"))
        }

    @Test
    fun `a written track whose card cannot be read offers Write alone, and writes only on a tap`() =
        runTest {
            // The call fails, so no card was ever shown: written in the ledger, nothing to read.
            provider.failure = IllegalStateException("a bug")
            started()
            play("Alpha")
            settle()
            host.write()
            settle()
            provider.failure = null
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
            assertEquals(false, host.state.value.rewrite)
            host.write()
            settle()
            assertEquals(2, provider.requestsFor("Alpha"))
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

    // The card kept for a written track (the seventh review round): shown whenever its run has no
    // insight of its own, with Write beside it only when a rewrite is offered.

    private suspend fun kotlinx.coroutines.test.TestScope.writtenAlpha() {
        started()
        play("Alpha")
        settle()
        host.write()
        settle()
        assertEquals(1, provider.requestsFor("Alpha"))
    }

    private fun shownWritten(): Boolean =
        host.state.value.track
            ?.insight is InsightState.Written

    @Test
    fun `the AI key removed after a write keeps the card shown, with no Write`() =
        runTest {
            writtenAlpha()
            keys = PipelineKeys()
            keyChanges.value++
            settle()

            assertTrue(shownWritten())
            assertEquals(false, host.state.value.rewrite)
            assertEquals(1, provider.requestsFor("Alpha"))
        }

    @Test
    fun `every source switched off after a write keeps the card shown`() =
        runTest {
            writtenAlpha()
            settings.value = settings.value.copy(enabledSources = emptySet())
            keyChanges.value++
            settle()

            assertTrue(shownWritten())
            assertEquals(false, host.state.value.rewrite)
        }

    @Test
    fun `the card stays shown while the replay gathers, and Write joins it once offered`() =
        runTest {
            writtenAlpha()
            lastFm.byName = 10.seconds
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            advanceTimeBy(2.seconds)
            assertTrue(
                host.state.value.track
                    ?.gathered == false,
            )
            assertTrue(shownWritten())
            assertEquals(false, host.state.value.rewrite)

            // Last.fm lands (10 s), the card is not found under the new evidence: Write offered.
            advanceTimeBy(15.seconds)
            assertTrue(shownWritten())
            assertTrue(host.state.value.rewrite)
        }

    @Test
    fun `a rewrite whose call fails brings the card back, with no retry`() =
        runTest {
            writtenAlpha()
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()
            provider.failure = IllegalStateException("a bug")
            host.write()
            settle()
            settle()

            assertEquals(2, provider.requestsFor("Alpha"))
            assertTrue(shownWritten())
            assertEquals(false, host.state.value.rewrite)
        }

    @Test
    fun `a card kept lives as long as its ledger entry, so back to a track with no card, nothing kept is shown`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            writtenAlpha()
            play("Bravo")
            settle()
            cache.clearCards()
            play("Alpha")
            settle()
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()

            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
            assertEquals(false, host.state.value.rewrite)
        }

    @Test
    fun `back to a written track at another length after another track is a new decision`() =
        runTest {
            // As on `dev-1.0`, and as D5 allows: a track change, written under the default after
            // the first action. The card kept is served only when the return's key matches it.
            cache.keepsResults = true
            started()
            play("Alpha", 106.seconds)
            settle()
            host.write()
            settle()
            play("Alpha", 279.seconds)
            settle()
            play("Bravo")
            settle()
            play("Alpha", 279.seconds)
            settle()

            assertEquals(2, provider.requestsFor("Alpha"))
        }

    // The card paid for lives in the ledger, reported by its run (the eighth review round).

    private fun shownText(): String? =
        (
            host.state.value.track
                ?.insight as? InsightState.Written
        )?.grounded?.text

    @Test
    fun `a rewrite's card survives the AI key removed straight after it`() =
        runTest {
            provider.reply = { n -> "It is a song, take $n [MB1]." }
            writtenAlpha()
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()
            assertEquals("It is a song, take 1 [MB1].", shownText())
            assertTrue(host.state.value.rewrite)

            // The rewrite completes, and the AI key goes at once, before anything else runs.
            host.write()
            runCurrent()
            keys = PipelineKeys(sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()

            assertEquals(2, provider.requestsFor("Alpha"))
            assertEquals("It is a song, take 2 [MB1].", shownText())
        }

    @Test
    fun `a track back after another, its card gone, shows its own failure, not the old card`() =
        runTest {
            writtenAlpha()
            play("Bravo")
            settle()
            cache.clearCards()
            provider.failure = IllegalStateException("a bug")
            // Alpha again, at the same length: a new decision, written (the first action made).
            play("Alpha")
            settle()

            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Failed,
            )
            assertEquals(false, host.state.value.rewrite)
        }

    // The card rules (the ninth review round).

    @Test
    fun `a reply kept as NO_EVIDENCE is written, so a new length after its cache hit is held`() =
        runTest {
            provider.reply = { "NO_EVIDENCE" }
            started()
            play("Alpha", 106.seconds)
            settle()
            host.write()
            settle()
            assertEquals(
                InsightState.NoEvidence,
                host.state.value.track
                    ?.insight,
            )
            play("Bravo")
            settle()
            // Alpha again: its reply served from the cache, which is written, with no card.
            play("Alpha", 106.seconds)
            settle()
            play("Alpha", 279.seconds)
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
        }

    @Test
    fun `the newest paid card survives a later cache hit of an older one`() =
        runTest {
            provider.reply = { n -> "It is a song, take $n [MB1]." }
            writtenAlpha()
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()
            host.write()
            settle()
            assertEquals("It is a song, take 2 [MB1].", shownText())

            // Back to the first evidence: the cache serves take 1, which does not replace take 2.
            keys = PipelineKeys(ai = Secret("ai"))
            keyChanges.value++
            settle()
            assertEquals("It is a song, take 1 [MB1].", shownText())
            // The AI key removed: the card paid for, shown in place of none, is the newest.
            keys = PipelineKeys()
            keyChanges.value++
            settle()

            assertEquals(2, provider.requestsFor("Alpha"))
            assertEquals("It is a song, take 2 [MB1].", shownText())
        }

    @Test
    fun `an old run's call that ends after its track returned brings nothing back, and the return decides afresh`() =
        runTest {
            provider.reply = { n -> "It is a song, take $n [MB1]." }
            provider.streaming = 10.seconds
            started()
            play("Alpha", 106.seconds)
            settle()
            host.write()
            advanceTimeBy(1.seconds)
            // A rebuild waits for the call; Bravo settles, then Alpha returns, at another length,
            // and settles.
            keyChanges.value++
            play("Bravo")
            advanceTimeBy(DEBOUNCE + 100.milliseconds)
            play("Alpha", 279.seconds)
            advanceTimeBy(DEBOUNCE + 100.milliseconds)
            // The old call ends: its card does not bring back its entry (under the old length); the
            // return gets its own run and decision, written after the first action.
            advanceTimeBy(30.seconds)

            assertEquals(2, provider.requestsFor("Alpha"))
            assertEquals("It is a song, take 2 [MB1].", shownText())
            assertEquals(
                279.seconds,
                host.state.value.track
                    ?.nowPlaying
                    ?.duration,
            )
        }
}
