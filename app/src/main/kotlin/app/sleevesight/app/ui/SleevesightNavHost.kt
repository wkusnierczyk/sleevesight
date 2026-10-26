package app.sleevesight.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.sleevesight.app.FirstRunAcknowledgement
import app.sleevesight.app.session.NotificationAccess
import app.sleevesight.app.ui.home.NothingPlaying
import app.sleevesight.app.ui.report.TesterReportFeed
import app.sleevesight.app.ui.report.WithTesterReport
import kotlinx.serialization.Serializable

/** What leaves the device, and the way to notification access; opened again from [Home] and [SettingsPage]. */
@Serializable
data object FirstRun

/** Where the app lands once notification access is on. */
@Serializable
data object Home

/** The settings screen, opened from [Home]. */
@Serializable
data object SettingsPage

/** The data sources and their licences, opened from [SettingsPage]. */
@Serializable
data object CreditsPage

/** The diagnostics screen, opened from [SettingsPage] while *Diagnostics* is on. */
@Serializable
data object DiagnosticsPage

/** Where the settings screen leads: the credits, the diagnostics screen, and the first-run screen. */
class SettingsLinks(
    val openCredits: () -> Unit,
    val openDiagnostics: () -> Unit,
    val openWhatLeaves: () -> Unit,
)

/**
 * The app's destinations. It opens on [FirstRun] until the user has continued past it and
 * notification access is on, and on [Home] after that. Continuing needs access on, so the
 * first-run screen is seen even when access was granted in Settings before the app was opened.
 * [Home] itself sends the user to [FirstRun] until they have continued past it, so no other way in
 * (a deep link, a restored back stack) skips it; nor do [SettingsPage], which [settings] draws,
 * given the ways to [CreditsPage] and [DiagnosticsPage], [CreditsPage], and [DiagnosticsPage],
 * which [diagnostics] draws. [Home] shows the pipeline's state, as [pipeline] draws it, given the
 * way to the settings, and, with a [testerReport] feed, the tester's report button while its
 * settings show it (#452).
 */
@Suppress("LongParameterList") // The access and first-run seams, and each screen's content.
@Composable
fun SleevesightNavHost(
    notificationAccess: NotificationAccess,
    firstRun: FirstRunAcknowledgement,
    openAccessSettings: () -> Boolean,
    navController: NavHostController = rememberNavController(),
    testerReport: TesterReportFeed? = null,
    settings: @Composable (links: SettingsLinks) -> Unit = {},
    pipeline: @Composable (openSettings: () -> Unit) -> Unit = { NothingPlaying() },
    diagnostics: @Composable () -> Unit = {},
) {
    val granted by notificationAccess.granted.collectAsState()
    // Chosen once: a change of access later does not move the user off the screen they are on.
    val start: Any = remember { if (firstRun.acknowledged && notificationAccess.granted.value) Home else FirstRun }

    NavHost(navController = navController, startDestination = start) {
        composable<FirstRun> {
            FirstRunScreen(
                accessGranted = granted,
                openAccessSettings = openAccessSettings,
                // Once: a second tap while the screen leaves would pop the screen under it too.
                onContinue =
                    dropUnlessResumed {
                        firstRun.acknowledge()
                        // Back to where the user came from (Home, or the settings); else Home
                        // replaces this screen.
                        if (navController.previousBackStackEntry == null || !navController.popBackStack()) {
                            navController.navigate(Home) { popUpTo<FirstRun> { inclusive = true } }
                        }
                    },
            )
        }
        composable<Home> {
            if (firstRun.acknowledged) {
                // Single top: a double tap (the cogwheel, or *Sources*' link) opens it once.
                val openSettings = { navController.navigate(SettingsPage) { launchSingleTop = true } }
                WithTesterReport(testerReport) { report ->
                    HomeScreen(
                        accessGranted = granted,
                        // Single top: a double tap opens it once, so one Continue comes back.
                        onOpenFirstRun = { navController.navigate(FirstRun) { launchSingleTop = true } },
                        onOpenSettings = openSettings,
                        report = report,
                        pipeline = { pipeline(openSettings) },
                    )
                }
            } else {
                FirstRunFirst(navController)
            }
        }
        composable<SettingsPage> {
            val links =
                SettingsLinks(
                    openCredits = { navController.navigate(CreditsPage) },
                    openDiagnostics = { navController.navigate(DiagnosticsPage) },
                    // Single top, as from Home.
                    openWhatLeaves = { navController.navigate(FirstRun) { launchSingleTop = true } },
                )
            if (firstRun.acknowledged) settings(links) else FirstRunFirst(navController)
        }
        composable<CreditsPage> {
            if (firstRun.acknowledged) CreditsScreen() else FirstRunFirst(navController)
        }
        composable<DiagnosticsPage> {
            if (firstRun.acknowledged) diagnostics() else FirstRunFirst(navController)
        }
    }
}

/**
 * However a destination past the first-run screen was reached (a deep link, a restored back
 * stack), the first-run screen comes first, and replaces everything on the stack.
 */
@Composable
private fun FirstRunFirst(navController: NavHostController) {
    LaunchedEffect(Unit) {
        navController.navigate(FirstRun) { popUpTo(navController.graph.id) { inclusive = true } }
    }
}
