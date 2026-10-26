package app.sleevesight.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.sleevesight.app.FirstRunAcknowledgement
import app.sleevesight.app.session.NotificationAccess
import app.sleevesight.app.ui.home.HomePage
import app.sleevesight.app.ui.home.NothingPlaying
import app.sleevesight.app.ui.report.TesterReportFeed
import app.sleevesight.app.ui.report.WithTesterReport
import kotlinx.serialization.Serializable

/**
 * What leaves the device, and the way to notification access; opened again from [Home] and
 * [SettingsPage], as a page that closes back to them (#549).
 */
@Serializable
data object FirstRun

/** Where the app lands once notification access is on. */
@Serializable
data object Home

/** The settings screen, opened from [Home]. */
@Serializable
data object SettingsPage

/** The data sources and their licences, opened from [SettingsPage], as a page that closes back to it (#549). */
@Serializable
data object CreditsPage

/** The diagnostics screen, opened from [SettingsPage] while *Diagnostics* is on, with a way back to it (#549). */
@Serializable
data object DiagnosticsPage

/**
 * Where Home and its pages lead: the settings ([openSettings]), a page of Home's ([openPage], under
 * *Layout*'s *Separate pages*, #447), and, from a page, back ([back]) to where it was opened from.
 */
class HomeLinks(
    val openSettings: () -> Unit,
    val openPage: (HomePage) -> Unit,
    val back: () -> Unit,
)

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
 * which [diagnostics] draws, given the way back to the settings. [Home] shows the pipeline's state,
 * as [pipeline] draws it, given the way to the settings and to Home's pages, and, with a
 * [testerReport] feed, the tester's report button while its settings show it (#452); each of
 * [HomePage]'s pages (#447) shows an item of it on a page of its own, as [page] draws it, and goes
 * back to where it was opened from, Home at the place it was scrolled to, as Android's back does.
 */
@Suppress("LongParameterList", "LongMethod") // The access and first-run seams, and each screen's content.
@Composable
fun SleevesightNavHost(
    notificationAccess: NotificationAccess,
    firstRun: FirstRunAcknowledgement,
    openAccessSettings: () -> Boolean,
    navController: NavHostController = rememberNavController(),
    testerReport: TesterReportFeed? = null,
    settings: @Composable (links: SettingsLinks) -> Unit = {},
    pipeline: @Composable (links: HomeLinks) -> Unit = { NothingPlaying() },
    page: @Composable (page: HomePage, links: HomeLinks) -> Unit = { _, _ -> },
    diagnostics: @Composable (back: () -> Unit) -> Unit = {},
) {
    val granted by notificationAccess.granted.collectAsState()
    // Chosen once: a change of access later does not move the user off the screen they are on.
    val start: Any = remember { if (firstRun.acknowledged && notificationAccess.granted.value) Home else FirstRun }

    NavHost(navController = navController, startDestination = start) {
        composable<FirstRun> {
            // Opened from somewhere (Home, or the settings), it closes back to it (#549), with the
            // back arrow or *Close*; at the start of the app, there is nowhere to go back to, and
            // *Continue* goes on. Read once, as it opens, so it stays a page while it leaves, when
            // the entry under it is the current one.
            val reopened = remember { navController.previousBackStackEntry != null }
            val close = dropUnlessResumed { navController.popBackStack() }
            FirstRunScreen(
                accessGranted = granted,
                openAccessSettings = openAccessSettings,
                onClose = if (reopened) close else null,
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
        composable<Home> { entry ->
            if (firstRun.acknowledged) {
                val links = homeLinks(navController, entry)
                WithTesterReport(testerReport) { report ->
                    HomeScreen(
                        accessGranted = granted,
                        // Single top: a double tap opens it once, so one Continue comes back.
                        onOpenFirstRun = { navController.navigate(FirstRun) { launchSingleTop = true } },
                        onOpenSettings = links.openSettings,
                        report = report,
                        pipeline = { pipeline(links) },
                    )
                }
            } else {
                FirstRunFirst(navController)
            }
        }
        // Home's pages (#447), each drawn by [page] for the route it was opened with, from its entry.
        val homePage = @Composable { route: HomePage, entry: NavBackStackEntry ->
            if (firstRun.acknowledged) page(route, homeLinks(navController, entry)) else FirstRunFirst(navController)
        }
        composable<HomePage.Sources> { homePage(it.toRoute<HomePage.Sources>(), it) }
        composable<HomePage.Genres> { homePage(HomePage.Genres, it) }
        composable<HomePage.GenresAt> { homePage(it.toRoute<HomePage.GenresAt>(), it) }
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
            // Once: a second tap while the page leaves would pop the screen under it too.
            val close = dropUnlessResumed { navController.popBackStack() }
            if (firstRun.acknowledged) CreditsScreen(onClose = close) else FirstRunFirst(navController)
        }
        composable<DiagnosticsPage> {
            // Once, as from the credits.
            val back = dropUnlessResumed { navController.popBackStack() }
            if (firstRun.acknowledged) diagnostics(back) else FirstRunFirst(navController)
        }
    }
}

/**
 * Where Home and its pages lead, through [navController], from the screen of [entry]. A page opens,
 * and back is taken, only while [entry] is the top of the back stack: a second tap once the first
 * one's page is on the stack, or once the page has been popped, does nothing, so one back always
 * returns to the screen the page was opened from. It asks the stack, not the entry's lifecycle
 * (#548): an entry can be left short of resumed while it is on top, as a page is after a back
 * swipe started and cancelled, and a tap then did nothing, its back arrow included.
 */
private fun homeLinks(
    navController: NavHostController,
    entry: NavBackStackEntry,
): HomeLinks {
    val onTop = { navController.currentBackStackEntry?.id == entry.id }
    return HomeLinks(
        // Single top: a double tap (the cogwheel, or *Sources*' link) opens it once.
        openSettings = { navController.navigate(SettingsPage) { launchSingleTop = true } },
        openPage = { page -> if (onTop()) navController.navigate(page) },
        back = { if (onTop()) navController.popBackStack() },
    )
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
