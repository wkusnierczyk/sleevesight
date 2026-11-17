package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.pipeline.KeyRefusals
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.OFFERED_PROVIDERS
import app.sleevesight.app.ui.settings.OPENROUTER_PROBLEM_TAG
import app.sleevesight.app.ui.settings.OPENROUTER_ROWS_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OpenRouter under *AI services* (#1157, #1397): first in the list, set up with a pasted key as the
 * other services, so with the link to its key under its switch and no sign-in; and with its key
 * refused since, that it was, above that link. Against a fake OpenRouter: nothing is sent anywhere.
 */
@RunWith(RobolectricTestRunner::class)
class OpenRouterSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refusals = KeyRefusals()

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun waitForNode(matcher: SemanticsMatcher) =
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = WAIT_MS) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun absent(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        keyRefusals = refusals,
                    ) { it }
                }
            }
            waitForNode(hasText(text(R.string.settings_title)))
            compose.openSection(SettingsSection.AI_SERVICES)
            block()
        }
    }

    // Nothing of the sign-in, removed (#1397): neither its button nor its lines.
    private fun noSignIn() {
        for (gone in listOf("Sign in", "Sign out", "Signed in", "Not signed in", "Or set your OpenRouter")) {
            assertTrue(gone, absent(hasText(gone, substring = true)))
        }
    }

    @Test
    fun openRouterIsFirstWithTheLinkToItsKeyAndNoSignIn() =
        show {
            waitForNode(hasTestTag(OPENROUTER_ROWS_TAG))
            compose.onNodeWithText(text(R.string.settings_ai_provider_openrouter)).assertIsOff()
            // First of the services offered, so first under *AI services*.
            assertEquals(ProviderId.OPENROUTER, OFFERED_PROVIDERS.first())
            compose.onNodeWithText("Set your OpenRouter API key here.").performScrollTo().assertIsDisplayed()
            assertTrue(absent(hasTestTag(OPENROUTER_PROBLEM_TAG)))
            noSignIn()
        }

    @Test
    fun aKeySavedOffersToUpdateItAsTheOthersDo() {
        runBlocking { secrets.set(SecretId.OPENROUTER, Secret("sk-or-v1-key")) }
        show {
            waitForNode(hasText("Update your OpenRouter API key here."))
            assertTrue(absent(hasTestTag(OPENROUTER_PROBLEM_TAG)))
            noSignIn()
        }
    }

    @Test
    fun aKeyRefusedSinceSaysToSetANewOneAboveTheLinkToIt() {
        runBlocking {
            secrets.set(SecretId.OPENROUTER, Secret("sk-or-v1-key"))
            val refusing =
                object : AiProvider {
                    override val id = ProviderId.OPENROUTER

                    override suspend fun models(key: Secret) = ModelList.Failed(AiErrorKind.UNAUTHORIZED)

                    override fun stream(
                        request: CompletionRequest,
                        key: Secret,
                    ): Flow<CompletionEvent> = flowOf(CompletionEvent.Error(AiErrorKind.UNAUTHORIZED))
                }
            refusals.watching(refusing).stream(CompletionRequest("m", "p"), Secret("sk-or-v1-key")).toList()
        }
        show {
            waitForNode(hasTestTag(OPENROUTER_PROBLEM_TAG))
            compose
                .onNodeWithText(
                    "OpenRouter no longer accepts this key: it may have been deleted there. Set a new key.",
                ).performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("Update your OpenRouter API key here.").performScrollTo().assertIsDisplayed()
            noSignIn()
        }
    }
}
