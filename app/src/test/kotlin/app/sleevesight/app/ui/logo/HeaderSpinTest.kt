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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.HomeScreen
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.LogoSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The header logo's record turning while music plays (#848), on the test's clock: it gets up to
 * speed in 1 s and comes to rest in 2 s, where it stops; it waits for the intro to end; it stands
 * still, upright, under *Remove animations*; and turning, it is drawn, with the header's larger
 * label, light and dark (kept as PNGs when `SLEEVESIGHT_PICTURES` names a directory).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderSpinTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var spin by mutableStateOf(LogoSpin(turning = true))
    private var running by mutableStateOf(true)
    private var motion: State<RecordMotion>? = null

    private val now: RecordMotion get() = checkNotNull(motion).value

    /** [content] in the app's theme, light or [dark], at animator duration [scale], the clock held. */
    private fun show(
        scale: Float = 1f,
        dark: Boolean = false,
        content: @Composable () -> Unit = { motion = recordMotion(spin, running) },
        check: () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
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
    fun underRemoveAnimationsItStandsStillUpright() =
        show(scale = 0f) {
            compose.mainClock.advanceTimeBy(4 * HALF_SECOND)
            assertEquals(RecordMotion(), now)
        }

    @Test
    fun turningTheHeaderDrawsTheRecordWithItsLargerLabelLightAndDark() {
        for (dark in listOf(false, true)) {
            spin = LogoSpin(turning = true)
            show(dark = dark, content = {
                HomeScreen(
                    accessGranted = true,
                    onOpenFirstRun = {},
                    onOpenSettings = {},
                    logoSpin = MutableStateFlow(spin),
                ) { Text("Spotify · Playing") }
            }) {
                compose.mainClock.advanceTimeBy(2 * HALF_SECOND)
                keep("home-header-turning-${if (dark) "dark" else "light"}")
            }
            show(dark = dark, content = {
                motion = recordMotion(spin, running)
                HeaderLogo(bug = false, Modifier.testTag("logo"), height = HEADER_LOGO_HEIGHT * 4, spin = spin)
            }) {
                compose.mainClock.advanceTimeBy(HALF_SECOND)
                val logo = compose.onNodeWithTag("logo").captureToImage().asAndroidBitmap()
                keep("header-logo-turning-${if (dark) "dark" else "light"}", logo)
                // The record, turned, with its red label, at the logo's end.
                val colors = if (dark) LogoColors.Dark else LogoColors.Light
                val record = Bitmap.createBitmap(logo, logo.width * 4 / 5, 0, logo.width / 5, logo.height)
                val pixels = IntArray(record.width * record.height)
                record.getPixels(pixels, 0, record.width, 0, 0, record.width, record.height)
                assertTrue("the red label", pixels.any { it == colors.sight.toArgb() })
                assertTrue("turned: ${now.degrees}", now.degrees > 0f)
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

        // A few frames, for a change to recompose and the motion to start.
        const val FIRST_FRAMES = 100L

        // A frame or two more, for the frame the motion starts on.
        const val SLACK = 50L
    }
}
