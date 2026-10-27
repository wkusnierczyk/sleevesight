package app.sleevesight.app

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageItemInfo
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.SleevesightListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How the debug build names itself on a phone (#614), apart from the Play build: by its label (the
 * launcher, App info, the notification-access list) and its version's `-debug`. The app's unit tests
 * run on the debug variant only; CI checks both APKs themselves, the release's still *Sleevesight*
 * with the bare version (`check-build-identity.sh`).
 */
@RunWith(RobolectricTestRunner::class)
class BuildIdentityTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun label(info: PackageItemInfo) = info.loadLabel(app.packageManager).toString()

    @Test
    fun theDebugBuildIsLabelledSleevesightDebug() {
        assertTrue(BuildConfig.DEBUG)
        assertEquals("Sleevesight Debug", label(app.applicationInfo))
    }

    @Test
    fun theNotificationAccessListNamesTheDebugBuildAsTheLauncherDoes() {
        val service = ComponentName(app, SleevesightListenerService::class.java)
        assertEquals("Sleevesight Debug", label(app.packageManager.getServiceInfo(service, 0)))
    }

    @Test
    fun theAppsOwnScreensStillSaySleevesight() {
        assertEquals("Sleevesight", app.getString(R.string.app_name))
    }

    @Test
    fun theDebugBuildsVersionEndsInDebug() {
        assertTrue(BuildConfig.VERSION_NAME, Regex("""\d+\.\d+\.\d+-debug""").matches(BuildConfig.VERSION_NAME))
    }
}
