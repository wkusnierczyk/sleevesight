package app.sleevesight.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.sleevesight.app.session.SleevesightListenerService
import app.sleevesight.app.ui.CreditsPage
import app.sleevesight.app.ui.FirstRun
import app.sleevesight.app.ui.Home
import app.sleevesight.app.ui.SettingsPage
import app.sleevesight.app.ui.SleevesightNavHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirstRunFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val component = ComponentName(app, SleevesightListenerService::class.java)
    private val listener = component.flattenToString()

    private fun text(id: Int) = app.getString(id)

    /**
     * What Android records when the user turns the listener on or off in Settings: the setting the
     * package list reads (Android 8.0), and the listener's own grant (8.1 on), which Robolectric
     * keeps apart.
     */
    private fun setAccess(granted: Boolean) {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", if (granted) listener else "")
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationListenerAccessGranted(component, granted)
    }

    /** As on a phone whose settings app has only [actions] of the notification-access screens. */
    private fun settingsScreens(vararg actions: String) {
        shadowOf(app).checkActivities(true)
        for (action in actions) {
            val screen =
                ResolveInfo().apply {
                    activityInfo =
                        ActivityInfo().apply {
                            packageName = "com.android.settings"
                            name = "com.android.settings.Settings"
                        }
                }
            shadowOf(app.packageManager).addResolveInfoForIntent(Intent(action), screen)
        }
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)

    /**
     * A launch with Navigation's explicit deep link to [Home], as another app could send it, with
     * all four of its extras as `NavDeepLinkBuilder` writes them.
     */
    private fun launchDeepLinkedToHome(): ActivityScenario<MainActivity> {
        // The graph `NavHost` builds has no route, so id 0; a typed destination's id is the hash of
        // its serial name.
        val graphId = 0
        val homeId =
            Home
                .serializer()
                .descriptor.serialName
                .hashCode()
        val intent =
            Intent(app, MainActivity::class.java)
                .putExtra(NavController.KEY_DEEP_LINK_IDS, intArrayOf(graphId, homeId))
                .putParcelableArrayListExtra(NavController.KEY_DEEP_LINK_ARGS, arrayListOf(Bundle(), Bundle()))
                .putExtra(NavController.KEY_DEEP_LINK_EXTRAS, Bundle())
                .putExtra(NavController.KEY_DEEP_LINK_INTENT, Intent(app, MainActivity::class.java))
        return ActivityScenario.launch(intent)
    }

    /** As after an earlier run in which the user continued past the first-run screen. */
    private fun acknowledgeFirstRun() = FirstRunAcknowledgement(app).acknowledge()

    @Test
    fun withoutAccessTheFirstRunScreenSaysWhatLeavesThePhone() {
        setAccess(granted = false)
        launch().use {
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_to_sources)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_to_ai)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.access_off)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theButtonOpensTheAppsOwnNotificationAccessSwitch() {
        setAccess(granted = false)
        launch().use { scenario ->
            compose.onNodeWithText(text(R.string.open_access_settings)).performScrollTo().performClick()

            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, started?.action)
            assertEquals(listener, started?.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
        }
    }

    @Test
    fun withoutTheAppsOwnSwitchTheButtonOpensTheListOfApps() {
        setAccess(granted = false)
        settingsScreens(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        launch().use { scenario ->
            compose.onNodeWithText(text(R.string.open_access_settings)).performScrollTo().performClick()

            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, started?.action)
            assertEquals(0, compose.onAllNodesWithText(text(R.string.no_access_settings)).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun withNoSettingsScreenForAccessTheFirstRunScreenSaysSo() {
        setAccess(granted = false)
        settingsScreens()
        launch().use { scenario ->
            compose.onNodeWithText(text(R.string.open_access_settings)).performScrollTo().performClick()

            compose.onNodeWithText(text(R.string.no_access_settings)).performScrollTo().assertIsDisplayed()
            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            assertNull(started)
        }
    }

    @Test
    @Config(sdk = [29])
    fun beforeAndroid11TheButtonOpensTheListOfApps() {
        setAccess(granted = false)
        launch().use { scenario ->
            compose.onNodeWithText(text(R.string.open_access_settings)).performScrollTo().performClick()

            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, started?.action)
        }
    }

    @Test
    fun accessGrantedInSettingsLeadsToHomeOnReturn() {
        setAccess(granted = false)
        launch().use { scenario ->
            compose.onNodeWithText(text(R.string.open_access_settings)).performScrollTo().performClick()
            setAccess(granted = true)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            compose.onNodeWithText(text(R.string.access_on)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()

            // The first-run screen is not left behind Home: nothing in the app handles Back.
            var handlesBack = true
            scenario.onActivity { handlesBack = it.onBackPressedDispatcher.hasEnabledCallbacks() }
            assertFalse(handlesBack)
        }
    }

    @Test
    fun accessGrantedBeforeTheFirstRunStillShowsTheFirstRunScreen() {
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
        // Continuing is remembered: the next start opens on Home.
        launch().use {
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun afterTheFirstRunAndWithAccessTheAppOpensOnHome() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun afterTheFirstRunButWithoutAccessTheAppOpensOnTheFirstRunScreen() {
        acknowledgeFirstRun()
        setAccess(granted = false)
        launch().use {
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()
        }
    }

    @Test
    fun aDeepLinkToHomeDoesNotSkipTheFirstRunScreen() {
        setAccess(granted = true)
        launchDeepLinkedToHome().use { scenario ->
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()

            // Dropped before the navigation graph read them.
            var left = emptyList<String>()
            scenario.onActivity { activity -> left = DEEP_LINK_EXTRAS.filter { activity.intent.hasExtra(it) } }
            assertEquals(emptyList<String>(), left)
        }
    }

    @Test
    fun homeSendsTheUserToTheFirstRunScreenUntilItIsAcknowledged() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use { scenario ->
            lateinit var nav: NavHostController
            scenario.onActivity { activity ->
                val graph = (activity.application as SleevesightApplication).graph
                activity.setContent {
                    nav = rememberNavController()
                    SleevesightNavHost(graph.notificationAccess, graph.firstRun, { true }, nav)
                }
            }
            compose.onNodeWithText(text(R.string.home_what_leaves)).performClick()
            // FirstRun drawn first, so the redirect checked below is the one Home makes now, not
            // one left over from the start.
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).performScrollTo().assertIsDisplayed()

            // Home reached with the first run not acknowledged, over a stack of Home and FirstRun,
            // as a deep link or a restored back stack could leave it, with no deep-link extras.
            app.getSharedPreferences("first_run", Context.MODE_PRIVATE).edit { clear() }
            scenario.onActivity { nav.navigate(Home) }

            compose.onNodeWithText(text(R.string.first_run_leaves_title)).performScrollTo().assertIsDisplayed()
            var routes = emptyList<String?>()
            var handlesBack = true
            scenario.onActivity { activity ->
                routes = nav.currentBackStack.value.map { it.destination.route }
                handlesBack = activity.onBackPressedDispatcher.hasEnabledCallbacks()
            }
            assertEquals(listOf(null, FirstRun.serializer().descriptor.serialName), routes)
            assertFalse(handlesBack)

            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
            scenario.onActivity { routes = nav.currentBackStack.value.map { it.destination.route } }
            assertEquals(listOf(null, Home.serializer().descriptor.serialName), routes)
        }
    }

    @Test
    fun homeReachedThroughTheControllerBeforeTheFirstRunIsAcknowledgedSendsTheUserBack() {
        setAccess(granted = true)
        launch().use { scenario ->
            lateinit var nav: NavHostController
            scenario.onActivity { activity ->
                val graph = (activity.application as SleevesightApplication).graph
                activity.setContent {
                    nav = rememberNavController()
                    SleevesightNavHost(graph.notificationAccess, graph.firstRun, { true }, nav)
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()

            scenario.onActivity { nav.navigate(Home) }

            compose.onNodeWithText(text(R.string.first_run_leaves_title)).performScrollTo().assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithText(text(R.string.home_placeholder)).fetchSemanticsNodes().size)
            var routes = emptyList<String?>()
            scenario.onActivity { routes = nav.currentBackStack.value.map { it.destination.route } }
            assertEquals(listOf(null, FirstRun.serializer().descriptor.serialName), routes)
        }
    }

    @Test
    fun deepLinkExtrasDoNoHarmAfterTheFirstRun() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launchDeepLinkedToHome().use {
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun homeLeadsBackToTheFirstRunScreen() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.home_what_leaves)).performClick()
            // Robolectric runs out of touch mode, so Continue takes focus and is scrolled into view
            // when Home opens the first-run screen; the heading is scrolled back to.
            compose.onNodeWithText(text(R.string.first_run_leaves_title)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun inTouchModeHomeOpensTheFirstRunScreenAtTheTop() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(true)
        try {
            launch().use {
                compose.onNodeWithText(text(R.string.home_what_leaves)).performClick()
                // As on a phone the user touches: nothing takes focus, so nothing is scrolled into view.
                compose.onNodeWithText(text(R.string.app_name)).assertIsDisplayed()
                compose.onNodeWithText(text(R.string.first_run_leaves_title)).assertIsDisplayed()
            }
        } finally {
            // Robolectric's default, which the other tests here expect.
            instrumentation.setInTouchMode(false)
        }
    }

    @Test
    fun homeOpensTheSettings() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.home_settings)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.settings_section_ai)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun theSettingsOpenTheCredits() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use {
            compose.onNodeWithText(text(R.string.home_settings)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.credits_title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.credits_title)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.credits_intro)).assertIsDisplayed()
        }
    }

    @Test
    fun theCreditsSendTheUserToTheFirstRunScreenUntilItIsAcknowledged() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use { scenario ->
            lateinit var nav: NavHostController
            scenario.onActivity { activity ->
                val graph = (activity.application as SleevesightApplication).graph
                activity.setContent {
                    nav = rememberNavController()
                    SleevesightNavHost(graph.notificationAccess, graph.firstRun, { true }, nav)
                }
            }
            compose.waitForIdle()
            app.getSharedPreferences("first_run", Context.MODE_PRIVATE).edit { clear() }
            scenario.onActivity { nav.navigate(CreditsPage) }

            compose.onNodeWithText(text(R.string.first_run_leaves_title)).performScrollTo().assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithText(text(R.string.credits_intro)).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun theSettingsSendTheUserToTheFirstRunScreenUntilItIsAcknowledged() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        launch().use { scenario ->
            lateinit var nav: NavHostController
            scenario.onActivity { activity ->
                val graph = (activity.application as SleevesightApplication).graph
                activity.setContent {
                    nav = rememberNavController()
                    SleevesightNavHost(
                        graph.notificationAccess,
                        graph.firstRun,
                        { true },
                        nav,
                    ) { Text("Settings drawn") }
                }
            }
            compose.waitForIdle()
            app.getSharedPreferences("first_run", Context.MODE_PRIVATE).edit { clear() }
            scenario.onActivity { nav.navigate(SettingsPage) }

            compose.onNodeWithText(text(R.string.first_run_leaves_title)).performScrollTo().assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithText("Settings drawn").fetchSemanticsNodes().size)
        }
    }

    private fun seenPlayers() = app.graph.sessions.seenPlayers.value

    /**
     * That the session layer reads no session: once the settings' first read is in, which the
     * layer also waits for, nothing appears for a while.
     */
    private fun assertNoSessionRead() {
        runBlocking {
            app.graph.settings.followedPlayers
                .first()
        }
        idleFor("no session read") { seenPlayers().isEmpty() }
    }

    /** A media session of a player not followed, so starting the session layer reads only the list. */
    private fun addSession(player: String) {
        val controller = MediaController(app, MediaSession(app, "test").sessionToken)
        shadowOf(controller).setPackageName(player)
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(controller)
    }

    @Test
    fun theAppStartsTheSessionLayerWithoutTheListenerBound() {
        // As after a force stop or an update: access granted, but the system has not bound the
        // listener again, so nothing calls `onListenerConnected`.
        acknowledgeFirstRun()
        setAccess(granted = false)
        addSession("com.example.player")
        launch().use { scenario ->
            assertNoSessionRead()
            setAccess(granted = true)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            idleUntil("the session list read") { seenPlayers().isNotEmpty() }
            assertEquals(setOf("com.example.player"), seenPlayers())
        }
    }

    @Test
    fun accessRevokedWhileAwayStopsTheSessionLayerTheAppStarted() {
        acknowledgeFirstRun()
        setAccess(granted = true)
        val controller = MediaController(app, MediaSession(app, "test").sessionToken)
        shadowOf(controller).setPackageName("com.spotify.music")
        shadowOf(controller).setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Sundown").build(),
        )
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(controller)
        launch().use { scenario ->
            idleUntil("Spotify's track shown") { app.graph.sessions.nowPlaying.value != null }
            assertEquals(
                "Sundown",
                app.graph.sessions.nowPlaying.value
                    ?.title,
            )
            assertEquals(1, shadowOf(controller).callbacks.size)

            setAccess(granted = false)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(app.graph.sessions.nowPlaying.value)
            assertEquals(0, shadowOf(controller).callbacks.size)
        }
    }

    @Test
    fun theSessionLayerStartedByTheAppStillWaitsForTheFirstRun() {
        setAccess(granted = true)
        addSession("com.example.player")
        launch().use {
            assertNoSessionRead()
            compose.onNodeWithText(text(R.string.first_run_continue)).performScrollTo().performClick()
            idleUntil("the session list read") { seenPlayers().isNotEmpty() }
            assertEquals(setOf("com.example.player"), seenPlayers())
        }
    }

    private companion object {
        /** `NavController`'s explicit deep-link extras, which `MainActivity` drops. */
        val DEEP_LINK_EXTRAS =
            listOf(
                NavController.KEY_DEEP_LINK_IDS,
                NavController.KEY_DEEP_LINK_ARGS,
                NavController.KEY_DEEP_LINK_EXTRAS,
                NavController.KEY_DEEP_LINK_INTENT,
            )
    }
}
