package app.sleevesight.app.pipeline

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Preparing the next track through the host (D13, *Evidence*; #20): the next queue item is
 * resolved by name, with no length, after the playing track's run; nothing of it is shown or paid
 * for; nothing is prepared with the setting off or while the app is hidden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostPrepareTest : PipelineHostFixture() {
    private val nextTrack = NowPlaying(title = "B", artists = "Artist", player = Settings.SPOTIFY, mediaId = "b")

    @Test
    fun `the next track is resolved by name after the playing track's run, with nothing shown or paid`() =
        runTest {
            started()
            play("A", 200.seconds)
            settle()
            tap()
            settle()
            assertEquals(1, provider.requests)

            next.value = nextTrack
            settle()
            assertEquals(listOf<Pair<String, Duration?>>("artist|a" to 200.seconds, "artist|b" to null), resolved)
            assertEquals(
                "A",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
            assertEquals(1, provider.requests)
            assertEquals("the adapters that do not prepare ahead are not asked", listOf("artist|a"), musicBrainz.asked)
        }

    @Test
    fun `a preparation that ends leaves no handle behind, however many tracks are prepared`() =
        runTest {
            started()
            play("A", 200.seconds)
            for (title in listOf("B", "C", "D")) {
                next.value = nextTrack.copy(title = title, mediaId = title)
                settle()
            }
            assertEquals(listOf("artist|b", "artist|c", "artist|d"), resolved.drop(1).map { it.first })
            assertEquals(0, host.preparationsHeld)
        }

    @Test
    fun `with the setting off, nothing is prepared, and turning it on prepares the next track`() =
        runTest {
            settings.value = settings.value.copy(prepareNextTrack = PrepareNextTrack.OFF)
            started()
            play("A", 200.seconds)
            next.value = nextTrack
            settle()
            assertEquals(listOf<Pair<String, Duration?>>("artist|a" to 200.seconds), resolved)

            settings.value = settings.value.copy(prepareNextTrack = PrepareNextTrack.EVIDENCE)
            settle()
            assertEquals("artist|b" to null, resolved.last())
        }

    @Test
    fun `nothing is prepared while the app is hidden, and the next track is on return`() =
        runTest {
            started()
            play("A", 200.seconds)
            settle()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            next.value = nextTrack
            settle()
            assertEquals(1, resolved.size)

            host.activityStarted()
            settle()
            assertEquals("artist|b" to null, resolved.last())
        }
}
