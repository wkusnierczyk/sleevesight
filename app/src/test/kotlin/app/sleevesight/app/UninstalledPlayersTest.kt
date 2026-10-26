package app.sleevesight.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** *Players* through the app graph (#535): a player no longer installed leaves the list on start. */
@RunWith(RobolectricTestRunner::class)
class UninstalledPlayersTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val graph get() = app.graph

    private fun install(player: String) {
        val packages = shadowOf(app.packageManager)
        packages.installPackage(
            PackageInfo().apply {
                packageName = player
                applicationInfo = ApplicationInfo().apply { packageName = player }
            },
        )
        packages.setApplicationIcon(player, ColorDrawable(Color.RED))
    }

    @Test
    fun onStartAPlayerNoLongerInstalledIsDroppedAndAnInstalledOneKeepsItsSwitch() {
        install(INSTALLED)
        runBlocking {
            graph.settings.update {
                it.copy(followedPlayers = it.followedPlayers + (INSTALLED to true) + (UNINSTALLED to true))
            }
        }
        // The check runs once the settings are read, after the first run is acknowledged.
        FirstRunAcknowledgement(app).acknowledge()

        idleUntil("the uninstalled player dropped") { UNINSTALLED !in graph.settings.now().followedPlayers }
        // Spotify, not installed here, keeps its entry: it is listed by default.
        assertEquals(mapOf(Settings.SPOTIFY to true, INSTALLED to true), graph.settings.now().followedPlayers)
        assertNotNull(graph.playerIcon(INSTALLED))
        assertNull(graph.playerIcon(UNINSTALLED))
    }

    private companion object {
        const val INSTALLED = "com.example.player"
        const val UNINSTALLED = "com.example.gone"
    }
}
