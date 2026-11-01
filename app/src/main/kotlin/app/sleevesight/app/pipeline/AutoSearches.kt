package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * *Search with AI*, the setting (`Settings.searchWithAi`, #773): a search that starts by itself as
 * soon as a source has nothing, whether its panel is open or not, with Home closed or the panel
 * folded, but only while Sleevesight is on screen: never a paid call while no one is looking, with
 * *Look up in the background* on and no *Saver* to stop it. A track whose sources answered while the
 * app was away is searched when it comes back on screen, if that track still plays. Not tied to
 * Home's three panels: what it searches for, [K], is the caller's, so a genre with no definition from
 * Wikipedia (#786) starts its own search through it, with its own [wanted] and [start].
 *
 * It follows the playing track ([follow]): with the setting on and the app on screen, each of
 * [wanted] (for *Dates*, *Genres* and *Lyrics*, those whose panel shows an empty state, once every
 * source has answered, not while any is still looking, and not for a panel hidden in *Appearance*)
 * is handed to [start], which asks it and says whether that is settled: false with no AI service
 * ready, so it is tried again on the next change (a key saved, a service switched on). One settled,
 * by its [subject] (for *Dates* the album too, so the same recording next from a compilation is
 * searched), is not started again on this play of the track, unless its search hands it back
 * ([release]: its gates, [open], closed while the cache was read, before its call): a failure is
 * tried again only when the track plays again. A search once stopped is never started by itself
 * again, the track coming back included ([start] declines it: `AppAiSearches`), nor is one answered
 * or kept. A search under way when the track changes runs on, and is kept (its call is paid for).
 */
class AutoSearches<K>(
    private val wanted: (TrackState, Settings) -> Set<K>,
    private val subject: (TrackState, K) -> Any = { _, wish -> wish as Any },
    private val start: (TrackState, K) -> Boolean,
) {
    // The play followed, by the track's identity as the sources name it; what settled on it, by
    // each wish's [subject] (for *Dates*, its album: the same recording from a compilation is
    // another); and the inputs last followed. Under [lock]: [follow] runs in one collector ([run]),
    // [release] wherever a search hands one back.
    private val lock = Any()
    private var playing: Any? = null
    private val started = mutableSetOf<Any>()
    private var last: Triple<TrackState?, Settings?, Boolean>? = null

    /**
     * [track] as the pipeline publishes it, under [settings] as last read, while the app is [onScreen]
     * or not: starts what it wants, on screen only.
     */
    fun follow(
        track: TrackState?,
        settings: Settings?,
        onScreen: Boolean = true,
    ) = synchronized(lock) {
        last = Triple(track, settings, onScreen)
        if (track == null || settings == null) return@synchronized
        val identity = track.normalized to track.nowPlaying.duration
        if (identity != playing) {
            playing = identity
            started.clear()
        }
        if (!settings.searchWithAi || !onScreen) return@synchronized
        for (wish in wanted(track, settings)) {
            val about = subject(track, wish)
            if (about !in started && start(track, wish)) started += about
        }
    }

    /**
     * Whether a search started by itself may still make its call (`AppAiSearches` asks just before
     * it): the setting on and the app on screen, as last followed.
     */
    fun open(): Boolean =
        synchronized(lock) {
            last?.let { (_, now, shown) -> now?.searchWithAi == true && shown } ==
                true
        }

    /**
     * [track]'s [wish], started but not asked, its gates closed before its call: as if it had not been
     * tried, so it starts once they open again, at once if they already have.
     */
    fun release(
        track: TrackState,
        wish: K,
    ) = synchronized(lock) {
        started.remove(subject(track, wish))
        last?.let { (now, settings, shown) -> follow(now, settings, shown) }
    }

    /**
     * Follows [tracks] (the pipeline's track) under [settings] while the app is [onScreen], and again
     * on each of [changes] (the pipeline's parts, so a search waiting for an AI service starts once one
     * is ready), until cancelled.
     */
    suspend fun run(
        tracks: Flow<TrackState?>,
        settings: Flow<Settings?>,
        onScreen: Flow<Boolean>,
        changes: Flow<*>,
    ) {
        combine(tracks, settings, onScreen, changes) { track, now, shown, _ -> Triple(track, now, shown) }
            .collect { (track, now, shown) -> follow(track, now, shown) }
    }
}
