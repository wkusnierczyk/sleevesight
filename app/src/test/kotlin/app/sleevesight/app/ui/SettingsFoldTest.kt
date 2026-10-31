package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
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

/**
 * The settings' sections (#444, #649): each a chip with its short description beside it, which
 * opens the section's panel in their place, any number at once; all are closed when the screen
 * opens, and each stays as left through a recreation.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsFoldTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // In memory, and read on the main thread, as `SettingsKeyLinksTest`'s (#622).
    private val settings = SettingsStore(MemoryStore())
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun shown(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    // The screen's saved state, as an activity keeps it: [restore] saves it, drops the content and
    // composes it afresh from what was saved, as Android does for a recreated activity.
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

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { shown(R.string.settings_title) }
            block()
        }
    }

    private fun open(section: SettingsSection) = tap(section.tag)

    private fun close(section: SettingsSection) = tap(section.panelTag + ":close")

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Whether [section] shows as its panel, not its chip. */
    private fun isOpen(section: SettingsSection): Boolean {
        val chip = count(section.tag)
        val panel = count(section.panelTag)
        assertEquals("${section.name}: a chip or a panel, never both", 1, chip + panel)
        return panel == 1
    }

    @Test
    fun theSectionsShowAsChipsInOrderEachReadAsAHeadingAndAButtonCollapsed() =
        show {
            val user = SettingsSection.entries - SettingsSection.DEVELOPER
            val tops =
                user.map {
                    compose
                        .onNodeWithTag(it.tag)
                        .fetchSemanticsNode()
                        .positionInRoot.y
                }
            assertEquals(tops.sorted(), tops)
            for (section in user) {
                assertTrue(section.name, !isOpen(section))
                compose
                    .onNodeWithTag(section.tag)
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(hasText(text(section.title)))
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.StateDescription,
                            text(R.string.settings_section_collapsed),
                        ),
                    )
                // The short description beside the chip, not part of it; the fuller one only in the panel.
                section.summary?.let { assertTrue(section.name, shown(it)) }
                section.details?.let { assertTrue(section.name, !shown(it)) }
            }
            // Developer is hidden until developer mode is on.
            assertEquals(0, count(SettingsSection.DEVELOPER.tag))
            // Closed, a section's rows are not drawn.
            assertTrue(!shown(R.string.settings_background))
        }

    @Test
    fun aChipOpensItsPanelAndCloseTurnsItBackIntoTheChip() =
        show {
            open(SettingsSection.INSIGHTS)
            assertTrue(isOpen(SettingsSection.INSIGHTS))
            compose
                .onNodeWithTag(SettingsSection.INSIGHTS.panelTag + ":heading")
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .assert(hasText(text(R.string.settings_section_insights)))
            assertTrue(shown(R.string.settings_insights_long))
            assertTrue(shown(R.string.settings_background))
            // The short description goes with the chip.
            assertTrue(!shown(R.string.settings_insights_short))
            // The others stay closed.
            assertTrue(!isOpen(SettingsSection.HISTORY))

            close(SettingsSection.INSIGHTS)
            assertTrue(!isOpen(SettingsSection.INSIGHTS))
            assertTrue(shown(R.string.settings_insights_short))
            assertTrue(!shown(R.string.settings_background))
        }

    @Test
    fun twoPanelsStayOpenAtOnceAndEachClosesAlone() =
        show {
            open(SettingsSection.INSIGHTS)
            open(SettingsSection.HISTORY)
            assertTrue(isOpen(SettingsSection.INSIGHTS))
            assertTrue(isOpen(SettingsSection.HISTORY))
            assertTrue(shown(R.string.settings_background))
            assertTrue(shown(R.string.settings_keep_history))

            close(SettingsSection.INSIGHTS)
            assertTrue(!isOpen(SettingsSection.INSIGHTS))
            assertTrue(isOpen(SettingsSection.HISTORY))
            assertTrue(shown(R.string.settings_keep_history))
        }

    @Test
    fun eachSectionStaysAsLeftThroughARecreation() =
        show {
            open(SettingsSection.HISTORY)
            restore()
            compose.waitUntil(5_000) { shown(R.string.settings_title) }
            assertTrue(isOpen(SettingsSection.HISTORY))
            assertTrue(shown(R.string.settings_keep_history))
            assertTrue(!isOpen(SettingsSection.ABOUT))
        }
}
