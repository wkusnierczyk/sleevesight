package app.sleevesight.app

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
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
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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

    /** A player with no launcher entry, only a media browser service, as the manifest queries it. */
    private fun installBrowserOnly(player: String) {
        install(player)
        val packages = shadowOf(app.packageManager)
        val service = ComponentName(player, "$player.Browser")
        packages.addOrUpdateService(
            ServiceInfo().apply {
                packageName = player
                name = service.className
            },
        )
        packages.addIntentFilterForService(service, IntentFilter(MEDIA_BROWSER_SERVICE))
    }

    @Test
    fun onStartAPlayerNoLongerInstalledIsDroppedAndAnInstalledOneKeepsItsSwitch() {
        install(INSTALLED)
        installBrowserOnly(BROWSER_ONLY)
        runBlocking {
            graph.settings.update {
                it.copy(
                    followedPlayers =
                        it.followedPlayers + (INSTALLED to true) + (BROWSER_ONLY to true) + (UNINSTALLED to true),
                )
            }
        }
        // The check runs once the settings are read, after the first run is acknowledged.
        FirstRunAcknowledgement(app).acknowledge()

        idleUntil("the uninstalled player dropped") { UNINSTALLED !in graph.settings.now().followedPlayers }
        // Spotify, not installed here, keeps its entry: it is listed by default.
        // A player with only a media browser service is kept too, with its icon.
        assertEquals(
            mapOf(Settings.SPOTIFY to true, INSTALLED to true, BROWSER_ONLY to true),
            graph.settings.now().followedPlayers,
        )
        assertNotNull(graph.playerIcon(INSTALLED))
        assertNotNull(graph.playerIcon(BROWSER_ONLY))
        assertNull(graph.playerIcon(UNINSTALLED))
    }

    /**
     * The players the app can see (package visibility, which Robolectric does not apply): launcher
     * apps (#435), and apps with a media browser service, so a player with no launcher entry is not
     * taken as uninstalled (#535). Read from the manifest itself.
     */
    @Test
    fun theManifestQueriesLauncherAppsAndMediaBrowserServices() {
        val manifest =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = manifest.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        val actions =
            (queries.item(0) as Element).getElementsByTagName("action").let { nodes ->
                (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }
            }
        assertEquals(listOf(Intent.ACTION_MAIN, MEDIA_BROWSER_SERVICE), actions)
    }

    private companion object {
        const val INSTALLED = "com.example.player"
        const val UNINSTALLED = "com.example.gone"
        const val BROWSER_ONLY = "com.example.browser"
        const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
    }
}
