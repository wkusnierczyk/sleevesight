package app.sleevesight.app.settings

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

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        const val OTHER = "com.example.player"
    }
}
