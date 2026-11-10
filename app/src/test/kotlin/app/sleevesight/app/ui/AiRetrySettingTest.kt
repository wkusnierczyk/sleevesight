package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.AI_RETRY_SLIDER_TAG
import app.sleevesight.app.ui.settings.AI_RETRY_VALUE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * *Retry when the AI fails* (#1164), as picked: the last row of *AI services*, after the services; a
 * stepped slider as *Wait when busy*'s, with the stops *Don’t retry*, *15 s*, *30 s* and *1 min*,
 * each labelled, 30 s chosen by default and shown at the row's right; its description naming the
 * value chosen; a move stored at once; read by a screen reader by its name with the value in words;
 * dimmed and not to be moved with no AI service on.
 */
@RunWith(RobolectricTestRunner::class)
class AiRetrySettingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = SettingsStore(MemoryStore())
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)

    // One at a time, in order: two saves in quick succession land as made.
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun googleOn() =
        runBlocking {
            secrets.set(SecretId.GOOGLE, Secret("google-key"))
            settings.update { it.copy(aiProvider = ProviderId.GOOGLE) }
        }

    private fun showAiServices(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.AI_SERVICES)
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_ai_retry)) }
            block()
        }
    }

    private fun slider() = compose.onNodeWithTag(AI_RETRY_SLIDER_TAG).performScrollTo()

    /** What a screen reader says of the slider's value. */
    private fun spoken(): String? = slider().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun shownValue(): String? =
        compose
            .onNodeWithTag(AI_RETRY_VALUE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString { it.text }

    private fun description(chosen: String) = app.getString(R.string.settings_ai_retry_summary, chosen)

    /** Whether the row's description names [chosen] as the time it retries for, folded or not. */
    private fun named(chosen: String) =
        compose
            .onAllNodesWithText("for up to $chosen before", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** Moves the slider to stop [index], as a screen reader's adjustment does. */
    private fun moveTo(index: Int) {
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
    }

    @Test
    fun theTextsAreThePickedOnes() {
        assertEquals("Retry when the AI fails", text(R.string.settings_ai_retry))
        assertEquals(
            "When your AI service is busy, the connection to it fails, or its reply breaks off, Sleevesight " +
                "asks again, waiting longer each time, for up to 30 s before it shows the error. A longer wait " +
                "fails less often; a shorter one tells you sooner.",
            description("30 s"),
        )
        assertEquals("Don’t retry", text(R.string.settings_ai_retry_none))
        assertEquals(
            "When your AI service is busy or the connection fails, Sleevesight shows the error at once. A longer " +
                "wait fails less often; a shorter one tells you sooner.",
            text(R.string.settings_ai_retry_summary_none),
        )
        assertEquals("the connection dropped", text(R.string.home_failure_connection_failed))
        assertEquals(
            "Asking AI failed: the connection dropped.",
            app.getString(R.string.home_ai_failed, "the connection dropped"),
        )
        assertEquals("no connection", text(R.string.home_failure_offline))
        assertEquals(listOf(Duration.ZERO, 15.seconds, 30.seconds, 1.minutes), Settings.AI_RETRIES)
        assertEquals(30.seconds, Settings().aiRetry)
    }

    @Test
    fun theRowComesLastAfterTheServicesWithThirtySecondsChosenEveryStopLabelledAndNamedInItsDescription() {
        googleOn()
        showAiServices {
            val row = compose.onNodeWithText(text(R.string.settings_ai_retry)).performScrollTo().fetchSemanticsNode()
            val lastService = app.getString(R.string.settings_ai_model_of, text(R.string.settings_ai_provider_google))
            val google = compose.onNodeWithText(lastService).fetchSemanticsNode()
            assertTrue(google.positionInRoot.y < row.positionInRoot.y)
            assertEquals("30 s", shownValue())
            for (label in listOf("Don’t retry", "15 s", "1 min")) {
                assertTrue(
                    label,
                    compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                )
            }
            assertTrue(named("30 s"))
            val node = slider().fetchSemanticsNode()
            assertEquals(
                listOf("Retry when the AI fails"),
                node.config.getOrNull(SemanticsProperties.ContentDescription),
            )
            assertEquals("30 seconds", spoken())
            slider().assertIsEnabled()
        }
    }

    @Test
    fun aMoveIsStoredAtOnceAndTheValueAndDescriptionFollow() {
        googleOn()
        showAiServices {
            moveTo(1)
            compose.waitUntil(WAIT_MS) { settings.now().aiRetry == 15.seconds }
            compose.waitUntil(WAIT_MS) { spoken() == "15 seconds" }
            assertEquals("15 s", shownValue())
            compose.waitUntil(WAIT_MS) { named("15 s") }
            moveTo(3)
            compose.waitUntil(WAIT_MS) { settings.now().aiRetry == 1.minutes }
            compose.waitUntil(WAIT_MS) { named("1 min") }
            moveTo(0)
            compose.waitUntil(WAIT_MS) { settings.now().aiRetry == Duration.ZERO }
            compose.waitUntil(WAIT_MS) { spoken() == "Don’t retry" }
            assertEquals("Don’t retry", shownValue())
            // Nothing asked again: the error at once.
            compose.waitUntil(WAIT_MS) {
                compose
                    .onAllNodesWithText(
                        "Sleevesight shows the error at once.",
                        substring = true,
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }

    @Test
    fun withNoAiServiceOnTheSliderCannotBeMoved() =
        showAiServices {
            slider().assertIsNotEnabled()
            assertEquals("30 seconds", spoken())
        }
}
