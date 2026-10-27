package app.sleevesight.app.ui

import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** *Players* (#444): *Open the app ↗* under a player's name, for one with a launcher entry. */
@RunWith(RobolectricTestRunner::class)
class PlayerSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private val spotifyLaunch = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(Settings.SPOTIFY, "Main"))

    private fun openLink(name: String) = app.getString(R.string.settings_open_app_of, name)

    @Test
    fun aPlayerWithALauncherEntryOffersToOpenItAndOneWithoutDoesNot() {
        val other = "com.example.player"
        runBlocking { settings.update { it.copy(followedPlayers = it.followedPlayers + (other to false)) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        playerLaunch = { if (it == Settings.SPOTIFY) spotifyLaunch else null },
                    ) { if (it == Settings.SPOTIFY) "Spotify" else "Example" }
                }
            }
            compose.openSection(SettingsSection.PLAYERS)
            // Each row shows once its name and link, asked off the main thread, have arrived.
            compose.waitUntil(5_000) {
                listOf("Example", "Spotify").all { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
            }
            // No launcher entry, no link.
            assertTrue(compose.onAllNodes(hasContentDescription(openLink("Example"))).fetchSemanticsNodes().isEmpty())

            compose.onNodeWithContentDescription(openLink("Spotify")).performScrollTo().performClick()
            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(spotifyLaunch.component, started?.component)
            // The link opens the player; it does not toggle its switch.
            compose.waitForIdle()
            assertEquals(true, settings.now().followedPlayers[Settings.SPOTIFY])
        }
    }

    @Test
    fun playersAreListedAToZByTheirNamesNotTheirPackages() {
        // The packages sort the other way from the names (#627).
        val zulu = "com.a.zzz"
        val alpha = "com.z.aaa"
        runBlocking { settings.update { it.copy(followedPlayers = mapOf(zulu to true, alpha to false)) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) {
                        mapOf(zulu to "Zulu", alpha to "Alpha")[it] ?: it
                    }
                }
            }
            compose.openSection(SettingsSection.PLAYERS)
            compose.waitUntil(5_000) {
                listOf("Alpha", "Zulu").all { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
            }
            val top = { name: String ->
                compose
                    .onNodeWithText(name)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
            assertTrue(top("Alpha") < top("Zulu"))
        }
    }
}
