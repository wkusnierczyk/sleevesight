package app.sleevesight.app.pipeline

import app.sleevesight.app.session.HttpArt
import app.sleevesight.app.session.onlyWhen
import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.Resolution
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The pipeline in the app (#51) under virtual time: nothing before the first run is acknowledged;
 * the debounce; lookups only while the app is visible unless *Look up in the background* is on
 * (D6), with the visible track catching up; *Write* on tap; the keys, and the rebuilds they take.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineHostTest {
    private val acknowledged = MutableStateFlow(true)
    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val settings = MutableStateFlow(Settings(aiProvider = ProviderId.ANTHROPIC))
    private val keyChanges = MutableStateFlow(0)
    private var keys = PipelineKeys(ai = Secret("ai"))
    private var keysReady: CompletableDeferred<Unit>? = null
    private var buildFailure: Exception? = null
    private var keyReads = 0
    private var nowPlayingCollected = false
    private var builds = 0
    private val failures = mutableListOf<Throwable>()

    private val musicBrainz = CountingAdapter(SourceId.MUSICBRAINZ)
    private val lastFm = CountingAdapter(SourceId.LASTFM)
    private val provider = CountingProvider()
    private val cache = CardCache()

    private val host =
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

    private fun TestScope.started(visible: Boolean = true) {
        if (visible) host.activityStarted()
        host.start(backgroundScope)
        runCurrent()
    }

    private fun TestScope.play(title: String) {
        nowPlaying.value = NowPlaying(title = title, artists = "Artist", player = Settings.SPOTIFY)
        runCurrent()
    }

    // Past the debounce, and long enough for a run to land.
    private fun TestScope.settle() = advanceTimeBy(DEBOUNCE_AND_RUN)

    @Test
    fun `nothing is read, built or sent before the first run is acknowledged`() =
        runTest {
            acknowledged.value = false
            started()
            play("Song")
            advanceTimeBy(10.seconds)

            assertFalse("the session layer's track is not read", nowPlayingCollected)
            assertEquals(0, keyReads)
            assertEquals(0, builds)
            assertEquals(emptyList<String>(), musicBrainz.asked)
            assertNull(host.state.value.track)

            acknowledged.value = true
            settle()
            assertEquals(listOf("artist|song"), musicBrainz.asked)
        }

    @Test
    fun `a change waits for the debounce, and only the latest of a burst runs`() =
        runTest {
            started()
            play("One")
            advanceTimeBy(300.milliseconds)
            play("Two")
            advanceTimeBy(600.milliseconds)
            assertEquals(emptyList<String>(), musicBrainz.asked)

            advanceTimeBy(100.milliseconds)
            settle()
            assertEquals(listOf("artist|two"), musicBrainz.asked)
            assertEquals(
                "Two",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
        }

    @Test
    fun `nothing is looked up while the app is not visible, and the visible track catches up`() =
        runTest {
            started()
            play("One")
            settle()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            play("Two")
            settle()
            play("Three")
            settle()
            assertEquals(listOf("artist|one"), musicBrainz.asked)

            host.activityStarted()
            runCurrent()
            settle()
            // The tracks played meanwhile are never looked up; the one shown is.
            assertEquals(listOf("artist|one", "artist|three"), musicBrainz.asked)
            assertEquals(
                "Three",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
        }

    @Test
    fun `the track last run is not run again on resume`() =
        runTest {
            started()
            play("One")
            settle()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            play("Two")
            play("One")
            host.activityStarted()
            runCurrent()
            settle()

            assertEquals(listOf("artist|one"), musicBrainz.asked)
        }

    @Test
    fun `a run begun while visible finishes when the app is hidden`() =
        runTest {
            musicBrainz.byName = 2.seconds
            started()
            play("One")
            advanceTimeBy(DEBOUNCE + 1.seconds)
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            settle()

            assertEquals(listOf("artist|one"), musicBrainz.asked)
            assertEquals(0, musicBrainz.cancelled)
            assertTrue(
                host.state.value.track
                    ?.gathered == true,
            )
        }

    @Test
    fun `with Look up in the background on, tracks run while the app is not visible`() =
        runTest {
            started(visible = false)
            play("One")
            settle()
            assertEquals(emptyList<String>(), musicBrainz.asked)

            // Turned on while hidden: the track shown catches up at once.
            settings.value = settings.value.copy(lookUpInBackground = true)
            settle()
            play("Two")
            settle()
            assertEquals(listOf("artist|one", "artist|two"), musicBrainz.asked)
        }

    @Test
    fun `Write on tap writes the current track's insight, and only then`() =
        runTest {
            started()
            play("One")
            settle()
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
            assertEquals(0, provider.requests)

            host.write()
            settle()
            assertEquals(1, provider.requests)
            val written =
                host.state.value.track
                    ?.insight as InsightState.Written
            assertEquals("It is a song [MB1].", written.grounded.text)
        }

    @Test
    fun `without an AI key nothing is written, and the view says insights are not set up`() =
        runTest {
            keys = PipelineKeys()
            started()
            play("One")
            settle()
            host.write()
            settle()

            assertFalse(host.state.value.aiReady)
            assertEquals(
                InsightState.Idle(canWrite = false),
                host.state.value.track
                    ?.insight,
            )
            assertEquals(0, provider.requests)
        }

    @Test
    fun `a keyed source on without its key needs a key, and is looked up once the key is saved`() =
        runTest {
            started()
            play("One")
            settle()
            val view = host.state.value
            assertEquals(setOf(SourceId.LASTFM, SourceId.WEB), view.needsKey)
            assertNull("no adapter, so no failed or unreachable state", view.track?.sources?.get(SourceId.LASTFM))

            // Switched off: no longer a key it needs.
            settings.value = settings.value.copy(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.LASTFM)
            runCurrent()
            assertEquals(setOf(SourceId.WEB), host.state.value.needsKey)

            // No engine on: web search is off, not waiting for a key.
            settings.value = settings.value.copy(webEngines = emptySet())
            runCurrent()
            assertEquals(emptySet<SourceId>(), host.state.value.needsKey)

            settings.value =
                settings.value.copy(
                    enabledSources = Settings.DEFAULT_SOURCES,
                    webEngines = Settings.DEFAULT_WEB_ENGINES,
                )
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()
            assertEquals(setOf(SourceId.WEB), host.state.value.needsKey)
            // The new pipeline runs the track shown again, with Last.fm.
            assertEquals(listOf("artist|one"), lastFm.asked)
            val landed =
                host.state.value.track
                    ?.sources
                    ?.get(SourceId.LASTFM) as SourceState.Done
            assertEquals(Outcome.SongArticle, landed.result.outcome)
        }

    @Test
    fun `the pipeline is rebuilt for a key, an engine or the AI service, not for other settings`() =
        runTest {
            started()
            play("One")
            settle()
            assertEquals(1, builds)

            settings.value = settings.value.copy(writeInsights = false, lookUpInBackground = true)
            settle()
            assertEquals(1, builds)

            settings.value = settings.value.copy(webEngines = emptySet())
            settle()
            assertEquals(2, builds)

            settings.value = settings.value.copy(aiProvider = null)
            settle()
            assertEquals(3, builds)

            keyChanges.value++
            settle()
            assertEquals(4, builds)
            // Each rebuild ran the track again; the same pipeline never does.
            assertEquals(4, musicBrainz.asked.size)
        }

    @Test
    fun `the first action carries over to a rebuilt pipeline, whose replay writes nothing by itself`() =
        runTest {
            started()
            play("One")
            settle()
            host.write()
            settle()
            assertEquals(1, provider.requests)

            // New evidence (a Last.fm key): the replay of the track shown is not a track change.
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()
            assertEquals(1, provider.requests)
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )

            // The next track is a change, written under the default after the first action.
            play("Two")
            settle()
            assertEquals(2, provider.requests)
        }

    @Test
    fun `a rebuild that changes nothing in the card's key serves the card kept, with no call`() =
        runTest {
            started()
            play("One")
            settle()
            host.write()
            settle()
            keyChanges.value++
            settle()

            assertEquals(1, provider.requests)
            assertTrue(
                (
                    host.state.value.track
                        ?.insight as InsightState.Written
                ).cached,
            )
        }

    @Test
    fun `a model call under way when a key changes finishes, and is the only one paid`() =
        runTest {
            provider.streaming = 10.seconds
            started()
            play("One")
            settle()
            host.write()
            advanceTimeBy(2.seconds)
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Writing,
            )

            keyChanges.value++
            advanceTimeBy(30.seconds)
            assertEquals(1, provider.requests)
            assertEquals(1, provider.completed)
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
            assertEquals(2, builds)
        }

    @Test
    fun `Write is withdrawn as a rebuild begins, until the new pipeline offers it`() =
        runTest {
            started()
            play("One")
            settle()
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )

            keysReady = CompletableDeferred()
            keyChanges.value++
            runCurrent()
            assertEquals(
                InsightState.Idle(canWrite = false),
                host.state.value.track
                    ?.insight,
            )
            host.write()
            runCurrent()

            keysReady!!.complete(Unit)
            settle()
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
            assertEquals(0, provider.requests)
        }

    @Test
    fun `Write is withdrawn while the track shown has moved on, and a tap then writes nothing`() =
        runTest {
            started()
            play("One")
            settle()
            host.activityStopped(changingConfigurations = false)
            play("Two")
            assertEquals(
                InsightState.Idle(canWrite = false),
                host.state.value.track
                    ?.insight,
            )

            host.activityStarted()
            host.write()
            settle()
            assertEquals(0, provider.requests)
            assertEquals(
                "Two",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
        }

    @Test
    fun `a pipeline that cannot be built is reported, and the next rebuild builds it`() =
        runTest {
            buildFailure = IllegalStateException("a resource missing")
            started()
            play("One")
            settle()
            assertEquals(listOf("IllegalStateException"), failures.map { it::class.simpleName })
            assertEquals(emptyList<String>(), musicBrainz.asked)

            buildFailure = null
            keyChanges.value++
            settle()
            assertEquals(listOf("artist|one"), musicBrainz.asked)
        }

    // D6 enforced: no request starts while lookups are not allowed.

    @Test
    fun `a change still in its debounce when the app is hidden waits, and runs on return`() =
        runTest {
            started()
            play("One")
            advanceTimeBy(100.milliseconds)
            host.activityStopped(changingConfigurations = false)
            settle()
            assertEquals(emptyList<String>(), musicBrainz.asked)

            host.activityStarted()
            settle()
            assertEquals(listOf("artist|one"), musicBrainz.asked)
        }

    @Test
    fun `lookups under way finish when the app is hidden, but no model call starts until it may`() =
        runTest {
            started()
            play("Zero")
            settle()
            host.write()
            settle()
            assertEquals(1, provider.requests)

            // Under way when hidden, with the first action made and the default auto-write.
            musicBrainz.byName = 2.seconds
            play("One")
            advanceTimeBy(DEBOUNCE + 1.seconds)
            host.activityStopped(changingConfigurations = false)
            settle()
            assertTrue(
                host.state.value.track
                    ?.gathered == true,
            )
            assertEquals(0, musicBrainz.cancelled)
            assertEquals(1, provider.requests)

            // Turned on in the background (no new opening, so the first action stands): it writes.
            settings.value = settings.value.copy(lookUpInBackground = true)
            settle()
            assertEquals(2, provider.requests)
        }

    @Test
    fun `after the app opens again nothing is written until a tap, the track waiting included`() =
        runTest {
            started()
            play("Zero")
            settle()
            host.write()
            settle()
            musicBrainz.byName = 2.seconds
            play("One")
            advanceTimeBy(DEBOUNCE + 1.seconds)
            host.activityStopped(changingConfigurations = false)
            play("Two")
            settle()

            host.activityStarted()
            settle()
            assertEquals(
                "Two",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
            assertEquals(1, provider.requests)

            host.write()
            settle()
            assertEquals(2, provider.requests)
        }

    @Test
    fun `a rotation is not the app opening, so the first action stands`() =
        runTest {
            started()
            play("One")
            settle()
            host.write()
            settle()
            // A rotation: a stop for a configuration change, and the new activity started.
            host.activityStopped(changingConfigurations = true)
            host.activityStarted()
            play("Two")
            settle()
            assertEquals(2, provider.requests)
        }

    @Test
    fun `remote art waits while lookups are not allowed`() =
        runTest {
            started()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            var fetched = 0
            val art =
                HttpArt {
                    fetched++
                    null
                }.onlyWhen { host.lookupsAllowed }
            val load = backgroundScope.launch { art.fetch("https://cdn/1") }
            settle()
            assertEquals(0, fetched)

            host.activityStarted()
            runCurrent()
            assertEquals(1, fetched)
            assertTrue(load.isCompleted)
        }

    @Test
    fun `a run that fails is reported, ends its call for the coordinator, and the next track runs`() =
        runTest {
            provider.failure = IllegalStateException("a bug")
            started()
            play("One")
            settle()
            host.write()
            settle()
            assertEquals(listOf("IllegalStateException"), failures.map { it::class.simpleName })

            // The failed call reported its end, so a rebuild is not left waiting for it.
            provider.failure = null
            play("Two")
            settle()
            keyChanges.value++
            settle()
            assertEquals(listOf("artist|one", "artist|two", "artist|two"), musicBrainz.asked)
        }

    // End to end, through the real `Pipeline`: the sequences of the third review round.

    @Test
    fun `consecutive rebuilds while the replay gathers write the track once`() =
        runTest {
            started()
            play("Alpha")
            settle()
            host.write()
            settle()
            musicBrainz.byName = 2.seconds
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            advanceTimeBy(500.milliseconds)
            keyChanges.value++
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
        }

    @Test
    fun `a rebuild in a newer track's debounce while the old track streams waits for the call, then goes on`() =
        runTest {
            provider.streaming = 10.seconds
            started()
            play("Alpha")
            settle()
            host.write()
            advanceTimeBy(2.seconds)
            play("Bravo")
            advanceTimeBy(100.milliseconds)
            keyChanges.value++
            advanceTimeBy(60.seconds)
            play("Charlie")
            advanceTimeBy(60.seconds)

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(listOf("artist|alpha", "artist|bravo", "artist|charlie"), musicBrainz.asked)
            assertEquals(
                "Charlie",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
        }

    @Test
    fun `a stale run made current again while hidden starts no model call`() =
        runTest {
            started()
            play("Zero")
            settle()
            host.write()
            settle()
            musicBrainz.byName = 2.seconds
            play("Alpha")
            advanceTimeBy(DEBOUNCE + 1.8.seconds)
            play("Bravo")
            advanceTimeBy(300.milliseconds)
            host.activityStopped(changingConfigurations = false)
            play("Alpha")
            settle()

            assertEquals(0, provider.requestsFor("Alpha"))
            assertEquals(0, provider.requestsFor("Bravo"))
        }

    private companion object {
        val DEBOUNCE: Duration = 650.milliseconds
        val DEBOUNCE_AND_RUN: Duration = DEBOUNCE + 5.seconds
    }
}
