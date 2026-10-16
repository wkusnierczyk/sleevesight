package app.sleevesight.app.settings

import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
    fun aDamagedFileReadsAsTheDefaults() =
        runBlocking {
            stores.file("settings").writeBytes(byteArrayOf(0x0a, 0x7f, 0x01, 0x02, 0x03))
            val store = store()
            assertEquals(Settings(), store.settings.first())

            // And the store works again: the damaged file was replaced.
            store.update { it.copy(writeInsights = false) }
            assertEquals(false, store.settings.first().writeInsights)
        }
}
