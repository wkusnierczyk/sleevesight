package app.sleevesight.app.session

import android.service.notification.NotificationListenerService
import app.sleevesight.app.SleevesightApplication

/**
 * Declared to obtain the session permission: while the user grants this listener notification
 * access, the app may read the players' media sessions. It ignores the notifications themselves
 * (architecture, *Session layer*) — on Android 12 (API 31) and later its manifest `<meta-data>`
 * also tells the OS not to deliver any notification categories to it (#739) — and only starts and
 * stops the [SessionRepository] with its binding; the repository waits for the first run to be
 * acknowledged before it reads anything. After process death the system binds the listener again,
 * and the repository rebuilds its state from the active sessions.
 */
class SleevesightListenerService : NotificationListenerService() {
    private val sessions: SessionRepository
        get() = (application as SleevesightApplication).graph.sessions

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessions.start()
    }

    override fun onListenerDisconnected() {
        sessions.stop()
        super.onListenerDisconnected()
    }
}
