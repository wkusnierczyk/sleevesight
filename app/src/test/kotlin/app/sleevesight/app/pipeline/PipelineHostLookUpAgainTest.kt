package app.sleevesight.app.pipeline

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * After *Clear history* (#1112): the track playing is held, with no run, and the view says so
 * ([PipelineView.cleared]) rather than leave Home waiting on a run that does not exist, whether the
 * clear came during a run or after one; *Look up the track again*'s *Automatically* looks it up
 * afresh once the clear has ended ([PipelineHost.clear]), as a new track, the insight
 * written again only if insights are written automatically; *On request* waits for *Look up again*
 * ([PipelineHost.lookUpAgain]), which does the same, and a tap made while the clear still wipes the
 * stores waits for its end. A track an ad's withdrawal holds is never looked up again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostLookUpAgainTest : PipelineHostFixture() {
    // The clear as the app makes it: the boundary, the host's part, the caches, and under
    // *Automatically* the track looked up again once the rest is cleared.
    private fun TestScope.clear(automatically: Boolean) {
        backgroundScope.launch {
            epoch.advance()
            host.clear(automatically) { cache.clearCards() }
        }
        runCurrent()
    }

    // A clear whose wipe waits for [wiping], as a slow one would: the stores are still being wiped.
    private fun TestScope.clearWaiting(
        automatically: Boolean,
        wiping: CompletableDeferred<Unit>,
    ) {
        backgroundScope.launch {
            epoch.advance()
            host.clear(automatically) {
                wiping.await()
                cache.clearCards()
            }
        }
        runCurrent()
    }

    private val playing: NowPlaying? get() = changes.value?.nowPlaying

    private fun TestScope.lookedUp(title: String) {
        val view = host.state.value
        assertNull("nothing held once it is looked up again", view.cleared)
        assertEquals(title, view.track?.nowPlaying?.title)
        assertTrue("its run's sources landed", view.track?.gathered == true)
    }

    @Test
    fun `on request, a clear after a run holds the track, says so, and asks nothing`() =
        runTest {
            started()
            play("One")
            settle()
            clear(automatically = false)
            settle()

            val view = host.state.value
            assertNull(view.track)
            assertEquals("One", view.cleared?.title)
            assertEquals("nothing asked again", listOf("artist|one"), musicBrainz.asked)
        }

    @Test
    fun `on request, a clear during a run holds the track, says so, and asks nothing`() =
        runTest {
            musicBrainz.byName = 3.seconds
            started()
            play("One")
            advanceTimeBy(DEBOUNCE + 1.seconds)
            assertEquals(listOf("artist|one"), musicBrainz.asked)

            clear(automatically = false)
            settle()

            val view = host.state.value
            assertNull(view.track)
            assertEquals("One", view.cleared?.title)
            assertEquals(listOf("artist|one"), musicBrainz.asked)
        }

    @Test
    fun `on request, Look up again runs the track as a new one`() =
        runTest {
            started()
            play("One")
            settle()
            clear(automatically = false)
            settle()

            host.lookUpAgain(checkNotNull(playing))
            settle()

            assertEquals(listOf("artist|one", "artist|one"), musicBrainz.asked)
            lookedUp("One")
            assertEquals("a user's action, for skip detection", 1, actions)
        }

    @Test
    fun `Look up again for a track no longer playing is dropped`() =
        runTest {
            started()
            play("One")
            settle()
            clear(automatically = false)
            settle()
            val shown = checkNotNull(playing)
            changes.value = changes.value?.copy(nowPlaying = shown.copy(title = "Two"))

            host.lookUpAgain(shown)
            runCurrent()

            assertEquals(listOf("artist|one"), musicBrainz.asked)
            assertEquals(0, actions)
        }

    @Test
    fun `automatically, a clear after a run looks the track up again`() =
        runTest {
            started()
            play("One")
            settle()
            clear(automatically = true)
            settle()

            assertEquals(listOf("artist|one", "artist|one"), musicBrainz.asked)
            lookedUp("One")
            assertEquals("not a user's action", 0, actions)
        }

    @Test
    fun `automatically, a clear during a run looks the track up again`() =
        runTest {
            musicBrainz.byName = 3.seconds
            started()
            play("One")
            advanceTimeBy(DEBOUNCE + 1.seconds)

            clear(automatically = true)
            advanceTimeBy(10.seconds)

            assertEquals(listOf("artist|one", "artist|one"), musicBrainz.asked)
            lookedUp("One")
        }

    @Test
    fun `automatically, the insight is written again when insights are written automatically`() =
        runTest {
            started()
            play("One")
            settle()
            // The first action: insights are written automatically from now on.
            tap()
            settle()
            assertEquals(1, provider.requestsFor("One"))

            clear(automatically = true)
            settle()

            assertEquals(2, provider.requestsFor("One"))
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
        }

    @Test
    fun `automatically, the insight is not written again when insights are written on a tap`() =
        runTest {
            settings.value =
                Settings(aiProvider = settings.value.aiProvider, writeAutomatically = WriteAutomatically.NEVER)
            started()
            play("One")
            settle()
            tap()
            settle()
            assertEquals(1, provider.requestsFor("One"))

            clear(automatically = true)
            settle()

            assertEquals(1, provider.requestsFor("One"))
            assertEquals(listOf("artist|one", "artist|one"), musicBrainz.asked)
            assertTrue("Write is offered", host.state.value.writeKey != null)
        }

    @Test
    fun `on request, the next track starts as usual and nothing is held`() =
        runTest {
            started()
            play("One")
            settle()
            clear(automatically = false)
            settle()

            play("Two")
            settle()

            assertEquals(listOf("artist|one", "artist|two"), musicBrainz.asked)
            lookedUp("Two")
        }

    @Test
    fun `Look up again tapped while the clear still wipes the stores waits for its end, then runs once`() =
        runTest {
            started()
            play("One")
            settle()
            val wiping = CompletableDeferred<Unit>()
            clearWaiting(automatically = false, wiping)
            assertEquals(
                "One",
                host.state.value.cleared
                    ?.title,
            )

            host.lookUpAgain(checkNotNull(playing))
            settle()
            assertEquals("no run before the wipe ends", listOf("artist|one"), musicBrainz.asked)

            wiping.complete(Unit)
            settle()
            assertEquals("exactly one run after it", listOf("artist|one", "artist|one"), musicBrainz.asked)
            lookedUp("One")
        }

    @Test
    fun `automatically with a tap while the stores are wiped, the track runs once`() =
        runTest {
            started()
            play("One")
            settle()
            val wiping = CompletableDeferred<Unit>()
            clearWaiting(automatically = true, wiping)
            host.lookUpAgain(checkNotNull(playing))
            settle()
            assertEquals(listOf("artist|one"), musicBrainz.asked)

            wiping.complete(Unit)
            settle()
            assertEquals(listOf("artist|one", "artist|one"), musicBrainz.asked)
        }

    @Test
    fun `automatically, nothing runs when the player stopped showing the track before the clear ended`() =
        runTest {
            started()
            play("One")
            settle()
            val wiping = CompletableDeferred<Unit>()
            clearWaiting(automatically = true, wiping)
            // The session layer shows nothing (the player gone); the pipeline hears no report of it.
            changes.value = null
            runCurrent()

            wiping.complete(Unit)
            settle()
            assertEquals(listOf("artist|one"), musicBrainz.asked)
            assertEquals(0, provider.requestsFor("One"))
        }

    @Test
    fun `a tap held through the wipe runs nothing when an ad plays by its end`() =
        runTest {
            started()
            play("One")
            settle()
            val wiping = CompletableDeferred<Unit>()
            clearWaiting(automatically = false, wiping)
            host.lookUpAgain(checkNotNull(playing))
            // An ad starts: the session layer shows no track while it plays, and reports none.
            changes.value = null
            runCurrent()

            wiping.complete(Unit)
            settle()
            assertEquals(listOf("artist|one"), musicBrainz.asked)
        }

    @Test
    fun `a clear cancelled while it waits to be taken still ends, so another clear's end looks up once`() =
        runTest {
            started()
            play("One")
            settle()
            val wiping = CompletableDeferred<Unit>()
            clearWaiting(automatically = false, wiping)
            host.lookUpAgain(checkNotNull(playing))
            runCurrent()
            // A second clear, cancelled while it waits for the loop to take it.
            val second =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { host.clear(lookUp = false) {} }
            second.cancel()
            settle()
            assertEquals("the first clear still wipes", listOf("artist|one"), musicBrainz.asked)

            wiping.complete(Unit)
            settle()
            assertEquals("one run once both ended", listOf("artist|one", "artist|one"), musicBrainz.asked)
            lookedUp("One")
        }

    @Test
    fun `a track an ad's withdrawal holds is not looked up again after a clear`() =
        runTest {
            started()
            play("One")
            settle()
            withdrawn.value = playing
            runCurrent()

            clear(automatically = true)
            settle()
            host.lookUpAgain(checkNotNull(playing))
            settle()

            assertEquals("no run of the ad", listOf("artist|one"), musicBrainz.asked)
            assertNull("an ad's hold is not shown as cleared", host.state.value.cleared)
        }

    @Test
    fun `while the stores are wiped, the cleared card shows only until a look-up is asked for`() =
        runTest {
            started()
            play("One")
            settle()
            val automatic = CompletableDeferred<Unit>()
            clearWaiting(automatically = true, automatic)
            assertNull("Automatically: the run follows, so nothing says cleared", host.state.value.cleared)
            automatic.complete(Unit)
            settle()

            val onRequest = CompletableDeferred<Unit>()
            clearWaiting(automatically = false, onRequest)
            assertEquals(
                "One",
                host.state.value.cleared
                    ?.title,
            )
            host.lookUpAgain(checkNotNull(playing))
            runCurrent()
            assertNull("tapped: the run follows", host.state.value.cleared)
            onRequest.complete(Unit)
            settle()
            lookedUp("One")
        }
}
