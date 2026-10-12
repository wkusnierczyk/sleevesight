package io.github.wkusnierczyk.sleevesight.spike

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Follows every active media session and logs what it publishes. At each track change it records
 * how far from its end the previous track was and the states just before, for decision D5.
 */
object SessionWatcher {
    private const val RECENT_STATES_WINDOW_MS = 3_000L
    private const val STATES_KEPT = 50

    private class Watched(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var title: String?,
        var duration: Long,
        val states: ArrayDeque<TrackChange.State> = ArrayDeque(),
    )

    private val handler = Handler(Looper.getMainLooper())
    private val watched = mutableMapOf<MediaSession.Token, Watched>()
    val observers = Observers()
    private var lastCommand: Pair<String, Long>? = null

    fun controllers(): List<MediaController> = watched.values.map { it.controller }

    /** Follows the sessions in [active] and drops the ones no longer there. */
    fun update(
        context: Context,
        active: List<MediaController>,
    ) {
        val tokens = active.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { token ->
            watched.remove(token)?.let { gone ->
                gone.controller.unregisterCallback(gone.callback)
                ProbeLog.event("session_removed", JSONObject().put("package", gone.controller.packageName))
            }
        }
        active.filter { it.sessionToken !in watched }.forEach { controller -> follow(context, controller) }
        observers.fire()
    }

    fun stopAll() {
        watched.values.forEach { it.controller.unregisterCallback(it.callback) }
        watched.clear()
        observers.fire()
    }

    private fun follow(
        context: Context,
        controller: MediaController,
    ) {
        val resolver = context.contentResolver
        val metadata = controller.metadata
        lateinit var entry: Watched
        val callback =
            object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = metadataChanged(entry, metadata, resolver)

                override fun onPlaybackStateChanged(state: PlaybackState?) = stateChanged(entry, state)

                override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) =
                    log(entry, "queue_changed", JSONObject().put("queue", Describe.queue(queue)))

                override fun onQueueTitleChanged(title: CharSequence?) =
                    log(entry, "queue_title_changed", JSONObject().put("queueTitle", title?.toString()))

                override fun onExtrasChanged(extras: Bundle?) =
                    log(entry, "extras_changed", JSONObject().put("extras", Describe.bundle(extras)))

                override fun onSessionEvent(
                    event: String,
                    extras: Bundle?,
                ) = log(entry, "session_event", JSONObject().put("event", event).put("extras", Describe.bundle(extras)))

                override fun onSessionDestroyed() = log(entry, "session_destroyed", JSONObject())
            }
        entry =
            Watched(
                controller,
                callback,
                metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            )
        controller.playbackState?.let { entry.states.addLast(stateOf(it)) }
        watched[controller.sessionToken] = entry
        controller.registerCallback(callback, handler)
        ProbeLog.event(
            "session_added",
            JSONObject()
                .put("controller", Describe.controller(controller))
                .put("metadata", Describe.metadata(metadata, resolver))
                .put("playbackState", Describe.playbackState(controller.playbackState))
                .put("queueTitle", controller.queueTitle?.toString())
                .put("queue", Describe.queue(controller.queue)),
        )
    }

    private fun metadataChanged(
        entry: Watched,
        metadata: MediaMetadata?,
        resolver: android.content.ContentResolver,
    ) {
        val now = SystemClock.elapsedRealtime()
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val change = JSONObject().put("sameTitle", title == entry.title)
        entry.states.lastOrNull()?.let { last ->
            val position = TrackChange.positionAt(last, now, PlaybackState.STATE_PLAYING)
            change
                .put("previousTitle", entry.title)
                .put("previousDuration", entry.duration)
                .put("previousPositionAtChange", position)
                .put("previousRemainingAtChange", TrackChange.remaining(entry.duration, position))
                .put(
                    "recentStates",
                    JSONArray(
                        TrackChange.recent(entry.states.toList(), now, RECENT_STATES_WINDOW_MS).map {
                            JSONObject().put("state", Describe.stateName(it.state)).put(
                                "msBeforeChange",
                                now - it.receivedAt,
                            )
                        },
                    ),
                )
        }
        lastCommand?.takeIf { now - it.second <= RECENT_STATES_WINDOW_MS }?.let {
            change.put("probeCommand", it.first).put("probeCommandMsBefore", now - it.second)
        }
        entry.title = title
        entry.duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        log(
            entry,
            "metadata_changed",
            JSONObject().put("trackChange", change).put("metadata", Describe.metadata(metadata, resolver)),
        )
    }

    private fun stateChanged(
        entry: Watched,
        state: PlaybackState?,
    ) {
        state?.let {
            entry.states.addLast(stateOf(it))
            while (entry.states.size > STATES_KEPT) entry.states.removeFirst()
        }
        log(entry, "playback_state", JSONObject().put("playbackState", Describe.playbackState(state)))
    }

    private fun stateOf(state: PlaybackState) =
        TrackChange.State(
            state.state,
            state.position,
            state.lastPositionUpdateTime,
            state.playbackSpeed,
            SystemClock.elapsedRealtime(),
        )

    private fun log(
        entry: Watched,
        type: String,
        fields: JSONObject,
    ) {
        ProbeLog.event(type, fields.put("package", entry.controller.packageName))
        observers.fire()
    }

    /** Sends a transport command from the probe, and logs it so a following change can be attributed. */
    fun command(
        controller: MediaController,
        name: String,
    ) {
        lastCommand = name to SystemClock.elapsedRealtime()
        ProbeLog.event("probe_command", JSONObject().put("package", controller.packageName).put("command", name))
        val controls = controller.transportControls
        when (name) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
        }
    }
}
