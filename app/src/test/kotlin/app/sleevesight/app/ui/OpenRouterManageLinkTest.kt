package app.sleevesight.app.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
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
import app.sleevesight.app.ui.settings.OPENROUTER_KEYS
import app.sleevesight.app.ui.settings.OPENROUTER_SIGNED_IN_TAG
import app.sleevesight.app.ui.settings.OpenRouterAccess
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.KeyExchange
import app.sleevesight.core.ai.KeySignIn
import app.sleevesight.core.ai.SignInStart
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Signed in to OpenRouter (#1396), *Manage the key on OpenRouter ↗* stays on one line at the usual
 * font on a narrow phone, reaching under the switch rather than wrapping in the text's column, and a
 * tap on the part under the switch opens the keys' page too. Native graphics, for the text's real
 * width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenRouterManageLinkTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manage = app.getString(R.string.settings_openrouter_manage)

    private val signIn =
        object : KeySignIn {
            override val callback = "https://sleevesight.app/auth/openrouter/"

            override fun begin() = SignInStart("verifier", "state", "https://openrouter.ai/auth?code_challenge=c")

            override suspend fun exchange(
                code: String,
                verifier: String,
            ): KeyExchange = KeyExchange.Refused
        }

    // The link's text on one line, in pixels, as the screen's theme sets it.
    private var oneLine = 0

    @After
    fun close() {
        saves.cancel()
    }

    @Test
    fun theKeysLinkStaysOnOneLineReachingUnderTheSwitch() {
        runBlocking { secrets.set(SecretId.OPENROUTER, Secret("sk-or-v1-key")) }
        val login = OpenRouterLogin(signIn, save = { true }, scope = saves)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = false) {
                        val measurer = rememberTextMeasurer()
                        oneLine = measurer.measure(manage, MaterialTheme.typography.bodyMedium).size.width
                        SettingsScreen(
                            memorySettings(),
                            secrets,
                            emptyMap(),
                            { CompletableDeferred(true) },
                            saves,
                            openRouter = OpenRouterAccess(login, KeyRefusals()),
                        ) { it }
                    }
                }
            }
            compose.openSection(SettingsSection.AI_SERVICES)
            val signedIn = hasTestTag(OPENROUTER_SIGNED_IN_TAG)
            compose.waitUntil(timeoutMillis = WAIT_MS) {
                compose.onAllNodes(signedIn, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val link = compose.onNode(hasContentDescription(manage)).performScrollTo()
            val column = compose.onNode(signedIn, useUnmergedTree = true).fetchSemanticsNode()
            val bounds = link.fetchSemanticsNode().boundsInRoot
            // The text on one line, then the gap and the symbol after it.
            val symbol = with(compose.density) { 20.dp.toPx() }
            assertTrue(
                "the link (${bounds.width} px) holds its text on one line ($oneLine px) and the symbol",
                bounds.width >= oneLine + symbol - 1,
            )
            // Wider than the text's column could hold: it reaches under the switch.
            assertTrue(
                "the link (${bounds.width} px) is wider than the text's column (${column.size.width} px)",
                bounds.width > column.size.width,
            )
            // A tap on its end, under the switch, opens the keys' page.
            link.performTouchInput { click(Offset(width - 4f, height / 2f)) }
            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(OPENROUTER_KEYS, started?.dataString)
        }
    }
}
