package app.sleevesight.app.session

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * **Players to follow** come from [followed], package names; the settings (#50) supply them, and
 * until then the default is `Settings`' (Spotify). Sessions of other players are dropped, and each
 * newly seen one is logged as ignored; no callback is registered on them. A player with several
 * sessions is followed through the first, the one the system ranks highest.
 *
 * **The player shown** is the first followed session that is playing (or buffering, or seeking),
 * else the first followed session: the system lists the most recently active first.
 *
 * **Lifecycle.** [SleevesightListenerService] calls [start] when the system binds it and [stop] when
 * it unbinds. On start the state is rebuilt from the active sessions, so after process death the
 * rebound service finds the playing track again without waiting for a change. All callbacks and
 * [scope] run on the main thread; [scope]'s dispatcher must be the main one, or an immediate one in
 * tests.
 *
 * Transport controls and handoff to the player are Phase 2.
 */
class SessionRepository(
    private val sessions: ActiveSessions,
    private val acknowledged: Flow<Boolean>,
    private val artLoader: ArtLoader,
    private val scope: CoroutineScope,
    private val followed: Flow<Set<String>> = flowOf(DEFAULT_FOLLOWED),
) {
    private val nowPlayingState = MutableStateFlow<NowPlaying?>(null)
    private val playbackState = MutableStateFlow(PlaybackInfo.NONE)
    private val queueState = MutableStateFlow<SessionQueue?>(null)
    private val artState = MutableStateFlow<Bitmap?>(null)
    private val seenState = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The track shown, for the UI at once and for the pipeline, which debounces it; null when no
     * followed player has a session, or its session names no track. Equal reports, which Spotify
     * sends constantly, do not emit.
     */
    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingState.asStateFlow()

    /** The shown player's playback state; [PlaybackInfo.NONE] when there is no followed session. */
    val playback: StateFlow<PlaybackInfo> = playbackState.asStateFlow()

    /** The shown player's queue, when it publishes one. */
    val queue: StateFlow<SessionQueue?> = queueState.asStateFlow()

    /**
     * The shown track's art (see [SessionMapping.artSources]). Cleared on a track change; kept
     * through an update of the same track until the new art has loaded.
     */
    val art: StateFlow<Bitmap?> = artState.asStateFlow()

    /** The players with an active session since the process started, followed or not: for *Players to follow*. */
    val seenPlayers: StateFlow<Set<String>> = seenState.asStateFlow()

    private var gate: Job? = null
    private var listening = false
    private var followedPlayers: Set<String> = emptySet()
    private var ignored: Set<String> = emptySet()
    private val attached = LinkedHashMap<Any, Attached>()
    private var artSources: List<ArtSource> = emptyList()
    private var artJob: Job? = null

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
        attached.values.forEach { it.session.unregister(it.callback) }
        attached.clear()
        ignored = emptySet()
        publish()
    }

    private fun onSessionsChanged(active: List<PlayerSession>) {
        seenState.update { it + active.map(PlayerSession::player) }
        val ignoredNow = active.map(PlayerSession::player).filterNot { it in followedPlayers }.toSet()
        (ignoredNow - ignored).forEach { Log.i(TAG, "Ignored: the media session of $it, a player not followed") }
        ignored = ignoredNow

        val wanted = active.filter { it.player in followedPlayers }.distinctBy(PlayerSession::player)
        val keys = wanted.map(PlayerSession::key).toSet()
        attached.values.filter { it.session.key !in keys }.forEach { it.session.unregister(it.callback) }
        val next = wanted.associateBy(PlayerSession::key) { attached[it.key] ?: Attached(it) }
        attached.clear()
        attached.putAll(next)
        publish()
    }

    private fun publish() {
        val shown = attached.values.firstOrNull { SessionMapping.isActive(it.state) } ?: attached.values.firstOrNull()
        val previous = nowPlayingState.value
        val track = shown?.let { SessionMapping.nowPlaying(it.session.player, it.metadata, it.state) }
        nowPlayingState.value = track
        playbackState.value =
            shown?.let { SessionMapping.playbackInfo(it.session.player, it.state) } ?: PlaybackInfo.NONE
        queueState.value = shown?.let { SessionMapping.queue(it.queue, it.queueTitle) }
        updateArt(previous, track, if (track == null) emptyList() else SessionMapping.artSources(shown.metadata))
    }

    private fun updateArt(
        previous: NowPlaying?,
        track: NowPlaying?,
        sources: List<ArtSource>,
    ) {
        if (sources == artSources) return
        artSources = sources
        artJob?.cancel()
        artJob = null
        val sameTrack = previous != null && track != null && TrackIdentity.same(previous, track)
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
                        artState.value = bitmap
                    }
            }
        }
    }

    /** A followed player's session, with the latest of what its callback reported. */
    private inner class Attached(
        val session: PlayerSession,
    ) {
        var metadata: MediaMetadata? = session.metadata
        var state: PlaybackState? = session.playbackState
        var queue: List<MediaSession.QueueItem>? = session.queue
        var queueTitle: CharSequence? = session.queueTitle

        val callback =
            object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    ifAttached { this@Attached.metadata = metadata }
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    ifAttached { this@Attached.state = state }
                }

                override fun onQueueChanged(queue: List<MediaSession.QueueItem>?) {
                    ifAttached { this@Attached.queue = queue }
                }

                override fun onQueueTitleChanged(title: CharSequence?) {
                    ifAttached { queueTitle = title }
                }

                override fun onSessionDestroyed() {
                    ifAttached {
                        session.unregister(this)
                        attached.remove(session.key)
                    }
                }
            }

        init {
            session.register(callback)
        }

        /** Applies [change] and republishes, unless this session was dropped meanwhile (a late callback). */
        private inline fun ifAttached(change: () -> Unit) {
            if (attached[session.key] !== this) return
            change()
            publish()
        }
    }

    companion object {
        /** The players followed until the settings supply them: `Settings`' default, Spotify. */
        val DEFAULT_FOLLOWED: Set<String> = Settings().followedPlayers.filterValues { it }.keys

        private const val TAG = "Sleevesight"
    }
}
