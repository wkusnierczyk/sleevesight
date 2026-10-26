package app.sleevesight.app.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.idleUntil
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.InstalledPlayers
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

/**
 * *Players* (#535): each player listed with its app's icon at the row's start; one not installed,
 * Spotify included, is not listed, and the others' entries are dropped as the section is drawn.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerIconsTest {
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

    /** The phone with only [players] installed, each with a plain icon; the drop goes to the store. */
    private fun phoneWith(vararg players: String) =
        InstalledPlayers(
            icon = { if (it in players) ColorDrawable(Color.RED) else null },
            forgetUninstalled = { settings.forgetPlayers { it in players } },
        )

    private fun stored(vararg players: Pair<String, Boolean>) =
        runBlocking { settings.update { it.copy(followedPlayers = it.followedPlayers + players) } }

    private fun showPlayers(
        installed: InstalledPlayers,
        test: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        installedPlayers = installed,
                    ) { NAMES.getValue(it) }
                }
            }
            compose.openSection(SettingsSection.PLAYERS)
            test()
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun anInstalledPlayerShowsItsIconAndAnUninstalledOneLeavesTheList() {
        stored(INSTALLED to false, UNINSTALLED to true)
        showPlayers(phoneWith(INSTALLED)) {
            compose.waitUntil(5_000) { shown("Example") }
            assertTrue("Spotify, not installed, is not listed", !shown("Spotify"))
            assertTrue("an uninstalled player is not listed", !shown("Gone"))

            // The uninstalled player's entry is dropped; Spotify's, a default, is kept.
            idleUntil("the uninstalled player dropped") { UNINSTALLED !in settings.now().followedPlayers }
            assertEquals(mapOf(Settings.SPOTIFY to true, INSTALLED to false), settings.now().followedPlayers)

            // The icon is at the row's start, before the name: 40 dp and a 16 dp gap.
            val row = compose.onNode(isToggleable() and hasText("Example"))
            val name = compose.onNodeWithText("Example", useUnmergedTree = true).getBoundsInRoot()
            assertTrue("the name follows the icon", name.left - row.getBoundsInRoot().left >= 56.dp)
            // And is decorative: the row is read by its name alone, as before.
            row.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            assertEquals(
                listOf("Example"),
                row.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text },
            )
        }
    }

    @Test
    fun withNoPlayerInstalledTheSectionSaysHowOneIsAdded() {
        showPlayers(phoneWith()) {
            val none = app.getString(R.string.settings_players_none)
            compose.waitUntil(5_000) { shown(none) }
            assertTrue(!shown("Spotify"))
            // Spotify's entry is kept, still on, for when it is installed.
            assertEquals(mapOf(Settings.SPOTIFY to true), settings.now().followedPlayers)
        }
    }

    private companion object {
        const val INSTALLED = "com.example.player"
        const val UNINSTALLED = "com.example.gone"
        val NAMES = mapOf(Settings.SPOTIFY to "Spotify", INSTALLED to "Example", UNINSTALLED to "Gone")
    }
}
