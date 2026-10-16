package app.sleevesight.app

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.sleevesight.app.ui.PipelineSummary
import app.sleevesight.app.ui.SleevesightNavHost
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.theme.SleevesightTheme

/** The one activity; every screen is a Compose destination in [SleevesightNavHost]. */
class MainActivity : ComponentActivity() {
    private val graph: AppGraph
        get() = (application as SleevesightApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The app declares no deep links, and the activity is exported (it is the launcher), so
        // another app could send Navigation's explicit deep-link extras to open a destination
        // directly. They are dropped before the navigation graph reads the intent.
        DEEP_LINK_EXTRAS.forEach(intent::removeExtra)
        setContent {
            SleevesightTheme {
                SleevesightNavHost(
                    notificationAccess = graph.notificationAccess,
                    firstRun = graph.firstRun,
                    openAccessSettings = ::openAccessSettings,
                    settings = { openCredits ->
                        SettingsScreen(
                            graph.settings,
                            graph.secrets,
                            graph.aiProviders,
                            graph.historyRetention::clear,
                            openCredits,
                            graph::playerLabel,
                        )
                    },
                    pipeline = {
                        val view by graph.pipeline.state.collectAsState()
                        PipelineSummary(view, onWrite = graph.pipeline::write)
                    },
                )
            }
        }
    }

    // The pipeline sends requests only while the app is visible, unless *Look up in the
    // background* is on (D6). Started and stopped, not resumed and paused: a dialog or the split
    // screen over the app leaves it visible. A stop for a configuration change (a rotation) is not
    // reported, so the start after it is not the app opening, which would ask for a new first
    // action (D5).
    override fun onStart() {
        super.onStart()
        graph.pipeline.visible(true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) graph.pipeline.visible(false)
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
