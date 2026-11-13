package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The insight with a sentence its line does not back (#1036), drawn as on a phone, light and dark, at
 * the default font size and twice it, with *Sources* open: the sentence dimmed, its chip and its
 * line's tag a dim outline. The pictures are written only when `SLEEVESIGHT_PICTURES` names a
 * directory; otherwise each case only checks the outlined tag is there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnsupportedCitationPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private val state =
        NowPlayingState(
            PipelineView(
                track(
                    insight =
                        written(
                            "It topped the charts in 1986 [W1]. Sundown was recorded in 1973 [MB1]. " +
                                "It is a single [WD1].",
                            support = true,
                        ),
                ),
                aiReady = true,
            ),
            sundown,
            PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
            settings = Settings(),
        )

    private fun show(
        dark: Boolean,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                show(dark) {
                    compose
                        .onAllNodesWithTag(SOURCES_CHIP_TAG)
                        .onFirst()
                        .performScrollTo()
                        .performClick()
                    compose.waitForIdle()
                    compose.onNodeWithTag(UNSUPPORTED_TAG_PREFIX + "W1").assertExists()
                    val dir = out ?: return@show
                    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                    val name = "unsupported-${if (dark) "dark" else "light"}-$size.png"
                    File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }
}
