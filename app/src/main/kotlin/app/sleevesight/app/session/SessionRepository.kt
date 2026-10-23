package app.sleevesight.app.session

import android.app.PendingIntent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.TrackChange
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the followed players are playing (`design/architecture.md`, *Session layer*): listens for
 * the active media sessions, registers a callback on the session of each followed player, and
 * publishes the one shown as [nowPlaying], [playback], [queue] and [art].
 *
 * **Nothing before the first run.** Until [acknowledged] is true (the user has read the first-run
 * screen and continued), the repository reads no session and registers nothing, whatever
 * notification access allows: access can be granted in Settings before the app is first opened,
 * which binds the listener service at once. It attaches when the acknowledgement arrives.
 *
 * **Players to follow** come from [followed], package names: in the app, the settings'
 * `SettingsStore.followedPlayers`, which `AppGraph` hands it (#126); with none given, `Settings`'
 * default (Spotify). Sessions of other players are dropped, and each newly seen one is logged as
 * ignored. A player with several sessions is followed through the first, the one the system ranks
 * highest.
 *
 * **Phone calls are never read** (#435): a session whose package is a call's (`Settings.isCall`:
 * telecom, the dialers) or that marks itself a call ([PlayerSession.marksCall]) is dropped whatever
 * [followed] says, before anything of it is read, and is never one of the [seenPlayers]. A
 * followed session that turns into a call shows no track while it is one.
 *
 * **Players seen** ([seenPlayers]), for *Players to follow*, are those whose session has reported
 * music, a title and an artist ([SessionMapping.reportsMusic]): a followed player's from its own
 * callback; another's from its session as listed, and until it has, from a callback that reads only
 * its metadata, for that test, and is removed once the player has passed it or its session ends.
 * Nothing else of an unfollowed player's session is read, and nothing of its metadata is kept.
 *
 * **The player shown** is the first followed session that is playing (or buffering, or seeking),
 * else the first followed session: the system lists the most recently active first.
 *
 * **Settling.** [nowPlaying] is mapped from a session's metadata and playback state once they
 * have settled for [SETTLE]: at most [MAX_SETTLE] after the first of them, or after the first that
 * changed the title or the queue item, unless the title or the item changes again in the last
 * [SETTLE] of that; and in any case within [LONGEST_BURST] of the first (see `Attached`). So the
 * two halves of a track change arrive as one. A player that becomes the one shown while its
 * reports are settling is shown once they have. [playback] and [queue] are published at once:
 * skip detection counts the `PLAYING` states before a change and reads the position they report
 * (D5), which coalescing would lose; [lastChange] keeps when each change began, and its times
 * never go back.
 *
 * **Skip detection.** Each change is judged once, as it is published, by [classify] (the app's
 * [SkipDetector]; without one, every change is a natural end): a skip in the player or a natural
 * end, which [trackChanges] carries to the pipeline with the track.
 *
 * **Lifecycle.** [SleevesightListenerService] calls [start] when the system binds it and [stop] when
 * it unbinds; `MainActivity` also calls [start] on resume while access is granted, since the
 * system does not always bind the listener again after a force stop or an update, and the
 * session list needs the listener enabled, not bound. On start the state is rebuilt from the
 * active sessions, so after process death the playing track is found again without waiting for
 * a change. All callbacks and [scope] run on the main thread; [scope]'s dispatcher is the main
 * one, or a test dispatcher whose virtual time runs the [SETTLE] window.
 *
 * Transport controls and handoff to the player are Phase 2; the player's own screen opens from the
 * home screen ([playerActivity]).
 */
@Suppress("LongParameterList", "TooManyFunctions") // What it follows and does; the clock and the judge are seams.
class SessionRepository(
    private val sessions: ActiveSessions,
    private val acknowledged: Flow<Boolean>,
    private val artLoader: ArtLoader,
    private val scope: CoroutineScope,
    private val followed: Flow<Set<String>> = flowOf(DEFAULT_FOLLOWED),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val classify: (SessionChange) -> SessionChange = { it },
) {
    private val nowPlayingState = MutableStateFlow<NowPlaying?>(null)
    private val playbackState = MutableStateFlow(PlaybackInfo.NONE)
    private val queueState = MutableStateFlow<SessionQueue?>(null)
    private val artState = MutableStateFlow<Bitmap?>(null)
    private val changeState = MutableStateFlow<SessionChange?>(null)
    private val trackChangeState = MutableStateFlow<TrackChange?>(null)

    // The cause the track shown carries, and that track as last published: set where a change is
    // judged, dropped at any break ([CarriedCause.next]). Touched on the main thread alone.
    private var carried: CarriedCause? = null
    private val playingState = MutableStateFlow<NowPlaying?>(null)
    private val seenState = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The track shown, for the UI at once and for the pipeline, which debounces it; null when no
     * followed player has a session, or its session names no track. Equal reports, which Spotify
     * sends constantly, do not emit.
     */
    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingState.asStateFlow()

    /**
     * The last change of [nowPlaying] from one track to another, with when it began and the
     * playback state before it, judged by skip detection as it is published ([classify], D5): see
     * [SessionChange].
     */
    val lastChange: StateFlow<SessionChange?> = changeState.asStateFlow()

    /**
     * [nowPlaying] with why its track came to play, for the pipeline (D5): the cause of the change
     * judged into it ([classify]; [SessionChange.cause]), kept only across the reports published
     * after it that each play on the one before ([TrackIdentity.playsOn]: a new length, album or
     * artists); any break (another track, no track, a track after none, the layer stopped) drops
     * it, and a track with no judged change into it is [ChangeCause.NATURAL_END], the verdict when
     * detection cannot tell ([CarriedCause]). Published with [nowPlaying] and [lastChange], so a
     * report never pairs with another track's cause.
     */
    val trackChanges: StateFlow<TrackChange?> = trackChangeState.asStateFlow()

    /**
     * The track shown while it plays, as of the settle that showed it or last updated it: its
     * metadata and the state it settled with, a consistent pair. [playback] is published at once,
     * so a new track's `PLAYING` state, which may come before its metadata (YouTube Music,
     * Castbox), would pair with the track before; this waits for the settle. Null once a pause, a
     * stop or no track has settled; unchanged while reports are still settling. For the session
     * history (#49).
     */
    val playing: StateFlow<NowPlaying?> = playingState.asStateFlow()

    /** The shown player's playback state; [PlaybackInfo.NONE] when there is no followed session. */
    val playback: StateFlow<PlaybackInfo> = playbackState.asStateFlow()

    /** The shown player's queue, when it publishes one. */
    val queue: StateFlow<SessionQueue?> = queueState.asStateFlow()

    /**
     * The shown track's art (see [SessionMapping.artSources]). Cleared on a track change, and
     * loaded again even when the new track names the same URI; kept through an update of the
     * same track until the new art has loaded, and kept when it fails to load. A load for a track
     * no longer shown never lands.
     */
    val art: StateFlow<Bitmap?> = artState.asStateFlow()

    /**
     * The players whose session has reported music since the process started, followed or not;
     * never a call: for *Players to follow* (#435).
     */
    val seenPlayers: StateFlow<Set<String>> = seenState.asStateFlow()

    /**
     * The own screen of the player whose track is shown (its session activity), for the home
     * screen's player row; null when there is no such player or it names none. Read when asked,
     * on the main thread: it asks the player's session, so it is not published with every state.
     */
    fun playerActivity(): PendingIntent? = lastShown?.session?.sessionActivity

    private var gate: Job? = null
    private var listening = false
    private var followedPlayers: Set<String> = emptySet()
    private var ignored: Set<String> = emptySet()
    private val attached = LinkedHashMap<Any, Attached>()
    private val probes = HashMap<Any, Probe>()
    private var artSources: List<ArtSource> = emptyList()
    private var artJob: Job? = null
    private var lastShown: Attached? = null

    /** Starts following, once [acknowledged] is true; calling it again while started does nothing. */
    fun start() {
        if (gate?.isActive == true) return
        gate =
            scope.launch {
                combine(acknowledged, followed) { ack, players -> ack to players }
                    .distinctUntilChanged()
                    .collect { (ack, players) -> if (ack) attach(players) else detach() }
            }
    }

    /** Stops following and clears the state: the listener service lost its binding. */
    fun stop() {
        gate?.cancel()
        gate = null
        detach()
    }

    private fun attach(players: Set<String>) {
        followedPlayers = players
        if (!listening) {
            sessions.listen(::onSessionsChanged)
            listening = true
        }
        onSessionsChanged(sessions.current())
    }

    private fun detach() {
        if (listening) {
            sessions.stop()
            listening = false
        }
        attached.values.forEach { it.release() }
        attached.clear()
        probes.values.forEach { it.release() }
        probes.clear()
        ignored = emptySet()
        publish()
        // Not replayed to a collector after the next start; `publish` has cleared `lastShown`.
        changeState.value = null
        carried = null
    }

    private fun onSessionsChanged(active: List<PlayerSession>) {
        // A list dispatched before the listener was removed may arrive after it.
        if (!listening) return
        // A call is dropped before anything of it is read, whatever the settings say.
        val readable = active.filterNot(::isCall)
        val ignoredNow =
            active
                .filterNot { it in readable && it.player in followedPlayers }
                .map(PlayerSession::player)
                .toSet()
        (ignoredNow - ignored).forEach {
            Log.i(TAG, "Ignored: the media session of $it, a player not followed or a call")
        }
        ignored = ignoredNow
        watchForMusic(readable)

        val wanted = readable.filter { it.player in followedPlayers }.distinctBy(PlayerSession::player)
        val keys = wanted.map(PlayerSession::key).toSet()
        attached.values.filter { it.session.key !in keys }.forEach { it.release() }
        val next = wanted.associateBy(PlayerSession::key) { attached[it.key] ?: Attached(it) }
        attached.clear()
        attached.putAll(next)
        publish()
    }

    /**
     * Notes which players not followed report music ([seenPlayers]): from their sessions as listed,
     * and, for those that have not yet, from a [Probe] on each session until one does. A followed
     * player's own callback notes it ([Attached]).
     */
    private fun watchForMusic(readable: List<PlayerSession>) {
        val others = readable.filter { it.player !in followedPlayers }
        others.filter { SessionMapping.reportsMusic(it.metadata) }.forEach { markSeen(it.player) }
        val unseen = others.filter { it.player !in seenState.value }
        val keys = unseen.map(PlayerSession::key).toSet()
        probes.values.filter { it.session.key !in keys }.forEach { it.release() }
        probes.keys.retainAll(keys)
        for (session in unseen) {
            if (session.key in probes) continue
            probes[session.key] = Probe(session)
            // Registered before this read, so music reported in between reaches one or the other.
            if (SessionMapping.reportsMusic(session.metadata)) markSeen(session.player)
        }
    }

    /** Adds [player] to [seenPlayers], once, and removes the probes on its sessions. */
    private fun markSeen(player: String) {
        if (player in seenState.value) return
        seenState.update { it + player }
        val done = probes.filterValues { it.session.player == player }
        done.values.forEach { it.release() }
        probes.keys.removeAll(done.keys)
    }

    /**
     * A session not followed that has not reported music yet: a callback that reads its metadata
     * only to tell whether it is music ([SessionMapping.reportsMusic]), keeps nothing of it, and is
     * removed once its player is seen ([markSeen]), its session leaves the list, or it ends.
     */
    private inner class Probe(
        val session: PlayerSession,
    ) {
        private val callback =
            object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (probes[session.key] !== this@Probe) return
                    if (SessionMapping.reportsMusic(metadata)) markSeen(session.player)
                }

                override fun onSessionDestroyed() {
                    if (probes[session.key] !== this@Probe) return
                    release()
                    probes.remove(session.key)
                }
            }

        init {
            session.register(callback)
        }

        fun release() {
            session.unregister(callback)
        }
    }

    /** Publishes the shown player's state; [settled] is the session whose settle triggered it, if one did. */
    private fun publish(settled: Attached? = null) {
        val shown = attached.values.firstOrNull { SessionMapping.isActive(it.state) } ?: attached.values.firstOrNull()
        playbackState.value =
            shown?.let { SessionMapping.playbackInfo(it.session.player, it.state) } ?: PlaybackInfo.NONE
        queueState.value = shown?.let { SessionMapping.queue(it.queue, it.queueTitle) }
        // A player that becomes the one shown because it started a new track has not settled
        // yet: its track is still the one before. The track shown stays until it settles.
        if (shown != null && shown !== lastShown && shown.pending) return
        val switched = shown !== lastShown
        lastShown = shown
        val previous = nowPlayingState.value
        var judgedCause: ChangeCause? = null
        val track = shown?.track
        nowPlayingState.value = track
        if (previous != null && track != null && !TrackIdentity.same(previous, track)) {
            val change = changeTo(previous, track, shown.change.takeIf { settled === shown }, switched)
            val last = changeState.value
            // Whatever order the sessions settle in, the times never go back: a change that began
            // before the last one published (a hidden player's, shown once the other paused) is
            // timed now, as a switch. Judged once, as published.
            val judged =
                classify(
                    if (last != null && change.changedAt < last.changedAt) {
                        SessionChange(track, clock(), null, from = previous)
                    } else {
                        change
                    },
                )
            changeState.value = judged
            judgedCause = judged.cause
        }
        publishCause(previous, track, judgedCause)
        // After the change, so a collector that sees a new track playing finds its change.
        publishPlaying(shown)
        updateArt(previous, track, if (track == null) emptyList() else SessionMapping.artSources(shown.trackMetadata))
    }

    /** Publishes [trackChanges]: [track], published after [previous], with the cause it carries. */
    private fun publishCause(
        previous: NowPlaying?,
        track: NowPlaying?,
        judged: ChangeCause?,
    ) {
        carried = CarriedCause.next(carried, previous, track, judged)
        trackChangeState.value = track?.let { TrackChange(it, carried?.cause ?: ChangeCause.NATURAL_END) }
    }

    /** Publishes [playing] from [shown]'s settled track and state, unless its reports are still settling. */
    private fun publishPlaying(shown: Attached?) {
        if (shown?.pending == true) return
        playingState.value = shown?.track?.takeIf { shown.state?.state == PlaybackState.STATE_PLAYING }
    }

    /**
     * The record of a change from [previous] to [track]: the session's own ([own]), made by the
     * settle that triggered this publish; on a switch to another player, only its timing, since
     * that player's playback states say nothing of the track left behind. A switch to a player
     * settled earlier (the one shown paused) began now.
     */
    private fun changeTo(
        previous: NowPlaying,
        track: NowPlaying,
        own: SessionChange?,
        switched: Boolean,
    ): SessionChange {
        val mine = own?.takeIf { it.track == track }
        return when {
            mine == null -> SessionChange(track, clock(), null, from = previous)
            switched -> mine.copy(before = null, from = previous, states = emptyList())
            else -> mine.copy(from = previous)
        }
    }

    private fun updateArt(
        previous: NowPlaying?,
        track: NowPlaying?,
        sources: List<ArtSource>,
    ) {
        val sameTrack = previous != null && track != null && TrackIdentity.same(previous, track)
        // The same art for the same track: Spotify re-sends it with every update. Another track
        // reloads even from the same URI, which a player may reuse for whatever is playing.
        if (sameTrack && sources == artSources) return
        artSources = sources
        artJob?.cancel()
        artJob = null
        val first = sources.firstOrNull()
        when {
            first == null -> artState.value = null
            first is ArtSource.Image -> artState.value = first.bitmap
            else -> {
                if (!sameTrack) artState.value = null
                artJob =
                    scope.launch {
                        val bitmap = artLoader.load(sources)
                        ensureActive()
                        // A failed load for the same track keeps the art already shown.
                        if (bitmap != null || !sameTrack) artState.value = bitmap
                    }
            }
        }
    }

    /**
     * A followed player's session: the latest of what its callback reported, and the track last
     * settled from it. Metadata and playback states are coalesced: each restarts a [SETTLE]
     * window, and when it passes, one track is mapped from the latest pair. A player that sends
     * the new track's state just before its metadata (YouTube Music, Castbox) so publishes the
     * new track once, with its own queue item, never the old title with the new item. The window
     * is capped at [MAX_SETTLE], but the cap never falls within [SETTLE] of a report that changed
     * the title or the queue item, which may be the first half of such a pair, until
     * [LONGEST_BURST] has passed since the burst's first report.
     */
    private inner class Attached(
        val session: PlayerSession,
    ) {
        val callback =
            object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    ifAttached {
                        if (SessionMapping.reportsMusic(metadata) && !session.marksCall) markSeen(session.player)
                        val title = SessionMapping.title(metadata)
                        val previousTitle = SessionMapping.title(this@Attached.metadata)
                        beginReport(newTitle = title != null && title != previousTitle)
                        this@Attached.metadata = metadata
                        settleLater()
                    }
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    ifAttached {
                        val item = SessionMapping.activeItem(state)
                        beginReport(
                            newItem = item != null && item != stateItem,
                            movesBack = SessionMapping.movedBack(this@Attached.state, state),
                        )
                        if (item != null) stateItem = item
                        this@Attached.state = state
                        remember(SessionMapping.playbackInfo(session.player, state))
                        settleLater()
                    }
                }

                override fun onQueueChanged(queue: List<MediaSession.QueueItem>?) {
                    ifAttached { this@Attached.queue = queue }
                }

                override fun onQueueTitleChanged(title: CharSequence?) {
                    ifAttached { queueTitle = title }
                }

                override fun onSessionDestroyed() {
                    ifAttached {
                        release()
                        attached.remove(session.key)
                    }
                }
            }

        init {
            // Registered before the first reads: a change in between then reaches the callback, or
            // the reads, rather than being lost until the next one.
            session.register(callback)
        }

        var metadata: MediaMetadata? = session.metadata
        var state: PlaybackState? = session.playbackState
        var queue: List<MediaSession.QueueItem>? = session.queue
        var queueTitle: CharSequence? = session.queueTitle

        /** The track last settled, and the metadata it was mapped from (for its art). */
        var track: NowPlaying? = null
            private set
        var trackMetadata: MediaMetadata? = null
            private set

        /** The change the last settle made, if it made one: for skip detection (D5). */
        var change: SessionChange? = null
            private set

        /** Whether reports have arrived that are not settled yet. */
        var pending = false
            private set

        /** The last queue item the state named, and the title it was named for. */
        private var knownItem: Long? = null
        private var knownItemTitle: String? = null

        /** An item the state still names after the title moved on: the previous track's. */
        private var staleItem: Long? = null

        /** The last queue item a playback state named; a state with no item (−1) leaves it. */
        private var stateItem: Long? = SessionMapping.activeItem(state)

        /**
         * When the reports now settling began, and the playback state just before them: from the
         * first report of the burst, or from the first that changed the title or the queue item
         * ([changeAt]), since the reports before it moved only the position.
         */
        private var burstStart = 0L
        private var beforeBurst: PlaybackInfo? = null
        private var statesBeforeBurst: List<StateReport> = emptyList()

        /**
         * The playback states reported in the last [STATES_BEFORE_CHANGE], at most
         * [MAX_STATES_BEFORE_CHANGE] of them, oldest first: the pattern of states before a change,
         * recorded with it (D15), as Spotify's clusters before a natural end.
         */
        private val recentStates = ArrayDeque<StateReport>()

        /** When the burst's first report came: [LONGEST_BURST] counts from it, whatever else moves. */
        private var burstFirst = 0L

        /** The last report of this burst that changed the title or the queue item; null if none has. */
        private var changeAt: Long? = null

        /**
         * The last state that fell back to the start of a track (see [SessionMapping.movedBack])
         * naming no new item, and the state before it: a new track's first state may name no new
         * item (Castbox), so the change began there if its metadata follows within [SETTLE]. Used
         * once at most.
         */
        private var movedBackAt: Long? = null
        private var beforeMovedBack: PlaybackInfo? = null
        private var statesBeforeMovedBack: List<StateReport> = emptyList()

        private var settling: Job? = null

        init {
            // The first track is settled at once: after process death the playing track is shown
            // without waiting.
            settle()
            if (SessionMapping.reportsMusic(metadata)) markSeen(session.player)
        }

        fun release() {
            settling?.cancel()
            session.unregister(callback)
        }

        /**
         * Notes a report before it is applied: the start of a burst, whether the report names a
         * [newTitle] or a [newItem] (the other half of the pair may follow it), and whether it
         * [movesBack] the position to the start of a track.
         */
        private fun beginReport(
            newTitle: Boolean = false,
            newItem: Boolean = false,
            movesBack: Boolean = false,
        ) {
            val changesTrack = newTitle || newItem
            val now = clock()
            val before = SessionMapping.playbackInfo(session.player, state)
            if (!pending) {
                pending = true
                burstFirst = now
                changeAt = null
                burstStart = now
                beforeBurst = before
                statesBeforeBurst = statesBefore(now)
            }
            if (changesTrack && changeAt == null) {
                // The change began here: at a new item, which marks it; or, at a new title, at a
                // state just before that fell back to the start naming no new item.
                val back = movedBackAt?.takeIf { newTitle && now - it <= SETTLE.inWholeMilliseconds }
                burstStart = back ?: now
                beforeBurst = if (back != null) beforeMovedBack else before
                statesBeforeBurst = if (back != null) statesBeforeMovedBack else statesBefore(now)
                movedBackAt = null
            }
            if (changesTrack) changeAt = now
            if (movesBack && !newItem) {
                movedBackAt = now
                beforeMovedBack = before
                statesBeforeMovedBack = statesBefore(now)
            }
        }

        /** Keeps [state], reported now, among the [recentStates]. */
        private fun remember(state: PlaybackInfo) {
            val now = clock()
            recentStates.addLast(StateReport(now, state))
            while (recentStates.size > MAX_STATES_BEFORE_CHANGE ||
                now - recentStates.first().at > STATES_BEFORE_CHANGE.inWholeMilliseconds
            ) {
                recentStates.removeFirst()
            }
        }

        /** The states reported in the [STATES_BEFORE_CHANGE] before [now], before the report now arriving. */
        private fun statesBefore(now: Long): List<StateReport> =
            recentStates.filter { now - it.at <= STATES_BEFORE_CHANGE.inWholeMilliseconds }

        /**
         * Settles [SETTLE] after the last report, restarted by each one; however often the player
         * reports, within [MAX_SETTLE] of the burst's start ([burstStart]), but never sooner than
         * [SETTLE] after a report that changed the title or the item, so the longest wait does not
         * cut a new track's state from its metadata; and in any case within [LONGEST_BURST] of the
         * burst's first report, so a player that keeps changing its title still settles.
         */
        private fun settleLater() {
            val now = clock()
            val deadline = burstStart + MAX_SETTLE.inWholeMilliseconds
            val floor = changeAt?.let { maxOf(deadline, it + SETTLE.inWholeMilliseconds) } ?: deadline
            val latest = minOf(floor, burstFirst + LONGEST_BURST.inWholeMilliseconds)
            val at = minOf(now + SETTLE.inWholeMilliseconds, latest)
            settling?.cancel()
            settling = scope.launch { delay((at - now).milliseconds).also { settleNow() } }
        }

        private fun settleNow() {
            settling?.cancel()
            settle()
            publish(this)
        }

        /**
         * Maps the latest metadata and state. A state with no item (−1, as YouTube Music reports
         * while stopped in the background) keeps the last item the state named, while the title
         * is unchanged.
         */
        private fun settle() {
            val previous = track
            // A followed session that has turned into a call (a VoIP app) names no track while it is one.
            val now =
                if (session.marksCall) null else SessionMapping.nowPlaying(session.player, metadata, item(previous))
            track = now
            trackMetadata = metadata
            val changed = previous != null && now != null && !TrackIdentity.same(previous, now)
            change =
                if (pending && changed) {
                    SessionChange(checkNotNull(now), burstStart, beforeBurst, states = statesBeforeBurst)
                } else {
                    null
                }
            pending = false
        }

        /** The queue item to map the latest pair with, after the [previous] track settled. */
        private fun item(previous: NowPlaying?): Long? {
            val title = SessionMapping.title(metadata)
            val item = SessionMapping.activeItem(state)
            // New metadata while the state still names the previous track's item: a player that
            // sends its metadata well before its state, or a stream that keeps one item. That item
            // is not this track's until the state names another; identity then reads the title.
            val previousItem = previous?.queueItemId?.takeIf { previous.title != title }
            // A state with no item (−1) leaves it: the item named after it may be the stale one again.
            if (item != null) staleItem = item.takeIf { it == previousItem || it == staleItem }
            if (item != null && item != staleItem) {
                knownItem = item
                knownItemTitle = title
            }
            return when {
                item == null -> knownItem.takeIf { title != null && title == knownItemTitle }
                item == staleItem -> null
                else -> item
            }
        }

        /** Applies [change] and republishes, unless this session was dropped meanwhile (a late callback). */
        private inline fun ifAttached(change: () -> Unit) {
            if (attached[session.key] !== this) return
            change()
            publish()
        }
    }

    companion object {
        /**
         * How long a session's metadata and playback state are left to settle before a track is
         * mapped from them: longer than the gap between a new track's first state and its
         * metadata (YouTube Music and Castbox send them just apart), short beside the pipeline's
         * 650 ms debounce. Not a setting: it only aligns two halves of one report.
         */
        val SETTLE: Duration = 300.milliseconds

        /**
         * The longest a burst of reports waits to settle, however often the player reports (a
         * scrubbed seek bar, a player that reports its position several times a second), counted
         * from its first report or from the first that changed the title or the queue item. With
         * the pipeline's 650 ms debounce after it, a lookup starts at most about 1.7 s after a
         * change, unless the title or the item changes again in the last [SETTLE] of that (*next*
         * pressed again and again): each such report waits for [SETTLE], up to [LONGEST_BURST].
         */
        val MAX_SETTLE: Duration = 1.seconds

        /**
         * The longest any burst waits to settle, counted from its first report, which nothing
         * moves: a player that changes its title every 250 ms still shows a track at least this
         * often, and a lookup starts at most about 3.7 s after the burst began.
         */
        val LONGEST_BURST: Duration = MAX_SETTLE * 3

        /**
         * How far back the playback states before a change are kept with it ([SessionChange.states]):
         * past Spotify's early cluster, about 3 s before a natural end (`design/design.md`, *Track
         * changes*), which skip detection may learn from (D15) besides the late cluster D5 counts.
         * Not a setting: it only sizes what is recorded.
         */
        val STATES_BEFORE_CHANGE: Duration = 5.seconds

        /** The most states kept before a change, the latest: two of Spotify's clusters of about six, with room. */
        const val MAX_STATES_BEFORE_CHANGE: Int = 32

        /** The players followed until the settings supply them: `Settings`' default, Spotify. */
        val DEFAULT_FOLLOWED: Set<String> = Settings().followedPlayers.filterValues { it }.keys

        private const val TAG = "Sleevesight"

        /** Whether [session] is a call: by its package (`Settings.isCall`) or by its own mark. */
        private fun isCall(session: PlayerSession): Boolean = Settings.isCall(session.player) || session.marksCall
    }
}
