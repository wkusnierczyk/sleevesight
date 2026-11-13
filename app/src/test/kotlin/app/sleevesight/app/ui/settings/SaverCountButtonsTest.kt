package app.sleevesight.app.ui.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
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
 * *Saver*'s count (#1212, #1234): a button group of the numbers alone, under *Saver*'s description,
 * with no name or line of its own, so that the chosen button's label never wraps where the others
 * do not: at the default font size, a large one and twice the default on a narrow phone, with each
 * count chosen, every label is on one line and every button as tall as the others. A screen reader
 * reads each button after the setting's name, "Saver: 5".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SaverCountButtonsTest {
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

    private val name get() = app.getString(R.string.settings_pause_auto_write)

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    /** The count's buttons, by what a screen reader reads for them. */
    private fun buttons(): List<SemanticsNode> =
        compose.onAllNodes(radio, useUnmergedTree = true).fetchSemanticsNodes().filter { node ->
            node.config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
                .any { it.startsWith("$name: ") }
        }

    /** Scrolls to the count's last button, so that all of them are on the screen. */
    private fun scrollToButtons() {
        compose.onNode(hasText("10") and isSelectable()).performScrollTo()
        compose.waitForIdle()
    }

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.INSIGHTS)
            block()
        }
    }

    /** With each count chosen in turn, the buttons' labels are each on one line, the buttons of one height. */
    private fun assertNoLabelWraps() =
        show {
            for (count in Settings.PAUSE_AUTO_WRITE_CHOICES) {
                runBlocking { settings.update { it.copy(pauseAutoWriteAfter = count) } }
                compose.waitUntil(WAIT_MS) { buttons().size == Settings.PAUSE_AUTO_WRITE_CHOICES.size }
                scrollToButtons()
                compose.waitForIdle()
                val shown = buttons()
                for (button in shown) {
                    val text = button.children.single { SemanticsProperties.Text in it.config }
                    val label = text.config[SemanticsProperties.Text].joinToString()
                    val layouts = mutableListOf<TextLayoutResult>()
                    text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                    assertEquals("\"$label\" with $count chosen: one line", 1, layouts.single().lineCount)
                }
                val heights = shown.map { it.boundsInRoot.height }
                assertTrue("with $count chosen, one height: $heights", heights.distinct().size == 1)
            }
        }

    @Test
    fun noLabelWrapsAtTheDefaultFontSize() = assertNoLabelWraps()

    @Test
    @Config(fontScale = 1.3f)
    fun noLabelWrapsAtALargeFontSize() = assertNoLabelWraps()

    @Test
    @Config(qualifiers = "w360dp-h780dp-420dpi", fontScale = 2f)
    fun noLabelWrapsAtTwiceTheFontSizeOnANarrowPhone() = assertNoLabelWraps()

    @Test
    fun eachButtonIsReadAfterTheName() =
        show {
            compose.waitUntil(WAIT_MS) { buttons().isNotEmpty() }
            scrollToButtons()
            assertEquals("Saver", name)
            val read = buttons().map { it.config[SemanticsProperties.ContentDescription].single() }
            assertEquals(Settings.PAUSE_AUTO_WRITE_CHOICES.map { "Saver: $it" }, read)
            val labels = buttons().map { it.children.single { c -> SemanticsProperties.Text in c.config } }
            assertEquals(
                Settings.PAUSE_AUTO_WRITE_CHOICES.map { "$it" },
                labels.map { it.config[SemanticsProperties.Text].joinToString() },
            )
        }
}
