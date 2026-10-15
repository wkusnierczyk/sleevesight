package io.github.wkusnierczyk.sleevesight.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.wkusnierczyk.sleevesight.app.FirstRunAcknowledgement
import io.github.wkusnierczyk.sleevesight.app.session.NotificationAccess
import kotlinx.serialization.Serializable

/** What leaves the phone, and the way to notification access. */
@Serializable
data object FirstRun

/** Where the app lands once notification access is on. */
@Serializable
data object Home

/**
 * The app's destinations. It opens on [FirstRun] until the user has continued past it and
 * notification access is on, and on [Home] after that. Continuing needs access on, so the
 * first-run screen is seen even when access was granted in Settings before the app was opened.
 */
@Composable
fun SleevesightNavHost(
    notificationAccess: NotificationAccess,
    firstRun: FirstRunAcknowledgement,
    openAccessSettings: () -> Boolean,
) {
    val granted by notificationAccess.granted.collectAsState()
    val navController = rememberNavController()
    // Chosen once: a change of access later does not move the user off the screen they are on.
    val start: Any = remember { if (firstRun.acknowledged && notificationAccess.granted.value) Home else FirstRun }

    NavHost(navController = navController, startDestination = start) {
        composable<FirstRun> {
            FirstRunScreen(
                accessGranted = granted,
                openAccessSettings = openAccessSettings,
                onContinue = {
                    firstRun.acknowledge()
                    // Back to Home if the user came from there; else Home replaces this screen.
                    if (!navController.popBackStack<Home>(inclusive = false)) {
                        navController.navigate(Home) { popUpTo<FirstRun> { inclusive = true } }
                    }
                },
            )
        }
        composable<Home> {
            HomeScreen(
                accessGranted = granted,
                onOpenFirstRun = { navController.navigate(FirstRun) },
            )
        }
    }
}
