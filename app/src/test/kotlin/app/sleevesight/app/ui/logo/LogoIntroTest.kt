package app.sleevesight.app.ui.logo

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.idleUntil
import app.sleevesight.app.settings.LastIntro
import app.sleevesight.app.ui.theme.SleevesightTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The logo's intro (#383): played when the app is opened from the launcher while it is not open,
 * not on a return or a rotation; a tap, Back or a key ends it; the screen beneath is out of
 * accessibility's reach while it plays; never under *Remove animations*, with the setting off, or
 * under touch exploration; and it lands on the header's logo.
 */
@RunWith(RobolectricTestRunner::class)
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
    fun aKeyEndsTheIntroAndGoesNoFurther() {
        ActivityScenario.launch<MainActivity>(fromLauncher()).use { scenario ->
            assertTrue(playing())
            var handled = false
            scenario.onActivity {
                handled = it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
            }
            assertTrue("the key taken by the intro", handled)
            assertFalse(playing())
        }
    }

    @Test
    fun aSystemKeyEndsTheIntroAndStillDoesItsOwnWork() {
        for (code in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_TAB)) {
            val intro = LogoIntro(IntroVariant.C)
            var passed = false
            val focus = FocusRequester()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        // What holds the focus beneath, as the screen does, seeing what gets past.
                        Box(Modifier.endsOnKeys(intro)) {
                            Box(
                                Modifier
                                    .focusRequester(focus)
                                    .onKeyEvent {
                                        passed = true
                                        false
                                    }.focusable(),
                            )
                        }
                    }
                }
                frame()
                scenario.onActivity { focus.requestFocus() }
                frame()
                scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }
                assertTrue("$code ends the intro", intro.finished)
                // Volume and media keys go on; a key for the screen does not.
                assertEquals("$code passed on", code != KeyEvent.KEYCODE_TAB, passed)
            }
        }
    }

    @Test
    fun theScreenBeneathIsOutOfAccessibilitysReachWhileTheIntroPlays() {
        // The first-run screen, as at a first start.
        val title = app.getString(R.string.first_run_leaves_title)

        fun beneath() = compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        ActivityScenario.launch<MainActivity>(fromLauncher()).use {
            assertTrue(playing())
            assertFalse("hidden while it plays", beneath())
            intro().performTouchInput { click() }
            frame()
            assertTrue("back once it ends", beneath())
        }
    }

    @Test
    fun aReturnFromTheBackgroundGoesOnWithTheSameIntro() {
        ActivityScenario.launch<MainActivity>(fromLauncher()).use { scenario ->
            assertTrue(playing())
            compose.mainClock.advanceTimeBy(1_000)
            var before: LogoIntro? = null
            scenario.onActivity { before = it.intro }
            val elapsed = before!!.elapsed
            assertTrue(elapsed > 0f)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            frame()
            var after: LogoIntro? = null
            scenario.onActivity { after = it.intro }
            // No new activity, so no new intro: the same one, not started again.
            assertSame(before, after)
            assertTrue("not restarted", after!!.elapsed >= elapsed)
        }
    }

    @Test
    fun aRotationPlaysNoIntro() {
        ActivityScenario.launch<MainActivity>(fromLauncher()).use { scenario ->
            assertTrue(playing())
            // A new activity with state to restore, as after a rotation.
            scenario.recreate()
            assertFalse(playing())
            scenario.onActivity { assertNull(it.intro) }
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
        // Off in the settings, and so in the mirror an earlier process left. Both, and the settings'
        // first read awaited: with the mirror alone, the first read of settings that say on (the
        // default) could land before the activity starts and turn the mirror back on.
        LastIntro(app).value = false
        runBlocking { app.graph.settings.update { it.copy(introAtStart = false) } }
        idleUntil("the setting read") { !app.graph.introAtStart.value }
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
