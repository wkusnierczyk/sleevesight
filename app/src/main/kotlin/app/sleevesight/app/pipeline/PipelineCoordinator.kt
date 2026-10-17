// The rule table in the KDoc of PipelineCoordinator is a table, row by row.
@file:Suppress("MaxLineLength")

package app.sleevesight.app.pipeline

import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.RunStep

/** A run the coordinator started. */
@JvmInline
value class RunId(
    val value: Long,
)

/** What happens, as [PipelineCoordinator] hears it: one at a time, from one queue. */
sealed interface CoordinatorEvent {
    /** The first run was acknowledged: nothing is read, built or sent before. */
    data object Acknowledged : CoordinatorEvent

    /** The session layer reported a track ([key] is its `RunKey`). */
    data class Reported(
        val key: RunKey,
        val nowPlaying: NowPlaying,
        val cause: ChangeCause = ChangeCause.NATURAL_END,
    ) : CoordinatorEvent

    /** The debounce of report [report] passed. */
    data class Settled(
        val report: Long,
    ) : CoordinatorEvent

    /** An activity started (`MainActivity.onStart`). */
    data object ActivityStarted : CoordinatorEvent

    /** An activity stopped; [changingConfigurations] for a rotation, whose new activity starts at once. */
    data class ActivityStopped(
        val changingConfigurations: Boolean,
    ) : CoordinatorEvent

    /** *Look up in the background* switched. */
    data class BackgroundChanged(
        val on: Boolean,
    ) : CoordinatorEvent

    /** A key, a web engine or the AI service changed: a new pipeline is needed. */
    data object RebuildRequested : CoordinatorEvent

    /** Build [build] ended; [built] is false when it failed. */
    data class RebuildFinished(
        val build: Long,
        val built: Boolean,
    ) : CoordinatorEvent

    /** Run [run] waits for [step] ([RunStep.LOOKUPS] or [RunStep.LABELS]). */
    data class StepRequested(
        val run: RunId,
        val step: RunStep,
    ) : CoordinatorEvent

    /**
     * Run [run] has its evidence and could write its insight; [writesWhenActed] is whether *Write
     * insights automatically* writes this change once the user has acted (`AutoWrite`).
     */
    data class InsightAsked(
        val run: RunId,
        val writesWhenActed: Boolean,
    ) : CoordinatorEvent

    /** The user tapped Write. */
    data object WriteTapped : CoordinatorEvent

    /** A step granted to [run] ended: done, failed or cancelled. */
    data class StepEnded(
        val run: RunId,
        val step: RunStep,
    ) : CoordinatorEvent

    /** Run [run] ended: done, failed or cancelled (from its job's `finally`). */
    data class RunEnded(
        val run: RunId,
    ) : CoordinatorEvent

    /** Run [run] showed a card kept in the synthesis cache: its insight is written, with no call. */
    data class CardShown(
        val run: RunId,
    ) : CoordinatorEvent

    /** Run [run] decided its insight without a call (no evidence, no AI service, insights off). */
    data class DecidedWithoutCall(
        val run: RunId,
    ) : CoordinatorEvent
}

/** What the coordinator has done, for the host to carry out. */
sealed interface CoordinatorEffect {
    /** Send [CoordinatorEvent.Settled] for [report] after the debounce. */
    data class StartTimer(
        val report: Long,
    ) : CoordinatorEffect

    /** Read the keys and build pipeline [build]; answer with [CoordinatorEvent.RebuildFinished]. */
    data class Build(
        val build: Long,
    ) : CoordinatorEffect

    /** Make pipeline [build] the live one (none, when it failed to build), ending the one before. */
    data class Swap(
        val build: Long,
        val built: Boolean,
    ) : CoordinatorEffect

    /** Start run [run] of [nowPlaying] on the live pipeline. */
    data class StartRun(
        val run: RunId,
        val nowPlaying: NowPlaying,
        val cause: ChangeCause,
    ) : CoordinatorEffect

    /** Cancel run [run]. */
    data class CancelRun(
        val run: RunId,
    ) : CoordinatorEffect

    /** Let [run] start [step]: lookups, labels, or the model call a tap asked for. */
    data class Grant(
        val run: RunId,
        val step: RunStep,
    ) : CoordinatorEffect

    /** Answer [run]'s [CoordinatorEvent.InsightAsked]: [write] starts the model call, else Write is offered. */
    data class Decide(
        val run: RunId,
        val write: Boolean,
    ) : CoordinatorEffect
}

/**
 * Everything the coordinator knows. [visible] while an activity is started, or one is restarting
 * after a configuration change; [allowed] (D6) once acknowledged, while visible or *Look up in the
 * background* is on. [decided] is the track whose insight is decided (written, being written,
 * shown from the cache, offered, or decided without a call), and whether it is written: a run of
 * the same key keeps it (a rebuild's run again), and so does a run of the same insight track
 * ([sameInsightTrack]) when it is written; any other run clears it. [building] is the rebuild
 * waiting to be swapped in, and [built] its outcome once it is known.
 */
data class CoordinatorState(
    val acknowledged: Boolean = false,
    val started: Int = 0,
    val restarting: Boolean = false,
    val background: Boolean = false,
    val acted: Boolean = false,
    val latest: Report? = null,
    val run: Run? = null,
    val decided: Decided? = null,
    val builds: Long = 0,
    val building: Long? = null,
    val built: Boolean? = null,
    val live: Boolean = false,
    val runs: Long = 0,
) {
    /** A decided insight: the track as reported, its key, and whether it is written (or being written). */
    data class Decided(
        val nowPlaying: NowPlaying,
        val key: RunKey,
        val written: Boolean,
    )

    /** The latest report: its number, track and cause, and whether its debounce has passed. */
    data class Report(
        val seq: Long,
        val key: RunKey,
        val nowPlaying: NowPlaying,
        val cause: ChangeCause,
        val settled: Boolean = false,
    )

    /**
     * The run in progress. [asked]: the steps it waits for; [insight]: its question whether to
     * write, while undecided ([CoordinatorEvent.InsightAsked]); [offered] while it offers Write;
     * [calling] while its model call is under way; [written] once a call was granted (on a tap or
     * without one) or a kept card shown. Requests and questions are remembered while the run is
     * not current, and granted if it becomes current again.
     */
    data class Run(
        val id: RunId,
        val key: RunKey,
        val nowPlaying: NowPlaying,
        val asked: Set<RunStep> = emptySet(),
        val insight: Boolean? = null,
        val offered: Boolean = false,
        val calling: Boolean = false,
        val written: Boolean = false,
    ) {
        /**
         * Whether this run is the one for [report]: of its key, or, once its insight is written, of
         * the same insight track (a new length, album or artists of it), so the insight paid for
         * stays, with no second run, card lookup or call.
         */
        fun holds(report: Report): Boolean =
            key == report.key || written && sameInsightTrack(nowPlaying, report.nowPlaying)
    }

    val visible: Boolean get() = started > 0 || restarting

    val allowed: Boolean get() = acknowledged && (visible || background)

    /** The run, while it is current: the one for the latest report, on the live pipeline, with no rebuild pending. */
    val current: Run? get() = run?.takeIf { run -> live && building == null && latest?.let(run::holds) == true }

    /** Whether Write is offered for the track [key]: the current run's, while it waits for a tap. */
    fun offersWrite(key: RunKey): Boolean = current?.let { it.key == key && it.offered && !it.calling } == true
}

/**
 * The same insight track: the session layer's identity ([TrackIdentity.same]) and the same title,
 * the rule the history uses for a track that plays on (`HistoryRecorder.sameAsLast`). A new length,
 * album or artists is the same insight track; a new title under one media id (a stream's next song)
 * is not.
 */
fun sameInsightTrack(
    a: NowPlaying,
    b: NowPlaying,
): Boolean = TrackIdentity.same(a, b) && a.title == b.title

/**
 * The one owner of the pipeline's coordination in the app (#51; architecture, *How the app runs
 * it*): a state machine that takes events one at a time and answers with effects. It grants every
 * step that sends a request or spends, and makes the decision whether to write, each from the
 * whole state at once: visibility, currency, the mode, the first action. Pure: no threads, no
 * time; [PipelineHost] carries the effects out and feeds the events back from one queue.
 *
 * After every event, once acknowledged, come the grants: while a run is current and requests are
 * [CoordinatorState.allowed], Grant for each step it waits for, and Decide for its question whether
 * to write: write when *Write insights automatically* writes this change, the user has acted since
 * the app opened, and the run starts undecided; else offer Write. Either way its track is then
 * decided. The table gives each event's own effects; the grants follow every row. "The run" is
 * the run in progress; "an old run" is any other, cancelled or replaced. "The key" is a report's
 * `RunKey`; "the same insight track" is [sameInsightTrack]; the run "holds" a report of its key,
 * and, once its insight is written (a call granted, or a kept card shown), of the same insight
 * track: a new length, album or artists of a written track keeps its run, its insight and its
 * decision.
 *
 * | State | Event | New state | Effects |
 * |---|---|---|---|
 * | not acknowledged | anything but the four below | unchanged | none |
 * | any | Acknowledged | acknowledged | none |
 * | any | ActivityStarted | started + 1; first action cleared if none was started and not restarting (the app opening) | none |
 * | any | ActivityStopped(c) | started − 1; restarting = c when none is left | none |
 * | any | BackgroundChanged(on) | background = on | none |
 * | any | Reported(key) | latest = the report; the run current only if it holds it; first action made if the cause is an action in Sleevesight | StartTimer |
 * | latest not this report | Settled | unchanged | none |
 * | no live pipeline, or a rebuild pending | Settled | latest settled | none (the swap starts it) |
 * | the run holds latest | Settled | latest settled | none |
 * | otherwise | Settled | latest settled; a new run of it, undecided unless of the decided key, or of the same insight track as a written one | CancelRun (old), StartRun |
 * | any | RebuildRequested | building = a new build; no run is current | Build |
 * | build not the one pending | RebuildFinished | unchanged | none |
 * | the pending build, the run's model call under way | RebuildFinished | built known; waits for the call | none |
 * | the pending build, no model call under way | RebuildFinished | the new pipeline live (or none); run cleared | CancelRun (old), Swap; StartRun of latest if settled, decided as above |
 * | the run, its model call under way | StepEnded(MODEL_CALL) | calling = false; then a pending swap, or a run of latest if settled and not held | as for RebuildFinished, or StartRun |
 * | the run | RunEnded | calling, offered, the steps it waits for and its question cleared (written and decided kept); then as for StepEnded(MODEL_CALL) | as above |
 * | the run (current or not) | StepRequested(step), InsightAsked(w) | the step or the question waiting | none |
 * | current run offering Write, allowed | WriteTapped | calling, written; decided (written); first action made | Grant(MODEL_CALL) |
 * | otherwise | WriteTapped | unchanged | none |
 * | the run | CardShown | written; decided (written) | none |
 * | the run | DecidedWithoutCall | decided (not written) | none |
 * | an old run | StepRequested, InsightAsked, StepEnded, RunEnded, CardShown, DecidedWithoutCall | unchanged | none |
 */
@Suppress("TooManyFunctions") // One for each kind of event, mostly: the table, row by row.
class PipelineCoordinator(
    initial: CoordinatorState = CoordinatorState(),
) {
    /** The state after the last event. */
    var state: CoordinatorState = initial
        private set

    private val effects = mutableListOf<CoordinatorEffect>()

    /** Takes [event], and returns what to do. */
    fun on(event: CoordinatorEvent): List<CoordinatorEffect> {
        effects.clear()
        when (event) {
            CoordinatorEvent.Acknowledged -> state = state.copy(acknowledged = true)
            CoordinatorEvent.ActivityStarted -> started()
            is CoordinatorEvent.ActivityStopped -> stopped(event.changingConfigurations)
            is CoordinatorEvent.BackgroundChanged -> state = state.copy(background = event.on)
            else -> if (state.acknowledged) onAcknowledged(event)
        }
        if (state.acknowledged) grants()
        return effects.toList()
    }

    private fun onAcknowledged(event: CoordinatorEvent) {
        when (event) {
            is CoordinatorEvent.Reported -> reported(event)
            is CoordinatorEvent.Settled -> settled(event.report)
            CoordinatorEvent.RebuildRequested -> rebuild()
            is CoordinatorEvent.RebuildFinished -> finished(event)
            is CoordinatorEvent.StepRequested -> ofRun(event.run) { it.copy(asked = it.asked + event.step) }
            is CoordinatorEvent.InsightAsked -> ofRun(event.run) { it.copy(insight = event.writesWhenActed) }
            CoordinatorEvent.WriteTapped -> tapped()
            is CoordinatorEvent.StepEnded -> if (event.step == RunStep.MODEL_CALL) callEnded(event.run)
            is CoordinatorEvent.RunEnded -> runEnded(event.run)
            is CoordinatorEvent.CardShown -> decided(event.run, written = true)
            is CoordinatorEvent.DecidedWithoutCall -> decided(event.run, written = false)
            else -> Unit
        }
    }

    private fun started() {
        val opening = state.started == 0 && !state.restarting
        state = state.copy(started = state.started + 1, restarting = false, acted = state.acted && !opening)
    }

    private fun stopped(changingConfigurations: Boolean) {
        val left = (state.started - 1).coerceAtLeast(0)
        state = state.copy(started = left, restarting = left == 0 && changingConfigurations)
    }

    private fun reported(event: CoordinatorEvent.Reported) {
        val seq = (state.latest?.seq ?: 0) + 1
        state =
            state.copy(
                latest = CoordinatorState.Report(seq, event.key, event.nowPlaying, event.cause),
                acted = state.acted || event.cause == ChangeCause.USER_ACTION,
            )
        effects += CoordinatorEffect.StartTimer(seq)
    }

    private fun settled(seq: Long) {
        val latest = state.latest?.takeIf { it.seq == seq } ?: return
        state = state.copy(latest = latest.copy(settled = true))
        if (startsNow(latest)) startRun(latest)
    }

    // Whether the settled [latest] report starts a run now: not without a live pipeline or with a
    // rebuild pending (the swap starts it), and not when the run holds it (`Run.holds`).
    private fun startsNow(latest: CoordinatorState.Report): Boolean =
        state.live && state.building == null && state.run?.holds(latest) != true

    private fun decided(
        id: RunId,
        written: Boolean,
    ) {
        val run = state.run?.takeIf { it.id == id } ?: return
        state =
            state.copy(
                run = run.copy(written = run.written || written),
                decided = CoordinatorState.Decided(run.nowPlaying, run.key, written),
            )
    }

    private fun startRun(report: CoordinatorState.Report) {
        state.run?.let { effects += CoordinatorEffect.CancelRun(it.id) }
        begin(report)
    }

    // A run of [report]'s track. Its track stays decided for a run of the same key (a rebuild's run
    // again), or of the same insight track once written; any other run starts undecided.
    private fun begin(report: CoordinatorState.Report) {
        val id = RunId(state.runs + 1)
        val decided =
            state.decided?.takeIf {
                it.key == report.key || it.written && sameInsightTrack(it.nowPlaying, report.nowPlaying)
            }
        val run = CoordinatorState.Run(id, report.key, report.nowPlaying)
        state = state.copy(runs = id.value, run = run, decided = decided)
        effects += CoordinatorEffect.StartRun(id, report.nowPlaying, report.cause)
    }

    private fun rebuild() {
        val build = state.builds + 1
        state = state.copy(builds = build, building = build, built = null)
        effects += CoordinatorEffect.Build(build)
    }

    private fun finished(event: CoordinatorEvent.RebuildFinished) {
        if (event.build != state.building) return
        state = state.copy(built = event.built)
        swap()
    }

    // The pending build goes live once it is built and no model call is under way: what the
    // call costs is not lost. The run of the latest report starts on it, as a run of the same
    // track again when it is one: its track stays decided.
    private fun swap() {
        val build = state.building
        val built = state.built
        if (build == null || built == null || state.run?.calling == true) return
        state.run?.let { effects += CoordinatorEffect.CancelRun(it.id) }
        effects += CoordinatorEffect.Swap(build, built)
        state = state.copy(building = null, built = null, live = built)
        val latest = state.latest
        state = state.copy(run = null)
        if (built && latest != null && latest.settled) begin(latest)
    }

    private fun tapped() {
        val run = state.current ?: return
        if (!run.offered || run.calling || !state.allowed) return
        state =
            state.copy(
                run = run.copy(offered = false, calling = true, written = true),
                decided = CoordinatorState.Decided(run.nowPlaying, run.key, written = true),
                acted = true,
            )
        effects += CoordinatorEffect.Grant(run.id, RunStep.MODEL_CALL)
    }

    private fun callEnded(id: RunId) {
        if (state.run?.id != id) return
        ofRun(id) { it.copy(calling = false) }
        afterCall()
    }

    // A run that ended waits for nothing: no call, no tap. Nothing then waits for it.
    private fun runEnded(id: RunId) {
        if (state.run?.id != id) return
        ofRun(id) { it.copy(calling = false, offered = false, asked = emptySet(), insight = null) }
        afterCall()
    }

    // What waited for the call: a pending swap, or a run of the latest report.
    private fun afterCall() {
        swap()
        val latest = state.latest?.takeIf { it.settled } ?: return
        if (startsNow(latest)) startRun(latest)
    }

    private fun ofRun(
        id: RunId,
        change: (CoordinatorState.Run) -> CoordinatorState.Run,
    ) {
        val run = state.run?.takeIf { it.id == id } ?: return
        state = state.copy(run = change(run))
    }

    // What the current run may do now, decided from the whole state at once.
    private fun grants() {
        val run = state.current ?: return
        if (!state.allowed) return
        run.asked.forEach { effects += CoordinatorEffect.Grant(run.id, it) }
        var next = run.copy(asked = emptySet())
        val writesWhenActed = run.insight
        if (writesWhenActed != null) {
            // Decided before: carried over to this run (`begin`), so not written again unasked.
            val write = writesWhenActed && state.acted && state.decided == null
            next = next.copy(insight = null, offered = !write, calling = write, written = write)
            state = state.copy(decided = CoordinatorState.Decided(run.nowPlaying, run.key, written = write))
            effects += CoordinatorEffect.Decide(run.id, write)
        }
        state = state.copy(run = next)
    }
}
