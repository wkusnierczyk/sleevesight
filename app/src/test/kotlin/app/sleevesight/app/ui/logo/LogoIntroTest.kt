package app.sleevesight.app.ui.logo

import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.settings.LastIntro
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The logo's intro (#383): played when the app is opened from the launcher while not running, not
 * on a return or a rotation; a tap or Back ends it; never under *Remove animations*, with the
 * setting off, or under touch exploration; and it lands on the header's logo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LogoIntroTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    @Before
    fun holdTheClock() {
        // Frame by frame, so the intro is seen while it plays rather than run to its end.
        compose.mainClock.autoAdvance = false
    }

    private fun fromLauncher() =
        Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

    private fun intro() = compose.onNodeWithTag(LOGO_INTRO_TAG)

    private fun frame() = compose.mainClock.advanceTimeByFrame()

    private fun playing(): Boolean {
        frame()
        return compose.onAllNodesWithTag(LOGO_INTRO_TAG).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aStartFromTheLauncherPlaysAnIntroToItsEnd() {
        ActivityScenario.launch<MainActivity>(fromLauncher()).use {
            assertTrue(playing())
            // The longest, R2's, ends 5.2 s in.
            compose.mainClock.advanceTimeBy(5_300)
            assertFalse(playing())
        }
    }

    @Test
    fun aTapEndsTheIntroAtOnce() {
        ActivityScenario.launch<MainActivity>(fromLauncher()).use {
            assertTrue(playing())
            intro().performTouchInput { click() }
            assertFalse(playing())
        }
    }

    @Test
    fun backEndsTheIntroAndLeavesTheAppOpen() {
        ActivityScenario.launch<MainActivity>(fromLauncher()).use { scenario ->
            assertTrue(playing())
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertFalse(playing())
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun aReturnFromTheBackgroundOrARotationPlaysNoIntro() {
        ActivityScenario.launch<MainActivity>(fromLauncher()).use { scenario ->
            assertTrue(playing())
            intro().performTouchInput { click() }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertFalse("back from the background", playing())
            // A new activity with state to restore, as after a rotation.
            scenario.recreate()
            assertFalse("after a rotation", playing())
        }
    }

    @Test
    fun aStartNotFromTheLauncherPlaysNoIntro() {
        // `ActivityScenario.launch(Class)` starts it as the launcher does; this intent names it alone.
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { assertFalse(playing()) }
    }

    @Test
    fun removeAnimationsPlaysNoIntro() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        ActivityScenario.launch<MainActivity>(fromLauncher()).use { assertFalse(playing()) }
    }

    @Test
    fun theSettingOffPlaysNoIntro() {
        // As left by an earlier process whose settings had it off.
        LastIntro(app).value = false
        ActivityScenario.launch<MainActivity>(fromLauncher()).use { assertFalse(playing()) }
    }

    @Test
    fun touchExplorationPlaysNoIntro() {
        val accessibility = app.getSystemService(AccessibilityManager::class.java)
        shadowOf(accessibility).setTouchExplorationEnabled(true)
        ActivityScenario.launch<MainActivity>(fromLauncher()).use { assertFalse(playing()) }
    }

    /** [intro] over a header logo, in a plain activity's content, its clock held. */
    private fun show(
        intro: LogoIntro,
        check: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                SleevesightTheme {
                    CompositionLocalProvider(LocalLogoIntro provides intro) {
                        Box {
                            HeaderLogo()
                            LogoIntroOverlay(intro)
                        }
                    }
                }
            }
        }
        check()
    }

    @Test
    fun theLogoLandsOnTheHeadersWhichShowsItOnceTheIntroEnds() {
        for (variant in IntroVariant.entries) {
            val intro = LogoIntro(variant)
            show(intro) {
                frame()
                assertNotNull("$variant: the header logo's place", intro.target)
                compose.mainClock.advanceTimeBy(variant.land + 100)
                assertFalse("$variant: landed, still the intro's", intro.finished)
                compose.mainClock.advanceTimeBy(variant.end(toHeader = true) - variant.land)
                assertTrue("$variant: over, the header's own logo shown", intro.finished)
            }
        }
    }

    @Test
    fun theAnimatorDurationScaleStretchesTheIntro() {
        val intro = LogoIntro(IntroVariant.C, timeScale = 2f)
        show(intro) {
            frame()
            compose.mainClock.advanceTimeBy(IntroVariant.C.end(toHeader = true) + 100)
            assertFalse("half way at twice the length", intro.finished)
            compose.mainClock.advanceTimeBy(IntroVariant.C.end(toHeader = true))
            assertTrue(intro.finished)
        }
    }
}
