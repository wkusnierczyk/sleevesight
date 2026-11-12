package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.DESCRIPTION_TAG
import app.sleevesight.app.ui.settings.ROW_TITLE_TAG
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * The heading over the read-aloud rows (#946): *Read aloud*, drawn as a setting's title (as *Ask AI
 * automatically*'s) and heard as a heading, with its description under it, folded to one line; the
 * settings' search finds it, by its name or its text, with the rows under it. And *Ask AI
 * automatically*'s description ends with a full stop.
 *
 * The texts are written out, not read from the strings, so they are checked word for word. Drawn as
 * on a phone (native graphics), so the description is wide enough to fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadAloudHeadingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun show(block: () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText(text(R.string.settings_section_insights)).fetchSemanticsNodes().isNotEmpty()
            }
            block()
        }

    private val title = hasText(HEADING) and hasTestTag(ROW_TITLE_TAG)

    private fun waitFor(
        matcher: SemanticsMatcher,
        unmerged: Boolean = true,
    ) = compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = WAIT_MS) {
        compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
    }

    private fun layout(matcher: SemanticsMatcher): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNode(matcher, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }

    @Test
    fun theHeadingIsDrawnAsASettingsTitleAndHeardAsAHeading() =
        show {
            compose.openSection(SettingsSection.INSIGHTS)
            waitFor(title)
            val node = compose.onNode(title, useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
            assertTrue("heard as a heading", SemanticsProperties.Heading in node.config)
            val ask = hasText(text(R.string.settings_search_with_ai)) and hasTestTag(ROW_TITLE_TAG)
            val askStyle = layout(ask).layoutInput.style
            val style = layout(title).layoutInput.style
            assertEquals(askStyle.fontSize, style.fontSize)
            assertEquals(askStyle.fontWeight, style.fontWeight)
            assertEquals(askStyle.color, style.color)
            // Over the first of the read-aloud rows.
            val music = compose.onNode(hasText(MUSIC) and hasTestTag(ROW_TITLE_TAG), useUnmergedTree = true)
            assertTrue(music.fetchSemanticsNode().positionInRoot.y > node.positionInRoot.y)
        }

    @Test
    fun itsDescriptionHasThePickedTextAndFoldsToOneLine() =
        show {
            compose.openSection(SettingsSection.INSIGHTS)
            val description = hasTestTag(DESCRIPTION_TAG) and hasText(DESCRIPTION)
            // The description's own node, which a screen reader reads, holds its text.
            waitFor(description, unmerged = false)
            val node = compose.onNode(description).performScrollTo().fetchSemanticsNode()
            assertEquals(
                text(R.string.settings_description_collapsed),
                node.config.getOrElseNullable(SemanticsProperties.StateDescription) { null },
            )
            val folded = layout(hasText(DESCRIPTION))
            assertEquals("one line", 1, folded.lineCount)
            assertTrue("cut, with an ellipsis", folded.hasVisualOverflow)
            // Under the heading.
            val heading = compose.onNode(title, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(node.positionInRoot.y > heading.positionInRoot.y)
        }

    @Test
    fun askAiAutomaticallysDescriptionEndsWithAFullStop() {
        val summary = app.getString(R.string.settings_search_with_ai_summary, "AI web search")
        assertTrue(summary, summary.endsWith("with web search, also Exa API calls."))
    }

    @Test
    fun theSearchFindsTheHeadingByItsNameOrItsTextWithTheReadAloudRows() =
        show {
            for (query in listOf(HEADING, "network voices")) {
                compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
                compose.waitForIdle()
                // The heading's name, not the search box holding the same words.
                waitFor(title and !hasSetTextAction())
                compose.onNode(title and !hasSetTextAction(), useUnmergedTree = true).performScrollTo()
                for (row in listOf(MUSIC, TRACK_LINE, NEW_INSIGHTS)) {
                    val matcher = hasText(row) and hasTestTag(ROW_TITLE_TAG)
                    waitFor(matcher)
                    compose.onNode(matcher, useUnmergedTree = true).performScrollTo()
                }
                compose.onNode(hasText(DESCRIPTION, substring = true), useUnmergedTree = true).assertExists()
            }
        }

    @Test
    fun aSearchThatFindsOnlyAnotherInsightsRowLeavesTheHeadingOut() =
        show {
            val ask = text(R.string.settings_search_with_ai)
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(ask)
            compose.waitForIdle()
            // *Insights* shows, filtered to the row found.
            val found = hasText(ask) and hasTestTag(ROW_TITLE_TAG) and !hasSetTextAction()
            waitFor(found)
            compose.onNode(found, useUnmergedTree = true).performScrollTo()
            assertTrue(
                "another Insights row is filtered out",
                compose
                    .onAllNodes(hasText(MUSIC) and hasTestTag(ROW_TITLE_TAG), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            assertTrue(
                "no heading over rows that do not show",
                compose.onAllNodes(title, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            )
        }

    private companion object {
        const val HEADING = "Read aloud"
        const val DESCRIPTION =
            "The speaker button beside insights triggers your phone’s text-to-speech engine to read the text " +
                "aloud. The engine is the one chosen in Android’s settings. Note: some, such as Google’s network " +
                "voices, send the text to their server to be spoken."
        const val MUSIC = "Music while reading aloud"
        const val TRACK_LINE = "Read the track line first"
        const val NEW_INSIGHTS = "Read new insights aloud"
    }
}
