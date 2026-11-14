package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The rows' names lined up with their buttons (#1199): each name's first line centred on its button,
 * at the default font size and twice it, the Composer row's longer button among them, and a name shown
 * whole on several lines still starting level with its button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItemRowAlignmentScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val playing =
        sundown.copy(
            title = "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante",
            artists = "Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields",
            album = "Mozart: Piano Concertos Nos. 20 & 23",
            mediaId = "spotify:track:k545",
        )

    private fun bounds(
        tag: String,
        unmerged: Boolean = false,
    ): Rect = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    // The centre of the first line of [item]'s name, its line [line] px tall, against its button's centre.
    private fun offset(
        item: InsightItem,
        line: Float,
    ): Float {
        val button = bounds(itemRowTag(item))
        val name = bounds(itemNameTag(item), unmerged = true)
        return abs((name.top + line / 2) - button.center.y)
    }

    private fun check(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val normalized = normalizer.normalize(playing)
        val rows = itemRows(playing, normalized, splitOf(null, normalized))
        val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val context =
                        ItemContext(null, settings, FakeItemNotes(), false, {}, { _, _, _ -> }, readAloud = null)
                    val folds = itemFolds(ItemFoldMemory(), playing, NewTrackFold.FOLDED)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ItemRows(rows, false, folds, context) {}
                    }
                }
            }
            compose.waitForIdle()
            // One line each: its height is the line's.
            val lines = rows.associate { it.item to bounds(itemNameTag(it.item), unmerged = true).height }
            for (row in rows) {
                val off = offset(row.item, lines.getValue(row.item))
                assertTrue("${row.item} at $scale: $off px", off <= 1f)
            }
            // The song's title shown whole: several lines, the first still on its button.
            compose.onNodeWithTag(itemNameTag(InsightItem.SONG), useUnmergedTree = true).performClick()
            compose.waitForIdle()
            val line = lines.getValue(InsightItem.SONG)
            assertTrue(bounds(itemNameTag(InsightItem.SONG), unmerged = true).height > line * 1.5f)
            val off = offset(InsightItem.SONG, line)
            assertTrue("whole song at $scale: $off px", off <= 1f)
        }
    }

    @Test
    fun eachNamesFirstLineIsCentredOnItsButtonAtTheDefaultFontSize() = check(1f)

    @Test
    fun eachNamesFirstLineIsCentredOnItsButtonAtTwiceIt() = check(2f)
}
