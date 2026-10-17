package app.sleevesight.app.pipeline

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AutoWrite
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.RunStep
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.synthesis.GroundedText
import org.junit.Assert.assertEquals
import java.util.PriorityQueue
import kotlin.time.Duration.Companion.seconds

// The simulated world `PipelineCoordinatorTest` runs its sequences in, and the steps of a sequence.

internal const val DEBOUNCE_MS = 650L

/** The host, the pipelines and the runs, as the coordinator's effects set them going. */
internal class World {
    val coordinator = PipelineCoordinator()
    private val normalizer = Normalizer(JvmAccentFolder)
    private var now = 0L
    private var order = 0L

    private class Scheduled(
        val at: Long,
        val order: Long,
        val run: RunId?,
        val event: CoordinatorEvent,
        val call: Long? = null,
    )

    private val queue = PriorityQueue<Scheduled>(compareBy<Scheduled>({ it.at }, { it.order }))
    private val titles = HashMap<RunId, String>()
    private val cancelled = HashSet<RunId>()
    private val calling = HashSet<RunId>()
    private val held = mutableListOf<CoordinatorEvent>()

    // Each model call under way, by run (numbered across the world), and those stopped, whose
    // scheduled events are dropped.
    private val callOf = HashMap<RunId, Long>()
    private val stoppedCalls = HashSet<Long>()
    private var callCount = 0L

    val calls = linkedMapOf<String, Int>()
    val lookups = mutableListOf<String>()

    /** The tracks whose model call was cancelled while under way: paid for, and lost. */
    val cut = mutableListOf<String>()

    /** The tracks whose model call the user stopped (Stop, #163). */
    val stopped = mutableListOf<String>()

    /** Whether the model calls fail (an error from the AI service): no card, and the run asks to retry. */
    var failing = false

    var lookupMs = 0L
    var streamMs = 0L

    /** The causes the runs' settings write on (`AutoWrite.causes`): *On every track change* unless set. */
    var writesOn: Set<ChangeCause> = mode(WriteAutomatically.ON_EVERY_TRACK_CHANGE)

    /** What the coordinator's effects have asked of the host other than runs: the actions taken. */
    var actions = 0
    var holdKeys = false
    var buildFails = false

    fun key(
        title: String,
        seconds: Long? = null,
    ): RunKey = RunKey.of(normalizer, nowPlaying(title, seconds))

    fun key(nowPlaying: NowPlaying): RunKey = RunKey.of(normalizer, nowPlaying)

    fun nowPlaying(
        title: String,
        seconds: Long? = null,
        artists: String = "Artist",
        mediaId: String? = null,
    ) = NowPlaying(
        title = title,
        artists = artists,
        duration = seconds?.seconds,
        player = "com.spotify.music",
        mediaId = mediaId,
    )

    // A report with no artists finds nothing: the run decides without a call.
    private val withoutEvidence = HashSet<RunId>()

    /**
     * With [cache] on, the world keeps what the store would: the keys looked up, and the cards
     * written under a key and the AI service (or evidence) in use, [service]; a rebuild that
     * changes the card's key raises [service].
     */
    var cache = false
    var service = 0

    /** The cards kept, by key and service: each card's text, or null for a reply kept as `NO_EVIDENCE`. */
    val cards = HashMap<Pair<RunKey, Int>, String?>()
    val cardsShown = mutableListOf<String>()
    private val retrieved = HashSet<RunKey>()
    private val keys = HashMap<RunId, RunKey>()
    private val pendingCards = HashMap<RunId, Pair<Pair<RunKey, Int>, String>>()

    fun send(event: CoordinatorEvent) = coordinator.on(event).forEach(::perform)

    private fun schedule(
        delayMs: Long,
        event: CoordinatorEvent,
        run: RunId? = null,
        call: Long? = null,
    ) {
        queue += Scheduled(now + delayMs, order++, run, event, call)
    }

    private fun perform(effect: CoordinatorEffect) {
        when (effect) {
            is CoordinatorEffect.StartTimer -> schedule(DEBOUNCE_MS, CoordinatorEvent.Settled(effect.report))
            is CoordinatorEffect.Build -> {
                val finished = CoordinatorEvent.RebuildFinished(effect.build, built = !buildFails)
                if (holdKeys) held += finished else schedule(0, finished)
            }
            is CoordinatorEffect.Swap -> Unit
            is CoordinatorEffect.StartRun -> start(effect)
            is CoordinatorEffect.CancelRun -> cancel(effect.run)
            is CoordinatorEffect.Grant -> granted(effect.run, effect.step)
            is CoordinatorEffect.Decide -> if (effect.write) granted(effect.run, RunStep.MODEL_CALL)
            is CoordinatorEffect.StopCall -> stop(effect.call.run)
            CoordinatorEffect.Acted -> actions++
        }
    }

    private fun start(effect: CoordinatorEffect.StartRun) {
        titles[effect.run] = effect.nowPlaying.title
        keys[effect.run] = key(effect.nowPlaying)
        if (effect.nowPlaying.artists.isEmpty()) withoutEvidence += effect.run
        // With the cache, a key looked up before needs no lookups.
        if (cache && keys.getValue(effect.run) in retrieved) {
            gathered(effect.run, delayMs = 0)
        } else {
            schedule(0, CoordinatorEvent.StepRequested(effect.run, RunStep.LOOKUPS), effect.run)
        }
    }

    // The call under way ends at once as stopped: no card, its end reported, and the run asks
    // whether to call again, as `InsightWriter` does.
    private fun stop(run: RunId) {
        if (!calling.remove(run)) return
        stoppedCalls += callOf.getValue(run)
        pendingCards.remove(run)
        stopped += titles.getValue(run)
        schedule(0, CoordinatorEvent.StepEnded(run, RunStep.MODEL_CALL), run)
        schedule(0, CoordinatorEvent.RetryAsked(run), run)
    }

    private fun cancel(run: RunId) {
        cancelled += run
        // The run's job ends, and says so from its `finally`; a call cut off reports its end too.
        schedule(0, CoordinatorEvent.RunEnded(run))
        if (calling.remove(run)) {
            cut += titles.getValue(run)
            schedule(0, CoordinatorEvent.StepEnded(run, RunStep.MODEL_CALL))
        }
    }

    private fun granted(
        run: RunId,
        step: RunStep,
    ) {
        val title = titles.getValue(run)
        when (step) {
            RunStep.LOOKUPS -> {
                lookups += title
                if (cache) retrieved += keys.getValue(run)
                schedule(lookupMs, CoordinatorEvent.StepEnded(run, step), run)
                gathered(run, lookupMs)
            }
            RunStep.LABELS -> schedule(0, CoordinatorEvent.StepEnded(run, step), run)
            RunStep.MODEL_CALL -> {
                calls[title] = (calls[title] ?: 0) + 1
                calling += run
                val call = ++callCount
                callOf[run] = call
                if (failing) {
                    // A call that fails writes no card; the run then asks whether to call again.
                    schedule(streamMs, CoordinatorEvent.StepEnded(run, step), run, call)
                    schedule(streamMs, CoordinatorEvent.RetryAsked(run), run, call)
                    return
                }
                // The card is reported before the call's end; neither, for a call cut off.
                schedule(streamMs, CoordinatorEvent.CardWritten(run, card("$title #${calls[title]}")), run, call)
                schedule(streamMs, CoordinatorEvent.StepEnded(run, step), run, call)
                // Kept once it ends normally; a call cut off is not (`deliver` drops its end).
                if (cache) pendingCards[run] = (keys.getValue(run) to service) to "$title #${calls[title]}"
            }
        }
    }

    // The evidence in: a card kept under this key and service is shown; else the question.
    private fun gathered(
        run: RunId,
        delayMs: Long,
    ) {
        val insight =
            when {
                cache && (keys.getValue(run) to service) in cards -> {
                    cardsShown += titles.getValue(run)
                    CoordinatorEvent.CardShown(run, cards[keys.getValue(run) to service]?.let(::card))
                }
                run in withoutEvidence -> CoordinatorEvent.DecidedWithoutCall(run)
                else -> CoordinatorEvent.InsightAsked(run, writesOn)
            }
        schedule(delayMs, insight, run)
        schedule(delayMs, CoordinatorEvent.StepRequested(run, RunStep.LABELS), run)
    }

    /** Lets [ms] pass, delivering what falls due; what a cancelled run would have sent is dropped. */
    fun advance(ms: Long) {
        val until = now + ms
        while (queue.peek()?.let { it.at <= until } == true) {
            val next = queue.poll()
            now = next.at
            if (next.call == null || next.call !in stoppedCalls) deliver(next.run, next.event)
        }
        now = until
    }

    private fun deliver(
        run: RunId?,
        event: CoordinatorEvent,
    ) {
        if (run != null && run in cancelled) return
        if (run != null && event is CoordinatorEvent.StepEnded && event.step == RunStep.MODEL_CALL) {
            calling -= run
            pendingCards.remove(run)?.let { (key, text) -> cards[key] = text }
        }
        send(event)
    }

    fun releaseKeys() {
        holdKeys = false
        held.forEach { schedule(0, it) }
        held.clear()
    }

    fun offersWriteFor(title: String) = coordinator.state.offersWrite(key(title))

    fun card(text: String) = Card(InsightState.Written(GroundedText(text, emptyList()), cached = false), null)

    /** The text of the card the ledger holds. */
    fun cardText(): String? =
        coordinator.state.ledger
            ?.card
            ?.insight
            ?.grounded
            ?.text
}

/** The causes *Write insights automatically* writes on in [mode], with *Write insights* on. */
internal fun mode(mode: WriteAutomatically): Set<ChangeCause> = AutoWrite.causes(Settings(writeAutomatically = mode))

// The steps of a sequence.
internal fun World.play(
    title: String,
    cause: ChangeCause = ChangeCause.NATURAL_END,
    seconds: Long? = null,
) = report(nowPlaying(title, seconds), cause)

internal fun World.report(
    playing: NowPlaying,
    cause: ChangeCause = ChangeCause.NATURAL_END,
) {
    send(CoordinatorEvent.Reported(key(playing), playing, cause))
    advance(0)
}

internal fun World.settle() = advance(DEBOUNCE_MS + 5_000)

internal fun World.hide() = send(CoordinatorEvent.ActivityStopped(changingConfigurations = false))

internal fun World.show() {
    send(CoordinatorEvent.ActivityStarted)
    advance(0)
}

/** A tap on Write as the screen sends it: under the key Write is offered for (when none is, any key). */
internal fun World.tap() = tap(coordinator.state.writeKey ?: key("No run offers Write"))

/** A tap on Write carrying [key], as a screen that showed that run sends it. */
internal fun World.tap(key: RunKey) {
    send(CoordinatorEvent.WriteTapped(key))
    advance(0)
}

/** A tap on Stop as the screen sends it: for the call Stop is offered for (when none is, a call of no run). */
internal fun World.stopTap() = stopTap(coordinator.state.stopCall ?: CallId(RunId(0), 1))

/** A tap on Stop carrying [call], as a screen that showed that call sends it. */
internal fun World.stopTap(call: CallId) {
    send(CoordinatorEvent.StopTapped(call))
    advance(0)
}

internal fun World.keyChange() {
    send(CoordinatorEvent.RebuildRequested)
    advance(0)
}

internal fun World.background(on: Boolean) {
    send(CoordinatorEvent.BackgroundChanged(on))
    advance(0)
}

/** A first action made: Zero played and written on a tap. */
internal fun World.acted() {
    play("Zero")
    settle()
    tap()
    settle()
}

internal fun started(): World =
    World().apply {
        send(CoordinatorEvent.Acknowledged)
        send(CoordinatorEvent.ActivityStarted)
        send(CoordinatorEvent.BackgroundChanged(false))
        send(CoordinatorEvent.RebuildRequested)
        advance(0)
    }

/**
 * A sequence, with the model calls per track, the tracks whose lookups were granted, and the
 * track Write is offered for at the end.
 */
internal class Case(
    val name: String,
    val calls: Map<String, Int>,
    val lookups: List<String>,
    val offered: String? = null,
    val script: World.() -> Unit,
)

/** Runs each of [cases] in a world of its own, and checks what it states. */
internal fun checkAll(cases: List<Case>) {
    for (case in cases) {
        val world = started()
        world.(case.script)()
        assertEquals(case.name, case.calls, world.calls)
        assertEquals(case.name, case.lookups, world.lookups)
        val run = world.coordinator.state.current
        val offered = run?.takeIf { world.coordinator.state.offersWrite(it.key) }?.nowPlaying?.title
        assertEquals(case.name, case.offered, offered)
    }
}
