package app.sleevesight.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.sleevesight.app.ui.DiagnosticsScreen
import app.sleevesight.app.ui.SleevesightNavHost
import app.sleevesight.app.ui.home.HomeFeed
import app.sleevesight.app.ui.home.NowPlayingHome
import app.sleevesight.app.ui.logo.LocalLogoIntro
import app.sleevesight.app.ui.logo.LogoIntro
import app.sleevesight.app.ui.logo.LogoIntroOverlay
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder

/** The one activity; every screen is a Compose destination in [SleevesightNavHost]. */
class MainActivity : ComponentActivity() {
    private val graph: AppGraph
        get() = (application as SleevesightApplication).graph

    // What the home screen reads of the session layer and the pipeline, and where its taps go.
    private val homeFeed: HomeFeed by lazy {
        HomeFeed(
            view = graph.pipeline.state,
            nowPlaying = graph.sessions.nowPlaying,
            playback = graph.sessions.playback,
            art = graph.sessions.art,
            settings = graph.settings.settings,
            normalizer = Normalizer(JvmAccentFolder),
            playerLabel = graph::playerLabel,
            playerActivity = graph.sessions::playerActivity,
            write = graph.pipeline::write,
            stop = graph.pipeline::stop,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The app declares no deep links, and the activity is exported (it is the launcher), so
        // another app could send Navigation's explicit deep-link extras to open a destination
        // directly. They are dropped before the navigation graph reads the intent.
        DEEP_LINK_EXTRAS.forEach(intent::removeExtra)
        val intro = if (playsIntro(savedInstanceState)) LogoIntro.random(animatorDurationScale()) else null
        setContent {
            SleevesightTheme(fonts = graph.font) {
                CompositionLocalProvider(LocalLogoIntro provides intro) {
                    Box(Modifier.fillMaxSize()) {
                        SleevesightNavHost(
                            notificationAccess = graph.notificationAccess,
                            firstRun = graph.firstRun,
                            openAccessSettings = ::openAccessSettings,
                            settings = { links ->
                                SettingsScreen(
                                    graph.settings,
                                    graph.secrets,
                                    graph.aiProviders,
                                    graph.historyRetention::clear,
                                    graph.settingsSaves,
                                    links.openCredits,
                                    links.openDiagnostics,
                                    playerLabel = graph::playerLabel,
                                )
                            },
                            pipeline = { NowPlayingHome(homeFeed) },
                            diagnostics = { DiagnosticsScreen(graph.diagnostics) },
                        )
                        intro?.let { LogoIntroOverlay(it) }
                    }
                }
            }
        }
    }

    /**
     * Whether this start plays the logo's intro (#383): the app opened from the launcher (or as the
     * launcher opens it, as the store's Open does) while it was not running, so with no state to
     * restore; not a return from the background, which brings back the running activity, nor a
     * rotation or Android restoring a screen it closed, which have state to restore. Its process
     * may have been running, for the notification listener, which keeps it for days: the intro
     * goes by whether the app was open, not by the process. Only with *Logo intro at start* on (as
     * last read, before the settings' first read), with the phone's animations on (*Remove
     * animations* sets the scale to 0), and without touch exploration (TalkBack), for which the
     * intro would be a silent wait.
     */
    private fun playsIntro(saved: Bundle?): Boolean {
        val fromLauncher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        val exploring = getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        return saved == null && fromLauncher && graph.introAtStart.value && animatorDurationScale() > 0f && !exploring
    }

    /** The phone's animator duration scale (developer options; 0 under *Remove animations*). */
    private fun animatorDurationScale(): Float =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    // The pipeline sends requests only while the app is visible, unless *Look up in the
    // background* is on (D6). Started and stopped, not resumed and paused: a dialog or the split
    // screen over the app leaves it visible. Counted, since a second instance of the activity is
    // possible; a stop for a configuration change (a rotation) says so, so the app stays visible
    // until the new activity starts. A start never asks for a new first action (D5): only a cold
    // start, a new process, begins without one (#190).
    override fun onStart() {
        super.onStart()
        graph.pipeline.activityStarted()
    }

    override fun onStop() {
        super.onStop()
        graph.pipeline.activityStopped(changingConfigurations = isChangingConfigurations)
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed notification access in Settings while the app was away.
        graph.notificationAccess.refresh()
        // The listener service starts the session layer when the system binds it, which it does
        // not always do again after a force stop or an update; the session list needs the
        // listener enabled, not bound, so the app starts it too. It still waits for the first run
        // to be acknowledged, and starting it twice does nothing. Started here, it would not be
        // stopped by the listener's unbinding, so access revoked meanwhile stops it here.
        if (graph.notificationAccess.granted.value) graph.sessions.start() else graph.sessions.stop()
    }

    /** Opens the first settings screen for notification access that exists; false if none does. */
    private fun openAccessSettings(): Boolean {
        val opened =
            graph.notificationAccess.settingsIntents().any { intent ->
                try {
                    startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        if (!opened) Log.w(TAG, "No settings screen for notification access")
        return opened
    }

    private companion object {
        const val TAG = "Sleevesight"

        // `NavController`'s `KEY_DEEP_LINK_*` keys, which are restricted to its library group, so
        // spelled out; `NavDeepLinkBuilder` writes them.
        val DEEP_LINK_EXTRAS =
            listOf("Ids", "Args", "Extras", "Intent").map { "android-support-nav:controller:deepLink$it" }
    }
}
