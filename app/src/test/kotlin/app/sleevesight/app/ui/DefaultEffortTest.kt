package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.effortName
import app.sleevesight.app.ui.settings.providerName
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Each AI service's reasoning effort says which level is the default (#1375): the level a text's
 * credit leaves unnamed ([Settings.creditedEffort]) reads "<level> (default)", in the row's value and
 * in the dialog, as the model row's default does; the service's own default reads *Set by the service*.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultEffortTest {
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

    private fun level(effort: ReasoningEffort) = text(effortName(effort))

    private fun marked(effort: ReasoningEffort) = app.getString(R.string.settings_ai_model_default, level(effort))

    private fun inDialog(label: String) = hasText(label) and hasAnyAncestor(isDialog())

    private fun present(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.waitUntil(WAIT_MS) { present(hasText(text(R.string.settings_title))) }
            compose.openSection(SettingsSection.AI_SERVICES)
            block()
        }
    }

    @Test
    fun everyServicesDefaultEffortIsMarkedInTheRowAndTheDialog() =
        show {
            assertEquals(
                setOf(ProviderId.OPENROUTER, ProviderId.ANTHROPIC, ProviderId.OPENAI, ProviderId.GOOGLE),
                Settings.EFFORT_SCALES.keys,
            )
            for ((provider, scale) in Settings.EFFORT_SCALES) {
                // The level marked is the one the credit leaves unnamed.
                val default = scale.levels.single { Settings.creditedEffort(provider, it) == null }
                val row = app.getString(R.string.settings_ai_effort_of, text(providerName(provider)))
                compose.onNode(hasText(row) and hasText(marked(default))).performScrollTo()
                compose.onNodeWithText(row).performClick()
                for (level in scale.levels) {
                    val shown = if (level == default) marked(level) else level(level)
                    assertTrue("$provider $level", present(inDialog(shown)))
                    if (level == default) assertTrue("$provider $level", !present(inDialog(level(level))))
                }
                assertTrue("$provider", present(inDialog(text(R.string.settings_effort_provider_default))))
                compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            }
        }

    @Test
    fun anotherLevelAndTheServicesOwnDefaultAreNotMarked() =
        show {
            val row = app.getString(R.string.settings_ai_effort_of, text(R.string.settings_ai_provider_anthropic))
            compose.onNodeWithText(row).performScrollTo().performClick()
            compose.onNode(inDialog(level(ReasoningEffort.HIGH))).performClick()
            compose.waitUntil(WAIT_MS) { settings.now().effort(ProviderId.ANTHROPIC) == ReasoningEffort.HIGH }
            compose.onNode(hasText(row) and hasText(level(ReasoningEffort.HIGH))).performScrollTo()

            compose.onNodeWithText(row).performClick()
            compose.onNode(inDialog(text(R.string.settings_effort_provider_default))).performClick()
            compose.waitUntil(WAIT_MS) { ProviderId.ANTHROPIC in settings.now().aiEfforts }
            assertNull(settings.now().effort(ProviderId.ANTHROPIC))
            compose.onNode(hasText(row) and hasText(text(R.string.settings_effort_provider_default))).performScrollTo()
        }
}
