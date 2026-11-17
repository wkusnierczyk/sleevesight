package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.CLEAR_HISTORY_BUTTON_TAG
import app.sleevesight.app.ui.settings.KEEP_HISTORY_SLIDER_TAG
import app.sleevesight.app.ui.settings.MUSICBRAINZ_WAIT_SLIDER_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The colours of *History*'s controls (#1126): the stepped sliders in the colour of the settings'
 * other controls, as the switches (a neutral ink, not the theme's accent), *Wait when busy*'s
 * too, since it is the same slider (and *Retry when the AI fails*'s); *Clear history*'s button in
 * the error colour. Native graphics, for the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryControlColoursTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun show(
        section: SettingsSection,
        title: String,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(section)
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            block()
        }
    }

    /** The pixel at the middle of the slider [tag], where its thumb is while at its middle stop. */
    private fun middle(tag: String): Color {
        val pixels =
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .captureToImage()
                .toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    /** The inkiest pixel of the node [tag], the one farthest from its corner's background. */
    private fun ink(tag: String): Color {
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
        return most
    }

    private fun distance(
        a: Color,
        b: Color,
    ) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    /** Neutral and dark, as the switches' ink on a light theme: no channel apart from the others. */
    private fun assertNeutralInk(
        what: String,
        color: Color,
    ) {
        val spread = maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)
        assertTrue("$what is neutral, not the accent: $color", spread < NEUTRAL)
        assertTrue("$what is dark ink: $color", color.red < DARK)
    }

    @Test
    fun keepHistoryForsThumbIsInTheControlsInk() =
        show(SettingsSection.HISTORY, "Keep history for") {
            // 90 days, the middle of five stops.
            assertNeutralInk("the thumb", middle(KEEP_HISTORY_SLIDER_TAG))
        }

    @Test
    fun waitWhenBusysSliderIsInTheControlsInkToo() =
        show(SettingsSection.SOURCES, "Wait when busy") {
            val pixels =
                compose
                    .onNodeWithTag(MUSICBRAINZ_WAIT_SLIDER_TAG)
                    .performScrollTo()
                    .captureToImage()
                    .toPixelMap()
            // Its track's filled part, left of the thumb, on the slider's middle line.
            val left = pixels[pixels.width / 8, pixels.height / 2]
            assertNeutralInk("the filled track", left)
        }

    @Test
    fun clearHistorysButtonIsInTheErrorColour() =
        show(SettingsSection.HISTORY, "Clear history") {
            val color = ink(CLEAR_HISTORY_BUTTON_TAG)
            assertTrue("red: $color", color.red > color.green + RED && color.red > color.blue + RED)
        }

    private companion object {
        const val NEUTRAL = 0.08f
        const val DARK = 0.3f
        const val RED = 0.3f
    }
}
