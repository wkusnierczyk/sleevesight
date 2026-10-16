package app.sleevesight.app

import android.content.Context
import app.sleevesight.app.session.ArtLoader
import app.sleevesight.app.session.HttpArt
import app.sleevesight.app.session.NotificationAccess
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.SystemActiveSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The app's objects, wired by hand in one place (architecture, *Technology choices*): one graph
 * per process, held by [SleevesightApplication]. Components take what they need through their
 * constructors; nothing outside this class constructs a long-lived object.
 */
class AppGraph(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** The process's main-thread scope, for components that live as long as it does. */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val notificationAccess = NotificationAccess(appContext)

    val firstRun = FirstRunAcknowledgement(appContext)

    /** *Players to follow*, by package name: the default until the settings supply it (#50). */
    private val followedPlayers: Flow<Set<String>> = flowOf(SessionRepository.DEFAULT_FOLLOWED)

    val sessions =
        SessionRepository(
            sessions = SystemActiveSessions(appContext),
            acknowledged = firstRun.state,
            // `http` art waits for the process's `NetClient` and `:sources`' `ArtFetcher` (#51).
            artLoader = ArtLoader(appContext.contentResolver, HttpArt.NONE, Dispatchers.IO),
            scope = mainScope,
            followed = followedPlayers,
        )
}
