package app.sleevesight.app.ui.logo

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.HOME_HEADER_TAG
import app.sleevesight.app.ui.HomeScreen
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.LogoSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * A debug build's Home header (#848), where the bug in the record's place (#652) crawls rather than
 * turns, on the test's clock: it rests, never turned, then crawls up out of the header and back in
 * from its bottom to its place, drawn only inside the header; faster at 45 rpm; a trip under way when
 * the music stops goes on to its place; not playing, it stays. Frames kept as PNGs, light and dark,
 * when `SLEEVESIGHT_PICTURES` names a directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BugCrawlPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val spin = MutableStateFlow(LogoSpin(turning = true))

    private fun show(
        dark: Boolean = false,
        access: Boolean = true,
        content: (@Composable () -> Unit)? = null,
        check: () -> Unit,
    ) {
        // The unit tests run on the debug build, whose header draws the bug.
        assumeTrue(BuildConfig.DEBUG)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        CompositionLocalProvider(LocalAnimatorDurationScale provides 1f) {
                            // Nothing under the header, so the logo's ink found there is the header's.
                            content?.invoke() ?: HomeScreen(
                                accessGranted = access,
                                onOpenFirstRun = {},
                                onOpenSettings = {},
                                logoSpin = spin,
                            ) {}
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeByFrame()
            check()
        }
    }

    /** The header's box and the record's column, in the root's pixels. */
    private fun places(): Pair<Rect, IntRange> {
        val header = compose.onNodeWithTag(HOME_HEADER_TAG).fetchSemanticsNode().boundsInRoot
        val toTop = app.getString(R.string.home_scroll_to_top, app.getString(R.string.app_name))
        val box = compose.onNodeWithContentDescription(toTop).fetchSemanticsNode().boundsInRoot
        val density = app.resources.displayMetrics.density
        val height = HEADER_LOGO_HEIGHT.value * density
        val left = box.left + 8 * density
        val width = height * LineBox.width / LineBox.height
        return header to ((left + width * .8f).roundToInt() until (left + width).roundToInt())
    }

    /**
     * The rows with the bug's ink in the record's column, from the top of the screen to half the header's
     * height below it, above the link under Home's content.
     */
    private fun inkRows(dark: Boolean): List<Int> {
        val (header, column) = places()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val ink = (if (dark) LogoColors.Dark else LogoColors.Light).ink.toArgb()
        val near = { c: Int -> listOf(16, 8, 0).all { abs((c shr it and 0xFF) - (ink shr it and 0xFF)) <= 6 } }
        return (0 until (header.bottom * 3 / 2).toInt()).filter { y -> column.any { near(image.getPixel(it, y)) } }
    }

    private fun recordSquare(): Bitmap {
        val (header, column) = places()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        return Bitmap.createBitmap(
            image,
            column.first,
            header.top.toInt(),
            column.last - column.first,
            header.height.toInt(),
        )
    }

    private fun at(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    @Test
    fun itRestsUnturnedThenCrawlsOutOfTheTopAndBackFromTheBottomInsideTheHeader() =
        show {
            val (header, _) = places()
            val rest = inkRows(false)
            val still = recordSquare()
            at(REST_MS - 2 * FRAME_MS)
            assertTrue("never turned while resting", still.sameAs(recordSquare()))
            at(2 * FRAME_MS + TRIP_MS / 5)
            val up = inkRows(false)
            assertTrue("up: ${up.first()} from ${rest.first()}", up.first() < rest.first() - 2)
            at(TRIP_MS * 2 / 5)
            val gone = inkRows(false)
            assertTrue("never above the header", gone.all { it >= header.top })
            assertTrue("never below it", gone.all { it < header.bottom })
            at(TRIP_MS / 5)
            val coming = inkRows(false)
            assertTrue("back from below: ${coming.last()} past ${rest.last()}", coming.last() > rest.last() + 2)
            assertTrue("never below the header", coming.all { it < header.bottom })
            at(TRIP_MS / 5 + SLACK_MS)
            assertEquals("in its place", rest, inkRows(false))
            assertTrue("unturned", still.sameAs(recordSquare()))
        }

    @Test
    fun restingItWritesNothingTheLogoDraws() {
        // What the logo draws is read from the crawl's state, so with no write while it rests, a rest
        // draws nothing.
        var crawl: State<BugCrawl>? = null
        val turning = LogoSpin(turning = true)
        show(content = { crawl = bugCrawl(turning, running = true) }) {
            at(4 * FRAME_MS)
            val resting = checkNotNull(crawl).value
            for (step in 1..REST_SAMPLES) {
                at((REST_MS - 10 * FRAME_MS) / REST_SAMPLES)
                assertSame("the same state, at step $step", resting, checkNotNull(crawl).value)
            }
            at(10 * FRAME_MS + TRIP_MS / 2)
            assertTrue("on its trip", checkNotNull(crawl).value.moving)
        }
    }

    @Test
    fun homeTurnsItOnlyWithNotificationAccess() {
        for (access in listOf(false, true)) {
            show(access = access) {
                val still = recordSquare()
                at(REST_MS + TRIP_MS / 5)
                assertEquals("access $access", !access, still.sameAs(recordSquare()))
            }
        }
    }

    @Test
    fun at45RpmTheTripIsFaster() {
        var at33: List<Int>? = null
        show {
            at(REST_MS + TRIP_MS / 5)
            at33 = inkRows(false)
        }
        spin.value = LogoSpin(turning = true, LogoSpeed.RPM_45)
        show {
            // A fifth of the way at 45 rpm, 1.35 times sooner.
            at(REST_MS + (TRIP_MS / 5 / 1.35f).roundToInt().toLong())
            val at45 = inkRows(false)
            assertEquals(checkNotNull(at33).first().toFloat(), at45.first().toFloat(), 2f)
        }
    }

    @Test
    fun notPlayingItStaysInItsPlace() {
        spin.value = LogoSpin.STILL
        show {
            val rest = inkRows(false)
            at(REST_MS + TRIP_MS / 2)
            assertEquals(rest, inkRows(false))
        }
    }

    @Test
    fun whenTheMusicStopsMidTripItGoesOnToItsPlaceAndStays() =
        show {
            val rest = inkRows(false)
            at(REST_MS + TRIP_MS / 5)
            spin.value = LogoSpin.STILL
            at(TRIP_MS / 5)
            assertFalse("still on its way", rest == inkRows(false))
            at(TRIP_MS * 3 / 5 + SLACK_MS)
            assertEquals(rest, inkRows(false))
            at(REST_MS + TRIP_MS)
            assertEquals("no new trip", rest, inkRows(false))
        }

    @Test
    fun framesOfATripLightAndDarkDrawTheBugOnlyInsideTheHeader() {
        for (dark in listOf(false, true)) {
            show(dark) {
                val (header, _) = places()
                val theme = if (dark) "dark" else "light"
                at(REST_MS)
                for (fifth in 0..5) {
                    val image = compose.onRoot().captureToImage().asAndroidBitmap()
                    val top = Bitmap.createBitmap(image, 0, 0, image.width, (header.bottom * 2).toInt())
                    keep("bug-crawl-$theme-$fifth", top)
                    val rows = inkRows(dark)
                    assertTrue("frame $fifth: $rows in $header", rows.all { it >= header.top && it < header.bottom })
                    at(TRIP_MS / 5)
                }
            }
        }
    }

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun keep(
        name: String,
        bitmap: Bitmap,
    ) {
        val dir = out ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val REST_MS = (CRAWL_REST_SECONDS * 1000).toLong()
        val TRIP_MS = (CRAWL_SECONDS * 1000).toLong()
        const val FRAME_MS = 16L
        const val SLACK_MS = 100L
        const val REST_SAMPLES = 20
    }
}
