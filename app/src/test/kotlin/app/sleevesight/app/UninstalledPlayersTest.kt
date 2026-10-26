package app.sleevesight.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.settings.PlayerRemovedReceiver
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * *Players* through the app graph (#535): an entry is deleted only when Android says the app was
 * removed, never because the phone does not show it to this app.
 */
@RunWith(RobolectricTestRunner::class)
class UninstalledPlayersTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val graph get() = app.graph

    @Before
    fun listPlayers() {
        val packages = shadowOf(app.packageManager)
        packages.installPackage(
            PackageInfo().apply {
                packageName = SHOWN
                applicationInfo = ApplicationInfo().apply { packageName = SHOWN }
            },
        )
        packages.setApplicationIcon(SHOWN, ColorDrawable(Color.RED))
        runBlocking {
            graph.settings.update {
                it.copy(
                    followedPlayers =
                        it.followedPlayers + (SHOWN to true) + (NOT_SHOWN to true) + (Settings.SPOTIFY to false),
                )
            }
        }
        FirstRunAcknowledgement(app).acknowledge()
    }

    private fun stored() = graph.settings.now().followedPlayers

    /**
     * Android's broadcast about [player], addressed to the receiver itself: Robolectric does not
     * match an implicit broadcast to a manifest filter with a data scheme (it finds no receiver),
     * so the filter is checked in the manifest instead ([theManifestQueriesPlayersAndHearsTheirRemoval]).
     */
    private fun broadcast(
        action: String,
        player: String,
        replacing: Boolean = false,
    ) = app.sendBroadcast(
        Intent(action, Uri.fromParts("package", player, null))
            .putExtra(Intent.EXTRA_REPLACING, replacing)
            .setClass(app, PlayerRemovedReceiver::class.java),
    )

    @Test
    fun aPlayerThePhoneDoesNotShowIsKeptOnStart() {
        assertTrue(graph.playerVisible(SHOWN))
        assertFalse(graph.playerVisible(NOT_SHOWN))
        assertNotNull(graph.playerIcon(SHOWN))
        assertNull(graph.playerIcon(NOT_SHOWN))

        // Started and acknowledged, with the settings read: nothing is deleted for not being shown.
        idleUntil("the settings read") { graph.latestSettings.value != null }
        idleFor("every entry kept") { stored().keys == setOf(Settings.SPOTIFY, SHOWN, NOT_SHOWN) }
    }

    @Test
    fun aPlayerIsDroppedOnceAndroidSaysItWasRemoved() {
        // An update, or a removal before its data is gone, deletes nothing.
        broadcast(Intent.ACTION_PACKAGE_REMOVED, NOT_SHOWN, replacing = true)
        broadcast(Intent.ACTION_PACKAGE_REPLACED, NOT_SHOWN)
        idleFor("kept through an update") { NOT_SHOWN in stored() }

        broadcast(Intent.ACTION_PACKAGE_FULLY_REMOVED, NOT_SHOWN)
        idleUntil("the removed player dropped") { NOT_SHOWN !in stored() }

        // Spotify, listed by default, keeps its entry, so switched off it stays off.
        broadcast(Intent.ACTION_PACKAGE_FULLY_REMOVED, Settings.SPOTIFY)
        idleFor("Spotify kept") { stored() == mapOf(Settings.SPOTIFY to false, SHOWN to true) }
    }

    /**
     * What the app can see and hear of other apps (package visibility, which Robolectric does not
     * apply), read from the manifest itself: launcher apps (#435), apps with a media browser
     * service or a media-button receiver (#535), and the removal of a package.
     */
    @Test
    fun theManifestQueriesPlayersAndHearsTheirRemoval() {
        val manifest =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = manifest.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        assertEquals(
            listOf(Intent.ACTION_MAIN, MEDIA_BROWSER_SERVICE, Intent.ACTION_MEDIA_BUTTON),
            (queries.item(0) as Element).actions(),
        )

        val receivers = manifest.getElementsByTagName("receiver")
        val removal =
            (0 until receivers.length)
                .map { receivers.item(it) as Element }
                .single { it.getAttribute("android:name") == ".settings.PlayerRemovedReceiver" }
        assertEquals(listOf(Intent.ACTION_PACKAGE_FULLY_REMOVED), removal.actions())
        val data = removal.getElementsByTagName("data").item(0) as Element
        assertEquals("package", data.getAttribute("android:scheme"))
    }

    private fun Element.actions(): List<String> =
        getElementsByTagName("action").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }
        }

    private companion object {
        const val SHOWN = "com.example.player"
        const val NOT_SHOWN = "com.example.hidden"
        const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
    }
}
