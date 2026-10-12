package io.github.wkusnierczyk.sleevesight.spike

import android.media.session.PlaybackState
import android.os.SystemClock
import org.json.JSONObject

/** A playback state as the probe keeps it, stamped with the time it arrived. */
fun PlaybackState.received() =
    TrackChange.State(state, position, lastPositionUpdateTime, playbackSpeed, SystemClock.elapsedRealtime())

/** Everything needed to recompute the track-change estimate offline. */
fun TrackChange.State.json(now: Long): JSONObject =
    JSONObject()
        .put("state", Describe.stateName(state))
        .put("position", position)
        .put("updateTime", updateTime)
        .put("speed", speed.toDouble())
        .put("receivedAt", receivedAt)
        .put("msBeforeMetadata", now - receivedAt)
