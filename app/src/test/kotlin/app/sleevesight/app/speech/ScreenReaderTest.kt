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

/**
 * What *Read new insights aloud* gives way to (#325): a screen reader, by its spoken feedback
 * (TalkBack with *Explore by touch* off too), or touch exploration.
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

    @Test
    fun aServiceGivingSpokenFeedbackIsAScreenReaderWithoutTouchExploration() {
        val talkBack = AccessibilityServiceInfo().apply { feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN }
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(talkBack))
        assertFalse(manager.isTouchExplorationEnabled)
        assertTrue(screenReaderOn(manager))
    }

    @Test
    fun touchExplorationIsAScreenReader() {
        shadowOf(manager).setTouchExplorationEnabled(true)
        assertTrue(screenReaderOn(manager))
    }
}
