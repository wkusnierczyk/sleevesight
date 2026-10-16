package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.InsightState
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Interleavings of track reports, the app's visibility, taps, rebuilds and the background setting,
 * each run through the real `Pipeline` with a provider that counts its requests per track: which
 * run, which track and which pipeline is current when a gated step or a decision to write
 * happens (#51; D5, D6). Each case states the model requests per track, the lookups, and the
 * final view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineHostSequencesTest {
    private sealed interface Step {
        data class Play(
            val title: String,
        ) : Step

        data class Wait(
            val time: Duration,
        ) : Step

        /** Past the debounce, and long enough for a run to land. */
        data object Settle : Step

        data object Hide : Step

        data object Show : Step

        /** A stop for a configuration change, and the new activity's start. */
        data object Rotate : Step

        data object Tap : Step

        data object KeyChange : Step

        /** The keys' read waits until [ReleaseKeys]: a rebuild under way. */
        data object HoldKeys : Step

        data object ReleaseKeys : Step

        data class Background(
            val on: Boolean,
        ) : Step

        /** The web engines switched off: a rebuild from the settings. */
        data object EnginesOff : Step

        data class SlowLookups(
            val byName: Duration,
        ) : Step

        data class Streaming(
            val time: Duration,
        ) : Step

        data class ExpectWrite(
            val offered: Boolean,
        ) : Step

        data class ExpectRequests(
            val title: String,
            val count: Int,
        ) : Step
    }

    private enum class Insight { WRITE_OFFERED, NO_WRITE, WRITTEN }

    private class Case(
        val name: String,
        val steps: List<Step>,
        val requests: Map<String, Int>,
        val asked: List<String>,
        val finalTitle: String,
        val finalInsight: Insight,
    )

    /** A first action made: Zero played and written on a tap. */
    private val acted = listOf(Step.Play("Zero"), Step.Settle, Step.Tap, Step.Settle)

    private val cases =
        listOf(
            Case(
                "returning during a newer track's debounce does not resume the older one",
                acted +
                    listOf(
                        Step.Hide,
                        Step.Play("Alpha"),
                        Step.Settle,
                        Step.Play("Bravo"),
                        Step.Wait(100.milliseconds),
                        Step.Show,
                        Step.Settle,
                    ),
                requests = mapOf("Zero" to 1),
                asked = listOf("zero", "bravo"),
                finalTitle = "Bravo",
                finalInsight = Insight.WRITE_OFFERED,
            ),
            Case(
                "background lookups turned on during a newer track's debounce write only the newer track",
                acted +
                    listOf(
                        Step.Hide,
                        Step.Play("Alpha"),
                        Step.Settle,
                        Step.Play("Bravo"),
                        Step.Wait(100.milliseconds),
                        Step.Background(on = true),
                        Step.Settle,
                    ),
                requests = mapOf("Zero" to 1, "Bravo" to 1),
                asked = listOf("zero", "bravo"),
                finalTitle = "Bravo",
                finalInsight = Insight.WRITTEN,
            ),
            Case(
                "a rebuild while a track gathers still writes it, once",
                acted +
                    listOf(
                        Step.SlowLookups(2.seconds),
                        Step.Play("Alpha"),
                        Step.Wait(DEBOUNCE + 500.milliseconds),
                        Step.KeyChange,
                        Step.Settle,
                    ),
                requests = mapOf("Zero" to 1, "Alpha" to 1),
                asked = listOf("zero", "alpha", "alpha"),
                finalTitle = "Alpha",
                finalInsight = Insight.WRITTEN,
            ),
            Case(
                "a rebuild from the settings offers no Write until the new pipeline does",
                listOf(
                    Step.Play("Alpha"),
                    Step.Settle,
                    Step.ExpectWrite(offered = true),
                    Step.HoldKeys,
                    Step.EnginesOff,
                    Step.ExpectWrite(offered = false),
                    Step.Tap,
                    Step.ReleaseKeys,
                    Step.Settle,
                ),
                requests = emptyMap(),
                asked = listOf("alpha", "alpha"),
                finalTitle = "Alpha",
                finalInsight = Insight.WRITE_OFFERED,
            ),
            Case(
                "an opening during a rebuild clears the first action",
                acted +
                    listOf(
                        Step.HoldKeys,
                        Step.KeyChange,
                        Step.Hide,
                        Step.Show,
                        Step.ReleaseKeys,
                        Step.Settle,
                        Step.Play("Bravo"),
                        Step.Settle,
                    ),
                requests = mapOf("Zero" to 1),
                asked = listOf("zero", "zero", "bravo"),
                finalTitle = "Bravo",
                finalInsight = Insight.WRITE_OFFERED,
            ),
            Case(
                "a change still in its debounce when the app is hidden runs on return",
                listOf(Step.Play("Alpha"), Step.Wait(100.milliseconds), Step.Hide, Step.Settle, Step.Show, Step.Settle),
                requests = emptyMap(),
                asked = listOf("alpha"),
                finalTitle = "Alpha",
                finalInsight = Insight.WRITE_OFFERED,
            ),
            Case(
                "lookups under way finish when hidden, and the write waits for background lookups",
                acted +
                    listOf(
                        Step.SlowLookups(2.seconds),
                        Step.Play("Alpha"),
                        Step.Wait(DEBOUNCE + 1.seconds),
                        Step.Hide,
                        Step.Settle,
                        Step.ExpectRequests("Alpha", 0),
                        Step.Background(on = true),
                        Step.Settle,
                    ),
                requests = mapOf("Zero" to 1, "Alpha" to 1),
                asked = listOf("zero", "alpha"),
                finalTitle = "Alpha",
                finalInsight = Insight.WRITTEN,
            ),
            Case(
                "a model call under way when a key changes is the only one paid",
                listOf(
                    Step.Streaming(10.seconds),
                    Step.Play("Alpha"),
                    Step.Settle,
                    Step.Tap,
                    Step.Wait(2.seconds),
                    Step.KeyChange,
                    Step.Wait(30.seconds),
                ),
                requests = mapOf("Alpha" to 1),
                asked = listOf("alpha", "alpha"),
                finalTitle = "Alpha",
                finalInsight = Insight.WRITTEN,
            ),
            Case(
                "a rotation keeps the first action",
                acted + listOf(Step.Rotate, Step.Play("Bravo"), Step.Settle),
                requests = mapOf("Zero" to 1, "Bravo" to 1),
                asked = listOf("zero", "bravo"),
                finalTitle = "Bravo",
                finalInsight = Insight.WRITTEN,
            ),
            Case(
                "Write, hide, another track, show: nothing is written until a tap",
                acted +
                    listOf(
                        Step.Hide,
                        Step.Play("Bravo"),
                        Step.Settle,
                        Step.Show,
                        Step.Settle,
                        Step.ExpectRequests("Bravo", 0),
                        Step.Tap,
                        Step.Settle,
                    ),
                requests = mapOf("Zero" to 1, "Bravo" to 1),
                asked = listOf("zero", "bravo"),
                finalTitle = "Bravo",
                finalInsight = Insight.WRITTEN,
            ),
        )

    /** One host, with its fakes, for one case. */
    private class Harness {
        val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        val settings = MutableStateFlow(Settings(aiProvider = ProviderId.ANTHROPIC))
        val keyChanges = MutableStateFlow(0)
        var keysReady: CompletableDeferred<Unit>? = null
        val musicBrainz = CountingAdapter(SourceId.MUSICBRAINZ)
        val provider = CountingProvider()
        private val cache = CardCache()

        val host =
            PipelineHost(
                acknowledged = MutableStateFlow(true),
                nowPlaying = nowPlaying,
                settings = settings,
                keyChanges = keyChanges.map { },
                readKeys = {
                    keysReady?.await()
                    PipelineKeys(ai = Secret("ai"))
                },
                parts = { keys, _ ->
                    PipelineParts(
                        normalizer = Normalizer(JvmAccentFolder),
                        adapters = listOf(musicBrainz),
                        resolver = TrackResolver { _, _ -> Resolution(Outcome.SongArticle, recordingMbid = "rec") },
                        hasher = JvmHasher,
                        ai = keys.ai?.let { AiAccess(provider, it) },
                        cache = cache,
                    )
                },
            )
    }

    private fun TestScope.apply(
        harness: Harness,
        step: Step,
        case: String,
    ) {
        when (step) {
            is Step.ExpectWrite -> {
                val insight =
                    harness.host.state.value.track
                        ?.insight
                assertEquals("$case: Write offered", step.offered, insight == InsightState.Idle(canWrite = true))
            }
            is Step.ExpectRequests ->
                assertEquals("$case: requests for ${step.title}", step.count, harness.provider.requestsFor(step.title))
            else -> act(harness, step)
        }
        runCurrent()
    }

    private fun TestScope.act(
        harness: Harness,
        step: Step,
    ) {
        val host = harness.host
        when (step) {
            is Step.Play -> harness.nowPlaying.value = NowPlaying(step.title, "Artist", player = Settings.SPOTIFY)
            is Step.Wait -> advanceTimeBy(step.time)
            Step.Settle -> advanceTimeBy(DEBOUNCE + 5.seconds)
            Step.Hide -> host.activityStopped(changingConfigurations = false)
            Step.Show -> host.activityStarted()
            Step.Rotate -> {
                host.activityStopped(changingConfigurations = true)
                host.activityStarted()
            }
            Step.Tap -> host.write()
            Step.KeyChange -> harness.keyChanges.value++
            Step.HoldKeys -> harness.keysReady = CompletableDeferred()
            Step.ReleaseKeys -> harness.keysReady?.complete(Unit)
            else -> change(harness, step)
        }
    }

    private fun change(
        harness: Harness,
        step: Step,
    ) {
        when (step) {
            is Step.Background -> harness.settings.value = harness.settings.value.copy(lookUpInBackground = step.on)
            Step.EnginesOff -> harness.settings.value = harness.settings.value.copy(webEngines = emptySet())
            is Step.SlowLookups -> harness.musicBrainz.byName = step.byName
            is Step.Streaming -> harness.provider.streaming = step.time
            else -> error("Not a step that changes the setup: $step")
        }
    }

    @Test
    fun eachInterleavingPaysOnlyForTheCurrentTrackAndShowsIt() {
        for (case in cases) {
            runTest {
                val harness = Harness()
                harness.host.activityStarted()
                harness.host.start(backgroundScope)
                runCurrent()
                for (step in case.steps) apply(harness, step, case.name)

                val counted = listOf("Zero", "Alpha", "Bravo").associateWith(harness.provider::requestsFor)
                assertEquals(case.name, case.requests, counted.filterValues { it > 0 })
                assertEquals(case.name, case.asked.map { "artist|$it" }, harness.musicBrainz.asked)
                val view = harness.host.state.value
                assertEquals(case.name, case.finalTitle, view.track?.nowPlaying?.title)
                val insight =
                    when (view.track?.insight) {
                        InsightState.Idle(canWrite = true) -> Insight.WRITE_OFFERED
                        is InsightState.Written -> Insight.WRITTEN
                        else -> Insight.NO_WRITE
                    }
                assertEquals(case.name, case.finalInsight, insight)
            }
        }
    }

    private companion object {
        val DEBOUNCE: Duration = 650.milliseconds
    }
}
