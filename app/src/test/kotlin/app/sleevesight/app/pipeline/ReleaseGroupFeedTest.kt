package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbGroupFirst
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The release groups' first releases *Dates* reads (#1099): asked once per group, only while lookups
 * are allowed and MusicBrainz is on, again after a failure, not again after MusicBrainz has no such
 * group, and dropped by *Clear history*.
 */
class ReleaseGroupFeedTest {
    private val actually = MbGroupFirst("g", "Actually", "1987-09-07")
    private val asked = mutableListOf<String>()
    private var answer: Lookup<MbGroupFirst> = Lookup.Found(actually)
    private val allowed = MutableStateFlow(true)
    private var on = true

    private fun TestScope.feed(
        epoch: ClearEpoch = ClearEpoch(),
        hold: CompletableDeferred<Unit>? = null,
    ) = ReleaseGroupFeed(
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
    fun aGroupIsAskedOnceAndKept() =
        runTest {
            val feed = feed()
            feed.look("g")
            feed.look("g")
            runCurrent()
            feed.look("g")
            runCurrent()
            assertEquals(listOf("g"), asked)
            assertEquals(actually, feed.firsts.value["g"])
        }

    @Test
    fun nothingIsAskedUntilLookupsAreAllowedNorWhileMusicBrainzIsOff() =
        runTest {
            allowed.value = false
            val feed = feed()
            feed.look("g")
            runCurrent()
            assertEquals(emptyList<String>(), asked)
            allowed.value = true
            runCurrent()
            assertEquals(listOf("g"), asked)
            on = false
            feed.look("h")
            runCurrent()
            assertEquals(listOf("g"), asked)
            assertNull(feed.firsts.value["h"])
            // Asked again once MusicBrainz is back on.
            on = true
            feed.look("h")
            runCurrent()
            assertEquals(listOf("g", "h"), asked)
        }

    @Test
    fun aFailureIsAskedAgainAndANotFoundIsNot() =
        runTest {
            answer = Lookup.Failed("Offline")
            val feed = feed()
            feed.look("g")
            runCurrent()
            assertNull(feed.firsts.value["g"])
            answer = Lookup.Found(actually)
            feed.look("g")
            runCurrent()
            assertEquals(listOf("g", "g"), asked)
            assertEquals(actually, feed.firsts.value["g"])
            answer = Lookup.NotFound
            feed.look("x")
            runCurrent()
            feed.look("x")
            runCurrent()
            assertEquals(listOf("g", "g", "x"), asked)
            assertNull(feed.firsts.value["x"])
        }

    @Test
    fun clearHistoryDropsEveryGroupAndALookupUnderWayKeepsNothing() =
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
            assertNull(feed.firsts.value["g"])
            // Asked again after it.
            feed.look("g")
            runCurrent()
            assertEquals(listOf("g", "g"), asked)
            assertEquals(actually, feed.firsts.value["g"])
        }
}
