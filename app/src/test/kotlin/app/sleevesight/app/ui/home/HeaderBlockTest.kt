package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * The block of the track's key data between the cover and the buttons (#1407): at least as tall as
 * the title, the artists, the album and the release line on one line each; the lines known packed at
 * its top in their order, with no blank line for one held back; the buttons under it not moving when
 * the artists or the labels come in, unless the lines outgrow the minimum, when the block grows and
 * keeps its gap above them. Native graphics, for the lines' real heights and wrapping; drawn as on a
 * phone, light and dark, at the default font size and twice it, the artists held back and named; with
 * `SLEEVESIGHT_PICTURES` set, saved there as pictures, as `MusicRowPicturesTest` saves its own.
 * Positions are checked to the pixel, at 420 dpi here and at 440 dpi in `HeaderBlock440dpiTest`,
 * where a gap of 2 dp is 5.5 px and rounding shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2200dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class HeaderBlockTest {
    /** Whether this class draws the pictures: once, at one density, is enough. */
    protected open val draws: Boolean = true

    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    @After
    fun resetFont() {
        RuntimeEnvironment.setFontScale(1f)
    }

    // What the key data shows: its artists or labels held back or not, the labels as known.
    private data class Shown(
        val held: Boolean,
        val labelHeld: Boolean,
        val labels: AlbumLabels = AlbumLabels.Looking,
    )

    // The key data of [playing] in Home's column, a box in the buttons' place under it, as [shown] says.
    private fun show(
        playing: NowPlaying,
        initial: Shown,
        block: (MutableState<Shown>) -> Unit,
    ) {
        val current = mutableStateOf(initial)
        val normalized = normalizer.normalize(playing)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(HOME_GAP),
                    ) {
                        val now = current.value
                        KeyData(
                            normalized,
                            playing,
                            HeaderYears(album = "1974"),
                            held = now.held,
                            labels = now.labels,
                            labelHeld = now.labelHeld,
                        )
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag(BUTTONS))
                    }
                }
            }
            compose.waitForIdle()
            block(current)
        }
    }

    private fun bounds(
        tag: String,
        unmerged: Boolean = false,
    ): Rect = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun text(text: String): Rect =
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun buttons(): Float = bounds(BUTTONS).top

    // [dp] in whole pixels, rounded as layout rounds a gap.
    private fun px(dp: Float): Float =
        (
            dp *
                RuntimeEnvironment
                    .getApplication()
                    .resources.displayMetrics.density
        ).roundToInt().toFloat()

    private fun assertAt(
        what: String,
        expected: Float,
        actual: Float,
    ) = assertEquals(what, expected, actual, 0f)

    private fun arrive(
        current: MutableState<Shown>,
        next: Shown,
    ) {
        current.value = next
        compose.waitForIdle()
    }

    @Test
    fun whileTheArtistsAreHeldBackTheAlbumSitsDirectlyUnderTheTitle() =
        show(sundown.copy(album = ALBUM), Shown(held = true, labelHeld = true)) {
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertExists()
            val title = text(sundown.title)
            val album = text(ALBUM)
            assertAt("the album one line gap under the title", title.bottom + px(KEY_LINE_GAP.value), album.top)
        }

    @Test
    fun theButtonsStayWhenTheArtistsAndThenTheLabelsArrive() =
        show(sundown.copy(album = ALBUM), Shown(held = true, labelHeld = true)) { current ->
            val before = buttons()
            arrive(current, Shown(held = false, labelHeld = true))
            compose.onNodeWithText(sundown.artists).assertExists()
            assertAt("the buttons after the artists", before, buttons())
            arrive(current, Shown(held = false, labelHeld = false, AlbumLabels.Known(listOf(LABEL))))
            compose.onNodeWithTag(RELEASE_LINE_TAG, useUnmergedTree = true).assertExists()
            assertAt("the buttons after the labels", before, buttons())
            // The four lines on one line each fill the minimum: the gap above the buttons alone under them.
            assertAt(
                "the buttons a gap under the release line",
                bounds(RELEASE_LINE_TAG, unmerged = true).bottom + px(HOME_GAP.value),
                buttons(),
            )
        }

    @Test
    fun theButtonsStayAtTwiceTheFontSizeTheMinimumGrowingWithIt() {
        RuntimeEnvironment.setFontScale(2f)
        show(sundown.copy(title = "Sun", album = "Sun"), Shown(held = true, labelHeld = true)) { current ->
            val before = buttons()
            arrive(current, Shown(held = false, labelHeld = false, AlbumLabels.Known(listOf(LABEL))))
            assertAt("the buttons after the artists and labels", before, buttons())
            assertAt(
                "the buttons a gap under the release line",
                bounds(RELEASE_LINE_TAG, unmerged = true).bottom + px(HOME_GAP.value),
                buttons(),
            )
        }
    }

    @Test
    fun atTwiceTheFontSizeLinesThatWrapGrowTheBlockKeepingTheGapAboveTheButtons() {
        RuntimeEnvironment.setFontScale(2f)
        val short = sundown.copy(title = "Sun", album = "Sun")
        var least = 0f
        show(short, Shown(held = false, labelHeld = false, AlbumLabels.Known(listOf(LABEL)))) {
            least = buttons()
        }
        val long = sundown.copy(title = LONG_TITLE, album = ALBUM)
        show(long, Shown(held = true, labelHeld = true)) { current ->
            val held = buttons()
            assertTrue("past the minimum: $held, the minimum's $least", held > least + 1f)
            // The wrapped title pushes the album down, packed under it; the block keeps its gap above.
            assertAt("the album one line gap under the title", text(LONG_TITLE).bottom + px(2f), text(ALBUM).top)
            arrive(current, Shown(held = false, labelHeld = false, AlbumLabels.Known(listOf(LABEL))))
            val release = bounds(RELEASE_LINE_TAG, unmerged = true)
            assertAt("the buttons a gap under the release line", release.bottom + px(HOME_GAP.value), buttons())
            assertTrue("the buttons pushed down as the lines arrive past the minimum", buttons() > held + 1f)
        }
    }

    // Home with Karajan's Ninth, MusicBrainz not answered ([answered] false) or answered.
    private fun home(
        answered: Boolean,
        dark: Boolean,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(ninth(answered), aiReady = true),
                NINTH,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(writeAutomatically = WriteAutomatically.NEVER),
            )
        // The clock still: the wait for MusicBrainz's answer (`NAMING_WAIT`) does not run out.
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.verticalScroll(rememberScrollState()).testTag(PICTURE)) {
                                NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                            }
                        }
                    }
                }
            }
            repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            block()
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun pictures() {
        assumeTrue(draws)
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                home(answered = false, dark = dark) {
                    compose.onNodeWithTag(ARTISTS_HELD_TAG).assertExists()
                    save("ninth-$theme-$size-held")
                }
                home(answered = true, dark = dark) {
                    compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
                    save("ninth-$theme-$size-named")
                }
            }
        }
    }

    private fun save(name: String) {
        val dir = out ?: return
        val bitmap = compose.onNodeWithTag(PICTURE).captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val BUTTONS = "buttons"
        const val FRAMES = 3
        const val PICTURE = "picture"
        const val ALBUM = "Gord's Gold"
        const val LABEL = "Reprise"
        const val LONG_TITLE = "If You Could Read My Mind (Live at Massey Hall, Toronto, 1972)"
        const val BEETHOVEN = "Ludwig van Beethoven"
        const val BERLIN = "Berliner Philharmoniker"
        const val KARAJAN = "Herbert von Karajan"

        // The space between the children of Home's column, the key data and the buttons among them.
        val HOME_GAP = 16.dp

        val PENDING = SourceState.Pending
        val ROLES = ArtistRoles(listOf(BEETHOVEN), listOf(BERLIN, KARAJAN))

        val NINTH: NowPlaying =
            sundown.copy(
                title = "Symphony No. 9 in D Minor, Op. 125 \"Choral\": I. Allegro ma non troppo",
                artists = "$BEETHOVEN, $BERLIN, $KARAJAN",
                album = "Beethoven: Symphony No. 9",
                mediaId = "spotify:track:ninth",
            )

        fun ninth(answered: Boolean): TrackState {
            val pending =
                HomeFixtures.track(
                    NINTH,
                    sources = mapOf(SourceId.MUSICBRAINZ to PENDING, SourceId.WIKIPEDIA to PENDING),
                    evidence = null,
                )
            return if (answered) {
                pending.copy(
                    sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.WIKIPEDIA to PENDING),
                    resolution = Resolution(recordingMbid = "r", roles = ROLES),
                )
            } else {
                pending
            }
        }
    }
}
