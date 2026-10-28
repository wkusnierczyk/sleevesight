package app.sleevesight.app.ui.home

import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The guide's picture full screen (#673): opened by a tap on the picture or on its magnifier,
 * magnified by a double tap or a pinch, and closed by *Close* or Back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GuideMagnifierTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun show(block: () -> Unit) {
        val actions = NowPlayingActions(normalizer::normalize, playerLabel = { "Spotify" })
        val settings = Settings(followedPlayers = mapOf(Settings.SPOTIFY to true))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NowPlayingContent(
                                    NowPlayingState(PipelineView(), playing = null, settings = settings),
                                    actions,
                                )
                            }
                        }
                    }
                }
            }
            compose.awaitIdleHome()
            compose.waitUntil(WAIT) {
                compose.onAllNodesWithTag(guideNoteTag(GuidePart.SOURCES)).fetchSemanticsNodes().isNotEmpty()
            }
            block()
        }
    }

    // The screen's saved state, as an activity keeps it: [restore] saves it, drops the content and
    // composes it afresh from what was saved, as Android does for an activity recreated on a rotation.
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun restore() {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    // Where the picture's top left corner is drawn, magnified.
    private fun pictureCorner(): Offset {
        val node = compose.onNodeWithTag(GUIDE_MAGNIFIED_PICTURE_TAG).fetchSemanticsNode()
        return node.layoutInfo.coordinates.localToRoot(Offset.Zero)
    }

    private fun shown() = compose.onAllNodesWithTag(GUIDE_MAGNIFIED_TAG).fetchSemanticsNodes().isNotEmpty()

    // The picture's width as drawn, magnified, not clipped to the screen as its bounds are.
    private fun pictureWidth(): Float {
        val node = compose.onNodeWithTag(GUIDE_MAGNIFIED_PICTURE_TAG).fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        val right = coordinates.localToRoot(Offset(node.size.width.toFloat(), 0f))
        return right.x - coordinates.localToRoot(Offset.Zero).x
    }

    @Test
    fun aTapOnThePictureOpensItFullScreen() =
        show {
            assertTrue(!shown())
            compose.onNodeWithTag(GUIDE_PICTURE_TAG).performScrollTo().performTouchInput {
                // In the drawn Home, on the guide's left, clear of the magnifier at its bottom.
                click(Offset(width * 0.15f, height * 0.4f))
            }
            compose.waitForIdle()
            assertTrue(shown())
            compose
                .onNodeWithTag(GUIDE_MAGNIFIED_PICTURE_TAG)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(app.getString(R.string.home_guide_magnified)),
                    ),
                )
        }

    @Test
    fun theMagnifierOpensItFullScreenAndCloseReturns() =
        show {
            compose.onNodeWithTag(GUIDE_MAGNIFY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(shown())
            compose.onNodeWithTag(GUIDE_MAGNIFIED_CLOSE_TAG).performClick()
            compose.waitForIdle()
            assertTrue(!shown())
            compose.onNodeWithTag(HOME_GUIDE_TAG).assertExists()
        }

    @Test
    fun backReturnsFromFullScreen() =
        show {
            compose.onNodeWithTag(GUIDE_MAGNIFY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(shown())
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
            compose.waitForIdle()
            assertTrue(!shown())
        }

    @Test
    fun aDoubleTapMagnifiesThePictureAndASecondFitsItBack() =
        show {
            compose.onNodeWithTag(GUIDE_MAGNIFY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            val fitted = pictureWidth()
            compose.onNodeWithTag(GUIDE_MAGNIFIED_PICTURE_TAG).performTouchInput { doubleClick(center) }
            compose.waitForIdle()
            assertEquals(fitted * GuideZoom.DOUBLE_TAP_SCALE, pictureWidth(), fitted * 0.02f)
            compose.onNodeWithTag(GUIDE_MAGNIFIED_PICTURE_TAG).performTouchInput { doubleClick(center) }
            compose.waitForIdle()
            assertEquals(fitted, pictureWidth(), 1f)
        }

    @Test
    fun aPinchMagnifiesThePicture() =
        show {
            compose.onNodeWithTag(GUIDE_MAGNIFY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            val fitted = pictureWidth()
            compose.onNodeWithTag(GUIDE_MAGNIFIED_PICTURE_TAG).performTouchInput {
                pinch(
                    start0 = center - Offset(20f, 0f),
                    end0 = center - Offset(200f, 0f),
                    start1 = center + Offset(20f, 0f),
                    end1 = center + Offset(200f, 0f),
                )
            }
            compose.waitForIdle()
            assertTrue("${pictureWidth()} after $fitted", pictureWidth() > fitted * 2f)
        }

    @Test
    fun reopenedThePictureIsFittedAgain() =
        show {
            compose.onNodeWithTag(GUIDE_MAGNIFY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            val fitted = pictureWidth()
            compose.onNodeWithTag(GUIDE_MAGNIFIED_PICTURE_TAG).performTouchInput { doubleClick(center) }
            compose.waitForIdle()
            assertTrue(pictureWidth() > fitted * 2f)
            compose.onNodeWithTag(GUIDE_MAGNIFIED_CLOSE_TAG).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(GUIDE_MAGNIFY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(fitted, pictureWidth(), 1f)
        }

    @Test
    fun aMagnifiedPictureStaysMagnifiedAndWhereItWasAcrossARecreation() =
        show {
            compose.onNodeWithTag(GUIDE_MAGNIFY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(GUIDE_MAGNIFIED_PICTURE_TAG).performTouchInput {
                doubleClick(Offset(width * 0.3f, height * 0.7f))
            }
            compose.waitForIdle()
            val width = pictureWidth()
            val corner = pictureCorner()
            restore()
            assertTrue(shown())
            assertEquals(width, pictureWidth(), 1f)
            assertEquals(corner.x, pictureCorner().x, 1f)
            assertEquals(corner.y, pictureCorner().y, 1f)
        }

    private companion object {
        const val WAIT = 5_000L
    }
}
