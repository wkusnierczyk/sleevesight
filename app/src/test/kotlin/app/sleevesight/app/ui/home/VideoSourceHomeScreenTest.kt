package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.session.VideoSources
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Home for a video source's track (#1257): an interview on YouTube shows its thumbnail as a wide
 * strip without the black bars YouTube letterboxes it in, read as "Video thumbnail", and no "Album not
 * reported" line; the same track on YouTube Music, a music player, shows as before: a square cover,
 * "Album art", the line. Drawn as on a phone, light and dark; with `SLEEVESIGHT_PICTURES` set, saved
 * there as pictures, as `AdHomeScreenTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoSourceHomeScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    // An interview's thumbnail as YouTube sends it: a 16:9 frame letterboxed in a 4:3 thumbnail.
    private fun thumbnail(bar: Int = BAR): Bitmap =
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.BLACK)
            val paint = Paint()
            paint.color = Color.rgb(74, 90, 107)
            canvas.drawRect(0f, bar.toFloat(), WIDTH.toFloat(), (HEIGHT - bar).toFloat(), paint)
            paint.color = Color.rgb(224, 180, 143)
            canvas.drawCircle(WIDTH * 0.35f, HEIGHT * 0.45f, 40f, paint)
            paint.color = Color.rgb(201, 143, 106)
            canvas.drawCircle(WIDTH * 0.65f, HEIGHT * 0.45f, 40f, paint)
        }

    private val interview =
        sundown.copy(
            title = "Mara Lind on Low Tide",
            artists = "Northside Sessions",
            album = null,
            duration = null,
            mediaId = null,
        )

    private fun show(
        playing: NowPlaying,
        dark: Boolean = false,
        art: Bitmap = thumbnail(),
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track(playing)),
                playing,
                PlaybackInfo(playing.player, PlaybackStatus.PAUSED),
                art = art,
                settings = Settings(writeAutomatically = WriteAutomatically.NEVER),
            )
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

    private fun coverRatio(): Float {
        val size = compose.onNodeWithTag(COVER_TAG).fetchSemanticsNode().size
        return size.width.toFloat() / size.height
    }

    private fun description() =
        compose
            .onNodeWithTag(COVER_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()

    @Test
    fun aYouTubeVideoShowsItsThumbnailWideWithoutBarsAndNoAlbumLine() =
        show(interview.copy(player = VideoSources.YOUTUBE)) {
            assertEquals(16f / 9f, coverRatio(), 0.02f)
            assertEquals("Video thumbnail", description())
            compose.onNodeWithText("Northside Sessions").assertExists()
            compose.onNodeWithText("Album not reported").assertDoesNotExist()
        }

    @Test
    fun aWidescreenFrameShowsAtItsOwnRatioOnceTrimmed() =
        // 2.39:1 letterboxed in 4:3: 16:9 while it is trimmed off the main thread, then 2.4:1.
        show(interview.copy(player = VideoSources.YOUTUBE), art = thumbnail(bar = WIDESCREEN_BAR)) {
            compose.waitUntil(TRIM_WAIT_MS) { abs(coverRatio() - 2.4f) < 0.03f }
        }

    @Test
    fun aVideoCoverTurnedOverIsSquareForItsBack() =
        show(interview.copy(player = VideoSources.YOUTUBE)) {
            assertEquals(16f / 9f, coverRatio(), 0.02f)
            compose.onNodeWithTag(COVER_TAG).performClick()
            compose.waitForIdle()
            assertEquals(1f, coverRatio(), 0.01f)
        }

    @Test
    fun theSameTrackOnYouTubeMusicShowsAsBefore() =
        show(interview.copy(player = YOUTUBE_MUSIC)) {
            assertEquals(1f, coverRatio(), 0.01f)
            assertEquals("Album art", description())
            compose.onNodeWithText("Album not reported").assertExists()
        }

    @Test
    fun anUnturnedVideoCoverIsReadAsAVideoThumbnail() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { ArtAndProgress(thumbnail(), PlaybackInfo.NONE, null, video = true) }
            }
            compose.onNodeWithContentDescription("Video thumbnail").assertExists()
        }
    }

    @Test
    fun pictures() {
        for ((name, player) in listOf("youtube" to VideoSources.YOUTUBE, "youtube-music" to YOUTUBE_MUSIC)) {
            for (dark in listOf(false, true)) {
                show(interview.copy(player = player), dark) {
                    val dir = out ?: return@show
                    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                    val file = "home-$name-${if (dark) "dark" else "light"}.png"
                    File(dir, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    private companion object {
        const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        const val WIDTH = 480
        const val HEIGHT = 360

        // The bars of a 2.39:1 frame in a 4:3 thumbnail, leaving 480 by 200.
        const val WIDESCREEN_BAR = 80
        const val TRIM_WAIT_MS = 5_000L

        // The bars of a 16:9 frame in a 4:3 thumbnail: (360 - 480 * 9 / 16) / 2.
        val BAR = ((HEIGHT - WIDTH * 9f / 16f) / 2).roundToInt()
    }
}
