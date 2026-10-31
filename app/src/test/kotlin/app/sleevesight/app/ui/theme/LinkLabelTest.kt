package app.sleevesight.app.ui.theme

import android.accessibilityservice.AccessibilityServiceInfo
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.SleevesightApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A [LinkLabel] (#442) is one link: a screen reader finds it among the links, with what a tap does
 * as its label; keyboard focus stops on it once; and a tap anywhere in its touch target follows it,
 * once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkLabelTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private var followed = 0

    private fun show(
        link: TextPart.Linked,
        check: (MainActivity) -> Unit,
    ) {
        val accessibility = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        accessibility.setEnabled(true)
        accessibility.setTouchExplorationEnabled(true)
        // A screen reader running: Compose builds nodes only for an enabled service.
        accessibility.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme {
                        LinkLabel(link, LABEL, MaterialTheme.typography.bodyMedium, Modifier.testTag(TAG))
                    }
                }
            }
            compose.waitForIdle()
            scenario.onActivity(check)
        }
    }

    /** The node a screen reader is given for the label, built as Android builds it. */
    private fun exported(activity: MainActivity): AccessibilityNodeInfo {
        val id = compose.onNodeWithTag(TAG).fetchSemanticsNode().id
        val provider = checkNotNull(composeView(activity.window.decorView).accessibilityNodeProvider)
        return checkNotNull(provider.createAccessibilityNodeInfo(id)) { "no node" }
    }

    // The view Compose draws into, under [root].
    private fun composeView(root: View): View =
        if (root.javaClass.simpleName == "AndroidComposeView") {
            root
        } else {
            (root as ViewGroup).children.firstNotNullOf { child ->
                runCatching { composeView(child) }.getOrNull()
            }
        }

    /** That [info] reads [shown], all of it one link span, and that a tap is labelled [LABEL]. */
    private fun assertOneLink(
        info: AccessibilityNodeInfo,
        shown: String,
    ) {
        val text = info.text as Spanned
        assertEquals(shown, text.toString())
        val spans = text.getSpans(0, text.length, ClickableSpan::class.java)
        assertEquals("one link span", 1, spans.size)
        assertEquals(0, text.getSpanStart(spans.single()))
        assertEquals(text.length, text.getSpanEnd(spans.single()))
        val click = info.actionList.single { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        assertEquals(LABEL, click.label?.toString())
    }

    /** That keyboard focus has one stop in the label: its link. */
    private fun assertOneFocusStop() {
        val stops =
            compose
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus) and hasAnyAncestor(hasTestTag(TAG)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertEquals(1, stops.size)
    }

    @Test
    fun aWebLinkIsOneLinkToItsPageWithItsLabel() =
        show(TextPart.Link("example.org", URL)) { activity ->
            assertOneLink(exported(activity), "example.org")
            assertOneFocusStop()
        }

    @Test
    fun anActionIsOneLinkWithItsLabelAndATapAnywhereInItsTargetFollowsItOnce() =
        show(TextPart.Action("What leaves the device?", "what-leaves") { followed++ }) { activity ->
            assertOneLink(exported(activity), "What leaves the device?")
            assertOneFocusStop()
            val target = compose.onNodeWithTag(TAG)
            val size = target.fetchSemanticsNode().size
            // The touch target is taller than the text: its top edge is outside the text, whose box
            // is centred in it.
            target.performTouchInput { click(Offset(size.width / 2f, 1f)) }
            compose.waitForIdle()
            assertEquals("a tap above the text", 1, followed)
            target.performTouchInput { click(Offset(size.width / 2f, size.height / 2f)) }
            compose.waitForIdle()
            assertEquals("a tap on the text, followed once", 2, followed)
            val minimum = 48 * app.resources.displayMetrics.density - 1
            assertTrue("a full touch target: ${size.height} px", size.height >= minimum)
        }

    private companion object {
        const val TAG = "label"
        const val URL = "https://example.org"
        const val LABEL = "Open it"
    }
}
