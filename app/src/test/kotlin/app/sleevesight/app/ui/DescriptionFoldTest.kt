package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.DESCRIPTION_TAG
import app.sleevesight.app.ui.settings.ROW_TITLE_TAG
import app.sleevesight.app.ui.settings.SEARCH_CLEAR_TAG
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.settings.TAP_BELOW
import app.sleevesight.app.ui.settings.oneLine
import app.sleevesight.app.ui.settings.paragraphsOf
import app.sleevesight.core.settings.DescriptionsOnReturn
import app.sleevesight.core.settings.Layout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings' descriptions folded to one line (#847): a tap unfolds one and another folds it, a
 * switch row's description has its own tap, one that fits on one line does not fold, a search shows
 * the descriptions it finds open, a screen reader hears the whole text and its fold, and a rotation
 * keeps the folds, a search's own too (#874). A tap a little below a folded description still
 * unfolds it, but not where a button group or a note under it takes the tap (#874).
 *
 * Drawn as on a phone (native graphics): Robolectric's own graphics measure text too narrow for
 * anything to fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DescriptionFoldTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // In memory, and read on the main thread, as `SettingsFoldTest`'s.
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    // The screen's saved state, as an activity keeps it ([restore]).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun restore() {
        val saved = registry.performSave()
        recompose(SaveableStateRegistry(saved) { true })
    }

    private fun recompose(next: SaveableStateRegistry) {
        emitted = false
        compose.waitForIdle()
        registry = next
        emitted = true
        compose.waitForIdle()
    }

    private fun show(
        section: SettingsSection = SettingsSection.APPEARANCE,
        block: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                if (emitted) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
        }
        compose.openSection(section)
        block()
    }

    private val spin get() = text(R.string.settings_spin_logo_summary)

    /** The description that folds and holds [words], as a screen reader finds it. */
    private fun description(words: String): SemanticsNodeInteraction {
        val matcher = hasTestTag(DESCRIPTION_TAG) and hasText(words, substring = true)
        compose.waitUntil(conditionDescription = "the description $words", timeoutMillis = WAIT_MS) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNode(matcher).performScrollTo()
    }

    private fun SemanticsNodeInteraction.state(): String? =
        fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }

    private fun SemanticsNodeInteraction.tapLabel(): String? =
        fetchSemanticsNode().config[SemanticsActions.OnClick].label

    /** How the text [words] is laid out, as drawn. */
    private fun layout(words: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNode(hasText(words, substring = true), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }

    private fun tap(node: SemanticsNodeInteraction) {
        node.performClick()
        compose.waitForIdle()
    }

    private fun tapTitle(id: Int) =
        tap(compose.onNode(hasText(text(id)) and hasTestTag(ROW_TITLE_TAG), useUnmergedTree = true).performScrollTo())

    @Test
    fun aDescriptionShowsFoldedToOneLineAndATapUnfoldsItAndAnotherFoldsIt() =
        show {
            val node = description(spin)
            assertEquals(text(R.string.settings_description_collapsed), node.state())
            val folded = layout(spin)
            assertEquals("one line", 1, folded.lineCount)
            assertTrue("cut, with an ellipsis", folded.hasVisualOverflow)
            tap(node)
            assertEquals(text(R.string.settings_description_expanded), description(spin).state())
            val unfolded = layout(spin)
            assertTrue("the whole text, on several lines", unfolded.lineCount > 1 && !unfolded.hasVisualOverflow)
            tap(description(spin))
            assertEquals(text(R.string.settings_description_collapsed), description(spin).state())
            assertEquals(1, layout(spin).lineCount)
        }

    @Test
    fun aSectionsIntroFoldsItsParagraphsJoinedOnOneLineAndUnfoldsToThem() =
        show(SettingsSection.PLAYERS) {
            val intro = text(R.string.settings_players_long)
            val paragraphs = paragraphsOf(intro)
            assertTrue("two paragraphs", paragraphs.size > 1)
            // Folded, one text: the paragraphs joined by a space.
            val node = description(paragraphs.first())
            assertEquals(
                oneLine(intro),
                node
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text,
            )
            tap(node)
            val open = description(paragraphs.first()).fetchSemanticsNode()
            assertEquals(paragraphs, open.config[SemanticsProperties.Text].map { it.text })
        }

    @Test
    @Config(qualifiers = "w900dp-h1200dp-420dpi")
    fun aDescriptionThatFitsOnOneLineDoesNotFold() =
        show(SettingsSection.SOURCES) {
            val short = text(R.string.settings_source_lastfm_summary)
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(short).fetchSemanticsNodes().isNotEmpty() }
            // A long one still folds at this width: the section's intro.
            description(paragraphsOf(text(R.string.settings_sources_long)).first())
            assertTrue(
                "no fold for one that fits",
                compose.onAllNodes(hasTestTag(DESCRIPTION_TAG) and hasText(short)).fetchSemanticsNodes().isEmpty(),
            )
            // Read with its switch, as before: no node, state or tap of its own.
            compose.onNode(isToggleable() and hasText(short)).assertExists()
        }

    @Test
    fun aTapOnASwitchRowsDescriptionFoldsItWithoutTogglingTheSwitchAndATapOnItsNameToggles() =
        show {
            assertTrue(settings.now().spinLogo)
            // On the name, even next to the folded description: its tap does not reach over the name.
            tapTitle(R.string.settings_spin_logo)
            compose.waitUntil(WAIT_MS) { !settings.now().spinLogo }
            tap(description(spin))
            assertEquals(text(R.string.settings_description_expanded), description(spin).state())
            // Only the name's tap toggles it back: had the tap on the description toggled it too,
            // the name's would turn it off again.
            tapTitle(R.string.settings_spin_logo)
            compose.waitUntil(WAIT_MS) { settings.now().spinLogo }
            compose.onNode(isToggleable() and hasText(text(R.string.settings_spin_logo))).assertIsOn()
        }

    @Test
    fun onADisabledRowTheDescriptionStillFoldsAndItsLinkWorksOnceUnfolded() {
        runBlocking { settings.update { it.copy(writeInsights = false) } }
        show(SettingsSection.INSIGHTS) {
            val facts =
                text(R.string.settings_artist_facts_once).let { title ->
                    compose.onNode(isToggleable() and hasText(title)).performScrollTo().assertExists()
                    paragraphsOf(
                        app.getString(
                            R.string.settings_artist_facts_once_summary,
                            text(R.string.settings_session_gap),
                            text(R.string.settings_section_history),
                        ),
                    ).first()
                }
            val node = description(facts)
            assertEquals(text(R.string.settings_description_collapsed), node.state())
            tap(node)
            assertEquals(text(R.string.settings_description_expanded), description(facts).state())
            val target = SettingsTarget.sessionGap
            val link = hasText(text(R.string.settings_session_gap), substring = true) and hasLinks()
            compose
                .onNode(link, useUnmergedTree = true)
                .performScrollTo()
                .performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == target.linkTag }
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(SettingsSection.HISTORY.panelTag).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun aScreenReaderReadsTheWholeDescriptionFoldedOrNotWithItsStateAndWhatATapDoes() =
        show {
            val node = description(spin)
            assertEquals(listOf(spin), node.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
            assertEquals(text(R.string.settings_description_collapsed), node.state())
            assertEquals(text(R.string.settings_description_expand), node.tapLabel())
            tap(node)
            val open = description(spin)
            assertEquals(listOf(spin), open.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
            assertEquals(text(R.string.settings_description_expanded), open.state())
            assertEquals(text(R.string.settings_description_collapse), open.tapLabel())
            // The switch's own node reads its name and state, not the description.
            val row = compose.onNode(isToggleable() and hasText(text(R.string.settings_spin_logo))).fetchSemanticsNode()
            assertFalse(row.config[SemanticsProperties.Text].any { it.text == spin })
        }

    @Test
    fun aSearchShowsTheDescriptionsItFindsUnfoldedTheMatchMarkedAndClearedLeavesThemAsBefore() =
        show {
            val intro = text(R.string.settings_intro_summary)
            tap(description(intro))
            assertEquals(text(R.string.settings_description_expanded), description(intro).state())
            val query = "comes to rest"
            assertTrue(query in spin)
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
            compose.waitForIdle()
            val found = description(spin)
            assertEquals(text(R.string.settings_description_expanded), found.state())
            val marks =
                found
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
                    .spanStyles
            assertTrue("the match marked", marks.any { spin.substring(it.start, it.end) == query })
            // Folded by a tap during the search, and open again in the next, unless tapped.
            tap(found)
            assertEquals(text(R.string.settings_description_collapsed), description(spin).state())
            compose.onNodeWithTag(SEARCH_CLEAR_TAG).performClick()
            compose.waitForIdle()
            // As the person had them: the intro open, the spin's description folded.
            assertEquals(text(R.string.settings_description_expanded), description(intro).state())
            assertEquals(text(R.string.settings_description_collapsed), description(spin).state())
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
            compose.waitForIdle()
            assertEquals(text(R.string.settings_description_expanded), description(spin).state())
        }

    @Test
    fun aRotationKeepsTheFoldsWhateverDescriptionsOnReturnIs() {
        // *Folded* forgets the taps on a return, but a rotation is not one.
        runBlocking { settings.update { it.copy(descriptionsOnReturn = DescriptionsOnReturn.FOLDED) } }
        show {
            tap(description(spin))
            restore()
            assertEquals(text(R.string.settings_description_expanded), description(spin).state())
        }
    }

    @Test
    fun aRotationDuringASearchKeepsTheSearchsOwnFolds() =
        show {
            val query = "comes to rest"
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
            compose.waitForIdle()
            tap(description(spin))
            assertEquals(text(R.string.settings_description_collapsed), description(spin).state())
            restore()
            assertEquals(
                "folded for the search, after a rotation",
                text(R.string.settings_description_collapsed),
                description(spin).state(),
            )
            // Cleared, as the person had it before the search: folded, on a first visit.
            compose.onNodeWithTag(SEARCH_CLEAR_TAG).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
            compose.waitForIdle()
            assertEquals(
                "open again in the next search",
                text(R.string.settings_description_expanded),
                description(spin).state(),
            )
        }

    @Test
    fun aTapJustBelowAFoldedDescriptionUnfoldsItWithoutTogglingTheSwitch() =
        show {
            assertTrue(settings.now().spinLogo)
            val node = description(spin)
            assertEquals(text(R.string.settings_description_collapsed), node.state())
            val box = node.fetchSemanticsNode().boundsInRoot
            val line = compose.onNode(hasText(spin), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val below = with(compose.density) { TAP_BELOW.toPx() }
            assertTrue("its tap reaches below its line", box.bottom - line.bottom >= below - 1f)
            assertTrue("but not above it, over the name", line.top - box.top < 1f)
            // Halfway into the row's padding under the line, where the switch's row took the tap.
            node.performTouchInput { click(Offset(width / 2f, line.bottom - box.top + below / 2f)) }
            compose.waitForIdle()
            assertEquals(text(R.string.settings_description_expanded), description(spin).state())
            assertTrue("the switch left as it was", settings.now().spinLogo)
        }

    @Test
    fun aTapWhereAButtonGroupUnderADescriptionReachesChoosesAndLeavesTheFold() =
        show {
            // *Layout*'s row: its description, then its buttons 4 dp below it.
            val summary = text(R.string.settings_layout_summary)
            val node = description(summary)
            assertEquals(text(R.string.settings_description_collapsed), node.state())
            assertEquals(Layout.EXPANDABLE_BOXES, settings.now().layout)
            val inRow = hasContentDescription(text(R.string.settings_layout), substring = true)
            val pages = hasText(text(R.string.settings_layout_pages)) and isSelectable() and inRow
            // The buttons on screen, the description just above them.
            val button =
                compose
                    .onNode(pages)
                    .performScrollTo()
                    .fetchSemanticsNode()
                    .boundsInRoot
            val box = node.fetchSemanticsNode().boundsInRoot
            val line = compose.onNode(hasText(summary), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val dp = compose.density.density
            // Above the button, which takes a tap there only by Compose's widening to 48 dp.
            assertTrue("the button starts below the tap", button.top - line.bottom > 6 * dp)
            assertTrue("the description reaches no further than the buttons' room", box.bottom - line.bottom < 6 * dp)
            val x = button.center.x - box.left
            node.performTouchInput { click(Offset(x, line.bottom - box.top + 6 * dp)) }
            compose.waitForIdle()
            compose.waitUntil(WAIT_MS) { settings.now().layout == Layout.SEPARATE_PAGES }
            assertEquals("left folded", text(R.string.settings_description_collapsed), description(summary).state())
            // Nearer the line, before the buttons' room, it still unfolds it.
            node.performTouchInput { click(Offset(x, line.bottom - box.top + 2 * dp)) }
            compose.waitForIdle()
            assertEquals(text(R.string.settings_description_expanded), description(summary).state())
            assertEquals(Layout.SEPARATE_PAGES, settings.now().layout)
        }

    @Test
    fun aTapOnTheNoteUnderADescriptionLeavesItsFold() {
        // *Show genres* off: each genre source's row says so under its description, in full.
        runBlocking { settings.update { it.copy(showGenres = false) } }
        show(SettingsSection.GENRES) {
            val summary = text(R.string.settings_labels_musicbrainz_summary)
            val node = description(summary)
            assertEquals(text(R.string.settings_description_collapsed), node.state())
            val box = node.fetchSemanticsNode().boundsInRoot
            val line = compose.onNode(hasText(summary), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("its tap ends at its line", box.bottom - line.bottom < 1f)
            // In the note's upper half, just under the line.
            val dp = compose.density.density
            node.performTouchInput { click(Offset(width / 2f, line.bottom - box.top + 2 * dp)) }
            compose.waitForIdle()
            assertEquals(text(R.string.settings_description_collapsed), description(summary).state())
        }
    }

    private fun hasLinks() =
        SemanticsMatcher("has a link") { node ->
            val texts = node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty()
            texts.any { it.getLinkAnnotations(0, it.length).isNotEmpty() }
        }
}
