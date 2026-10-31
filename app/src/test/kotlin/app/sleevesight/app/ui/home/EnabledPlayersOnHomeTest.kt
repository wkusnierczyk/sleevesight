package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.now
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsCodec
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.openSection
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Only enabled players on Home* (#674): off by default, Home with nothing playing lists every
 * player *Players* lists, the switched-off ones dimmed; on, only the switched-on ones, and with none
 * switched on a line says so and links to *Players*. A switch under *Players*.
 */
@RunWith(RobolectricTestRunner::class)
class EnabledPlayersOnHomeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stores by lazy { TestStores(tmp.root) }

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private val openedAt = mutableListOf<SettingsTarget>()
    private val names = mapOf(ZULU to "Zulu", ALPHA to "Alpha", POWERAMP to "Poweramp")

    private val players = Settings(followedPlayers = mapOf(ZULU to true, ALPHA to false, POWERAMP to false))

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun show(
        settings: Settings,
        block: () -> Unit,
    ) {
        val actions =
            NowPlayingActions(
                normalizer::normalize,
                playerLabel = { names[it] ?: it },
                openSettingsAt = { openedAt += it },
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(NowPlayingState(PipelineView(), playing = null, settings = settings), actions)
                    }
                }
            }
            compose.awaitIdleHome()
            block()
        }
    }

    @Test
    fun offByDefaultAndStoredUnderItsKeyWhenOn() {
        assertFalse(Settings().onlyEnabledPlayersOnHome)
        // As stored before the setting existed: other settings, and nothing for it.
        val before = mutablePreferencesOf(booleanPreferencesKey("display.home_guide") to false)
        assertFalse(SettingsCodec.read(before).onlyEnabledPlayersOnHome)
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(onlyEnabledPlayersOnHome = true), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("players.only_enabled_on_home") to true), prefs)
    }

    @Test
    fun offHomeListsEveryPlayerTheSwitchedOffOnesToo() =
        show(players) {
            for (player in listOf(ZULU, ALPHA, POWERAMP)) assertEquals(player, 1, count(idlePlayerTag(player)))
            assertEquals(0, count(PLAYERS_LINK_TAG))
        }

    @Test
    fun onHomeListsOnlyTheSwitchedOnPlayers() =
        show(players.copy(onlyEnabledPlayersOnHome = true)) {
            assertEquals(1, count(idlePlayerTag(ZULU)))
            assertEquals(0, count(idlePlayerTag(ALPHA)))
            assertEquals(0, count(idlePlayerTag(POWERAMP)))
            assertEquals(0, count(enableTag(ALPHA)))
            assertEquals(0, count(PLAYERS_LINK_TAG))
        }

    @Test
    fun onWithNoneSwitchedOnALineSaysSoAndLinksToPlayers() {
        val none = Settings(followedPlayers = mapOf(ALPHA to false, POWERAMP to false), onlyEnabledPlayersOnHome = true)
        show(none) {
            assertEquals(0, count(idlePlayerTag(ALPHA)))
            assertEquals(0, count(idlePlayerTag(POWERAMP)))
            val link = app.getString(R.string.home_players_none_link)
            compose
                .onNodeWithText(app.getString(R.string.home_players_none_enabled, link))
                .performScrollTo()
                .performFirstLinkClick()
            assertEquals(listOf(SettingsTarget.players), openedAt)
            // Not the line for no player seen: players are listed under *Players*, just none is on.
            val unseen = app.getString(R.string.home_players_none, link)
            assertEquals(0, compose.onAllNodesWithText(unseen).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun theSwitchUnderPlayersTurnsItOn() {
        val settings = SettingsStore(stores.open("settings"))
        val secrets = SecretStore(stores.open("secrets"), testCipher())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            val title = app.getString(R.string.settings_players_on_home)
            compose.openSection(SettingsSection.PLAYERS)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(app.getString(R.string.settings_players_on_home_summary)).assertExists()
            val row = compose.onNodeWithText(title).performScrollTo()
            row.assertIsOff()
            row.performClick()
            compose.waitUntil(5_000) { settings.now().onlyEnabledPlayersOnHome }
        }
    }

    private companion object {
        const val ZULU = "com.a.zzz"
        const val ALPHA = "com.z.aaa"
        const val POWERAMP = "com.maxmpz.audioplayer"
    }
}
