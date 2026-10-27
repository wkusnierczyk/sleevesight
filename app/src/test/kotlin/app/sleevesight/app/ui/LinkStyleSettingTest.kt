package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import app.sleevesight.app.ui.theme.LocalLinkStyle
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.LinkStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Link style* (#519) under *Developer*: *Underline* by default, *Highlight* chosen in its dialog,
 * the theme's links following it, and *Developer mode* off underlining them again.
 */
@RunWith(RobolectricTestRunner::class)
class LinkStyleSettingTest {
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

    private fun text(id: Int) = app.getString(id)

    private fun shown(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theRowSetsTheLinksStyleAndDeveloperModeOffUnderlinesThemAgain() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        var drawn: LinkStyle? = null
        val linkStyles =
            settings.settings.map { it.linkStyle }.stateIn(saves, SharingStarted.Eagerly, LinkStyle.UNDERLINE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    // The theme follows the stored settings, as `AppGraph.linkStyle` has it follow them.
                    SleevesightTheme(MutableStateFlow(FontChoice.APP_FONT), linkStyles = linkStyles) {
                        drawn = LocalLinkStyle.current
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            compose.openSection(SettingsSection.DEVELOPER)
            compose.waitUntil(5_000) { shown(R.string.settings_link_style) }
            compose.onNodeWithText(text(R.string.settings_link_style_summary)).assertExists()
            compose.onNodeWithText(text(R.string.settings_link_style_underline)).assertExists()
            compose.waitUntil(5_000) { drawn == LinkStyle.UNDERLINE }

            // One tap on the choice's button in the row (#637).
            compose
                .onNode(hasText(text(R.string.settings_link_style_highlight)) and isSelectable())
                .performScrollTo()
                .performClick()
            compose.waitUntil(5_000) { settings.now().linkStyle == LinkStyle.HIGHLIGHT && drawn == LinkStyle.HIGHLIGHT }
            compose.onNode(hasText(text(R.string.settings_link_style_highlight)) and isSelectable()).assertIsSelected()

            compose.onNodeWithText(text(R.string.settings_developer_mode)).performScrollTo().performClick()
            compose.waitUntil(5_000) {
                !settings.now().developerMode &&
                    settings.now().linkStyle == LinkStyle.UNDERLINE &&
                    drawn == LinkStyle.UNDERLINE
            }
        }
    }
}
