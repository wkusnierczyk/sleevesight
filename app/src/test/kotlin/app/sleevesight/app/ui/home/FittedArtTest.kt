package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The cover on Home's cover side (#1097, [FittedArt]) as large as fits with the position under it, where
 * the position is shorter for a wider cover: its times on two lines below a width, side by side from it.
 */
@RunWith(RobolectricTestRunner::class)
class FittedArtTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // A position 74 tall below 180 wide (its times on two lines) and 42 from 180 (side by side).
    private object Position : MeasurePolicy {
        fun height(width: Int) = if (width >= BESIDE) BESIDE_HEIGHT else STACKED_HEIGHT

        override fun MeasureScope.measure(
            measurables: List<Measurable>,
            constraints: Constraints,
        ): MeasureResult = layout(constraints.maxWidth, height(constraints.maxWidth)) {}

        override fun IntrinsicMeasureScope.maxIntrinsicHeight(
            measurables: List<IntrinsicMeasurable>,
            width: Int,
        ): Int = height(width)

        override fun IntrinsicMeasureScope.minIntrinsicHeight(
            measurables: List<IntrinsicMeasurable>,
            width: Int,
        ): Int = height(width)
    }

    @Test
    fun theCoverIsTheLargestThatFitsWhereAWiderCoverHasAShorterPosition() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    // One pixel to the dp, so the sizes read as given.
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        Box(Modifier.size(WIDTH.dp, HEIGHT.dp)) {
                            FittedArt(
                                cover = { modifier -> Box(modifier.testTag(ART)) },
                                progress = { modifier -> Layout({}, modifier, Position) },
                                gap = GAP.dp,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            // 186 + 8 + 42 = 236: side by side, it fits; a search assuming that a cover that does not
            // fit has no larger one that does stopped at 154 (154 + 8 + 74).
            val art = compose.onNodeWithTag(ART).fetchSemanticsNode().size
            assertEquals(HEIGHT - GAP - BESIDE_HEIGHT, art.width)
            assertEquals(art.width, art.height)
        }
    }

    @Test
    fun aVideosWideCoverIsAsWideAsTheHeightLeavesAtItsOwnRatio() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        Box(Modifier.size(WIDTH.dp, WIDE_HEIGHT.dp)) {
                            FittedArt(
                                cover = { modifier -> Box(modifier.testTag(ART)) },
                                progress = { modifier -> Layout({}, modifier, Position) },
                                gap = GAP.dp,
                                ratio = 16f / 9f,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            // 200 - 8 - 42 = 150 tall leaves a 16:9 cover 266 wide (#1257), not a square 150 one.
            val art = compose.onNodeWithTag(ART).fetchSemanticsNode().size
            assertEquals(WIDE_HEIGHT - GAP - BESIDE_HEIGHT, art.height)
            assertEquals(266, art.width)
        }
    }

    private companion object {
        const val WIDE_HEIGHT = 200
        const val ART = "art"
        const val WIDTH = 400
        const val HEIGHT = 236
        const val GAP = 8
        const val BESIDE = 180
        const val BESIDE_HEIGHT = 42
        const val STACKED_HEIGHT = 74
    }
}
