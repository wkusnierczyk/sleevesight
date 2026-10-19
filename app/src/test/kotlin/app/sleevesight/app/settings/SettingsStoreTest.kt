package app.sleevesight.app.settings

import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }

    @After
    fun close() = stores.close()

    private fun store() = SettingsStore(stores.open("settings"))

    @Test
    fun aChangeIsKeptAcrossARestart() =
        runBlocking {
            store().update { it.copy(lookUpInBackground = true, aiModel = "claude-opus-5-5") }
            stores.close()

            val read = store().settings.first()
            assertTrue(read.lookUpInBackground)
            assertEquals("claude-opus-5-5", read.aiModel)
        }

    @Test
    fun onlySpotifyIsFollowedAtFirst() =
        runBlocking {
            assertEquals(setOf(Settings.SPOTIFY), store().followedPlayers.first())
        }

    @Test
    fun aPlayerSeenIsListedOffAndAListedOneKeepsItsSwitch() =
        runBlocking {
            val store = store()
            store.playerSeen("com.example.player")
            store.playerSeen(Settings.SPOTIFY)
            assertEquals(
                mapOf(Settings.SPOTIFY to true, "com.example.player" to false),
                store.settings.first().followedPlayers,
            )
            assertEquals(setOf(Settings.SPOTIFY), store.followedPlayers.first())

            store.update { it.copy(followedPlayers = it.followedPlayers + ("com.example.player" to true)) }
            store.playerSeen("com.example.player")
            assertEquals(setOf(Settings.SPOTIFY, "com.example.player"), store.followedPlayers.first())
        }

    @Test
    fun aFailedReadFollowsNoPlayerShowsNoDefaultsAndIsRetried() =
        runBlocking {
            val flaky = FlakyDataStore(stores.open("settings"))
            val store = SettingsStore(flaky)
            store.update { it.copy(followedPlayers = mapOf(Settings.SPOTIFY to false)) }
            // The first read of each of the two flows fails.
            flaky.failures.set(2)

            val followed = mutableListOf<Set<String>>()
            val shown = mutableListOf<Settings>()
            val jobs =
                listOf(
                    launch { store.followedPlayers.collect { followed += it } },
                    launch { store.settings.collect { shown += it } },
                )
            // A read is counted when it starts, before its value arrives: wait for both values.
            withTimeout(5.seconds) { while (followed.isEmpty() || shown.isEmpty()) delay(10) }
            assertEquals(4, flaky.reads.get())
            assertEquals(listOf(emptySet<String>()), followed)
            assertEquals(listOf(mapOf(Settings.SPOTIFY to false)), shown.map { it.followedPlayers })

            // The flows go on after the retry: a later switch reaches both.
            store.update { it.copy(followedPlayers = it.followedPlayers + (OTHER_PLAYER to true)) }
            withTimeout(5.seconds) { while (followed.last() != setOf(OTHER_PLAYER)) delay(10) }
            withTimeout(5.seconds) { while (shown.last().followedPlayers[OTHER_PLAYER] != true) delay(10) }
            assertEquals(listOf(emptySet(), setOf(OTHER_PLAYER)), followed)
            jobs.forEach { it.cancel() }
        }

    @Test
    fun theFollowedPlayersWaitForAFirstReadAndNameNoneAfterALaterFailure() =
        runBlocking {
            val flaky = FlakyDataStore(stores.open("settings"))
            val store = SettingsStore(flaky)
            val followed = mutableListOf<Set<String>>()

            // A failed first read emits nothing: the session layer waits, as for any first read.
            flaky.failures.set(1)
            val first = launch { store.followedPlayers.collect { followed += it } }
            withTimeout(5.seconds) { while (followed.isEmpty()) delay(10) }
            assertEquals(listOf(setOf(Settings.SPOTIFY)), followed)
            first.cancel()

            // A second collection's first read fails too: it waits as well, though the first
            // collection had a value (that belongs to the collection). Then a failure after a read
            // names no player until the retry succeeds.
            followed.clear()
            flaky.failures.set(1)
            flaky.failuresAfterAValue.set(1)
            val second = launch { store.followedPlayers.collect { followed += it } }
            withTimeout(5.seconds) { while (followed.size < 3) delay(10) }
            assertEquals(listOf(setOf(Settings.SPOTIFY), emptySet(), setOf(Settings.SPOTIFY)), followed)
            second.cancel()
        }

    @Test
    fun theBackoffDoublesFromItsFirstRetryUpToItsLongest() {
        val waits = (0..9).map { SettingsStore.backoff(it) }
        assertEquals(
            listOf(250, 500, 1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000).map { it.milliseconds },
            waits,
        )
        assertEquals(SettingsStore.FIRST_RETRY, waits.first())
        assertEquals(SettingsStore.MAX_RETRY, waits.last())
        assertEquals(SettingsStore.MAX_RETRY, SettingsStore.backoff(Int.MAX_VALUE))
    }

    @Test
    fun theBackoffStartsAgainAfterAReadThatSucceeds() =
        runBlocking {
            // Each read gives the file and then fails: failure, recovery, failure, five times over.
            val flaky = FlakyDataStore(stores.open("settings"))
            flaky.failuresAfterAValue.set(5)
            val store = SettingsStore(flaky)
            val shown = mutableListOf<Settings>()
            val job = launch { store.settings.collect { shown += it } }
            // From the first retry's wait each time (250 ms), about 1.3 s; doubling, it would be 7.75 s.
            withTimeout(4.seconds) { while (flaky.reads.get() < 6) delay(10) }
            store.update { it.copy(writeInsights = false) }
            withTimeout(5.seconds) { while (shown.last().writeInsights) delay(10) }
            job.cancel()
        }

    @Test
    fun aDamagedFileReadsAsTheDefaults() =
        runBlocking {
            stores.file("settings").writeBytes(byteArrayOf(0x0a, 0x7f, 0x01, 0x02, 0x03))
            val store = store()
            assertEquals(Settings(), store.settings.first())

            // And the store works again: the damaged file was replaced.
            store.update { it.copy(writeInsights = false) }
            assertEquals(false, store.settings.first().writeInsights)
        }

    private companion object {
        const val OTHER_PLAYER = "com.example.player"
    }
}
