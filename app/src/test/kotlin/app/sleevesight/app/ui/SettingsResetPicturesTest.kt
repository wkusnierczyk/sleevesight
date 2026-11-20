package app.sleevesight.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.RESET_DIALOG_TAG
import app.sleevesight.app.ui.settings.RESET_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * *Save and reset* (#960) open, and *Reset settings?* over it, drawn as on a phone, light and dark.
 * Each is checked to show; with `SLEEVESIGHT_PICTURES` set, each is also saved there as a picture,
 * as `SongVersionPicturesTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsResetPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun pictures(dark: Boolean) {
        val theme = if (dark) "dark" else "light"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            compose.openSection(SettingsSection.SAVE_AND_RESET)
            // The whole panel in sight: the chip under it scrolled to.
            compose.onNodeWithTag(SettingsSection.ABOUT.tag).performScrollTo()
            compose.waitForIdle()
            save("save-and-reset-$theme", compose.onAllNodes(isRoot())[0])
            compose.onNodeWithTag(RESET_TAG).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(RESET_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            save("reset-dialog-$theme", compose.onNodeWithTag(RESET_DIALOG_TAG))
        }
    }

    private fun save(
        name: String,
        node: SemanticsNodeInteraction,
    ) {
        node.assertExists()
        val dir = out ?: return
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun pictures() {
        runBlocking {
            settings.update {
                it.copy(
                    theme = ThemeChoice.DARK,
                    logoSpeed = LogoSpeed.RPM_45,
                    sessionGap = 60.minutes,
                    followedPlayers = mapOf("org.example.tunes" to true),
                    aiProvider = ProviderId.ANTHROPIC,
                )
            }
        }
        for (dark in listOf(false, true)) pictures(dark)
    }
}
