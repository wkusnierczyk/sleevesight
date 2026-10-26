package app.sleevesight.app.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SeenPlayersRecorderTest {
    private val seen = MutableStateFlow(emptySet<String>())
    private val recorded = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    @Test
    fun eachPlayerIsRecordedOnceAsItIsSeen() =
        runTest(UnconfinedTestDispatcher()) {
            val job = launch { SeenPlayersRecorder(seen, { recorded += it }, { warnings += it }).run() }

            seen.value = setOf(SPOTIFY)
            seen.value = setOf(SPOTIFY, OTHER)

            assertEquals(listOf(SPOTIFY, OTHER), recorded)
            assertEquals(emptyList<String>(), warnings)
            job.cancel()
        }

    @Test
    fun aFailedRecordIsReportedAndTriedAgainWithTheNextPlayerSeen() =
        runTest(UnconfinedTestDispatcher()) {
            var failing = true
            val record: suspend (String) -> Unit = {
                if (failing) throw IOException("/data/user/0/app.sleevesight/files/datastore/settings")
                recorded += it
            }
            val job = launch { SeenPlayersRecorder(seen, record, { warnings += it }).run() }

            seen.value = setOf(OTHER)
            assertEquals(listOf("Could not list $OTHER under Players"), warnings)

            failing = false
            seen.value = setOf(OTHER, SPOTIFY)
            assertEquals(setOf(OTHER, SPOTIFY), recorded.toSet())
            assertEquals(2, recorded.size)
            job.cancel()
        }

    @Test
    fun aForgottenPlayerIsRecordedAgainWhenSeenAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val recorder = SeenPlayersRecorder(seen, { recorded += it }, { warnings += it })
            val job = launch { recorder.run() }

            seen.value = setOf(OTHER)
            recorder.forget(OTHER)
            seen.value = emptySet()
            seen.value = setOf(OTHER)

            assertEquals(listOf(OTHER, OTHER), recorded)
            job.cancel()
        }

    /** #579: a player forgotten while its record is under way is recorded again when seen again. */
    @Test
    fun aPlayerForgottenWhileItsRecordIsUnderWayIsRecordedAgainWhenSeenAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val write = CompletableDeferred<Unit>()
            val record: suspend (String) -> Unit = {
                write.await()
                recorded += it
            }
            val recorder = SeenPlayersRecorder(seen, record, { warnings += it })
            val job = launch { recorder.run() }

            seen.value = setOf(OTHER)
            // The write is held: the player is forgotten (its app removed) before it ends.
            recorder.forget(OTHER)
            write.complete(Unit)
            seen.value = emptySet()
            seen.value = setOf(OTHER)

            assertEquals(listOf(OTHER, OTHER), recorded)
            job.cancel()
        }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        const val OTHER = "com.example.player"
    }
}
