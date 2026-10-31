package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.LinkStyle
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings with a few short choices as button groups (#637): *Theme*, *Font* and *Layout*, and
 * in developer mode *Report button* and *Link style*. Each row shows its choices as one group of
 * radio buttons, the stored one selected; one tap stores a choice, with no dialog; and the row
 * follows the stored value, whatever changed it.
 */
@RunWith(RobolectricTestRunner::class)
class ButtonGroupSettingsTest {
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

    /**
     * A setting: its name's id, its choices (each label's id and the value it stores), and how to
     * read and store its value.
     */
    private class Spec<T>(
        val title: Int,
        val choices: List<Pair<Int, T>>,
        val stored: (Settings) -> T,
        val store: (Settings, T) -> Settings,
    )

    private val theme =
        Spec(
            R.string.settings_theme,
            listOf(
                R.string.settings_theme_system to ThemeChoice.SYSTEM,
                R.string.settings_theme_light to ThemeChoice.LIGHT,
                R.string.settings_theme_dark to ThemeChoice.DARK,
            ),
            { it.theme },
            { s, v -> s.copy(theme = v) },
        )
    private val font =
        Spec(
            R.string.settings_font,
            listOf(
                R.string.settings_font_app to FontChoice.APP_FONT,
                R.string.settings_font_system to FontChoice.SYSTEM_FONT,
            ),
            { it.font },
            { s, v -> s.copy(font = v) },
        )
    private val layout =
        Spec(
            R.string.settings_layout,
            listOf(
                R.string.settings_layout_boxes to Layout.EXPANDABLE_BOXES,
                R.string.settings_layout_pages to Layout.SEPARATE_PAGES,
            ),
            { it.layout },
            { s, v -> s.copy(layout = v) },
        )
    private val reportButton =
        Spec(
            R.string.settings_report_button,
            listOf(
                R.string.settings_report_button_show to ReportButton.SHOW,
                R.string.settings_report_button_auto to ReportButton.AUTO,
                R.string.settings_report_button_hide to ReportButton.HIDE,
            ),
            { it.reportButton },
            { s, v -> s.copy(reportButton = v) },
        )
    private val linkStyle =
        Spec(
            R.string.settings_link_style,
            listOf(
                R.string.settings_link_style_underline to LinkStyle.UNDERLINE,
                R.string.settings_link_style_highlight to LinkStyle.HIGHLIGHT,
            ),
            { it.linkStyle },
            { s, v -> s.copy(linkStyle = v) },
        )

    private fun show(
        section: SettingsSection,
        block: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
            }
        }
        compose.openSection(section)
        block()
    }

    private fun button(label: Int) = compose.onNode(hasText(text(label)) and isSelectable())

    private fun buttonNode(label: Int): SemanticsNode = button(label).performScrollTo().fetchSemanticsNode()

    private fun selectedIs(label: Int) =
        compose.onAllNodes(hasText(text(label)) and isSelectable() and isSelected()).fetchSemanticsNodes().isNotEmpty()

    /**
     * [spec]'s row: radio buttons of one group; a tap on each stores it at once and selects it, with
     * no dialog; then a value stored elsewhere is the one selected.
     */
    private fun <T> assertChosenByButtons(spec: Spec<T>) {
        val parents = mutableSetOf<Int>()
        for ((label, _) in spec.choices) {
            val node = buttonNode(label)
            assertEquals(text(label), Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
            // Read with the setting's name, so a button reached straight away says what it sets.
            val read =
                node.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                    .joinToString()
            assertTrue("${text(label)} is read as \"$read\"", read.startsWith("${text(spec.title)}: ${text(label)}"))
            val group = checkNotNull(node.parent)
            assertTrue("${text(label)} is in a selectable group", SemanticsProperties.SelectableGroup in group.config)
            parents += group.id
        }
        assertEquals("one group", 1, parents.size)
        for ((label, value) in spec.choices.reversed() + spec.choices) {
            button(label).performScrollTo().performClick()
            compose.waitUntil(5_000) { spec.stored(settings.now()) == value && selectedIs(label) }
            assertEquals("${text(label)}: no dialog", 0, compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)
            for ((other, _) in spec.choices.filter { it.first != label }) assertFalse(text(other), selectedIs(other))
        }
        // Stored elsewhere, as Home's theme button will store *Theme* (#636): the row follows.
        val (label, value) = spec.choices.first()
        runBlocking { settings.update { spec.store(it, value) } }
        compose.waitUntil(5_000) { selectedIs(label) }
    }

    @Test
    fun themeFontAndLayoutAreChosenByTheirButtons() =
        show(SettingsSection.APPEARANCE) {
            assertChosenByButtons(theme)
            assertChosenByButtons(font)
            assertChosenByButtons(layout)
        }

    @Test
    fun reportButtonAndLinkStyleAreChosenByTheirButtonsInDeveloperMode() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        show(SettingsSection.DEVELOPER) {
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(text(R.string.settings_link_style))).fetchSemanticsNodes().isNotEmpty()
            }
            assertChosenByButtons(reportButton)
            assertChosenByButtons(linkStyle)
        }
    }
}
