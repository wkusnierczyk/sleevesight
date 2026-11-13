package app.sleevesight.app.ui.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import app.sleevesight.app.ui.openSection
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * No check mark on the chosen button of a button group (#1234): the fill alone marks the choice, so
 * the chosen label takes no more room than the others. In *Theme*, *Reactive* and *Saver*'s count,
 * with each choice chosen in turn, at the default font size and a large one: the chosen label sits
 * in the middle of its button, with nothing before it, and never wraps where the others fit; a
 * screen reader still reads the chosen button as selected, and only it. Native graphics, for the
 * labels' real widths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChosenButtonNoCheckTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    /**
     * A group: its [name], read before each button's label, its [section], and the settings with
     * each of its choices chosen, in the buttons' order.
     */
    private class Group(
        val name: Int,
        val section: SettingsSection,
        val choices: List<(Settings) -> Settings>,
    )

    private val theme =
        Group(
            R.string.settings_theme,
            SettingsSection.APPEARANCE,
            ThemeChoice.entries.map { choice -> { s: Settings -> s.copy(theme = choice) } },
        )
    private val reactive =
        Group(
            R.string.settings_write_automatically,
            SettingsSection.INSIGHTS,
            WRITING_MODES.map { it.mode }.map { mode -> { s: Settings -> s.copy(writeAutomatically = mode) } },
        )
    private val saverCount =
        Group(
            R.string.settings_pause_auto_write_after,
            SettingsSection.INSIGHTS,
            Settings.PAUSE_AUTO_WRITE_CHOICES.map { n -> { s: Settings -> s.copy(pauseAutoWriteAfter = n) } },
        )

    /** The buttons of the group read after [name]. */
    private fun buttons(name: String): List<SemanticsNode> =
        compose.onAllNodes(readAfter(name), useUnmergedTree = true).fetchSemanticsNodes()

    /** A button read after [name]. */
    private fun readAfter(name: String) =
        radio and
            SemanticsMatcher("read after $name") { node ->
                node.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                    .any { it.startsWith("$name: ") }
            }

    private fun text(button: SemanticsNode) = button.children.single { SemanticsProperties.Text in it.config }

    private fun lines(button: SemanticsNode): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        text(button).config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().lineCount
    }

    /** With each of [group]'s choices chosen in turn, the chosen button as described above. */
    private fun assertNoCheck(group: Group) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(group.section)
            val name = app.getString(group.name)
            group.choices.forEachIndexed { index, choose ->
                runBlocking { settings.update(choose) }
                compose.waitUntil(WAIT_MS) { buttons(name).size == group.choices.size }
                // Scrolled to the group's last button, so that the whole group is on the screen.
                compose.onAllNodes(readAfter(name), useUnmergedTree = true)[group.choices.size - 1].performScrollTo()
                compose.waitForIdle()
                val shown = buttons(name)
                val selected = shown.map { it.config.getOrNull(SemanticsProperties.Selected) }
                assertEquals("$name, choice $index: only it selected", shown.indices.map { it == index }, selected)
                val chosen = shown[index]
                val label = text(chosen).config[SemanticsProperties.Text].joinToString()
                val others = shown.filterIndexed { i, _ -> i != index }
                if (others.all { lines(it) == 1 }) {
                    assertEquals("$name: \"$label\" chosen, on one line as the others", 1, lines(chosen))
                }
                val button = chosen.boundsInRoot
                assertTrue("$name: \"$label\" on the screen, $button", button.width > 0f)
                val inside = text(chosen).boundsInRoot
                val before = inside.left - button.left
                val after = button.right - inside.right
                assertTrue(
                    "$name: \"$label\" chosen, in the middle: $before px before, $after after",
                    abs(before - after) <= 1f,
                )
            }
        }
    }

    @Test
    fun themeAtTheDefaultFontSize() = assertNoCheck(theme)

    @Test
    fun reactiveAtTheDefaultFontSize() = assertNoCheck(reactive)

    @Test
    fun saversCountAtTheDefaultFontSize() = assertNoCheck(saverCount)

    @Test
    @Config(fontScale = 1.3f)
    fun themeAtALargeFontSize() = assertNoCheck(theme)

    @Test
    @Config(fontScale = 1.3f)
    fun reactiveAtALargeFontSize() = assertNoCheck(reactive)

    @Test
    @Config(fontScale = 1.3f)
    fun saversCountAtALargeFontSize() = assertNoCheck(saverCount)
}
