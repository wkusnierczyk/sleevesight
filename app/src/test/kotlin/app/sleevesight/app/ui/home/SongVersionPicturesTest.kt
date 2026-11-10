package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The rows under *Key insights* with a song whose title names a version (#1160), drawn as on a
 * phone, light and dark, at the default font size and twice it: the *Song* row closed, reading the
 * title as under the cover, and its panel open, the version under the name. Each is checked; with
 * `SLEEVESIGHT_PICTURES` set, each is also saved there as a picture, as `InsightBylineScreenTest`
 * saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SongVersionPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun pictures(
        size: String,
        dark: Boolean,
    ) {
        val playing =
            sundown.copy(
                title = "Material Girl - 2024 Remaster",
                artists = "Madonna",
                album = "Material Girl (2024 Remaster)",
                mediaId = "spotify:track:3",
            )
        val track = track(playing)
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                playing,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(writeAutomatically = WriteAutomatically.NEVER),
            )
        val theme = if (dark) "dark" else "light"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(ITEM_ROWS_TAG).performScrollTo()
            compose
                .onNodeWithTag(itemRowTag(InsightItem.SONG))
                .assertContentDescriptionEquals("Song, Material Girl, uses AI")
            save("song-row-closed-$theme-$size")
            compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(ITEM_ROWS_TAG).performScrollTo()
            compose.onNodeWithText("Version: 2024 Remaster", useUnmergedTree = true).assertExists()
            save("song-panel-open-$theme-$size")
        }
    }

    private fun save(name: String) {
        val dir = out ?: return
        val bitmap = compose.onNodeWithTag(ITEM_ROWS_TAG).captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) pictures(size, dark)
        }
    }
}
