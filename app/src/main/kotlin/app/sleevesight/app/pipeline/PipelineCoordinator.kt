// The rule table in the KDoc of PipelineCoordinator is a table, row by row.
@file:Suppress("MaxLineLength")

package app.sleevesight.app.pipeline

import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.AutoWrite
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.Gathered
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.RunStep
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.settings.Settings

/** A card paid for: the insight written and checked, and the evidence it cites. */
data class Card(
    val insight: InsightState.Written,
    val evidence: EvidenceSet?,
)

/**
 * What decides, from a run's settings, whether it writes without a tap: the causes *Reactive*
 * writes on ([writesOn], `AutoWrite.causes`: none while *Show insights* is off or
 * under *Manual*), and whether the model's own knowledge is allowed ([allowModel]), which decides
 * whether the evidence has anything to narrate (`EvidenceSet.nothingToNarrate`).
 */
data class WriteSettings(
    val writesOn: Set<ChangeCause>,
    val allowModel: Boolean,
) {
    companion object {
        /** The ones [settings] give. */
        fun of(settings: Settings): WriteSettings =
            WriteSettings(AutoWrite.causes(settings), settings.isEnabled(SourceId.MODEL))
    }
}

/** A run the coordinator started. */
@JvmInline
value class RunId(
    val value: Long,
)

/**
 * One model call: the [number]-th (from 1) of run [run], counted as the coordinator grants them (a
 * tap's grant, or the decision to write). A Stop names the call it was shown for, so it never
 * reaches a later one.
 */
data class CallId(
    val run: RunId,
    val number: Int,
)

/** What happens, as [PipelineCoordinator] hears it: one at a time, from one queue. */
sealed interface CoordinatorEvent {
    /** The first run was acknowledged: nothing is read, built or sent before. */
    data object Acknowledged : CoordinatorEvent

    /**
     * The session layer reported a track ([key] is its `RunKey`), and why it came to play ([cause]:
     * the change into it, as skip detection judged it, or an action in Sleevesight).
     */
    data class Reported(
        val key: RunKey,
        val nowPlaying: NowPlaying,
        val cause: ChangeCause = ChangeCause.NATURAL_END,
    ) : CoordinatorEvent

    /** The debounce of report [report] passed. */
    data class Settled(
        val report: Long,
    ) : CoordinatorEvent

    /**
     * An activity started (`MainActivity.onStart`). Never the app opening on its own: a return to
     * the app, the screen turned on, keeps the first action ([CoordinatorState.acted]), and starts
     * the count of track changes away again ([CoordinatorState.away], #203).
     */
    data object ActivityStarted : CoordinatorEvent

    /** An activity stopped; [changingConfigurations] for a rotation, whose new activity starts at once. */
    data class ActivityStopped(
        val changingConfigurations: Boolean,
    ) : CoordinatorEvent

    /** *Look up in the background* switched. */
    data class BackgroundChanged(
        val on: Boolean,
    ) : CoordinatorEvent

    /**
     * *Saver* switched (#203): [after] track changes reported while no
     * activity is started are written as usual, and the first action expires at the next one; null
     * for *Off*, never.
     */
    data class PauseAutoWriteChanged(
        val after: Int?,
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
     * Run [run] has its evidence and could write its insight; [writesOn] are the causes of a change
     * that *Reactive* writes on, once the user has acted, under the run's
     * settings (`AutoWrite.causes`: none while *Show insights* is off or under *Manual*), and
     * [allowModel] whether its settings allow the model's own knowledge (#192: a run that asks
     * again writes without a tap only under the settings the track was decided under).
     */
    data class InsightAsked(
        val run: RunId,
        val writesOn: Set<ChangeCause>,
        val allowModel: Boolean = false,
    ) : CoordinatorEvent

    /**
     * The user tapped Write (or Rewrite) offered for the run under [key], as the screen showed it
     * ([CoordinatorState.writeKey]): a tap for any other run, one the screen showed before a
     * change reached the coordinator, does nothing.
     */
    data class WriteTapped(
        val key: RunKey,
    ) : CoordinatorEvent

    /**
     * The user tapped Stop shown for model call [call] while it was under way
     * ([CoordinatorState.stopCall]): a tap for any other call (another run's, or an earlier one of
     * this run), or once the call has ended, does nothing.
     */
    data class StopTapped(
        val call: CallId,
    ) : CoordinatorEvent

    /**
     * Run [run]'s model call failed or was stopped, and the run waits for a tap on Write to make
     * another (`RunControl.awaitRetry`). Sent after the call's [StepEnded].
     */
    data class RetryAsked(
        val run: RunId,
    ) : CoordinatorEvent

    /** A step granted to [run] ended: done, failed or cancelled. */
    data class StepEnded(
        val run: RunId,
        val step: RunStep,
    ) : CoordinatorEvent

    /** Run [run] ended: done, failed or cancelled (from its job's `finally`). */
    data class RunEnded(
        val run: RunId,
    ) : CoordinatorEvent

    /**
     * Run [run] was served its insight from the synthesis cache: written, with no call. [card] is
     * the card, or null for a reply kept as `NO_EVIDENCE`.
     */
    data class CardShown(
        val run: RunId,
        val card: Card?,
    ) : CoordinatorEvent

    /** Run [run]'s model call wrote [card] (before the call's end is reported). */
    data class CardWritten(
        val run: RunId,
        val card: Card,
    ) : CoordinatorEvent

    /**
     * Run [run] decided its insight with no call and nothing cached (nothing to narrate, as
     * `EvidenceSet.nothingToNarrate` decides; no AI service; insights off).
     * [nothingToNarrate] when it was for nothing to narrate (`RunControl.nothingToNarrate`), which
     * new evidence may change (back online, #192): the run's [WriteSettings]; null when insights
     * are off or no AI service is set up (`RunControl.decidedWithoutCall`).
     */
    data class DecidedWithoutCall(
        val run: RunId,
        val nothingToNarrate: WriteSettings? = null,
    ) : CoordinatorEvent

    /**
     * The track to prepare ahead (D13) is now [next]: the item after the playing track in the shown
     * player's queue (`NextInQueue`), or null when there is none or *Proactive* is off.
     */
    data class NextChanged(
        val next: NowPlaying?,
    ) : CoordinatorEvent

    /** Preparation [prep] ended: done, failed or cancelled (from its job's `finally`). */
    data class PreparationEnded(
        val prep: RunId,
    ) : CoordinatorEvent

    /**
     * The phone's connection changed (#192): [online] while its default network has validated
     * internet access (`ConnectivityManager`, through the host's `online`).
     */
    data class NetworkChanged(
        val online: Boolean,
    ) : CoordinatorEvent

    /**
     * Every source of run [run] has landed: [gathered] is what they ended with, reported before
     * its evidence is assembled (`RunControl.gathered`).
     */
    data class SourcesGathered(
        val run: RunId,
        val gathered: Gathered,
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

    /**
     * Start run [run] of [nowPlaying] on the live pipeline; with [again], what the run before it
     * gathered, asking again only the sources that were unreachable (#192, `Pipeline.run`).
     */
    data class StartRun(
        val run: RunId,
        val nowPlaying: NowPlaying,
        val again: Gathered? = null,
    ) : CoordinatorEffect

    /** Cancel run [run]. */
    data class CancelRun(
        val run: RunId,
    ) : CoordinatorEffect

    /** Start preparation [prep] of [next] on the live pipeline (D13, `Pipeline.prepare`). */
    data class StartPreparation(
        val prep: RunId,
        val next: NowPlaying,
    ) : CoordinatorEffect

    /** Cancel preparation [prep]. */
    data class CancelPreparation(
        val prep: RunId,
    ) : CoordinatorEffect

    /** Let [run] (or a preparation) start [step]: lookups, labels, or the model call a tap asked for. */
    data class Grant(
        val run: RunId,
        val step: RunStep,
    ) : CoordinatorEffect

    /** Answer [run]'s [CoordinatorEvent.InsightAsked]: [write] starts the model call, else Write is offered. */
    data class Decide(
        val run: RunId,
        val write: Boolean,
    ) : CoordinatorEffect

    /** Stop model call [call], under way: it ends as stopped, and reports its end as any call does. */
    data class StopCall(
        val call: CallId,
    ) : CoordinatorEffect

    /**
     * The user acted in Sleevesight, now: a tap on Write was taken. The time is kept for the track
     * changes' features (D15, `UserActions`); the first action is in the state.
     */
    data object Acted : CoordinatorEffect
}

/**
 * Everything the coordinator knows. [visible] while an activity is started, or one is restarting
 * after a configuration change; [allowed] (D6) once acknowledged, while visible or *Look up in the
 * background* is on. [acted]: the user's first action since the app started (D5, #190). Only a
 * cold start, a new process, starts without it: the host makes one coordinator per process
 * (`PipelineHost`, held by `AppGraph`, which the `Application` holds), so a return to the app from
 * the background, the screen turned off and on, or a configuration change keeps it. Only its
 * expiry clears it (#203): [away] counts the track changes reported while no activity is started
 * (a report of another insight track than the report before), from the last activity start or
 * action, and once it passes [pauseAfter] (*Saver*; null for *Off*), at
 * the next change after that many, the first action, if made, expires: [acted] false and
 * [expired] true, until the user acts again, and a tap kept for the run (#192) is dropped. An
 * activity start resets [away], not [expired]. [ledger] is the insight of the current insight
 * track ([Ledger]). [building] is the rebuild waiting to be swapped in, and [built] its outcome
 * once it is known. [next] is the track to prepare ahead (D13), [preparation] the preparation in progress, and [prepared] the
 * track whose preparation ended on the live pipeline, so it is not prepared again. [online] is
 * the phone's connection as last reported (online until told otherwise), and [returns] counts its
 * returns, from offline to online, so a run knows whether the network came back after its lookups
 * were granted (#192).
 */
data class CoordinatorState(
    val acknowledged: Boolean = false,
    val started: Int = 0,
    val restarting: Boolean = false,
    val background: Boolean = false,
    val acted: Boolean = false,
    val pauseAfter: Int? = null,
    val away: Int = 0,
    val expired: Boolean = false,
    val latest: Report? = null,
    val run: Run? = null,
    val ledger: Ledger? = null,
    val builds: Long = 0,
    val building: Long? = null,
    val built: Boolean? = null,
    val live: Boolean = false,
    val runs: Long = 0,
    val next: NowPlaying? = null,
    val preparation: Preparation? = null,
    val prepared: NowPlaying? = null,
    val online: Boolean = true,
    val returns: Long = 0,
) {
    /**
     * The insight ledger: one entry, for the current insight track ([sameInsightTrack]), saying
     * whether its insight is decided and whether it is [written]: a model call granted (on a tap or
     * without one; a call that then fails, is stopped or is cut off counts), or a card shown from the cache.
     * [track] and [key] are the report and `RunKey` the insight was decided, written or shown
     * under, so a card is found again under its own key; [shown] is the latest report of the track,
     * whose metadata (artists, album, length) the header shows. Only a report of another insight
     * track, once settled, replaces the entry; runs, rebuilds and new keys never make [written]
     * false while it stands; every cache hit counts as written, a reply kept as `NO_EVIDENCE`
     * included. [card] is the card paid for, as its run reported it, with the evidence it cites:
     * what the view shows whenever the track's run has no insight of its own. A card written by a
     * call (a rewrite included) always replaces it; one served from the cache only fills an empty
     * slot; a call that fails, is stopped or is cut off adds none; it goes with the entry. So an insight written
     * from a title alone stays when the artists arrive, and the header shows the artists: nothing
     * is written twice for one track without a tap (D5), and a title-only report that found
     * nothing is decided afresh. A rewrite (Write offered beside the card kept) runs under [track],
     * not [shown]: it is a rebuild's run, which starts under the key the card was written under so
     * that the card is found when the rebuild changed nothing in it. A track that returns after
     * another has settled is a new decision (D5): the card in the store is served when the
     * return's key matches it. [wouldWrite] (#192) is set only by the first decision for its key,
     * when it was made with no call for nothing to narrate (`DecidedWithoutCall.nothingToNarrate`)
     * and the grant's own check would have written without a tap had there been evidence (the
     * latest report's cause among the causes its settings write on, the first action made, the
     * key neither written nor decided before): the settings it was decided under, and the sources
     * unreachable then ([WouldWrite]). A run that asks again may then write without a tap, under
     * the same settings, once one of those sources has returned lines; a run of the key asking
     * again that again finds nothing to narrate keeps it, under the same settings (else it is
     * cleared); any other decision (a Decide, a tap, a card, a rebuild's run) replaces the entry
     * without it. [paused] (#203) is the settings of a decision that offered Write, or found nothing
     * to narrate, only because the first action had expired: a run of the key asking again under
     * the same settings, with the latest report's cause still one they write on, says so too
     * ([Run.lapsed]). It never decides a write. A lapsed Decide sets it; a decision for nothing to
     * narrate sets it as [wouldWrite] is set, with the first action expired in place of made, and a
     * run of the key asking again keeps it under the same settings; any other decision drops it.
     */
    data class Ledger(
        val track: NowPlaying,
        val key: RunKey,
        val written: Boolean,
        val shown: NowPlaying = track,
        val card: Card? = null,
        val wouldWrite: WouldWrite? = null,
        val paused: WriteSettings? = null,
    )

    /**
     * What a decision for nothing to narrate that would have written leaves (#192): the
     * [settings] it was made under, and the sources [unreachable] then, against which a run that
     * asks again measures new evidence, however many replacements came between.
     */
    data class WouldWrite(
        val settings: WriteSettings,
        val unreachable: Set<SourceId>,
    ) {
        /** Whether [gathered] holds lines from a source unreachable when the decision was made. */
        fun brought(gathered: Gathered?): Boolean =
            unreachable.any { source ->
                (gathered?.sources?.get(source) as? SourceState.Done)?.result?.evidence?.isNotEmpty() == true
            }
    }

    /** The latest report: its number, track and cause, and whether its debounce has passed. */
    data class Report(
        val seq: Long,
        val key: RunKey,
        val nowPlaying: NowPlaying,
        val cause: ChangeCause,
        val settled: Boolean = false,
    )

    /**
     * The run in progress, of [nowPlaying] under [key]. [asked]: the steps it waits for;
     * [insight]: its question whether to write, while undecided, with the causes its settings
     * write on ([CoordinatorEvent.InsightAsked]), and [allowModel] as they say;
     * [offered] while it offers Write; [calling] while its model call is under way, the [calls]-th
     * it was granted; [labelled] once its labels step has ended (the last of its requests, asked
     * for after its evidence), or the run has: a preparation waits for it, so it never stalls the
     * run. Requests and
     * questions are remembered while the run is not current, and granted if it becomes current
     * again. [orphaned] once a report of another insight track has settled while it ran: it then
     * holds no report and is never current again, and its reports to the ledger are ignored, so
     * it cannot bring its track back; a report of its track that returns gets a run, and a
     * decision, of its own. [gathered] is what its sources ended with, once every one has landed;
     * [lookedUp] the network's [returns] when its lookups were granted; [again] whether it asks
     * again the sources its run before found unreachable (#192). [keepsTap] while such a run has not
     * decided and a run it replaced, itself or one before it, offered Write: a tap shown for that
     * run is kept ([tapped]), through further replacements, and answers its question as a tap.
     * [lapsed] when it offers Write only because the first action expired (#203): it would have
     * written had the first action stood, or it asks again for a key whose decision was paused
     * ([Ledger.paused]) under its own settings and cause.
     */
    data class Run(
        val id: RunId,
        val key: RunKey,
        val nowPlaying: NowPlaying,
        val asked: Set<RunStep> = emptySet(),
        val insight: Set<ChangeCause>? = null,
        val allowModel: Boolean = false,
        val offered: Boolean = false,
        val calling: Boolean = false,
        val calls: Int = 0,
        val orphaned: Boolean = false,
        val labelled: Boolean = false,
        val gathered: Gathered? = null,
        val lookedUp: Long? = null,
        val again: Boolean = false,
        val keepsTap: Boolean = false,
        val tapped: Boolean = false,
        val lapsed: Boolean = false,
    )

    /**
     * A preparation of the next track (D13), of [nowPlaying]; [asked]: the steps it waits for. It
     * is never granted a model call.
     */
    data class Preparation(
        val id: RunId,
        val nowPlaying: NowPlaying,
        val asked: Set<RunStep> = emptySet(),
    )

    val visible: Boolean get() = started > 0 || restarting

    val allowed: Boolean get() = acknowledged && (visible || background)

    /** The ledger's entry, when it is written and [report] is of its insight track. */
    fun written(report: NowPlaying): Ledger? = ledger?.takeIf { it.written && sameInsightTrack(it.track, report) }

    /**
     * Whether [run] holds [report]: it is of the report's key, or the report is of the insight
     * track the ledger has written, and so is the run: a new length, album or artists of a
     * written track starts no run, so the insight paid for stays. An orphaned run holds nothing,
     * not even a report of its own key: a report of the run's key but another insight track
     * (another player on the same release, or another media id or queue item with the same title,
     * artists, album and length) is a track change, as [sameInsightTrack] judges it, so once it
     * settles the run is replaced, a call under way is cut off, and the new track decides afresh
     * (D5): written again where the mode and the first action allow, otherwise Write is offered.
     * The coordinator follows the session layer's identity rather than a rule of its own (#255).
     * #297 takes the queue slot out of identity for players that send no track id; the media id
     * still decides when both reports send one, so another player on the same release, or another
     * media id with the same metadata, stays a track change.
     */
    fun holds(
        run: Run,
        report: Report,
    ): Boolean =
        !run.orphaned &&
            (
                run.key == report.key ||
                    written(report.nowPlaying)?.let { sameInsightTrack(it.track, run.nowPlaying) } == true
            )

    /** The run, while it is current: it holds the latest report, on the live pipeline, with no rebuild pending. */
    val current: Run? get() = run?.takeIf { run -> live && building == null && latest?.let { holds(run, it) } == true }

    /**
     * Whether the next track may be prepared now (D13): requests are allowed, and the current run's
     * requests are over (its labels step ended, or the run did), so a preparation never stalls or
     * displaces the playing track's run.
     */
    val prepares: Boolean get() = allowed && current?.labelled == true

    /**
     * Whether the playing track's run asks its unreachable sources again now (back online, #192):
     * requests are allowed, the phone is online, and the current run has no model call under way
     * (what it costs is not lost), its sources have landed with some unreachable, and the network
     * came back after its lookups were granted. Once per return: the run that asks again has its
     * lookups granted after it.
     */
    val asksAgain: Boolean
        get() {
            val run = current ?: return false
            return allowed &&
                online &&
                !run.calling &&
                run.gathered?.unreachable?.isNotEmpty() == true &&
                run.lookedUp?.let { it < returns } == true
        }

    /** Whether Write is offered for the track [key]: the current run's, while it waits for a tap. */
    fun offersWrite(key: RunKey): Boolean = writeKey == key

    /** The key of the run Write is offered for, a rewrite included: the current run's, while it waits for a tap. */
    val writeKey: RunKey? get() = current?.takeIf { it.offered && !it.calling }?.key

    /**
     * The call Stop is offered for: the model call under way of the run in progress, current or
     * not (a rebuild waits for the call, and Stop may still end it).
     */
    val stopCall: CallId? get() = run?.takeIf { it.calling }?.let { CallId(it.id, it.calls) }

    /**
     * Whether the Write offered is a rewrite: the current run is of the ledger's written track, so
     * the card paid for stays shown beside it.
     */
    fun rewrites(): Boolean = current?.let { it.offered && !it.calling && written(it.nowPlaying) != null } == true

    /**
     * Whether the Write offered is offered only because the first action expired (#203): the
     * current run's decision would have written had it stood, and the user has not acted since.
     */
    val lapsed: Boolean get() = expired && current?.takeIf { it.offered && !it.calling }?.lapsed == true
}

/** The same insight track: [TrackIdentity.playsOn], the session history's rule for a track that plays on. */
fun sameInsightTrack(
    a: NowPlaying,
    b: NowPlaying,
): Boolean = TrackIdentity.playsOn(a, b)

/**
 * The one owner of the pipeline's coordination in the app (#51; architecture, *How the app runs
 * it*): a state machine that takes events one at a time and answers with effects. It grants every
 * step that sends a request or spends, and makes the decision whether to write, each from the
 * whole state at once: visibility, currency, the mode, the first action, the ledger. Pure: no
 * threads, no time; [PipelineHost] carries the effects out and feeds the events back from one
 * queue.
 *
 * After every event, once acknowledged, comes asking again, then the grants. Asking again (back
 * online, #192): when [CoordinatorState.asksAgain] (requests allowed, the phone online, the current
 * run with no model call under way, its sources landed with some unreachable, and the network
 * back since its lookups were granted), the run is replaced by a run of its key and report that
 * asks again only those sources, keeping what the others found ([CoordinatorEffect.StartRun]'s
 * `again`), as a cancelled run's replacement does: a preparation in progress is cancelled. Its
 * decision, for a key the ledger decided, writes without a tap only when all hold: the key's
 * first decision was made with no call for nothing to narrate and would have written had there
 * been evidence ([CoordinatorState.Ledger.wouldWrite], the settings it was decided under, set by
 * DecidedWithoutCall from the grant's own check); the run's settings still give the same causes
 * and the same model-knowledge switch; a source unreachable when it was made has returned lines
 * ([CoordinatorState.WouldWrite], kept with it, so it holds however many replacements came
 * between); and the grant's check holds now. Else Write is offered, never written unasked:
 * Write offered before, insights off, no AI service, a setting changed since, nothing new found.
 * A later decision for the key never sets it: a run asking again that again finds nothing to
 * narrate keeps it under the same settings, and clears it under others; any other
 * (a Decide, a tap, a card, a rebuild's run) replaces the entry without it, and so does a report
 * of another insight track, which empties the ledger. One written is not written again without a
 * tap. A tap shown for a run replaced, which offered Write, is kept until a replacement decides,
 * through further replacements, and answers its question as a tap: one call, the ledger written
 * ([CoordinatorState.Run.keepsTap]); a replacement that decides otherwise (a card shown, no call)
 * or ends drops it. The grants: while a run is
 * current and requests are [CoordinatorState.allowed], Grant for each step it waits for (the
 * network's returns kept with its lookups), and Decide for its question whether
 * to write. For the insight track the ledger has written, the answer is never to write: the
 * question comes only when a rebuild's run cannot find its card under its new card key (new
 * evidence, another service), and Write is offered beside the card paid for, which the view keeps
 * showing ([CoordinatorState.rewrites]), as the user's choice to rewrite it. Else write when the
 * latest report's cause, which the current run holds, is one *Reactive* writes
 * on under the run's settings (`InsightAsked.writesOn`: every cause under *On track change*,
 * a skip in the player or an action in Sleevesight under *On skip*, none under *Manual* or with
 * *Show insights* off), the user has acted since the app started (a cold start: a return to the
 * app keeps the first action) and that first action has not expired, and the ledger has not decided
 * this key (a rebuild's run of it again is not a track change, D5); else offer Write, marking the
 * run lapsed when only the expiry kept it from writing ([CoordinatorState.Run.lapsed]), so the
 * view says why. The ledger then records the decision. A model call that fails or is stopped
 * (#163) leaves the ledger written, as every call granted does, so nothing writes the track again
 * without a tap: its run offers Write again, and a tap on it is a new paid call. Then the next track's preparation (D13,
 * *Evidence*), while requests
 * are allowed and the current run's requests are over (its labels step ended: [CoordinatorState.prepares]),
 * so it never stalls or displaces the playing track's run (every StartRun, a new length or album
 * of the playing track's included, also cancels a preparation in progress, which then starts again
 * once the new run's requests are over): StartPreparation of the next track
 * unless there is none, it was prepared on the live pipeline already, or it is the track playing;
 * else Grant for each step the preparation waits for, never a model call, a Stop or Write again
 * (they act on the run alone). Automatic writing expires when the app is unattended (#203): a
 * track change (a report of another insight track than the report before) while no activity is
 * started counts one more away, an activity start resets the count, and at the count *Pause
 * automatic writing after* names ([CoordinatorEvent.PauseAutoWriteChanged], none for *Off*) the
 * first action, if made, expires at the next change after that many (so that many are written as
 * usual), and that track and the ones after it offer Write until the user acts again; a tap kept
 * for the run is dropped then, so it pays for nothing. "The first action made" (a tap, a kept tap, an action in Sleevesight) also undoes an
 * expiry and resets the count. The table gives each
 * event's own effects; asking again, the grants and the preparation follow every row.
 * "The run" is the run in progress; "an old run" is any other, cancelled or replaced. "The key" is
 * a report's `RunKey`; "the ledger's written track" is the insight track whose entry is written;
 * the run "holds" a report as [CoordinatorState.holds] says. A run of the ledger's written track
 * always starts under the ledger's key and report, so its card is found under its own key.
 *
 * | State | Event | New state | Effects |
 * |---|---|---|---|
 * | not acknowledged | anything but the five below | unchanged | none |
 * | any | Acknowledged | acknowledged | none |
 * | any | ActivityStarted | started + 1; restarting = false; the first action kept (only a new process, with a new coordinator, starts without it); the count of track changes away reset, a first action expired staying expired (#203) | none |
 * | any | ActivityStopped(c) | started − 1; restarting = c when none is left | none |
 * | any | BackgroundChanged(on) | background = on | none |
 * | any | PauseAutoWriteChanged(n) | the pause after n track changes away (none for *Off*) | none |
 * | any | Reported(key, cause) | latest = the report, with its cause; the run current only if it holds it; first action made if the cause is an action in Sleevesight; a preparation cancelled unless the report is of the insight track reported before | CancelPreparation (if one is cancelled), StartTimer |
 * | none visible (no activity started or restarting) | Reported(key, cause), of another insight track than the report before (or the first), not an action in Sleevesight | as above; one more track change away; past the pause's count (the change after that many), with the first action made: the first action expired (acted false), until the user acts again, and a tap kept for the run dropped (keepsTap and tapped cleared); with *Off*, never | as above |
 * | latest not this report | Settled | unchanged | none |
 * | any | Settled, of another insight track than the ledger's | latest settled; the ledger emptied; then as below | as below |
 * | a run of another insight track in progress | Settled | latest settled; the run orphaned (it holds nothing, its ledger reports are ignored); then as below | as below |
 * | any | Settled, of the ledger's insight track | latest settled; the ledger's header from it; then as below | as below |
 * | no live pipeline, or a rebuild pending | Settled | as above | none (the swap starts it) |
 * | the run holds latest | Settled | as above | none |
 * | otherwise | Settled | as above; a new run of it (of the ledger's key and report, for its written track); a preparation cancelled, the next track kept | CancelRun (old), CancelPreparation (if one is cancelled), StartRun |
 * | any | RebuildRequested | building = a new build; no run is current; a preparation cancelled, and nothing prepared on the new pipeline yet | CancelPreparation (if one is cancelled), Build |
 * | build not the one pending | RebuildFinished | unchanged | none |
 * | the pending build, the run's model call under way | RebuildFinished | built known; waits for the call | none |
 * | the pending build, no model call under way | RebuildFinished | the new pipeline live (or none); run cleared; the ledger kept | CancelRun (old), Swap; StartRun of latest if settled (of the ledger's key and report, for its written track) |
 * | the run, its model call under way | StepEnded(MODEL_CALL) | calling = false; then a pending swap, or a run of latest if settled and not held | as for RebuildFinished, or StartRun |
 * | the run | StepEnded(LABELS) | labelled: its requests are over | none |
 * | the run | RunEnded | calling, offered, the steps it waits for and its question cleared; labelled; a kept tap dropped (keepsTap and tapped cleared); then as for StepEnded(MODEL_CALL) | as above |
 * | the run (current or not) | StepRequested(step), InsightAsked(causes, model knowledge) | the step or the question waiting, with the causes its settings write on; for the ledger's written track (its card not found), answered by offering Write to rewrite it, never by writing | none |
 * | current run asking again, not yet decided, a run it replaced (or one before) offering Write, no tap kept yet, allowed, of the tap's key | WriteTapped(key) | the tap kept: it answers the run's question as a tap (one call, the ledger written); first action made | Acted |
 * | current run offering Write, allowed, of the tap's key | WriteTapped(key) | calling (its next call); the ledger written, under the run's key (or kept, if written already: a rewrite); first action made | Grant(MODEL_CALL), Acted |
 * | otherwise (a tap for another run's key included) | WriteTapped(key) | unchanged | none |
 * | the run, the tap's call under way | StopTapped(call) | unchanged (the call ends with its StepEnded; the ledger stays written, so nothing writes it again without a tap) | StopCall(call) |
 * | otherwise (no call under way, another run's call, or an earlier call of the run) | StopTapped(call) | unchanged | none |
 * | the run (current or not) | RetryAsked (its call failed or was stopped) | Write offered again: a tap grants a new call, never the grants on their own; the ledger unchanged | none |
 * | the run, not orphaned | CardShown(card or none) | the ledger written, under the run's key, unless written already; the card only if the ledger has none; a kept tap dropped | none |
 * | the run, not orphaned | CardWritten(card) | the ledger's card replaced (the ledger written already, when the call was granted); a kept tap dropped | none |
 * | the run, orphaned (a report of another insight track settled while it ran) | CardShown, CardWritten, DecidedWithoutCall | unchanged | none |
 * | the run, not orphaned | DecidedWithoutCall(nothing to narrate?) | the ledger decided (not written), under the run's key, unless written already; would-write with the run's settings only when for nothing to narrate, the key's first decision, by the current run, and the grant's check would have written; kept, under the same settings, by a run of the key asking again; else none; paused (#203) likewise, with the first action expired in place of made; a kept tap dropped | none |
 * | an old run | StepRequested, InsightAsked, StepEnded, RunEnded, CardShown, CardWritten, DecidedWithoutCall, RetryAsked | unchanged | none |
 * | any | NextChanged(next) | next = it; a preparation of another track cancelled | CancelPreparation (if one is cancelled) |
 * | the preparation | StepRequested(step) | the step waiting (granted once the preparation may run; a model call never) | none |
 * | the preparation | PreparationEnded | none in progress; its track prepared | none |
 * | an old preparation | StepRequested, PreparationEnded | unchanged | none |
 * | a preparation, current or old | StepEnded | unchanged | none |
 * | any | NetworkChanged(online) | online = it; one more return when it was offline and is online | none |
 * | the run | SourcesGathered(gathered) | what its sources ended with kept | none |
 * | an old run | SourcesGathered | unchanged | none |
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
            is CoordinatorEvent.PauseAutoWriteChanged -> state = state.copy(pauseAfter = event.after)
            else -> if (state.acknowledged) onAcknowledged(event)
        }
        if (state.acknowledged) {
            askAgain()
            grants()
            prepare()
        }
        return effects.toList()
    }

    private fun onAcknowledged(event: CoordinatorEvent) {
        when (event) {
            is CoordinatorEvent.Reported -> reported(event)
            is CoordinatorEvent.Settled -> settled(event.report)
            CoordinatorEvent.RebuildRequested -> rebuild()
            is CoordinatorEvent.RebuildFinished -> finished(event)
            is CoordinatorEvent.WriteTapped -> tapped(event.key)
            is CoordinatorEvent.StopTapped -> stopTapped(event.call)
            is CoordinatorEvent.NextChanged -> nextChanged(event.next)
            is CoordinatorEvent.PreparationEnded -> preparationEnded(event.prep)
            is CoordinatorEvent.NetworkChanged -> network(event.online)
            else -> fromRun(event)
        }
    }

    // What a run reports.
    private fun fromRun(event: CoordinatorEvent) {
        when (event) {
            is CoordinatorEvent.StepRequested -> requested(event.run, event.step)
            is CoordinatorEvent.InsightAsked -> {
                ofRun(event.run) { it.copy(insight = event.writesOn, allowModel = event.allowModel) }
            }
            is CoordinatorEvent.RetryAsked -> ofRun(event.run) { it.copy(offered = true) }
            is CoordinatorEvent.StepEnded -> stepEnded(event.run, event.step)
            is CoordinatorEvent.RunEnded -> runEnded(event.run)
            is CoordinatorEvent.CardShown -> record(event.run, written = true, card = event.card, replaces = false)
            is CoordinatorEvent.CardWritten -> record(event.run, written = true, card = event.card, replaces = true)
            is CoordinatorEvent.DecidedWithoutCall -> {
                record(event.run, written = false, nothingToNarrate = event.nothingToNarrate)
            }
            is CoordinatorEvent.SourcesGathered -> ofRun(event.run) { it.copy(gathered = event.gathered) }
            else -> Unit
        }
    }

    // The first action stays: only a new process, with a new coordinator, starts without it (#190).
    // The count of track changes away starts again; a first action expired stays so (#203).
    private fun started() {
        state = state.copy(started = state.started + 1, restarting = false, away = 0)
    }

    // The user acted: the first action made, any expiry of it undone, and the count away reset.
    private fun act() {
        state = state.copy(acted = true, expired = false, away = 0)
    }

    // A track change reported while no activity is started (#203): one more away, and past the
    // count *Saver* names, the first action, if made, expires. A tap kept
    // for the run (#192) goes with it: it was made before, and must not pay after.
    private fun counted() {
        val away = state.away + 1
        val expires = state.acted && state.pauseAfter?.let { away > it } == true
        state = state.copy(away = away, acted = state.acted && !expires, expired = state.expired || expires)
        if (expires) state = state.copy(run = state.run?.copy(keepsTap = false, tapped = false))
    }

    // A return of the network, from offline to online, is counted (#192).
    private fun network(online: Boolean) {
        val returned = online && !state.online
        state = state.copy(online = online, returns = state.returns + if (returned) 1 else 0)
    }

    private fun stopped(changingConfigurations: Boolean) {
        val left = (state.started - 1).coerceAtLeast(0)
        state = state.copy(started = left, restarting = left == 0 && changingConfigurations)
    }

    private fun reported(event: CoordinatorEvent.Reported) {
        // Another track playing: its run comes first, and the next item will change too.
        val before = state.latest?.nowPlaying
        val another = before == null || !sameInsightTrack(before, event.nowPlaying)
        if (another) cancelPreparation()
        val seq = (state.latest?.seq ?: 0) + 1
        state = state.copy(latest = CoordinatorState.Report(seq, event.key, event.nowPlaying, event.cause))
        when {
            event.cause == ChangeCause.USER_ACTION -> act()
            another && !state.visible -> counted()
        }
        effects += CoordinatorEffect.StartTimer(seq)
    }

    // A settled report of another insight track empties the ledger; one of its track updates the
    // header it shows.
    private fun settled(seq: Long) {
        val latest = state.latest?.takeIf { it.seq == seq } ?: return
        val ledger = state.ledger
        val kept =
            when {
                ledger == null -> null
                sameInsightTrack(ledger.track, latest.nowPlaying) -> ledger.copy(shown = latest.nowPlaying)
                else -> null
            }
        val run =
            state.run?.let {
                if (sameInsightTrack(
                        it.nowPlaying,
                        latest.nowPlaying,
                    )
                ) {
                    it
                } else {
                    it.copy(orphaned = true)
                }
            }
        state = state.copy(latest = latest.copy(settled = true), ledger = kept, run = run)
        if (startsNow(latest)) startRun(latest)
    }

    // Whether the settled [latest] report starts a run now: not without a live pipeline or with a
    // rebuild pending (the swap starts it), and not when the run holds it.
    private fun startsNow(latest: CoordinatorState.Report): Boolean =
        state.live && state.building == null && state.run?.let { state.holds(it, latest) } != true

    private fun startRun(report: CoordinatorState.Report) {
        state.run?.let { effects += CoordinatorEffect.CancelRun(it.id) }
        begin(report)
    }

    // A run of [report]; of the ledger's key and report for its written track, so the card paid
    // for is found under its own key.
    // A preparation in progress is cancelled: the new run's lookups come first, and the
    // preparation starts again, from what is kept, once they are over.
    private fun begin(report: CoordinatorState.Report) {
        cancelPreparation()
        val id = RunId(state.runs + 1)
        val written = state.written(report.nowPlaying)
        val key = written?.key ?: report.key
        val playing = written?.track ?: report.nowPlaying
        state = state.copy(runs = id.value, run = CoordinatorState.Run(id, key, playing))
        effects += CoordinatorEffect.StartRun(id, playing)
    }

    // Back online (#192): the current run is replaced by one of its key and report that asks again
    // the sources it found unreachable, and keeps what the others found.
    private fun askAgain() {
        if (!state.asksAgain) return
        val run = state.current ?: return
        cancelPreparation()
        effects += CoordinatorEffect.CancelRun(run.id)
        val id = RunId(state.runs + 1)
        // A tap shown for the run replaced, or one before it, which offered Write, may still be on
        // its way, or be kept already: it is carried over.
        val replacement =
            CoordinatorState.Run(
                id,
                run.key,
                run.nowPlaying,
                again = true,
                keepsTap = run.offered || run.keepsTap,
                tapped = run.keepsTap && run.tapped,
            )
        state = state.copy(runs = id.value, run = replacement)
        effects += CoordinatorEffect.StartRun(id, run.nowPlaying, again = run.gathered)
    }

    private fun rebuild() {
        // The preparation runs on the pipeline being replaced: the new one prepares again, from
        // what is kept in memory.
        cancelPreparation()
        state = state.copy(prepared = null)
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
    // call costs is not lost. The latest report runs on it, as `begin` says; the ledger stands.
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

    private fun tapped(key: RunKey) {
        if (!state.allowed || keepsTap(key)) return
        // Only the run Write is offered for, while it may be granted.
        val run = state.current?.takeIf { state.writeKey == key } ?: return
        state =
            state.copy(
                run = run.copy(offered = false, calling = true, calls = run.calls + 1, lapsed = false),
                ledger = entry(run, written = true),
            )
        act()
        effects += CoordinatorEffect.Grant(run.id, RunStep.MODEL_CALL)
        effects += CoordinatorEffect.Acted
    }

    // A tap shown for the run an ask-again replaced (#192): kept until its replacement, of the
    // same key, decides, and taken now (the first action). Whether it was kept.
    private fun keepsTap(key: RunKey): Boolean {
        val keeping = state.current?.takeIf { it.keepsTap && !it.tapped && it.key == key } ?: return false
        state = state.copy(run = keeping.copy(tapped = true))
        act()
        effects += CoordinatorEffect.Acted
        return true
    }

    // Only the call Stop was shown for, while it is under way. Stopping spends nothing, so it
    // needs neither currency nor requests allowed, and it is no action (D5, D15).
    private fun stopTapped(call: CallId) {
        if (state.stopCall != call) return
        effects += CoordinatorEffect.StopCall(call)
    }

    private fun callEnded(id: RunId) {
        if (state.run?.id != id) return
        ofRun(id) { it.copy(calling = false) }
        afterCall()
    }

    // A run that ended waits for nothing: no call, no tap. Nothing then waits for it.
    private fun runEnded(id: RunId) {
        if (state.run?.id != id) return
        ofRun(id) {
            it.copy(
                calling = false,
                offered = false,
                asked = emptySet(),
                insight = null,
                labelled = true,
                keepsTap = false,
                tapped = false,
            )
        }
        afterCall()
    }

    // A step asked for by the run, or by the preparation.
    private fun requested(
        id: RunId,
        step: RunStep,
    ) {
        val preparation = state.preparation
        if (preparation?.id == id) {
            state = state.copy(preparation = preparation.copy(asked = preparation.asked + step))
        } else {
            ofRun(id) { it.copy(asked = it.asked + step) }
        }
    }

    private fun stepEnded(
        id: RunId,
        step: RunStep,
    ) {
        when (step) {
            RunStep.MODEL_CALL -> callEnded(id)
            RunStep.LABELS -> ofRun(id) { it.copy(labelled = true) }
            RunStep.LOOKUPS -> Unit
        }
    }

    // The next track changed: a preparation of another one is cancelled.
    private fun nextChanged(next: NowPlaying?) {
        if (state.preparation?.nowPlaying != next) cancelPreparation()
        state = state.copy(next = next)
    }

    private fun preparationEnded(id: RunId) {
        val preparation = state.preparation?.takeIf { it.id == id } ?: return
        state = state.copy(preparation = null, prepared = preparation.nowPlaying)
    }

    private fun cancelPreparation() {
        val preparation = state.preparation ?: return
        effects += CoordinatorEffect.CancelPreparation(preparation.id)
        state = state.copy(preparation = null)
    }

    // The next track's preparation (D13), once the current run's requests are over: started when
    // there is a next track not yet prepared and not the one playing, else granted each step it
    // waits for, never a model call.
    private fun prepare() {
        if (!state.prepares) return
        val preparation = state.preparation
        if (preparation != null) {
            preparation.asked.filter { it != RunStep.MODEL_CALL }.forEach {
                effects += CoordinatorEffect.Grant(preparation.id, it)
            }
            state = state.copy(preparation = preparation.copy(asked = emptySet()))
        } else {
            state.next?.takeIf(::wanted)?.let { next ->
                val id = RunId(state.runs + 1)
                state = state.copy(runs = id.value, preparation = CoordinatorState.Preparation(id, next))
                effects += CoordinatorEffect.StartPreparation(id, next)
            }
        }
    }

    // Whether [next] is to be prepared: not prepared on the live pipeline already, and not the
    // track playing.
    private fun wanted(next: NowPlaying): Boolean {
        val playing = state.latest?.nowPlaying
        return next != state.prepared && (playing == null || !sameInsightTrack(playing, next))
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

    // A run's report into the ledger: [written] or not, with [card] if it has one. A card written
    // by a call always [replaces] the entry's; one served from the cache only fills an empty slot,
    // so the newest paid card survives a later cache hit of an older one. Ignored from an orphaned
    // run: a report of another insight track settled while it ran, and it must not bring its
    // track back, whatever has been reported since.
    private fun record(
        id: RunId,
        written: Boolean,
        card: Card? = null,
        replaces: Boolean = false,
        nothingToNarrate: WriteSettings? = null,
    ) {
        val run = state.run?.takeIf { it.id == id && !it.orphaned } ?: return
        val before = state.ledger?.takeIf { sameInsightTrack(it.track, run.nowPlaying) }
        val recorded = entry(run, written)
        val kept = if (card != null && (replaces || recorded.card == null)) card else recorded.card
        val would = nothingToNarrate?.let { wouldWrite(run, before, recorded, it) }
        val paused = nothingToNarrate?.let { pausedFor(run, before, recorded, it) }
        // The run has decided: a tap kept for it has nothing left to answer.
        state =
            state.copy(
                ledger = recorded.copy(card = kept, wouldWrite = would, paused = paused),
                run = run.copy(keepsTap = false, tapped = false),
            )
    }

    // What a decision with no call for nothing to narrate, under [settings], leaves in the ledger
    // (#192): the first decision for its key, made by the current run, sets the settings when the
    // grant's own check would have written without a tap had there been evidence; a run of the
    // key asking again keeps them, under the same settings; any other decision leaves none.
    private fun wouldWrite(
        run: CoordinatorState.Run,
        before: CoordinatorState.Ledger?,
        recorded: CoordinatorState.Ledger,
        settings: WriteSettings,
    ): CoordinatorState.WouldWrite? {
        val cause = state.latest?.cause
        val first = before == null || before.key != run.key
        val writes = cause != null && cause in settings.writesOn && state.acted && state.current?.id == run.id
        val unreachable = run.gathered?.unreachable.orEmpty()
        return when {
            recorded.written -> null
            first -> CoordinatorState.WouldWrite(settings, unreachable).takeIf { writes }
            else -> before?.wouldWrite?.takeIf { run.again && it.settings == settings }
        }
    }

    // What a decision with no call for nothing to narrate, under [settings], leaves for the line
    // saying why Write is offered (#203): as [wouldWrite], with the first action expired in place
    // of made. Never a write.
    private fun pausedFor(
        run: CoordinatorState.Run,
        before: CoordinatorState.Ledger?,
        recorded: CoordinatorState.Ledger,
        settings: WriteSettings,
    ): WriteSettings? {
        val cause = state.latest?.cause
        val first = before == null || before.key != run.key
        val blocked = cause != null && cause in settings.writesOn && state.expired && state.current?.id == run.id
        return when {
            recorded.written -> null
            first -> settings.takeIf { blocked }
            else -> before?.paused?.takeIf { run.again && it == settings }
        }
    }

    // The ledger after [run] decided, [written] or not: an entry written stays as it is, under
    // the key its card was written under; else it is the run's.
    private fun entry(
        run: CoordinatorState.Run,
        written: Boolean,
    ): CoordinatorState.Ledger {
        val standing = state.written(run.nowPlaying)
        if (standing != null) return standing
        val shown = state.ledger?.takeIf { sameInsightTrack(it.track, run.nowPlaying) }?.shown ?: run.nowPlaying
        return CoordinatorState.Ledger(run.nowPlaying, run.key, written, shown)
    }

    // What the current run may do now, decided from the whole state at once.
    private fun grants() {
        val run = state.current ?: return
        if (!state.allowed) return
        run.asked.forEach { effects += CoordinatorEffect.Grant(run.id, it) }
        val lookedUp = if (RunStep.LOOKUPS in run.asked) state.returns else run.lookedUp
        var next = run.copy(asked = emptySet(), lookedUp = lookedUp)
        val writesOn = run.insight
        if (writesOn != null) {
            val write = writes(run, writesOn, state.acted)
            val lapsed = !write && lapsed(run, writesOn)
            // For a written track, the question comes only when its card is not found (a rebuild
            // changed the card's key): Write is offered beside the card paid for, to rewrite it.
            next =
                next.copy(
                    insight = null,
                    offered = !write,
                    calling = write,
                    calls = next.calls + if (write) 1 else 0,
                    keepsTap = false,
                    tapped = false,
                    lapsed = lapsed,
                )
            val decided = entry(run, write)
            val settings = WriteSettings(writesOn, run.allowModel)
            state = state.copy(ledger = if (lapsed) decided.copy(paused = settings) else decided)
            effects += CoordinatorEffect.Decide(run.id, write)
        }
        state = state.copy(run = next)
    }

    // Whether Write is offered for [run] only because the first action expired (#203): it would
    // have written had the first action stood, or it asks again for a key whose decision was
    // paused under the same settings, the latest report's cause still one they write on. Read
    // under the run's own settings, so a mode changed since does not show it.
    private fun lapsed(
        run: CoordinatorState.Run,
        writesOn: Set<ChangeCause>,
    ): Boolean {
        if (!state.expired) return false
        val ledger = state.ledger?.takeIf { sameInsightTrack(it.track, run.nowPlaying) }
        val cause = state.latest?.cause
        val carried =
            run.again &&
                ledger?.key == run.key &&
                ledger.paused == WriteSettings(writesOn, run.allowModel) &&
                cause in writesOn
        return carried || writes(run, writesOn, acted = true)
    }

    // The answer to [run]'s question whether to write, under the causes its settings write on,
    // with the first action made or not ([acted]).
    private fun writes(
        run: CoordinatorState.Run,
        writesOn: Set<ChangeCause>,
        acted: Boolean,
    ): Boolean {
        // A tap kept for it (#192) answers as a tap: one call, the ledger written.
        if (run.tapped) return true
        val ledger = state.ledger?.takeIf { sameInsightTrack(it.track, run.nowPlaying) }
        val written = ledger?.written == true
        // The cause of the latest report, which the run, being current, holds: the latest word
        // on how the user came to this track (a skip back to it during its run included).
        val cause = state.latest?.cause
        val writes = cause != null && cause in writesOn && acted && !written
        // A key the ledger decided: a rebuild's run of it is not a track change (D5). A run that
        // asks again (#192) writes only as the decision for nothing to narrate would have, under
        // the same settings, once a source unreachable when it was made has returned lines.
        val decided = ledger != null && ledger.key == run.key
        val would = ledger?.wouldWrite
        val asWouldHave =
            run.again && would?.settings == WriteSettings(writesOn, run.allowModel) && would.brought(run.gathered)
        return writes && (!decided || asWouldHave)
    }
}
