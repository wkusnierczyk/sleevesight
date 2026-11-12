package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.MUSICBRAINZ_WAIT_SLIDER_TAG
import app.sleevesight.app.ui.settings.MUSICBRAINZ_WAIT_STOPS_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * *Wait when busy*'s labels under its stops (#1120) at twice the font size on a narrow phone: each
 * within the row, none over another, none broken inside a word, in the order of the stops. Native
 * graphics, for the labels' real widths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-420dpi", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicBrainzWaitLargeFontTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private val labels = listOf("Don’t wait", "15 s", "30 s", "1 min")

    @Test
    fun theStopsLabelsFitTheRowWithoutOverlapOrBrokenWords() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.SOURCES)
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText("Wait when busy").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(MUSICBRAINZ_WAIT_SLIDER_TAG).performScrollTo()
            compose.onNodeWithTag(MUSICBRAINZ_WAIT_STOPS_TAG).performScrollTo()
            val row = compose.onNodeWithTag(MUSICBRAINZ_WAIT_STOPS_TAG).fetchSemanticsNode().boundsInRoot
            val slider = compose.onNodeWithTag(MUSICBRAINZ_WAIT_SLIDER_TAG).fetchSemanticsNode().boundsInRoot
            val bounds = labels.map { label -> bounds(label) }
            for ((label, box) in labels.zip(bounds)) {
                assertTrue(
                    "$label within the row: $box in $row",
                    box.left >= row.left - 1 && box.right <= row.right + 1,
                )
                assertTrue("$label under the slider: $box, $slider", box.top >= slider.bottom - 1)
                val lines = layout(label)
                for (line in 0 until lines.lineCount) {
                    val end = lines.getLineEnd(line)
                    // A line ends at the text's end or where a space was.
                    assertTrue(
                        "$label broken inside a word: ${(0 until lines.lineCount).map {
                            lines.getLineEnd(
                                it,
                            )
                        }} ${box.width}",
                        end == label.length || label[end - 1] == ' ' || label[end] == ' ',
                    )
                }
            }
            for ((a, b) in bounds.zipWithNext()) {
                assertTrue("in order and apart: $a, $b", a.right <= b.left)
            }
        }
    }

    /** The label [label] under the stops: the value at the row's right may read the same. */
    private fun stop(label: String) =
        compose.onNode(
            hasText(label) and hasAnyAncestor(hasTestTag(MUSICBRAINZ_WAIT_STOPS_TAG)),
            useUnmergedTree = true,
        )

    private fun bounds(label: String): Rect = stop(label).fetchSemanticsNode().boundsInRoot

    private fun layout(label: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        stop(label)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }
}
