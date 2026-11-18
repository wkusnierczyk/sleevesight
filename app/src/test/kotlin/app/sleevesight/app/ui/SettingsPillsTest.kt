package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.lab.PromptLab
import app.sleevesight.app.memorySettings
import app.sleevesight.app.pipeline.KeyRefusals
import app.sleevesight.app.secrets.OpenRouterLogin
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.lab.LAB_CLEAR_TAG
import app.sleevesight.app.ui.lab.LAB_EXPORT_TAG
import app.sleevesight.app.ui.lab.LocalPromptLab
import app.sleevesight.app.ui.lab.PromptLabBlock
import app.sleevesight.app.ui.settings.OPENROUTER_SIGN_IN_TAG
import app.sleevesight.app.ui.settings.OPENROUTER_SIGN_OUT_TAG
import app.sleevesight.app.ui.settings.OpenRouterAccess
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.app.ui.theme.SleevesightTheme
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
import app.sleevesight.core.synthesis.LabComparison
import app.sleevesight.core.synthesis.PromptKind
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.time.Instant

/**
 * The settings' buttons as one pill (#1387, as *Clear history*'s and *Load keys*'), drawn in the
 * app's theme, light and dark: OpenRouter's *Sign in*, labelled so and read "Sign in with
 * OpenRouter", and *Sign out*, outlined, unfilled, their text in the controls' colour (the theme's
 * `onSurface`), not the accent; *Sign in* disabled still readable, and grey; the prompt lab's
 * *Clear the log*, which deletes, in the error colour, its outline too, and in greys while disabled;
 * and a panel's *Close*, the same pill. Native graphics, for the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPillsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refusals = KeyRefusals()

    private val signIn =
        object : KeySignIn {
            override val callback = "https://sleevesight.app/auth/openrouter/"

            override fun begin() = SignInStart("verifier", "state", "https://openrouter.ai/auth?code_challenge=c")

            override suspend fun exchange(
                code: String,
                verifier: String,
            ): KeyExchange = KeyExchange.Refused
        }

    private val login = OpenRouterLogin(signIn, save = { true }, scope = saves)

    // The theme's colours, as drawn.
    private var colours: ColorScheme? = null

    @After
    fun close() {
        saves.cancel()
    }

    private fun waitFor(tag: String) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    // [content] in the app's theme, light or [dark]; [block] given the theme's colours.
    private fun showing(
        dark: Boolean,
        content: @Composable () -> Unit,
        block: (ColorScheme) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        colours = MaterialTheme.colorScheme
                        content()
                    }
                }
            }
            compose.waitUntil(WAIT_MS) { colours != null }
            block(colours!!)
        }
    }

    // *AI services*, with OpenRouter's sign-in and a key saved and refused since, so *Sign in* is
    // offered again with *Sign out* under it; without [access], no sign-in and no key, so *Sign in*
    // is offered disabled.
    private fun showOpenRouter(
        dark: Boolean,
        access: Boolean = true,
        block: (ColorScheme) -> Unit,
    ) {
        if (access) refusedKey()
        showing(dark, {
            SettingsScreen(
                settings,
                secrets,
                emptyMap(),
                { CompletableDeferred(true) },
                saves,
                openRouter = if (access) OpenRouterAccess(login, refusals) else null,
            ) { it }
        }) { scheme ->
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodes(hasText(app.getString(R.string.settings_title))).fetchSemanticsNodes().isNotEmpty()
            }
            compose.openSection(SettingsSection.AI_SERVICES)
            waitFor(OPENROUTER_SIGN_IN_TAG)
            block(scheme)
        }
    }

    // OpenRouter's key saved, then refused by it.
    private fun refusedKey() {
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

    private fun pixels(tag: String): PixelMap =
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .captureToImage()
            .toPixelMap()

    private fun distance(
        a: Color,
        b: Color,
    ) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private fun chroma(color: Color) =
        maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)

    /** The inkiest pixel of [pixels], the one farthest from its corner's background: its text. */
    private fun ink(pixels: PixelMap): Color {
        val background = pixels[0, 0]
        var most = background
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                if (distance(pixels[x, y], background) > distance(most, background)) most = pixels[x, y]
            }
        }
        return most
    }

    /** How far from the background the pill is inside its start, between its edge and its text. */
    private fun fill(pixels: PixelMap): Float {
        val inside = pixels[(INSIDE_DP * app.resources.displayMetrics.density).toInt(), pixels.height / 2]
        return distance(inside, pixels[0, 0])
    }

    /** The pill's outline: at the middle of its start edge, the pixel farthest from the background. */
    private fun outline(pixels: PixelMap): Color {
        val y = pixels.height / 2
        val edge = (EDGE_DP * app.resources.displayMetrics.density).toInt()
        return (0 until edge).map { pixels[it, y] }.maxBy { distance(it, pixels[0, 0]) }
    }

    @Test
    fun signInIsLabelledSignInAndReadAsSignInWithOpenRouter() =
        showOpenRouter(dark = false) {
            waitFor(OPENROUTER_SIGN_OUT_TAG)
            assertEquals(Triple(Role.Button, "Sign in with OpenRouter", "Sign in"), button(OPENROUTER_SIGN_IN_TAG))
            assertEquals(Triple(Role.Button, "Sign out of OpenRouter", "Sign out"), button(OPENROUTER_SIGN_OUT_TAG))
        }

    @Test
    fun signInAndSignOutAreUnfilledInTheControlsColourLight() = inTheControlsColour(dark = false)

    @Test
    fun signInAndSignOutAreUnfilledInTheControlsColourDark() = inTheControlsColour(dark = true)

    private fun inTheControlsColour(dark: Boolean) =
        showOpenRouter(dark) { scheme ->
            waitFor(OPENROUTER_SIGN_OUT_TAG)
            for (tag in listOf(OPENROUTER_SIGN_IN_TAG, OPENROUTER_SIGN_OUT_TAG)) {
                compose.onNodeWithTag(tag).assertIsEnabled()
                val pixels = pixels(tag)
                val ink = ink(pixels)
                assertTrue("dark=$dark: $tag in ${scheme.onSurface}: $ink", distance(ink, scheme.onSurface) < NEAR)
                assertTrue("dark=$dark: $tag not in the accent: $ink", distance(ink, scheme.primary) > FAR)
                assertTrue("dark=$dark: $tag not filled", fill(pixels) < UNFILLED)
            }
        }

    @Test
    fun aDisabledSignInIsGreyAndReadableLight() = disabledGrey(dark = false)

    @Test
    fun aDisabledSignInIsGreyAndReadableDark() = disabledGrey(dark = true)

    private fun disabledGrey(dark: Boolean) =
        showOpenRouter(dark, access = false) { scheme ->
            compose.onNodeWithTag(OPENROUTER_SIGN_IN_TAG).assertIsNotEnabled()
            val pixels = pixels(OPENROUTER_SIGN_IN_TAG)
            val ink = ink(pixels)
            assertTrue("dark=$dark: readable: $ink", distance(ink, pixels[0, 0]) > READABLE)
            assertTrue("dark=$dark: grey: $ink", chroma(ink) < GREY)
            assertTrue("dark=$dark: not in the accent: $ink", distance(ink, scheme.primary) > FAR)
            assertTrue("dark=$dark: not filled", fill(pixels) < UNFILLED)
        }

    @Test
    fun clearTheLogIsInTheErrorColourItsOutlineTooLight() = clearInRed(dark = false)

    @Test
    fun clearTheLogIsInTheErrorColourItsOutlineTooDark() = clearInRed(dark = true)

    private fun clearInRed(dark: Boolean) {
        val lab = PromptLab(folder.root, saves, Dispatchers.Unconfined)
        lab.record(logged())
        showing(dark, { LabBlock(lab) }) { scheme ->
            waitFor(LAB_CLEAR_TAG)
            compose.onNodeWithTag(LAB_CLEAR_TAG).assertIsEnabled()
            val pixels = pixels(LAB_CLEAR_TAG)
            val ink = ink(pixels)
            assertTrue("dark=$dark: text in ${scheme.error}: $ink", distance(ink, scheme.error) < NEAR)
            val edge = outline(pixels)
            assertTrue("dark=$dark: outline in ${scheme.error}: $edge", distance(edge, scheme.error) < NEAR)
            // *Export the log*, beside it, deletes nothing: in the controls' colour.
            val export = ink(pixels(LAB_EXPORT_TAG))
            assertTrue("dark=$dark: export in ${scheme.onSurface}: $export", distance(export, scheme.onSurface) < NEAR)
        }
    }

    @Test
    fun anEmptyLogsClearIsDisabledItsOutlineNoLongerRedLight() = clearDisabled(dark = false)

    @Test
    fun anEmptyLogsClearIsDisabledItsOutlineNoLongerRedDark() = clearDisabled(dark = true)

    // Nothing logged: *Clear the log* disabled, its text and outline Material's disabled greys.
    private fun clearDisabled(dark: Boolean) {
        val lab = PromptLab(folder.root, saves, Dispatchers.Unconfined)
        showing(dark, { LabBlock(lab) }) { scheme ->
            waitFor(LAB_CLEAR_TAG)
            compose.onNodeWithTag(LAB_CLEAR_TAG).assertIsNotEnabled()
            val pixels = pixels(LAB_CLEAR_TAG)
            val edge = outline(pixels)
            assertTrue("dark=$dark: outline not ${scheme.error}: $edge", distance(edge, scheme.error) > FAR)
            assertTrue("dark=$dark: outline grey: $edge", chroma(edge) < GREY)
            val ink = ink(pixels)
            assertTrue("dark=$dark: text not ${scheme.error}: $ink", distance(ink, scheme.error) > FAR)
        }
    }

    @Test
    fun aPanelsCloseIsTheUnfilledPillInTheControlsColourLight() = panelClose(dark = false)

    @Test
    fun aPanelsCloseIsTheUnfilledPillInTheControlsColourDark() = panelClose(dark = true)

    private fun panelClose(dark: Boolean) =
        showing(dark, {
            Surface {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Panel(AnnotatedString("Genres"), tag = PANEL, onClose = {}) { Text("Blues") }
                }
            }
        }) { scheme ->
            waitFor("$PANEL:close")
            val pixels = pixels("$PANEL:close")
            val ink = ink(pixels)
            assertTrue("dark=$dark: in ${scheme.onSurface}: $ink", distance(ink, scheme.onSurface) < NEAR)
            assertTrue("dark=$dark: not in the accent: $ink", distance(ink, scheme.primary) > FAR)
            assertTrue("dark=$dark: not filled", fill(pixels) < UNFILLED)
            assertTrue("dark=$dark: outlined", distance(outline(pixels), pixels[0, 0]) > OUTLINED)
            assertEquals(Triple(Role.Button, null, "Close"), button("$PANEL:close"))
        }

    // The lab's settings, scrolling, on the theme's surface, as in Settings.
    @Composable
    private fun LabBlock(lab: PromptLab) =
        Surface {
            CompositionLocalProvider(LocalPromptLab provides lab) {
                Column(Modifier.verticalScroll(rememberScrollState())) { PromptLabBlock(lab) }
            }
        }

    private fun logged() =
        LabComparison(
            time = Instant.parse("2026-11-17T19:58:12Z"),
            app = "0.8.0-debug",
            kind = PromptKind.SONG,
            title = "Sundown",
            artist = "Gordon Lightfoot",
            album = null,
            service = "ANTHROPIC",
            model = "claude-haiku-5-5",
            effort = null,
            evidence = "",
            instructions = emptyMap(),
            versions = emptyList(),
        )

    private companion object {
        // Inside the pill's start, past its rounded edge and before its text (24 dp in).
        const val INSIDE_DP = 14

        // The start of a pill, where its 1 dp outline is, at its middle.
        const val EDGE_DP = 3
        const val NEAR = 0.15f
        const val FAR = 0.15f
        const val READABLE = 0.2f
        const val GREY = 0.12f
        const val UNFILLED = 0.05f
        const val OUTLINED = 0.08f
        const val PANEL = "panel"
    }
}
