package app.sleevesight.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.sleevesight.app.report.AppBuild
import app.sleevesight.app.report.TesterReports
import app.sleevesight.app.ui.DiagnosticsScreen
import app.sleevesight.app.ui.SleevesightNavHost
import app.sleevesight.app.ui.home.HomeFeed
import app.sleevesight.app.ui.home.NowPlayingHome
import app.sleevesight.app.ui.home.NowPlayingPage
import app.sleevesight.app.ui.logo.LocalLogoIntro
import app.sleevesight.app.ui.logo.LogoIntro
import app.sleevesight.app.ui.logo.LogoIntroOverlay
import app.sleevesight.app.ui.logo.endsOnKeys
import app.sleevesight.app.ui.report.TesterReportFeed
import app.sleevesight.app.ui.settings.InstalledPlayers
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.app.ui.theme.isAppInDarkTheme
import app.sleevesight.app.ui.theme.isDark
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
            settings = graph.latestSettings,
            normalizer = Normalizer(JvmAccentFolder),
            playerLabel = graph::playerLabel,
            playerActivity = graph.sessions::playerActivity,
            playerLaunch = graph::playerLaunchIntent,
            write = graph.pipeline::write,
            stop = graph.pipeline::stop,
            unavailableModel = graph.unavailableModel,
            // The insight card's menu of Reactive's modes (#615), saved in the process's scope; an
            // automatic mode chosen is the first action (#625).
            chooseWritingMode = graph.settings.writingModeChooser(graph.settingsSaves, graph.pipeline::modeChosen),
        )
    }

    // What Home's tester report button reads (#452): what the app knows at the tap, never a key.
    private val testerReport: TesterReportFeed by lazy {
        TesterReportFeed(
            settings = graph.settings.settings,
            // The menu's *Hide* (#518), saved in the process's scope.
            hide = graph.settings.reportButtonHider(graph.settingsSaves),
            reports =
                TesterReports(
                    build =
                        AppBuild(
                            versionName = BuildConfig.VERSION_NAME,
                            versionCode = BuildConfig.VERSION_CODE.toLong(),
                            testBuild = BuildConfig.DEVELOPER_MODE_DEFAULT,
                        ),
                    view = graph.pipeline.state,
                    playing = graph.sessions.nowPlaying,
                    playback = graph.sessions.playback,
                    diagnostics = graph.diagnostics,
                ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The window light or dark as *Theme* (#444) last read, from its first frame: the starting
        // window before this follows the system below Android 12, where the theme cannot reach it.
        val dark = graph.theme.value.isDark(systemDark())
        setTheme(if (dark) R.style.Theme_Sleevesight_Dark else R.style.Theme_Sleevesight_Light)
        super.onCreate(savedInstanceState)
        followTheme(dark)
        // The app declares no deep links, and the activity is exported (it is the launcher), so
        // another app could send Navigation's explicit deep-link extras to open a destination
        // directly. They are dropped before the navigation graph reads the intent.
        DEEP_LINK_EXTRAS.forEach(intent::removeExtra)
        val intro = if (playsIntro(savedInstanceState)) LogoIntro.random(animatorDurationScale()) else null
        this.intro = intro
        setContent {
            SleevesightTheme(graph.font, graph.theme, graph.linkStyle, graph.developerMark) {
                SystemBarsFollowTheme()
                CompositionLocalProvider(LocalLogoIntro provides intro) {
                    // While the intro plays, the screen beneath is out of reach of keys and of
                    // accessibility services (Switch Access and the like), as it is of touch.
                    val hidden = intro != null && !intro.finished
                    Box(Modifier.fillMaxSize().endsOnKeys(intro)) {
                        Box(if (hidden) Modifier.clearAndSetSemantics {} else Modifier) {
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
                                        onOpenWhatLeaves = links.openWhatLeaves,
                                        playerLaunch = graph::playerLaunchIntent,
                                        installedPlayers =
                                            InstalledPlayers(
                                                graph::playerIcon,
                                                graph::playerVisible,
                                                graph.sessions.seenPlayers,
                                            ),
                                        modelCheck = graph.modelCheck,
                                        landOn = links.landOn,
                                        // Reactive's choice (#625): an automatic mode is the first action.
                                        onWritingModeChosen = graph.pipeline::modeChosen,
                                        playerLabel = graph::playerLabel,
                                    )
                                },
                                pipeline = { links ->
                                    NowPlayingHome(homeFeed, links.openSettings, links.openPage, links.openSettingsAt)
                                },
                                diagnostics = { back -> DiagnosticsScreen(graph.diagnostics, back) },
                                page = { page, links ->
                                    NowPlayingPage(homeFeed, page, links.openSettings, links.openPage, links.back)
                                },
                                testerReport = testerReport,
                            )
                        }
                        intro?.let { LogoIntroOverlay(it) }
                    }
                }
            }
        }
    }

    /** Whether the system's dark theme is on, as this activity's configuration says. */
    private fun systemDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * Edge to edge, with the status and navigation bars' icons dark on a light app and light on a
     * dark one, as *Theme* (#444) has it ([dark]) rather than the system's dark theme, which
     * `enableEdgeToEdge` goes by unless told.
     */
    private fun followTheme(dark: Boolean) =
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )

    /** The bars follow each change of *Theme* while the app is open ([followTheme]). */
    @Composable
    private fun SystemBarsFollowTheme() {
        val dark = isAppInDarkTheme()
        LaunchedEffect(dark) { followTheme(dark) }
    }

    /** The logo's intro this activity plays, or null; for tests. */
    internal var intro: LogoIntro? = null
        private set

    /**
     * Whether this start plays the logo's intro (#383): the app opened from the launcher (or as the
     * launcher opens it, as the store's Open does) while it was not open, so with no state to
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

        // `enableEdgeToEdge`'s own scrims for the three-button navigation bar, which it keeps private.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        // `NavController`'s `KEY_DEEP_LINK_*` keys, which are restricted to its library group, so
        // spelled out; `NavDeepLinkBuilder` writes them.
        val DEEP_LINK_EXTRAS =
            listOf("Ids", "Args", "Extras", "Intent").map { "android-support-nav:controller:deepLink$it" }
    }
}
