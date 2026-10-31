package app.sleevesight.app.ui.home

import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * *Sources*' paging group (#666) beyond what `SourcesFoldScreenTest` checks of it: the focus
 * handed on within the group when a button disables itself, and the group at twice the font size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SourcesPagingGroupTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val density =
        ApplicationProvider
            .getApplicationContext<SleevesightApplication>()
            .resources.displayMetrics.density

    /** A track with [count] MusicBrainz lines. */
    private fun long(count: Int): NowPlayingState {
        val mb = (1..count).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val track =
            track(
                sources = mapOf(SourceId.MUSICBRAINZ to done(evidence = mb)),
                evidence = EvidenceSet.of(mb, JvmHasher),
            )
        return NowPlayingState(
            PipelineView(track, aiReady = true),
            track.nowPlaying,
            PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
            settings = Settings(),
        )
    }

    // Without [touch], the test's input is taken as a keyboard's, as in `SourcesPanelScreenTest`.
    private fun show(
        state: NowPlayingState,
        touch: Boolean = false,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                if (touch) enterTouchMode(activity.window.decorView)
                activity.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                    }
                }
            }
            compose.waitForIdle()
            tap(SOURCES_CHIP_TAG)
            block()
        }
    }

    /** Puts [decor]'s window in touch mode, as a touch on the screen does. */
    private fun enterTouchMode(decor: View) {
        val root = decor.parent
        val ensure = root.javaClass.getDeclaredMethod("ensureTouchModeLocally", Boolean::class.javaPrimitiveType)
        ensure.isAccessible = true
        ensure.invoke(root, true)
        assertTrue(decor.isInTouchMode)
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun focused() = compose.onAllNodes(isFocused()).fetchSemanticsNodes().size

    // Where [tag]'s node is laid out, unclipped: the list runs past the screen's bottom.
    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag).fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }

    @Test
    fun fromAKeyboardShowAllHandsTheFocusToShowLess() =
        show(long(20)) {
            tap(FOLD_ALL_TAG)
            compose.onNodeWithTag(FOLD_LESS_TAG).assertIsFocused()
        }

    @Test
    fun fromAKeyboardShowMoreReachingTheEndHandsTheFocusToShowLessAndShowLessToShowMore() =
        show(long(20)) {
            tap(FOLD_MORE_TAG)
            // Not at the end: *Show more* can still be used, and no focus is moved to *Show less*.
            compose.onNodeWithTag(FOLD_LESS_TAG).assertIsNotFocused()
            tap(FOLD_MORE_TAG)
            compose.onNodeWithTag(FOLD_LESS_TAG).assertIsFocused()
            tap(FOLD_LESS_TAG)
            compose.onNodeWithTag(FOLD_MORE_TAG).assertIsFocused()
        }

    @Test
    fun touchedNoPagingButtonTakesTheFocus() =
        show(long(20), touch = true) {
            tap(FOLD_ALL_TAG)
            assertEquals(0, focused())
            tap(FOLD_LESS_TAG)
            assertEquals(0, focused())
        }

    // Android scales large text less than small (non-linear font scaling, Android 14 on), so at
    // 200% the labels still fit side by side at a common phone's width; where they would not, the
    // group stacks them (`ButtonGroupTest`). Either way: in order, nothing cut, 48 dp targets.
    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeTheButtonsKeepTheirOrderUnderTheListWholeAndEach48DpOrMore() =
        show(long(20)) {
            val tags = listOf(FOLD_LESS_TAG, FOLD_MORE_TAG, FOLD_ALL_TAG)
            val buttons = tags.map(::bounds)
            for ((a, b) in buttons.zipWithNext()) {
                assertTrue("in order, side by side or stacked: $a, $b", a.right <= b.left || a.bottom <= b.top)
            }
            for ((tag, b) in tags.zip(buttons)) {
                assertTrue("$tag 48 dp or more high: $b", b.height / density >= 48f - 0.5f)
                val label = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text]
                val text = labelBounds(label.single().text)
                assertTrue(
                    "$tag's label whole in its button: $text in $b",
                    text.left >= b.left && text.right <= b.right,
                )
            }
            val last = bounds(LINE_PREFIX + "MB8")
            assertTrue("under the list", buttons.all { last.bottom <= it.top })
        }

    private fun labelBounds(label: String): Rect =
        compose
            .onNodeWithText(label, useUnmergedTree = true)
            .fetchSemanticsNode()
            .let { Rect(it.positionInRoot, it.size.toSize()) }
}
