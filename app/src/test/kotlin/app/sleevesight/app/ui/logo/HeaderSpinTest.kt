package app.sleevesight.app.ui.logo

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.HomeScreen
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.LogoSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The header logo's record turning while music plays (#848), on the test's clock: it gets up to
 * speed in 1 s and comes to rest in 2 s, where it stops; it waits for the intro to end; it stands
 * still where it is under *Remove animations* and off screen, and goes on from there; it moves 30
 * times a second, not on every frame (#963); and turning,
 * it is drawn with the header's larger label, light and dark (kept as PNGs when
 * `SLEEVESIGHT_PICTURES` names a directory).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderSpinTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var spin by mutableStateOf(LogoSpin(turning = true))
    private var running by mutableStateOf(true)
    private var scale by mutableStateOf(1f)
    private var motion: State<RecordMotion>? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    private val now: RecordMotion get() = checkNotNull(motion).value

    /** [content] in the app's theme, light or [dark], at animator duration [scale], the clock held. */
    private fun show(
        dark: Boolean = false,
        content: @Composable () -> Unit = { motion = recordMotion(spin, running) },
        check: () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { launched ->
            scenario = launched
            launched.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        CompositionLocalProvider(LocalAnimatorDurationScale provides scale) { content() }
                    }
                }
            }
            compose.mainClock.advanceTimeByFrame()
            check()
        }
    }

    /** [block]'s change of state, sent on to the composition, as the app's own changes are. */
    private fun change(block: () -> Unit) {
        block()
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun turningItIsUpToSpeedInASecond() =
        show {
            compose.mainClock.advanceTimeBy(HALF_SECOND)
            assertTrue("speeding up: ${now.speed}", now.speed > 0f && now.speed < LP)
            compose.mainClock.advanceTimeBy(HALF_SECOND + SLACK)
            assertEquals(LP, now.speed, .001f)
        }

    @Test
    fun itTurnsInStepsOf30ASecondNotEveryFrame() =
        show {
            compose.mainClock.advanceTimeBy(2 * HALF_SECOND + SLACK)
            // A second of the test clock's 16 ms frames: the record moves on every second or third
            // of them, not on each (#963).
            var steps = 0
            repeat(FRAMES_A_SECOND) {
                val before = now.degrees
                compose.mainClock.advanceTimeByFrame()
                if (now.degrees != before) steps++
            }
            assertTrue("steps in a second: $steps", steps in FRAMES_A_SECOND / 4..FRAMES_A_SECOND / 2)
        }

    @Test
    fun at45RpmItTurnsFaster() =
        show {
            change { spin = LogoSpin(turning = true, LogoSpeed.RPM_45) }
            compose.mainClock.advanceTimeBy(2 * HALF_SECOND + SLACK)
            assertEquals(SINGLE, now.speed, .001f)
        }

    @Test
    fun stoppedItComesToRestInTwoSecondsAndStaysWhereItStops() =
        show {
            compose.mainClock.advanceTimeBy(2 * HALF_SECOND + SLACK)
            change { spin = LogoSpin.STILL }
            compose.mainClock.advanceTimeBy(2 * HALF_SECOND)
            assertTrue("slowing down: ${now.speed}", now.speed > 0f && now.speed < LP)
            compose.mainClock.advanceTimeBy(2 * HALF_SECOND + SLACK)
            assertEquals(0f, now.speed)
            val rest = now.degrees
            assertNotEquals("not back upright", 0f, rest)
            compose.mainClock.advanceTimeBy(4 * HALF_SECOND)
            assertEquals(rest, now.degrees)
            // Playing again, it starts from there.
            change { spin = LogoSpin(turning = true) }
            compose.mainClock.advanceTimeBy(FIRST_FRAMES)
            assertTrue("from where it rested: ${now.degrees}", now.degrees > rest && now.degrees < rest + 5f)
        }

    @Test
    fun itDoesNotMoveUntilTheIntroHasEnded() {
        running = false
        show {
            compose.mainClock.advanceTimeBy(4 * HALF_SECOND)
            assertEquals(RecordMotion(), now)
            change { running = true }
            compose.mainClock.advanceTimeBy(HALF_SECOND)
            assertTrue(now.speed > 0f)
        }
    }

    @Test
    fun underRemoveAnimationsItStandsStillWhereItIsAndGoesOnFromThere() =
        show {
            compose.mainClock.advanceTimeBy(2 * HALF_SECOND + SLACK)
            change { scale = 0f }
            compose.mainClock.advanceTimeBy(FIRST_FRAMES)
            val held = now.degrees
            assertEquals(0f, now.speed)
            assertTrue("not snapped upright: $held", held > 0f)
            compose.mainClock.advanceTimeBy(4 * HALF_SECOND)
            assertEquals(held, now.degrees)
            change { scale = 1f }
            compose.mainClock.advanceTimeBy(FIRST_FRAMES)
            assertTrue("from where it stood: ${now.degrees}", now.degrees > held && now.degrees < held + 5f)
        }

    @Test
    fun offScreenItStopsAndBackOnScreenItGoesOnWithoutAJump() =
        show {
            compose.mainClock.advanceTimeBy(2 * HALF_SECOND + SLACK)
            checkNotNull(scenario).moveToState(Lifecycle.State.CREATED)
            compose.mainClock.advanceTimeBy(FIRST_FRAMES)
            val left = now
            compose.mainClock.advanceTimeBy(4 * HALF_SECOND)
            assertEquals("nothing moves off screen", left, now)
            checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
            compose.mainClock.advanceTimeBy(FIRST_FRAMES)
            // At most the frames since it is back, at full speed: not the time away, nor a capped
            // first frame's 20 degrees on top.
            val moved = (now.degrees - left.degrees + 360f) % 360f
            assertTrue("moved $moved", moved > 0f && moved <= LP * FIRST_FRAMES / 1000f)
            assertEquals(LP, now.speed, .001f)
        }

    @Test
    fun backOnScreenWithTheMusicStoppedMeanwhileItStandsWhereItLeftOff() =
        show {
            compose.mainClock.advanceTimeBy(2 * HALF_SECOND + SLACK)
            checkNotNull(scenario).moveToState(Lifecycle.State.CREATED)
            compose.mainClock.advanceTimeBy(FIRST_FRAMES)
            val left = now.degrees
            change { spin = LogoSpin.STILL }
            compose.mainClock.advanceTimeBy(FIRST_FRAMES)
            checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
            compose.mainClock.advanceTimeBy(FIRST_FRAMES)
            // No coast beside a line that says it has stopped: at rest where it was.
            assertEquals(RecordMotion(left, 0f), now)
            // Stopped while it shows, it still coasts.
            change { spin = LogoSpin(turning = true) }
            compose.mainClock.advanceTimeBy(2 * HALF_SECOND + SLACK)
            change { spin = LogoSpin.STILL }
            compose.mainClock.advanceTimeBy(FIRST_FRAMES)
            assertTrue("coasting: ${now.speed}", now.speed > 0f)
        }

    /** The record's square in [logo], the header logo as drawn. */
    private fun recordOf(logo: Bitmap): Bitmap {
        val k = logo.height / LineBox.height
        val centre = recordCentre(k)
        // Its centre's pixel, and as far each way as fits.
        val r = minOf((LogoOutlines.RECORD_R * k).toInt(), logo.width - 1 - centre.x.toInt(), centre.y.toInt())
        return Bitmap.createBitmap(logo, centre.x.toInt() - r, centre.y.toInt() - r, 2 * r + 1, 2 * r + 1)
    }

    private fun recordCentre(k: Float) =
        Offset(
            (RecordCentre.x + LineShift.x - LineBox.LEFT) * k,
            (RecordCentre.y + LineShift.y - LineBox.TOP) * k,
        )

    /** How far out the red label reaches along the record's middle row, as a share of its radius. */
    private fun labelShare(
        record: Bitmap,
        colors: LogoColors,
        k: Float,
    ): Float {
        val r = record.width / 2
        val red = colors.sight.toArgb()
        val near = { c: Int -> listOf(16, 8, 0).all { abs((c shr it and 0xFF) - (red shr it and 0xFF)) <= 6 } }
        val reach = (0 until record.width).filter { near(record.getPixel(it, r)) }.maxOf { abs(it - r) + .5f }
        return reach / (LogoOutlines.RECORD_R * k)
    }

    @Test
    fun theHeaderDrawsItsRecordWithTheLargerLabelTurningLightAndDark() {
        for (dark in listOf(false, true)) {
            val colors = if (dark) LogoColors.Dark else LogoColors.Light
            show(dark = dark, content = {
                HeaderLogo(bug = false, Modifier.testTag("logo"), height = HEADER_LOGO_HEIGHT * 3, spin = spin)
            }) {
                val logo = { compose.onNodeWithTag("logo").captureToImage().asAndroidBitmap() }
                val first = logo()
                keep("header-logo-${if (dark) "dark" else "light"}-0ms", first)
                // 56% of the rim's radius, not the mark's 40%.
                val share = labelShare(recordOf(first), colors, first.height / LineBox.height)
                assertEquals("the label's reach", HEADER_LABEL / 7.5f, share, .03f)
                compose.mainClock.advanceTimeBy(2 * HALF_SECOND)
                val later = logo()
                keep("header-logo-${if (dark) "dark" else "light"}-1000ms", later)
                assertFalse("the record has turned", recordOf(first).sameAs(recordOf(later)))
                val words = { logo: Bitmap -> Bitmap.createBitmap(logo, 0, 0, logo.width * 3 / 5, logo.height) }
                assertTrue("the words stay", words(first).sameAs(words(later)))
            }
            // Home's header, turning, for looking at.
            show(dark = dark, content = {
                HomeScreen(
                    accessGranted = true,
                    onOpenFirstRun = {},
                    onOpenSettings = {},
                    logoSpin = MutableStateFlow(LogoSpin(turning = true)),
                ) { Text("Spotify · Playing") }
            }) {
                compose.mainClock.advanceTimeBy(2 * HALF_SECOND)
                keep("home-header-turning-${if (dark) "dark" else "light"}")
            }
        }
    }

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun keep(
        name: String,
        bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap(),
    ) {
        val dir = out ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val LP = LogoSpeed.RPM_33.rpm * DEGREES_PER_RPM
        val SINGLE = LogoSpeed.RPM_45.rpm * DEGREES_PER_RPM
        const val HALF_SECOND = 500L

        // The test clock's frames in a second, 16 ms each.
        const val FRAMES_A_SECOND = 62

        // A few frames, for a change to recompose and the motion to start.
        const val FIRST_FRAMES = 100L

        // A frame or two more, for the frame the motion starts on.
        const val SLACK = 50L
    }
}
