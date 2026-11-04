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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.AdPlaying
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Home while the player plays an ad (#880): the player row, a plain tile with the position, "Ad
 * playing on Spotify", the line under it and the note, and nothing else: none of the buttons a track
 * has, so no tap can start a lookup or a call; nothing of the track before, which the pipeline still
 * holds. Drawn as on a phone, light and dark, at the default font size and twice it; with
 * `SLEEVESIGHT_PICTURES` set, each is also saved there as a picture, as `RewrittenNotePicturesTest`
 * saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdHomeScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    // The pipeline still holds the track before the ad, written: none of it shows.
    private val state =
        NowPlayingState(
            view = PipelineView(track(insight = written("Sundown was recorded in 1973 [MB1]."))),
            playing = null,
            playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING, position = 11.seconds),
            settings = Settings(),
            ad = AdPlaying(Settings.SPOTIFY, 28.seconds),
        )

    private fun show(
        dark: Boolean = false,
        block: () -> Unit,
    ) {
        val actions = NowPlayingActions(normalizer::normalize, playerLabel = { "Spotify" })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                NowPlayingContent(state, actions)
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
    fun `an ad shows the title, the line under it and the note, and none of the buttons`() =
        show {
            compose.onNodeWithTag(AD_HOME_TAG).assertExists()
            compose.onNodeWithText("Ad playing on Spotify").assertExists()
            compose.onNodeWithText("The next track shows when the ad ends.").assertExists()
            compose
                .onNodeWithText("An ad is playing. Sleevesight looks nothing up for ads and writes no insight.")
                .assertExists()
            // The player row alone can be tapped: it opens the player.
            val taps = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            assertEquals(1, taps.size)
            compose.onNode(hasClickAction() and hasTestTag(PLAYER_TAG)).assertExists()
            for (text in NOT_SHOWN) {
                assertEquals(text, 0, compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size)
            }
        }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                show(dark) {
                    compose.onNodeWithText("Ad playing on Spotify").assertExists()
                    val dir = out ?: return@show
                    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                    val name = "ad-home-${if (dark) "dark" else "light"}-$size.png"
                    File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    private companion object {
        // What a track shows that an ad does not: its buttons, panels and rows, and the track before.
        val NOT_SHOWN =
            listOf(
                "Dates",
                "Genres",
                "Lyrics",
                "Sources",
                "Song",
                "Artist",
                "Album",
                "Key insights",
                "Write",
                "Ask AI",
                "Read aloud",
                "Sundown",
                "1973",
            )
    }
}
