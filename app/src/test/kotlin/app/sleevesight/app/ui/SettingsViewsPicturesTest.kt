package app.sleevesight.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.HIDDEN_LINE_TAG
import app.sleevesight.app.ui.settings.HIDDEN_SHOW_TAG
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SEARCH_MORE_SHOW_TAG
import app.sleevesight.app.ui.settings.SEARCH_MORE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.VIEW_SWITCH_TAG
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.SettingsView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The settings' views (#1447) drawn as on a phone, light and dark, at the normal and the largest font
 * size: *Basic* with *Appearance* open and its hidden change said, the change shown, marked, *All*,
 * and a search with matches outside the view. Each is checked to show; with `SLEEVESIGHT_PICTURES`
 * set, each is also saved there as a picture, as `SettingsResetPicturesTest` saves its own.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsViewsPicturesTest {
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

    private fun pictures(font: String) {
        for (dark in listOf(false, true)) {
            runBlocking { settings.update { it.copy(settingsView = SettingsView.BASIC, logoSpeed = LogoSpeed.RPM_45) } }
            val name = { state: String -> "views-$state-$font-${if (dark) "dark" else "light"}" }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        SleevesightTheme(darkTheme = dark) {
                            SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                        }
                    }
                }
                compose.openSection(SettingsSection.APPEARANCE)
                save(name("basic-hidden"))
                compose.onNodeWithTag(HIDDEN_SHOW_TAG).performScrollTo().performClick()
                compose.waitForIdle()
                // A tap on the link's words shows the hidden change.
                assertEquals(0, compose.onAllNodesWithTag(HIDDEN_LINE_TAG).fetchSemanticsNodes().size)
                compose.onNode(hasText("Logo speed")).performScrollTo()
                compose.waitForIdle()
                save(name("basic-shown"))
                compose.onNode(hasText("Advanced") and hasAnyAncestor(hasTestTag(VIEW_SWITCH_TAG))).performClick()
                compose.waitForIdle()
                save(name("advanced"))
                compose.onNode(hasText("All") and hasAnyAncestor(hasTestTag(VIEW_SWITCH_TAG))).performClick()
                compose.waitForIdle()
                save(name("all"))
                compose.onNode(hasText("Basic") and hasAnyAncestor(hasTestTag(VIEW_SWITCH_TAG))).performClick()
                compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement("theme")
                compose.waitForIdle()
                save(name("search"))
                compose.onNodeWithTag(SEARCH_MORE_SHOW_TAG).performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals(0, compose.onAllNodesWithTag(SEARCH_MORE_TAG).fetchSemanticsNodes().size)
                save(name("search-shown"))
            }
        }
    }

    private fun save(name: String) {
        val node: SemanticsNodeInteraction = compose.onAllNodes(isRoot())[0]
        node.assertExists()
        val dir = out ?: return
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun pictures() = pictures("100")

    @Test
    @Config(fontScale = 2f)
    fun picturesAtTheLargestFont() = pictures("200")
}
