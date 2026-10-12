package io.github.wkusnierczyk.sleevesight.spike

import android.content.ContentResolver
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
 *
 * Touched only on the main thread: the active-sessions listener and the controller callbacks
 * both run on the main looper.
 */
object SessionWatcher {
    private const val RECENT_STATES_WINDOW_MS = 3_000L
    private const val STATES_KEPT = 50

    /** What identifies a track: the media id when the player sends one, else title, artist and length. */
    private data class Track(
        val mediaId: String?,
        val title: String?,
        val artist: String?,
        val duration: Long,
    ) {
        /** No track at all: metadata cleared between tracks, for example. */
        val isEmpty get() = mediaId == null && title == null && artist == null && duration <= 0

        /**
         * The same track, perhaps described more fully: the media id decides when both have one;
         * otherwise title, artist and length, each compared only when both sides know it (players
         * may send a field later, the length as 0 first).
         */
        fun isSameAs(other: Track) =
            if (mediaId != null && other.mediaId != null) {
                mediaId == other.mediaId
            } else {
                agree(title, other.title) &&
                    agree(artist, other.artist) &&
                    (duration <= 0 || other.duration <= 0 || duration == other.duration)
            }

        /** This track as [newer] describes it, keeping what [newer] leaves out. */
        fun completedBy(newer: Track) =
            Track(
                newer.mediaId ?: mediaId,
                newer.title ?: title,
                newer.artist ?: artist,
                if (newer.duration > 0) newer.duration else duration,
            )

        private fun agree(
            a: String?,
            b: String?,
        ) = a == null || b == null || a == b

        fun json(): JSONObject =
            JSONObject()
                .put("mediaId", mediaId)
                .put("title", title)
                .put("artist", artist)
                .put("duration", duration)

        companion object {
            fun of(metadata: MediaMetadata?) =
                Track(
                    metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                    metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
                )
        }
    }

    private class Watched(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var track: Track,
        val states: ArrayDeque<TrackChange.State> = ArrayDeque(),
        var lastCommand: Pair<String, Long>? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val watched = mutableMapOf<MediaSession.Token, Watched>()
    val observers = Observers()

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
        active.filter { it.sessionToken !in watched }.forEach { controller ->
            follow(context.contentResolver, controller)
        }
        observers.fire()
    }

    fun stopAll() {
        watched.values.forEach { it.controller.unregisterCallback(it.callback) }
        watched.clear()
        observers.fire()
    }

    private fun follow(
        resolver: ContentResolver,
        controller: MediaController,
    ) {
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
        val metadata = controller.metadata
        entry = Watched(controller, callback, Track.of(metadata))
        controller.playbackState?.let { entry.states.addLast(stateOf(it)) }
        watched[controller.sessionToken] = entry
        controller.registerCallback(callback, handler)
        Describe.artUris(metadata).forEach { ArtProbe.check(it, controller.packageName, resolver) }
        ProbeLog.event(
            "session_added",
            JSONObject()
                .put("controller", Describe.controller(controller))
                .put("metadata", Describe.metadata(metadata))
                .put("playbackState", Describe.playbackState(controller.playbackState))
                .put("queueTitle", controller.queueTitle?.toString())
                .put("queue", Describe.queue(controller.queue)),
        )
    }

    private fun metadataChanged(
        entry: Watched,
        metadata: MediaMetadata?,
        resolver: ContentResolver,
    ) {
        Describe.artUris(metadata).forEach { ArtProbe.check(it, entry.controller.packageName, resolver) }
        val track = Track.of(metadata)
        if (track.isEmpty) {
            // Cleared between tracks: the change is measured when the next track arrives.
            log(entry, "metadata_cleared", JSONObject().put("metadata", Describe.metadata(metadata)))
            return
        }
        if (entry.track.isEmpty) {
            // The session started without metadata: the first track is set, not changed to.
            entry.track = track
            log(
                entry,
                "metadata_set",
                JSONObject().put("track", track.json()).put("metadata", Describe.metadata(metadata)),
            )
            return
        }
        if (track.isSameAs(entry.track)) {
            // The same track updated (art or length arriving later, for example): not a track change.
            entry.track = entry.track.completedBy(track)
            log(
                entry,
                "metadata_updated",
                JSONObject().put("track", entry.track.json()).put("metadata", Describe.metadata(metadata)),
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        val change = JSONObject().put("previous", entry.track.json()).put("next", track.json())
        val estimate =
            TrackChange.estimate(
                entry.states.toList(),
                now,
                PlaybackState.STATE_PLAYING,
                RECENT_STATES_WINDOW_MS,
            )
        estimate?.let {
            change
                .put("previousPositionAtChange", it.position)
                .put("previousRemainingAtChange", TrackChange.remaining(entry.track.duration, it.position))
                .put("changeMsBeforeMetadata", now - it.changeAt)
                .put("newStateBeforeMetadata", it.newTrackFrom != null)
        }
        change.put(
            "recentStates",
            JSONArray(
                TrackChange.recent(entry.states.toList(), now, RECENT_STATES_WINDOW_MS).map { stateJson(it, now) },
            ),
        )
        entry.lastCommand?.takeIf { now - it.second <= RECENT_STATES_WINDOW_MS }?.let {
            change.put("probeCommand", it.first).put("probeCommandMsBefore", now - it.second)
        }
        // States from the reset on belong to the new track; the rest to the previous one.
        val kept = estimate?.newTrackFrom?.let { entry.states.drop(it) }.orEmpty()
        entry.states.clear()
        entry.states.addAll(kept)
        entry.track = track
        log(
            entry,
            "track_changed",
            JSONObject().put("trackChange", change).put("metadata", Describe.metadata(metadata)),
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

    /** Everything needed to recompute the estimate offline. */
    private fun stateJson(
        state: TrackChange.State,
        now: Long,
    ) = JSONObject()
        .put("state", Describe.stateName(state.state))
        .put("position", state.position)
        .put("updateTime", state.updateTime)
        .put("speed", state.speed.toDouble())
        .put("receivedAt", state.receivedAt)
        .put("msBeforeMetadata", now - state.receivedAt)

    private fun log(
        entry: Watched,
        type: String,
        fields: JSONObject,
    ) {
        ProbeLog.event(type, fields.put("package", entry.controller.packageName))
        observers.fire()
    }

    /** Sends a transport command from the probe, and logs it so the next change of that session can be attributed. */
    fun command(
        controller: MediaController,
        name: String,
    ) {
        watched[controller.sessionToken]?.lastCommand = name to SystemClock.elapsedRealtime()
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
