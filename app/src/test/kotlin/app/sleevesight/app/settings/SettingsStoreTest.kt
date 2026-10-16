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
            withTimeout(5.seconds) { while (shown.isEmpty() || flaky.reads.get() < 4) delay(10) }
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
