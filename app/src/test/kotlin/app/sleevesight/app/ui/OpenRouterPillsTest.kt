package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * OpenRouter's *Sign in* and *Sign out* as the settings' other pills (#1387, as *Load keys*' and
 * *Clear history*'s): small, outlined, unfilled, their text in the controls' black, labelled *Sign in*
 * and *Sign out* and read by what they do ("Sign in with OpenRouter"). With a key saved and refused
 * since, so both show. Native graphics, for the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenRouterPillsTest {
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

            override fun begin() = SignInStart("verifier", "state", "https://openrouter.ai/auth?code_challenge=c")

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

    private fun waitFor(tag: String) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    // A key saved and refused since: *Sign in* offered again, with *Sign out* under it.
    private fun showBoth(block: () -> Unit) {
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
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodes(hasText(app.getString(R.string.settings_title))).fetchSemanticsNodes().isNotEmpty()
            }
            compose.openSection(SettingsSection.AI_SERVICES)
            waitFor(OPENROUTER_SIGN_IN_TAG)
            waitFor(OPENROUTER_SIGN_OUT_TAG)
            block()
        }
    }

    /** The button [tag]'s role, the name a screen reader reads it by, and its label. */
    private fun button(tag: String): Triple<Role?, String?, String?> {
        val config =
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .fetchSemanticsNode()
                .config
        return Triple(
            config.getOrNull(SemanticsProperties.Role),
            config.getOrNull(SemanticsProperties.ContentDescription)?.single(),
            config.getOrNull(SemanticsProperties.Text)?.single()?.text,
        )
    }

    private fun distance(
        a: Color,
        b: Color,
    ) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    /**
     * The node [tag]'s inkiest pixel, the one farthest from its corner's background (its text), and
     * how far from that background the pill is inside its start, between its edge and its text (a
     * fill there; none for an outlined pill).
     */
    private fun looks(tag: String): Pair<Color, Float> {
        val pixels =
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .captureToImage()
                .toPixelMap()
        val background = pixels[0, 0]
        var most = background
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                if (distance(pixels[x, y], background) > distance(most, background)) most = pixels[x, y]
            }
        }
        val inside = pixels[(INSIDE_DP * app.resources.displayMetrics.density).toInt(), pixels.height / 2]
        return most to distance(inside, background)
    }

    // Near black and grey, not a hue: the controls' colour, not the theme's.
    private fun black(color: Color) =
        maxOf(color.red, color.green, color.blue) < DARK &&
            maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) < GREY

    @Test
    fun signInIsAPillLabelledSignInAndReadAsSignInWithOpenRouter() =
        showBoth {
            assertEquals(Triple(Role.Button, "Sign in with OpenRouter", "Sign in"), button(OPENROUTER_SIGN_IN_TAG))
            assertEquals(Triple(Role.Button, "Sign out of OpenRouter", "Sign out"), button(OPENROUTER_SIGN_OUT_TAG))
        }

    @Test
    fun signInAndSignOutAreOutlinedUnfilledAndBlack() =
        showBoth {
            for (tag in listOf(OPENROUTER_SIGN_IN_TAG, OPENROUTER_SIGN_OUT_TAG)) {
                val (ink, fill) = looks(tag)
                assertTrue("$tag in black: $ink", black(ink))
                assertTrue("$tag not filled: $fill", fill < UNFILLED)
            }
        }

    private companion object {
        // Inside the pill's start, past its rounded edge and before its text (24 dp in).
        const val INSIDE_DP = 14
        const val DARK = 0.3f
        const val GREY = 0.12f
        const val UNFILLED = 0.05f
    }
}
