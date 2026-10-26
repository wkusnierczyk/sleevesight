package app.sleevesight.app.ui.theme

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The developer mark (#536), [DeveloperMark]: drawn in its teal while [LocalDeveloperMark] is on,
 * not at all while it is off, and in either case nothing added to what a screen reader reads: the
 * *Developer* tag is drawn only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeveloperMarkTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun show(
        marked: Boolean,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = false, developerMark = marked) {
                        Box(Modifier.testTag(CONTENT)) { DeveloperMark { Text(PACKAGE) } }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun tealPixels(): Int {
        val bitmap = compose.onNodeWithTag(CONTENT).captureToImage().asAndroidBitmap()
        return bitmap.pixels().count { it == TEAL_LIGHT }
    }

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    // Every text and description in the tree, as a screen reader may meet them, unmerged.
    private fun spoken(): List<String> {
        fun SemanticsNode.walk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walk() }
        return compose
            .onRoot(useUnmergedTree = true)
            .fetchSemanticsNode()
            .walk()
            .flatMap { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text } +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
    }

    @Test
    fun markedTheContentIsOutlinedInTealAndTagged() =
        show(marked = true) {
            assertEquals(1, compose.onAllNodesWithTag(DEVELOPER_MARK_TAG).fetchSemanticsNodes().size)
            val teal = tealPixels()
            assertTrue("$teal pixels of the mark's teal", teal > OUTLINE_PIXELS)
        }

    @Test
    fun unmarkedNothingIsDrawnAndNoMarkIsInTheTree() =
        show(marked = false) {
            assertEquals(0, compose.onAllNodesWithTag(DEVELOPER_MARK_TAG).fetchSemanticsNodes().size)
            assertEquals(0, tealPixels())
        }

    @Test
    fun theTagAddsNoTextForAScreenReader() {
        val tag = app.getString(R.string.developer_mark_tag)
        var unmarked: List<String> = emptyList()
        show(marked = false) { unmarked = spoken() }
        show(marked = true) {
            val marked = spoken()
            assertEquals(unmarked, marked)
            assertTrue(marked.toString(), marked.none { it.contains(tag) })
            assertTrue(marked.toString(), PACKAGE in marked)
        }
    }

    private companion object {
        const val CONTENT = "content"
        const val PACKAGE = "com.example.player"

        // `DEVELOPER_MARK_LIGHT`, opaque, as `Bitmap.getPixels` gives it.
        const val TEAL_LIGHT = 0xFF00796B.toInt()

        // The outline alone, 1.5 dp wide around a line of text, is some hundreds of pixels.
        const val OUTLINE_PIXELS = 300
    }
}
