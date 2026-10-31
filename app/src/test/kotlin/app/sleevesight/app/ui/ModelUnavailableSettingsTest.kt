package app.sleevesight.app.ui

import androidx.activity.compose.setContent
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
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.pipeline.ModelCheck
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *AI services* while a service's selected model is found unavailable (#540): its row says so and
 * asks for another; the model choice says so too, and offers only the models the key can use.
 */
@RunWith(RobolectricTestRunner::class)
class ModelUnavailableSettingsTest {
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

    /** Anthropic's client, whose list no longer holds its default, Claude Haiku 5.5. */
    private val provider =
        object : AiProvider {
            override val id = ProviderId.ANTHROPIC

            override suspend fun models(key: Secret): ModelList =
                ModelList.Listed(
                    listOf(
                        ModelInfo("claude-opus-5-5", "Claude Opus 5.5", null),
                        ModelInfo("claude-sonnet-4-5", "Claude Sonnet 4.5", null, offered = false),
                    ),
                )

            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> = emptyFlow()
        }

    private val said =
        "The currently selected Anthropic model Claude Haiku 5.5 is not available. " +
            "Please select another model or provider."

    private fun absent(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()

    private fun absentInDialog(text: String) =
        compose.onAllNodes(hasText(text) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isEmpty()

    private fun waitForText(text: String) =
        compose.waitUntil(conditionDescription = text, timeoutMillis = WAIT_MS) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun theRowAndTheChoiceSaySoAndOnlyUsableModelsAreOffered() {
        runBlocking { secrets.set(SecretId.ANTHROPIC, Secret("sk-test")) }
        val check = ModelCheck(mapOf(ProviderId.ANTHROPIC to provider), { Secret("sk-test") }, saves)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        mapOf(ProviderId.ANTHROPIC to provider),
                        { CompletableDeferred(true) },
                        saves,
                        modelCheck = check,
                    ) { it }
                }
            }
            waitForText(app.getString(R.string.settings_title))
            compose.openSection(SettingsSection.AI_SERVICES)
            assertTrue("nothing said before a check", absent(said))

            // The choice lists the models: the default is not among them.
            compose.onNodeWithText("Anthropic model").performScrollTo().performClick()
            waitForText("Claude Opus 5.5")
            compose.onNode(hasText(said) and hasAnyAncestor(isDialog())).assertExists()
            // Neither the default (still the row's value, behind the dialog) nor a deprecated model is offered.
            assertTrue(absentInDialog(app.getString(R.string.settings_ai_model_default, "Claude Haiku 5.5")))
            assertTrue(absentInDialog("Claude Sonnet 4.5"))
            compose.onNodeWithText(app.getString(R.string.settings_cancel)).performClick()

            // The row says so, from the check the list made.
            compose.waitUntil(timeoutMillis = WAIT_MS) {
                check.unavailable.value[ProviderId.ANTHROPIC]?.model == "claude-haiku-5-5"
            }
            compose.onNodeWithText(said).performScrollTo()

            // Another model chosen: the row says nothing more.
            compose.onNodeWithText("Anthropic model").performScrollTo().performClick()
            waitForText("Claude Opus 5.5")
            compose.onNode(hasText("Claude Opus 5.5") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(timeoutMillis = WAIT_MS) {
                settings.now().aiModels[ProviderId.ANTHROPIC] == "claude-opus-5-5"
            }
            compose.waitUntil(timeoutMillis = WAIT_MS) { absent(said) }
        }
    }
}
