package app.sleevesight.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsCodec
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.ROW_TITLE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SymbolOpens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The header symbol's two settings under *Players* (#1445), at its end, after *Only enabled players
 * on Home*: *Show the current player's logo*, on by default, and *What the symbol opens*, *Player
 * menu* by default or *The player*; both stored as moved and read back. The section is kept as
 * PNGs, light and dark, when `SLEEVESIGHT_PICTURES` names a directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerSymbolSettingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun show(
        dark: Boolean = false,
        block: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                SleevesightTheme(darkTheme = dark) {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
        }
        compose.openSection(SettingsSection.PLAYERS)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(text(R.string.settings_player_logo)).fetchSemanticsNodes().isNotEmpty()
        }
        block()
    }

    private fun logoSwitch() = compose.onNode(hasText(text(R.string.settings_player_logo)) and isToggleable())

    // A tap on the switch row's name, which toggles it, as `LogoSpinSettingTest` taps its switch.
    private fun tapLogoSwitch() =
        compose
            .onNode(hasText(text(R.string.settings_player_logo)) and hasTestTag(ROW_TITLE_TAG), useUnmergedTree = true)
            .performScrollTo()
            .performClick()

    private fun opens(option: Int) = compose.onNode(hasText(text(option)) and isSelectable())

    @Test
    fun theLogoIsShownAndTheSymbolOpensThePlayerMenuByDefault() {
        assertEquals(true, Settings().showPlayerLogo)
        assertEquals(SymbolOpens.PLAYER_MENU, Settings().symbolOpens)
        // A settings file from before #1445 reads as the defaults.
        val before = mutablePreferencesOf(booleanPreferencesKey("players.only_enabled_on_home") to true)
        val read = SettingsCodec.read(before)
        assertEquals(true, read.showPlayerLogo)
        assertEquals(SymbolOpens.PLAYER_MENU, read.symbolOpens)
    }

    // `SettingsCodecTest.everySettingRoundTrips` is at its size limit: these two are read back here.
    @Test
    fun bothAreStoredWhenMovedReadBackAndLoseTheirKeysAtTheDefault() {
        val prefs = mutablePreferencesOf()
        val changed = Settings(showPlayerLogo = false, symbolOpens = SymbolOpens.PLAYER)
        SettingsCodec.write(changed, prefs)
        assertEquals(
            mutablePreferencesOf(
                booleanPreferencesKey("players.show_logo") to false,
                stringPreferencesKey("players.symbol_opens") to "PLAYER",
            ),
            prefs,
        )
        assertEquals(changed, SettingsCodec.read(prefs))
        SettingsCodec.write(Settings(), prefs)
        assertTrue("back at the defaults, they have no keys", prefs.asMap().isEmpty())
    }

    @Test
    fun theSwitchTurnsTheLogoOffAndOnAndTheButtonsChooseWhatTheSymbolOpens() =
        show {
            compose.onNodeWithText(text(R.string.settings_player_logo_summary)).assertExists()
            compose.onNodeWithText(text(R.string.settings_symbol_opens_summary)).assertExists()
            logoSwitch().performScrollTo().assertIsOn()
            tapLogoSwitch()
            compose.waitUntil(WAIT_MS) { !settings.now().showPlayerLogo }
            logoSwitch().assertIsOff()
            tapLogoSwitch()
            compose.waitUntil(WAIT_MS) { settings.now().showPlayerLogo }
            opens(R.string.settings_symbol_opens_menu).performScrollTo().assertIsSelected()
            opens(R.string.settings_symbol_opens_player).performClick()
            compose.waitUntil(WAIT_MS) { settings.now().symbolOpens == SymbolOpens.PLAYER }
            opens(R.string.settings_symbol_opens_player).assertIsSelected()
            opens(R.string.settings_symbol_opens_menu).performClick()
            compose.waitUntil(WAIT_MS) { settings.now().symbolOpens == SymbolOpens.PLAYER_MENU }
        }

    @Test
    fun theRowsComeLastAfterOnlyEnabledPlayersOnHomeInOneRowLightAndDark() {
        for (dark in listOf(false, true)) {
            show(dark) {
                opens(R.string.settings_symbol_opens_player).performScrollTo()
                keep("players-symbol-${if (dark) "dark" else "light"}")
                val bounds = { id: Int ->
                    compose
                        .onNodeWithText(text(id))
                        .fetchSemanticsNode()
                        .boundsInRoot
                }
                val onHome = bounds(R.string.settings_players_on_home).top
                val logo = bounds(R.string.settings_player_logo).top
                val opensTitle = bounds(R.string.settings_symbol_opens).top
                assertTrue("$onHome < $logo < $opensTitle", onHome < logo && logo < opensTitle)
                // The two choices side by side, one row.
                val menu = opens(R.string.settings_symbol_opens_menu).fetchSemanticsNode().boundsInRoot
                val player = opens(R.string.settings_symbol_opens_player).fetchSemanticsNode().boundsInRoot
                assertEquals(menu.top, player.top)
                assertTrue(menu.right <= player.left)
            }
        }
    }

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun keep(name: String) {
        val dir = out ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
