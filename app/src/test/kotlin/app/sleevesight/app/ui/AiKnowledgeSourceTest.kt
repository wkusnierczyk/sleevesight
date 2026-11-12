package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Sources → AI service knowledge* while no AI service can be used (#846): none switched on, or the
 * one chosen without its key. Its row is dimmed and its switch shows the value stored and cannot be
 * moved, which nothing writes; with an AI service usable, it switches as any source's.
 */
@RunWith(RobolectricTestRunner::class)
class AiKnowledgeSourceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)

    /** Where the screen saves, as the process's scope in the app: it outlives the screen. */
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    /** *AI service knowledge*'s row, by what it is. */
    private val knowledge: SemanticsMatcher
        get() =
            hasText(
                app.getString(R.string.settings_source_model_summary, text(R.string.settings_section_ai)),
                substring = true,
            )

    /** The line saying no AI service is on. */
    private val noService = hasText(text(R.string.settings_source_model_no_service), substring = true)

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            // The screen draws once the settings are read.
            waitForNode(hasText(text(R.string.settings_title)))
            compose.openSection(SettingsSection.SOURCES)
            block()
        }
    }

    private fun current(): Settings = settings.now()

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = compose.waitUntil(conditionDescription = what, timeoutMillis = WAIT_MS) { condition() }

    private fun waitForNode(matcher: SemanticsMatcher) =
        waitFor("a node: ${matcher.description}") { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** The row, once the keys' states are read and it is [enabled] or not. */
    private fun row(enabled: Boolean): SemanticsNodeInteraction {
        waitForNode(knowledge and if (enabled) isEnabled() else isNotEnabled())
        return compose.onNode(knowledge).performScrollTo()
    }

    /**
     * Taps the row, and waits for every save to end: a toggle would start its save in [saves] during
     * the tap, so by then the setting would have changed.
     */
    private fun tapAndSettle(row: SemanticsNodeInteraction) {
        row.performClick()
        compose.waitForIdle()
        val pending = saves.coroutineContext.job
        waitFor("every save ended") { pending.children.none { it.isActive } }
    }

    @Test
    fun withNoAiServiceItShowsOnStoredOnAndCannotBeMoved() {
        runBlocking { settings.update { it.copy(enabledSources = it.enabledSources + SourceSwitch.MODEL) } }
        show {
            val row = row(enabled = false)
            row.assertIsOn().assertIsNotEnabled()
            assertEquals(1, compose.onAllNodes(noService).fetchSemanticsNodes().size)
            tapAndSettle(row)
            assertTrue(SourceSwitch.MODEL in current().enabledSources)
            row.assertIsOn()
        }
    }

    @Test
    fun withNoAiServiceItShowsOffStoredOffAndCannotBeMoved() =
        show {
            // Off, as by default.
            val row = row(enabled = false)
            row.assertIsOff().assertIsNotEnabled()
            tapAndSettle(row)
            assertTrue(SourceSwitch.MODEL !in current().enabledSources)
            row.assertIsOff()
        }

    @Test
    fun aChosenServiceWithoutItsKeyIsNoServiceToUse() {
        // As for a moment after its key is removed, before the keys' gate switches it off (#667).
        runBlocking {
            settings.update {
                it.copy(aiProvider = ProviderId.ANTHROPIC, enabledSources = it.enabledSources + SourceSwitch.MODEL)
            }
        }
        show {
            // Dimmed, with no line, too, for the moment before the keys' states are read: wait for
            // them, read once the line shows.
            waitForNode(noService)
            val row = row(enabled = false)
            row.assertIsOn().assertIsNotEnabled()
            assertEquals(1, compose.onAllNodes(noService).fetchSemanticsNodes().size)
            tapAndSettle(row)
            assertTrue(SourceSwitch.MODEL in current().enabledSources)
        }
    }

    @Test
    fun withAnAiServiceUsableItSwitchesAsBefore() {
        runBlocking {
            secrets.set(SecretId.ANTHROPIC, Secret("anthropic-key"))
            settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) }
        }
        show {
            val row = row(enabled = true)
            row.assertIsOff().assertIsEnabled()
            assertTrue(compose.onAllNodes(noService).fetchSemanticsNodes().isEmpty())
            row.performClick()
            waitFor("AI service knowledge stored on") { SourceSwitch.MODEL in current().enabledSources }
            // The screen shows the store a frame later: tap again only once it shows on.
            waitForNode(knowledge and isOn())
            compose.onNode(knowledge).performScrollTo().performClick()
            waitFor("AI service knowledge stored off") { SourceSwitch.MODEL !in current().enabledSources }
            waitForNode(knowledge and isOff())
        }
    }
}
