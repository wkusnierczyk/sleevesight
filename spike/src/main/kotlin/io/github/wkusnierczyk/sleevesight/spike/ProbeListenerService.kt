package io.github.wkusnierczyk.sleevesight.spike

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import org.json.JSONObject

/**
 * Declared only to obtain access to the media sessions: `getActiveSessions` requires an enabled
 * notification listener. The notifications themselves are ignored.
 */
class ProbeListenerService : NotificationListenerService() {
    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers: List<MediaController>? ->
            ProbeLog.event("active_sessions_changed", JSONObject().put("count", controllers?.size ?: 0))
            SessionWatcher.update(this, controllers.orEmpty())
        }

    override fun onListenerConnected() {
        ProbeLog.init(this)
        ProbeLog.event("listener_connected", Install.describe(this))
        val manager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, ProbeListenerService::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsChanged, component)
        SessionWatcher.update(this, manager.getActiveSessions(component))
    }

    override fun onListenerDisconnected() {
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged)
        SessionWatcher.stopAll()
        ProbeLog.event("listener_disconnected")
    }
}
