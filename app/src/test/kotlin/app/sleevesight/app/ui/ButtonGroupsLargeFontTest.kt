package app.sleevesight.app.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every button group of the settings (#821), at twice the font size on a narrow phone: each stays in
 * one row, its buttons side by side, and no label is cut, wrapping in its button between its words,
 * never inside one. Native graphics, for the labels' real widths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-420dpi", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButtonGroupsLargeFontTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun showSettings(block: () -> Unit) {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            block()
        }
    }

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    /** The radio buttons now on the screen. */
    private fun choices(): List<SemanticsNode> = compose.onAllNodes(radio, useUnmergedTree = true).fetchSemanticsNodes()

    /**
     * The group of [buttons], of one setting, is in one row, its buttons side by side, and each
     * label is whole within its button, its words each on one line.
     */
    private fun assertOneRowNothingCut(
        setting: String,
        buttons: List<SemanticsNode>,
    ) {
        val bounds = buttons.map { it.boundsInRoot }
        assertTrue("$setting: one row, $bounds", bounds.all { it.top == bounds.first().top })
        assertTrue("$setting: side by side, $bounds", bounds.zipWithNext().all { (a, b) -> a.right <= b.left })
        for (button in buttons) {
            val text = button.children.single { SemanticsProperties.Text in it.config }
            val label = text.config[SemanticsProperties.Text].joinToString()
            val layouts = mutableListOf<TextLayoutResult>()
            text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            for (word in Regex("\\S+").findAll(label)) {
                val lines = layout.getLineForOffset(word.range.first) to layout.getLineForOffset(word.range.last)
                assertEquals("$setting: \"${word.value}\" of \"$label\" on one line", lines.first, lines.second)
            }
            val inside = button.boundsInRoot.inflate(1f)
            assertTrue("$setting: \"$label\" in its button", text.boundsInRoot.intersect(inside) == text.boundsInRoot)
        }
    }

    /** Opens [section], and checks each of the groups named by [settings] there. */
    private fun check(
        section: SettingsSection,
        vararg settings: Int,
    ) {
        compose.openSection(section)
        val app = ApplicationProvider.getApplicationContext<Context>()
        for (id in settings) {
            val name = app.getString(id)
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(name)[0].performScrollTo()
            compose.waitForIdle()
            val buttons =
                choices().filter { node ->
                    val read = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    read.any { it.startsWith("$name: ") }
                }
            assertTrue("$name: its buttons are on the screen", buttons.size >= 2)
            assertOneRowNothingCut(name, buttons)
        }
    }

    @Test
    fun appearancesGroupsStayInOneRowAndNoneIsCut() =
        showSettings {
            check(
                SettingsSection.APPEARANCE,
                R.string.settings_theme,
                R.string.settings_font,
                R.string.settings_layout,
            )
        }

    @Test
    fun insightsGroupsStayInOneRowAndNoneIsCut() =
        showSettings {
            check(SettingsSection.INSIGHTS, R.string.settings_ai_web_search, R.string.settings_read_aloud_music)
        }

    @Test
    fun developersGroupsStayInOneRowAndNoneIsCut() =
        showSettings {
            check(SettingsSection.DEVELOPER, R.string.settings_report_button, R.string.settings_link_style)
        }
}
