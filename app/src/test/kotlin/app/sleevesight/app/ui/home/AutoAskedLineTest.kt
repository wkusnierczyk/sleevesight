package app.sleevesight.app.ui.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.TextPart
import org.junit.After
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
 * The note's line on its own (#956, `NamedLinkText`): in a right-to-left layout, the link's node lies
 * over its words, inside the line; and with the link wrapped over two lines (narrow, large font), a
 * screen reader still reads the line first, then the link, both kept. Drawn as on a phone (native
 * graphics), so the words are laid out as they are there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoAskedLineTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val tag = "line"

    @After
    fun stopScreenReader() {
        // The accessibility manager outlives the test: leave no screen reader on for the next one.
        val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        manager.setTouchExplorationEnabled(false)
        manager.setEnabledAccessibilityServiceList(emptyList())
        manager.setEnabled(false)
    }

    // A screen reader that explores by touch, as TalkBack does, for Compose to report to.
    private fun startScreenReader() {
        val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        manager.setEnabled(true)
        manager.setTouchExplorationEnabled(true)
        manager.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    private val parts =
        listOf(
            TextPart.Words("Claude Haiku 5.5 found nothing on the web; "),
            TextPart.Action("asked automatically", SettingsTarget.searchWithAi.linkTag) {},
            TextPart.Words("."),
        )

    private val width = mutableStateOf(400.dp)
    private val scale = mutableStateOf<Float?>(null)

    private fun show(
        direction: LayoutDirection = LayoutDirection.Ltr,
        block: (MainActivity) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var activity: MainActivity? = null
            scenario.onActivity {
                activity = it
                it.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalLayoutDirection provides direction,
                        LocalDensity provides Density(density.density, scale.value ?: density.fontScale),
                    ) {
                        Box(Modifier.width(width.value)) { AutoAskedLine(parts, italic = false, tag) }
                    }
                }
            }
            compose.waitForIdle()
            block(activity!!)
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun rightToLeftTheLinksNodeLiesOverItsWordsInsideTheLine() =
        show(LayoutDirection.Rtl) {
            val line = bounds(tag)
            val link = bounds(AUTO_ASKED_LINK_TAG)
            assertTrue("$link in $line", link.width > 0f)
            assertTrue("$link in $line", link.left >= line.left && link.right <= line.right)
            assertTrue("$link in $line", link.top >= line.top && link.bottom <= line.bottom)
        }

    @Test
    fun aLinkWrappedOverTwoLinesIsStillReadAfterTheLine() =
        show { activity ->
            // Narrow enough, in a large font, that "asked" ends the first of two lines and
            // "automatically" starts the second: the link's box then starts where the line does, at
            // its top, which an order by place would read first.
            var wrapped = false
            search@ for (fontScale in listOf(1.3f, 1.5f, 1.8f, 2f)) {
                scale.value = fontScale
                for (dp in 160..411 step 2) {
                    width.value = dp.dp
                    compose.waitForIdle()
                    val line = bounds(tag)
                    val link = bounds(AUTO_ASKED_LINK_TAG)
                    if (link.left <= line.left + 1f && link.top <= line.top + 1f && link.height > 0f) {
                        wrapped = true
                        break@search
                    }
                }
            }
            assertTrue("no width wraps the link from the first line", wrapped)
            startScreenReader()
            compose.waitForIdle()
            val view = composeView(activity.window.decorView)
            val provider = view.accessibilityNodeProvider
            val lineId = compose.onNodeWithTag(tag).fetchSemanticsNode().id
            val linkId = compose.onNodeWithTag(AUTO_ASKED_LINK_TAG).fetchSemanticsNode().id
            val lineInfo = provider.createAccessibilityNodeInfo(lineId)!!
            val linkInfo = provider.createAccessibilityNodeInfo(linkId)!!
            // Both stops kept: the line's words, the link's.
            assertEquals("Claude Haiku 5.5 found nothing on the web; asked automatically.", lineInfo.text.toString())
            assertEquals("asked automatically", linkInfo.text.toString())
            // The line is read before the link, the link after the line, as Compose tells tests.
            provider.addExtraDataToAccessibilityNodeInfo(lineId, lineInfo, TRAVERSAL_BEFORE, null)
            provider.addExtraDataToAccessibilityNodeInfo(linkId, linkInfo, TRAVERSAL_AFTER, null)
            assertEquals(linkId, lineInfo.extras.getInt(TRAVERSAL_BEFORE, -1))
            assertEquals(lineId, linkInfo.extras.getInt(TRAVERSAL_AFTER, -1))
        }

    // The view Compose draws in, under [root].
    private fun composeView(root: View): View {
        if (root.javaClass.name.endsWith("AndroidComposeView")) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                runCatching { composeView(root.getChildAt(i)) }.getOrNull()?.let { return it }
            }
        }
        error("no compose view")
    }

    private companion object {
        // Where Compose puts the screen reader's order for tests.
        const val TRAVERSAL_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"
        const val TRAVERSAL_AFTER = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALAFTER_VAL"
    }
}
