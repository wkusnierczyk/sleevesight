package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.PipelineParts
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
    protected val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    protected val settings = MutableStateFlow(Settings(aiProvider = ProviderId.ANTHROPIC))
    protected val keyChanges = MutableStateFlow(0)
    protected var keys = PipelineKeys(ai = Secret("ai"))
    protected var keysReady: CompletableDeferred<Unit>? = null
    protected var buildFailure: Exception? = null
    protected var keyReads = 0
    protected var nowPlayingCollected = false
    protected var builds = 0
    protected val failures = mutableListOf<Throwable>()

    protected val musicBrainz = CountingAdapter(SourceId.MUSICBRAINZ)
    protected val lastFm = CountingAdapter(SourceId.LASTFM)
    protected val provider = CountingProvider()
    protected val cache = CardCache()

    protected val host =
        PipelineHost(
            acknowledged = acknowledged,
            nowPlaying = nowPlaying.onStart { nowPlayingCollected = true },
            settings = settings,
            keyChanges = keyChanges.map { },
            readKeys = {
                keyReads++
                keysReady?.await()
                keys
            },
            onFailure = { failures += it },
            normalizer = Normalizer(JvmAccentFolder),
            parts = { keys, _ ->
                builds++
                buildFailure?.let { throw it }
                PipelineParts(
                    normalizer = Normalizer(JvmAccentFolder),
                    adapters = listOfNotNull(musicBrainz, keys.sources.lastFm?.let { lastFm }),
                    resolver = TrackResolver { _, _ -> Resolution(Outcome.SongArticle, recordingMbid = "rec") },
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
    ) {
        nowPlaying.value = NowPlaying(title = title, artists = "Artist", duration = duration, player = Settings.SPOTIFY)
        runCurrent()
    }

    // Past the debounce, and long enough for a run to land.
    protected fun TestScope.settle() = advanceTimeBy(DEBOUNCE_AND_RUN)

    protected companion object {
        val DEBOUNCE: Duration = 650.milliseconds
        val DEBOUNCE_AND_RUN: Duration = DEBOUNCE + 5.seconds
    }
}
