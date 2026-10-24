package app.sleevesight.app.session

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Whether the app's own listener has notification access, as each Android version can say. */
@RunWith(RobolectricTestRunner::class)
class NotificationAccessTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val ours = ComponentName(app, SleevesightListenerService::class.java)

    // Another listener in the app's package, as a library could declare one.
    private val other = ComponentName(app, "${app.packageName}.OtherListener")

    /** What Android records when the user turns [listener] on in Settings, for every version's way of asking. */
    private fun grant(listener: ComponentName) {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", listener.flattenToString())
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationListenerAccessGranted(listener, true)
    }

    @Test
    fun theAppsOwnListenerWithAccessIsAccess() {
        grant(ours)
        assertTrue(NotificationAccess(app).granted.value)
    }

    @Test
    fun anotherListenerOfThePackageWithAccessIsNotAccess() {
        grant(other)
        assertFalse(NotificationAccess(app).granted.value)
    }

    @Test
    @Config(sdk = [26])
    fun onAndroid8AnyListenerOfThePackageIsAllThereIsToAskFor() {
        grant(other)
        assertTrue(NotificationAccess(app).granted.value)
    }
}
