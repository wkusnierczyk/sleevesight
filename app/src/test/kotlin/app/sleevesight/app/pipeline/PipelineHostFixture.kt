package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackChange
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the host's tests share: a host on fakes, under virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class PipelineHostFixture {
    protected val acknowledged = MutableStateFlow(true)
    protected val changes = MutableStateFlow<TrackChange?>(null)
    protected val settings = MutableStateFlow(Settings(aiProvider = ProviderId.ANTHROPIC))
    protected val keyChanges = MutableStateFlow(0)
    protected var keys = PipelineKeys(ai = Secret("ai"))
    protected var keysReady: CompletableDeferred<Unit>? = null
    protected var buildFailure: Exception? = null
    protected var keyReads = 0
    protected var nowPlayingCollected = false
    protected var builds = 0
    protected val failures = mutableListOf<Throwable>()

    /** The user's actions the host has passed on (a tap on Write taken). */
    protected var actions = 0

    protected val musicBrainz = CountingAdapter(SourceId.MUSICBRAINZ)
    protected val lastFm = CountingAdapter(SourceId.LASTFM)
    protected val provider = CountingProvider()
    protected val cache = CardCache()

    /** The next item in the queue (D13), and each resolution asked: the lookup key and the length. */
    protected val next = MutableStateFlow<NowPlaying?>(null)

    /** Whether the phone is online, as the host is told (#192). */
    protected val online = MutableStateFlow(true)
    protected val resolved = mutableListOf<Pair<String, Duration?>>()

    /** The resolver's answers, in turn; once they run out, a recording found. */
    protected val resolutions = ArrayDeque<Resolution>()

    protected val host =
        PipelineHost(
            acknowledged = acknowledged,
            changes = changes.onStart { nowPlayingCollected = true },
            latestPlaying = { changes.value?.nowPlaying },
            settings = settings,
            keyChanges = keyChanges.map { },
            readKeys = {
                keyReads++
                keysReady?.await()
                keys
            },
            onFailure = { failures += it },
            onAction = { actions++ },
            next = next,
            online = online,
            normalizer = Normalizer(JvmAccentFolder),
            parts = { keys, _ ->
                builds++
                buildFailure?.let { throw it }
                PipelineParts(
                    normalizer = Normalizer(JvmAccentFolder),
                    adapters = listOfNotNull(musicBrainz, keys.sources.lastFm?.let { lastFm }),
                    resolver =
                        TrackResolver { track, duration ->
                            resolved += track.lookupKey to duration
                            resolutions.removeFirstOrNull() ?: Resolution(Outcome.SongArticle, recordingMbid = "rec")
                        },
                    hasher = JvmHasher,
                    ai = keys.ai?.let { AiAccess(provider, it) },
                    cache = cache,
                )
            },
        )

    protected fun TestScope.started(visible: Boolean = true) {
        if (visible) host.activityStarted()
        host.start(backgroundScope)
        runCurrent()
    }

    protected fun TestScope.play(
        title: String,
        duration: Duration? = null,
        cause: ChangeCause = ChangeCause.NATURAL_END,
    ) {
        val playing = NowPlaying(title = title, artists = "Artist", duration = duration, player = Settings.SPOTIFY)
        changes.value = TrackChange(playing, cause)
        runCurrent()
    }

    protected fun TestScope.report(playing: NowPlaying) {
        changes.value = TrackChange(playing)
        runCurrent()
    }

    /** A tap on Write as the screen sends it: under the key Write is offered for, beside the track shown. */
    protected fun tap() {
        val key = host.state.value.writeKey ?: return
        host.write(key, changes.value?.nowPlaying ?: return)
    }

    // Past the debounce, and long enough for a run to land.
    protected fun TestScope.settle() = advanceTimeBy(DEBOUNCE_AND_RUN)

    protected companion object {
        val DEBOUNCE: Duration = 650.milliseconds
        val DEBOUNCE_AND_RUN: Duration = DEBOUNCE + 5.seconds
    }
}
