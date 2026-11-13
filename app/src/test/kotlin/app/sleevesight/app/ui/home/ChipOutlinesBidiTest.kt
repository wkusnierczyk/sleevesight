package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An outlined chip (#1036) in a paragraph that opens in Arabic: the English after it, and the chip in
 * it, are set left to right inside a right-to-left line, and the chip's closing space and the stop
 * after it go to the line's other end. The outline goes round the chip's glyphs only, never round the
 * prose between its parts (Codex, PR #1238).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChipOutlinesBidiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun anOutlineEnclosesNoProseUnderBidiReordering() {
        val text =
            buildAnnotatedString {
                append("أغنية was released in 1985")
                pushStringAnnotation(OUTLINE_TAG, "W1")
                append(" W1 ")
                pop()
                append(".")
            }
        var layout: TextLayoutResult? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    // A paragraph set right to left, as one opening in Arabic is on a phone.
                    val rtl = TextStyle(textDirection = TextDirection.Rtl)
                    Text(text, style = rtl, onTextLayout = { result -> layout = result })
                }
            }
            compose.waitForIdle()
            val result = checkNotNull(layout)
            val chips = outlinedChips(text)
            val rects = chipRects(result, chips)
            assertTrue("an outline is drawn", rects.isNotEmpty())
            val chip = chips.single()

            val prose =
                text.text.indices
                    .filter { it !in chip && !text.text[it].isWhitespace() }
                    .map { it to result.getBoundingBox(it) }
                    .filter { (_, box) -> box.width > 0f }
            for ((offset, box) in prose) {
                val middle = (box.left + box.right) / 2
                for (rect in rects) {
                    assertTrue(
                        "'${text.text[offset]}' at $offset ($middle) is outside the outline $rect",
                        middle !in rect.left..rect.right,
                    )
                }
            }
        }
    }
}
