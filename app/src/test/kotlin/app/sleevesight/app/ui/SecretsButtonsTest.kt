package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.LOAD_KEYS_BUTTON_TAG
import app.sleevesight.app.ui.settings.PURGE_KEYS_BUTTON_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * *Secrets*' actions as rows with a button at their right (#1365, as #1126 has *Clear history*):
 * *Load keys* and *Purge keys*, each button labelled with a short verb (*Load*, *Purge*, as
 * *Clear history*'s *Clear*) and read by the row's name ("Load keys, button"), with
 * the row's description beside it as before; *Purge keys*' button, which deletes, in the error
 * colour, *Load keys*' not. Native graphics, for the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecretsButtonsTest {
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

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun showSecrets(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.SECRETS)
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_scan_keys_value)) }
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

    private fun red(color: Color) = color.red > color.green + RED && color.red > color.blue + RED

    @Test
    fun loadKeysIsARowWithAButtonNamedForItNotInTheErrorColour() =
        showSecrets {
            assertEquals(Triple(Role.Button, "Load keys", "Load"), button(LOAD_KEYS_BUTTON_TAG))
            val color = ink(LOAD_KEYS_BUTTON_TAG)
            assertTrue("not red: $color", !red(color))
            // No key stored: nothing to purge.
            assertTrue(compose.onAllNodesWithTag(PURGE_KEYS_BUTTON_TAG).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun purgeKeysIsARowWithAButtonInTheErrorColourOnceAKeyIsStored() =
        showSecrets {
            runBlocking { secrets.set(SecretId.EXA, Secret("0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f")) }
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(PURGE_KEYS_BUTTON_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(Triple(Role.Button, "Purge keys", "Purge"), button(PURGE_KEYS_BUTTON_TAG))
            assertTrue(shows(text(R.string.settings_remove_all_keys_value)))
            val color = ink(PURGE_KEYS_BUTTON_TAG)
            assertTrue("red: $color", red(color))
        }

    private companion object {
        const val RED = 0.3f
    }
}
