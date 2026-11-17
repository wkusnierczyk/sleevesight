package app.sleevesight.app.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import app.sleevesight.app.pipeline.KeyRefusals
import app.sleevesight.app.secrets.OpenRouterLogin
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.OFFERED_PROVIDERS
import app.sleevesight.app.ui.settings.OPENROUTER_PROBLEM_TAG
import app.sleevesight.app.ui.settings.OPENROUTER_SIGNED_IN_TAG
import app.sleevesight.app.ui.settings.OPENROUTER_SIGN_IN_TAG
import app.sleevesight.app.ui.settings.OPENROUTER_SIGN_OUT_TAG
import app.sleevesight.app.ui.settings.OpenRouterAccess
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.KeyExchange
import app.sleevesight.core.ai.KeySignIn
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.SignInStart
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
import org.robolectric.Shadows.shadowOf

/**
 * OpenRouter under *AI services* (#1157): first in the list, with its sign-in in place of the link
 * to its key, the key link under it; while the browser is open, after a sign-in that did not finish,
 * signed in, with *Sign out* and its dialog, and with its key refused since. Against a fake
 * OpenRouter: nothing is sent anywhere.
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

    private val signIn =
        object : KeySignIn {
            override val callback = "app.sleevesight://openrouter"

            override fun begin() = SignInStart("verifier", "https://openrouter.ai/auth?code_challenge=c")

            override suspend fun exchange(
                code: String,
                verifier: String,
            ): KeyExchange = KeyExchange.Refused
        }

    private val login = OpenRouterLogin(signIn, save = { true }, scope = saves)

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

    private fun show(block: ActivityScenario<MainActivity>.() -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        openRouter = OpenRouterAccess(login, refusals),
                    ) { it }
                }
            }
            waitForNode(hasText(text(R.string.settings_title)))
            compose.openSection(SettingsSection.AI_SERVICES)
            scenario.block()
        }
    }

    @Test
    fun openRouterIsFirstAndOffersItsSignInWithItsTermsAndTheKeyLinkUnderIt() =
        show {
            waitForNode(hasTestTag(OPENROUTER_SIGN_IN_TAG))
            compose.onNodeWithText(text(R.string.settings_ai_provider_openrouter)).assertIsOff()
            // First of the services offered, so first under *AI services*.
            assertEquals(ProviderId.OPENROUTER, OFFERED_PROVIDERS.first())
            compose.onNodeWithText(text(R.string.settings_openrouter_sign_in)).performScrollTo().assertIsDisplayed()
            compose
                .onNodeWithText(text(R.string.settings_openrouter_sign_in_line))
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(
                    "Signing in means agreeing to OpenRouter’s Terms of Service. They require you to be 18 or older.",
                ).performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("Or set your OpenRouter API key here.").performScrollTo().assertIsDisplayed()
            assertTrue(absent(hasTestTag(OPENROUTER_SIGN_OUT_TAG)))
        }

    @Test
    fun signingInOpensOpenRoutersPageAndSaysToFinishThere() =
        show {
            waitForNode(hasTestTag(OPENROUTER_SIGN_IN_TAG))
            compose.onNodeWithTag(OPENROUTER_SIGN_IN_TAG).performScrollTo().performClick()
            waitForNode(hasText(text(R.string.settings_openrouter_waiting)))
            var started: Intent? = null
            onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Intent.ACTION_VIEW, started?.action)
            assertEquals("https://openrouter.ai/auth?code_challenge=c", started?.dataString)
            assertTrue(login.pending != null)
        }

    @Test
    fun aSignInThatDidNotFinishSaysWhyAboveTheButton() =
        show {
            login.start()
            login.resumed()
            waitForNode(hasTestTag(OPENROUTER_PROBLEM_TAG))
            compose.onNodeWithText(text(R.string.settings_openrouter_closed)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(OPENROUTER_SIGN_IN_TAG).performScrollTo().assertIsDisplayed()
        }

    @Test
    fun signedInItSaysSoWithTheKeysPageAndSignOutWhichDeletesTheKey() {
        runBlocking { secrets.set(SecretId.OPENROUTER, Secret("sk-or-v1-key")) }
        show {
            waitForNode(hasTestTag(OPENROUTER_SIGNED_IN_TAG))
            compose
                .onNodeWithText(text(R.string.settings_openrouter_signed_in))
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNode(hasContentDescription(text(R.string.settings_openrouter_manage)))
                .performScrollTo()
                .assertIsDisplayed()
            assertTrue(absent(hasTestTag(OPENROUTER_SIGN_IN_TAG)))
            compose.onNodeWithTag(OPENROUTER_SIGN_OUT_TAG).performScrollTo().performClick()
            waitForNode(hasText(text(R.string.settings_openrouter_sign_out_title)))
            compose
                .onNode(hasText(text(R.string.settings_openrouter_sign_out_text)) and hasAnyAncestor(isDialog()))
                .assertIsDisplayed()
            compose
                .onNode(hasText(text(R.string.settings_openrouter_sign_out)) and hasAnyAncestor(isDialog()))
                .performClick()
            compose.waitUntil(timeoutMillis = WAIT_MS) {
                runBlocking { secrets.states.first()[SecretId.OPENROUTER] } == SecretState.ABSENT
            }
            waitForNode(hasTestTag(OPENROUTER_SIGN_IN_TAG))
        }
    }

    @Test
    fun aKeyRefusedSinceSaysToSignInAgainAndOffersSignOut() {
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
                .onNodeWithText(text(R.string.settings_openrouter_key_refused))
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithTag(OPENROUTER_SIGN_IN_TAG).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(OPENROUTER_SIGN_OUT_TAG).performScrollTo().assertIsDisplayed()
            assertTrue(absent(hasTestTag(OPENROUTER_SIGNED_IN_TAG)))
        }
    }
}
