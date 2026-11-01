package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * *Search with AI*, the setting (`Settings.searchWithAi`, #773): a search that starts by itself as
 * soon as a source has nothing, whether its panel is open or not, with Home closed too. Not tied to
 * Home's three panels: what it searches for, [K], is the caller's, so a genre with no definition
 * from Wikipedia (#786) starts its own search through it, with its own [wanted] and [start].
 *
 * It follows the playing track ([follow]): with the setting on, each of [wanted] (for *Dates*,
 * *Genres* and *Lyrics*, those whose panel shows an empty state, once every source has answered,
 * not while any is still looking, and not for a panel hidden in *Appearance*) is handed to [start],
 * which asks it and says whether it counts: false with no AI service ready, so it is tried again on
 * the next change (a key saved, a service switched on). One that counted is not started again for
 * the track: not after *Stop*, nor after a failure, until another track plays and this one plays
 * again; [start] itself never asks again for a search answered or kept. A search under way when the
 * track changes runs on, and is kept (its call is paid for).
 */
class AutoSearches<K>(
    private val wanted: (TrackState, Settings) -> Set<K>,
    private val start: (TrackState, K) -> Boolean,
) {
    // The track followed, by its identity as the sources name it, and what started for it. Touched
    // only by [follow], which one collector calls ([run]).
    private var playing: Any? = null
    private val started = mutableSetOf<K>()

    /** [track] as the pipeline publishes it, under [settings] as last read: starts what it wants. */
    fun follow(
        track: TrackState?,
        settings: Settings?,
    ) {
        if (track == null || settings == null) return
        val identity = track.normalized to track.nowPlaying.duration
        if (identity != playing) {
            playing = identity
            started.clear()
        }
        if (!settings.searchWithAi) return
        for (wish in wanted(track, settings)) {
            if (wish !in started && start(track, wish)) started += wish
        }
    }

    /**
     * Follows [tracks] (the pipeline's track) under [settings], and again on each of [changes] (the
     * pipeline's parts, so a search waiting for an AI service starts once one is ready), until
     * cancelled.
     */
    suspend fun run(
        tracks: Flow<TrackState?>,
        settings: Flow<Settings?>,
        changes: Flow<*>,
    ) {
        combine(tracks, settings, changes) { track, now, _ -> track to now }.collect { (track, now) ->
            follow(track, now)
        }
    }
}
