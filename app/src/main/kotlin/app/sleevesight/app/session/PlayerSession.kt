package app.sleevesight.app.session

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One player's media session, as the session layer reads it: a seam over `MediaController`, so
 * the repository's tests drive it with plain values.
 */
interface PlayerSession {
    /** Tells sessions apart across lists of active sessions, which hold new controller objects. */
    val key: Any

    /** The player's package name. */
    val player: String
    val metadata: MediaMetadata?
    val playbackState: PlaybackState?
    val queue: List<MediaSession.QueueItem>?
    val queueTitle: CharSequence?

    /** Registers [callback] for this session's changes, delivered on the main thread. */
    fun register(callback: MediaController.Callback)

    fun unregister(callback: MediaController.Callback)
}

/** The system's list of active media sessions: a seam over `MediaSessionManager`. */
interface ActiveSessions {
    /** The active sessions, highest priority first (the system puts the most recently active first). */
    fun current(): List<PlayerSession>

    /** Calls [onChange] with the new list whenever it changes, on the main thread, until [stop]. */
    fun listen(onChange: (List<PlayerSession>) -> Unit)

    fun stop()
}

/** A [PlayerSession] over a `MediaController`. */
class ControllerSession(
    private val controller: MediaController,
) : PlayerSession {
    override val key: Any = controller.sessionToken
    override val player: String = controller.packageName
    override val metadata: MediaMetadata? get() = controller.metadata
    override val playbackState: PlaybackState? get() = controller.playbackState
    override val queue: List<MediaSession.QueueItem>? get() = controller.queue
    override val queueTitle: CharSequence? get() = controller.queueTitle

    override fun register(callback: MediaController.Callback) {
        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    override fun unregister(callback: MediaController.Callback) {
        controller.unregisterCallback(callback)
    }
}

/**
 * [ActiveSessions] from the `MediaSessionManager`, which answers only while
 * [SleevesightListenerService] has notification access; without it, the list is empty.
 */
class SystemActiveSessions(
    context: Context,
) : ActiveSessions {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, SleevesightListenerService::class.java)
    private var registered: MediaSessionManager.OnActiveSessionsChangedListener? = null

    override fun current(): List<PlayerSession> =
        try {
            manager.getActiveSessions(listener).map(::ControllerSession)
        } catch (_: SecurityException) {
            Log.w(TAG, "No notification access: no media sessions")
            emptyList()
        }

    override fun listen(onChange: (List<PlayerSession>) -> Unit) {
        stop()
        val changed =
            MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                onChange(controllers.orEmpty().map(::ControllerSession))
            }
        try {
            manager.addOnActiveSessionsChangedListener(changed, listener, Handler(Looper.getMainLooper()))
            registered = changed
        } catch (_: SecurityException) {
            Log.w(TAG, "No notification access: not listening for media sessions")
        }
    }

    override fun stop() {
        registered?.let(manager::removeOnActiveSessionsChangedListener)
        registered = null
    }

    private companion object {
        const val TAG = "Sleevesight"
    }
}
