package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import app.sleevesight.app.ui.settings.CROSSFADE_SLIDER_TAG
import app.sleevesight.app.ui.settings.CROSSFADE_STOPS_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.WriteAutomatically
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
 * *Crossfade*'s labels on a narrow screen at twice the font size (#1365): of its seventeen stops
 * only the ends are labelled, and the blank ones between take no room, so *Autodetect* and *15 s*
 * each keep one line, within the row, apart. Native graphics, for the text's measure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrossfadeStopLabelsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    /** The labels under the stops: each one's text, lines and bounds. */
    private fun labels(): List<Triple<String, Int, Rect>> {
        val out = mutableListOf<Triple<String, Int, Rect>>()

        fun collect(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            if (!text.isNullOrEmpty()) {
                val layouts = mutableListOf<TextLayoutResult>()
                n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                out += Triple(text, layouts.single().lineCount, n.boundsInRoot)
            }
            n.children.forEach(::collect)
        }
        collect(compose.onNodeWithTag(CROSSFADE_STOPS_TAG, useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    @Test
    fun theEndsKeepOneLineEachWithinTheRowAndApart() {
        runBlocking { settings.update { it.copy(writeAutomatically = WriteAutomatically.WHEN_I_SKIP) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.INSIGHTS)
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(CROSSFADE_SLIDER_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(CROSSFADE_SLIDER_TAG).performScrollTo()
            val row =
                compose
                    .onNodeWithTag(CROSSFADE_STOPS_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val shown = labels()
            assertEquals(listOf("Autodetect", "15 s"), shown.map { it.first })
            shown.forEach { (text, lines, _) -> assertEquals("$text on one line", 1, lines) }
            val (first, last) = shown.map { it.third }
            assertTrue("apart: $first, $last", first.right <= last.left)
            assertTrue("within the row: $first, $last in $row", first.left >= row.left && last.right <= row.right)
        }
    }
}
