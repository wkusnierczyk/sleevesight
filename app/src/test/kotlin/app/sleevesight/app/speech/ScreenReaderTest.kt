package app.sleevesight.app.speech

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.SleevesightApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * What *Read new insights aloud* gives way to (#325): touch exploration, or a service giving spoken
 * feedback that can turn it on (TalkBack, with *Explore by touch* off too); not a service that speaks
 * only what is selected (Select to Speak).
 */
@RunWith(RobolectricTestRunner::class)
class ScreenReaderTest {
    private val manager =
        ApplicationProvider
            .getApplicationContext<SleevesightApplication>()
            .getSystemService(AccessibilityManager::class.java)

    @Test
    fun noServiceIsNoScreenReader() {
        assertFalse(screenReaderOn(manager))
        assertFalse(screenReaderOn(null))
    }

    // An enabled service giving spoken feedback, declaring it can turn touch exploration on or not.
    private fun speaking(touchExploration: Boolean) =
        AccessibilityServiceInfo().apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
            val can = if (touchExploration) AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION else 0
            ReflectionHelpers.setField(this, "mCapabilities", can)
        }

    @Test
    fun aScreenReaderIsCaughtWithExploreByTouchOff() {
        // TalkBack: spoken feedback, able to turn touch exploration on, though it is off.
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(speaking(touchExploration = true)))
        assertFalse(manager.isTouchExplorationEnabled)
        assertTrue(screenReaderOn(manager))
    }

    @Test
    fun aServiceThatSpeaksOnlyWhatIsSelectedIsNoScreenReader() {
        // Select to Speak: spoken feedback, with no touch exploration to ask for.
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(speaking(touchExploration = false)))
        assertFalse(screenReaderOn(manager))
    }

    @Test
    fun touchExplorationIsAScreenReader() {
        shadowOf(manager).setTouchExplorationEnabled(true)
        assertTrue(screenReaderOn(manager))
    }
}
