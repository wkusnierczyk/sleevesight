package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings' sections (#444): each folds under its heading, with its short description always
 * shown; all are folded when the screen opens, and each stays as left through a recreation.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsFoldTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun shown(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()

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

    private fun state(section: SettingsSection) =
        compose
            .onNodeWithTag(section.tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]

    private fun tap(section: SettingsSection) {
        compose.onNodeWithTag(section.tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun theSectionsShowFoldedInOrderWithTheirShortDescriptions() =
        show {
            val user = SettingsSection.entries - SettingsSection.DEVELOPER
            val tops =
                user.map { section ->
                    compose
                        .onNodeWithTag(section.tag)
                        .fetchSemanticsNode()
                        .positionInRoot.y
                }
            assertEquals(tops.sorted(), tops)
            for (section in user) {
                val heading = compose.onNodeWithTag(section.tag)
                // A heading a screen reader reads with its state, and its short description.
                heading.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                heading.assert(hasText(text(section.title)))
                section.summary?.let { heading.assert(hasText(text(it))) }
                assertEquals(text(R.string.settings_section_collapsed), state(section))
                section.details?.let { assertTrue(section.name, !shown(it)) }
            }
            // Developer is hidden until developer mode is on.
            assertTrue(compose.onAllNodesWithTag(SettingsSection.DEVELOPER.tag).fetchSemanticsNodes().isEmpty())
            // Folded, a section's rows are not drawn.
            assertTrue(!shown(R.string.settings_background))
        }

    @Test
    fun aHeadingUnfoldsAndFoldsItsSection() =
        show {
            tap(SettingsSection.INSIGHTS)
            assertEquals(text(R.string.settings_section_expanded), state(SettingsSection.INSIGHTS))
            assertTrue(shown(R.string.settings_insights_long))
            assertTrue(shown(R.string.settings_background))
            // The others stay folded.
            assertEquals(text(R.string.settings_section_collapsed), state(SettingsSection.HISTORY))

            tap(SettingsSection.INSIGHTS)
            assertEquals(text(R.string.settings_section_collapsed), state(SettingsSection.INSIGHTS))
            assertTrue(!shown(R.string.settings_background))
        }

    @Test
    fun eachSectionStaysAsLeftThroughARecreation() =
        show {
            tap(SettingsSection.HISTORY)
            restore()
            compose.waitUntil(5_000) { shown(R.string.settings_title) }
            assertEquals(text(R.string.settings_section_expanded), state(SettingsSection.HISTORY))
            assertTrue(shown(R.string.settings_keep_history))
            assertEquals(text(R.string.settings_section_collapsed), state(SettingsSection.ABOUT))
        }
}
