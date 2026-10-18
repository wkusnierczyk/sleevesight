package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The steps of a run that send requests, each started only once the run's [RunControl] grants it. */
enum class RunStep {
    /**
     * The lookups of the sources the cache missed, with MusicBrainz's resolution alongside, and
     * the requests the adapters make of their own once it lands (Wikipedia's checks).
     */
    LOOKUPS,

    /** MusicBrainz's genre labels (#84): a request for each artist its result names. */
    LABELS,

    /** The model call that writes the insight: a paid call on the user's key. */
    MODEL_CALL,
}

/**
 * Who decides when a run's requests start, and whether its insight is written: the run asks and
 * waits, and reports back when a step ends. In the app this is its coordinator (`:app`, #51); the
 * CLI and [Pipeline.follow] use controls that grant at once. A run cancelled while it waits never
 * starts the step.
 */
@Suppress("TooManyFunctions") // One for each step a run asks for or reports: the hook's whole contract.
interface RunControl {
    /** Returns once [step] ([RunStep.LOOKUPS] or [RunStep.LABELS]) may start. */
    suspend fun await(step: RunStep)

    /**
     * Asked once the evidence is in and a model call would write the insight (insights on, an AI
     * service set up, evidence, no card kept): true starts the call at once; false offers Write.
     * [settings] are the run's.
     */
    suspend fun decide(settings: Settings): Boolean

    /** Whether a run that offers Write waits for a tap ([awaitTap]); if not, it ends offering it. */
    val waitsForTap: Boolean

    /** Returns once a tap on Write starts the model call. */
    suspend fun awaitTap()

    /**
     * Asked after a model call that failed or was stopped ([InsightState.Failed],
     * [InsightState.Stopped]): Write is offered again, and true once a tap on it starts another
     * call, a new paid call; false ends the run with none. Never true without a tap. By default
     * false: the CLI and [Pipeline.follow] do not offer Write again.
     */
    suspend fun awaitRetry(): Boolean = false

    /**
     * Returns when the user stops the model call under way: the call is cut off, nothing is kept,
     * and the insight shows as [InsightState.Stopped]; once the reply has ended (done or failed),
     * a Stop changes nothing. By default never.
     */
    suspend fun awaitStop() {
        awaitCancellation()
    }

    /** A step that was granted ended: done, failed or cancelled. Reported from a `finally`. */
    fun ended(step: RunStep)

    /**
     * Every source has landed, from the cache or a lookup: what they ended with, reported once,
     * before the evidence is assembled. The app's coordinator keeps it, so that a run started
     * with it asks again only the sources that were unreachable (#192). By default nothing.
     */
    fun gathered(gathered: Gathered) = Unit

    /**
     * The insight was decided with no model call and nothing from the cache, for lack of evidence:
     * the evidence has nothing to narrate (`EvidenceSet.nothingToNarrate`), which new evidence may
     * change (back online, #192).
     */
    fun decidedWithoutCall()

    /**
     * The insight was decided with no model call and nothing from the cache because it cannot be
     * written now: insights off, or no AI service set up. New evidence does not change that. By
     * default as [decidedWithoutCall].
     */
    fun cannotWrite() = decidedWithoutCall()

    /**
     * The insight was served from the synthesis cache, with no call: a reply paid for once, so
     * written. [card] is the card from [evidence], or null when the reply kept was `NO_EVIDENCE`.
     */
    fun cardShown(
        card: InsightState.Written?,
        evidence: EvidenceSet,
    ) = decidedWithoutCall()

    /**
     * A model call wrote the insight, [card] from [evidence], checked and shown (reported before the
     * call's end). A call that fails, is declined or finds no evidence writes no card.
     */
    fun cardWritten(
        card: InsightState.Written,
        evidence: EvidenceSet,
    ) = Unit

    /** Grants every step at once and writes when [write]: [Pipeline.run] for the CLI. */
    class Unattended(
        private val write: Boolean,
    ) : RunControl {
        override suspend fun await(step: RunStep) = Unit

        override suspend fun decide(settings: Settings): Boolean = write

        override val waitsForTap: Boolean = false

        override suspend fun awaitTap() = Unit

        override fun ended(step: RunStep) = Unit

        override fun decidedWithoutCall() = Unit
    }
}

/**
 * [block], unless the user stops it first ([RunControl.awaitStop]): then it is cancelled, and the
 * result is null. A Stop that came before is seen before [block] starts, so it sends nothing; once
 * [block] has returned, a Stop changes nothing.
 */
internal suspend fun <T : Any> RunControl.unlessStopped(block: suspend () -> T): T? {
    var result: T? = null
    coroutineScope {
        val work = launch(start = CoroutineStart.LAZY) { result = block() }
        val stop =
            launch(start = CoroutineStart.UNDISPATCHED) {
                awaitStop()
                work.cancel()
            }
        work.join()
        stop.cancel()
    }
    return result
}

/** [Pipeline.follow]'s control: every step at once, the write as decided at the run's start, then taps. */
internal class Followed(
    private val writes: Boolean,
    private val taps: StateFlow<Long>,
) : RunControl {
    private val tapsAtStart = taps.value

    override suspend fun await(step: RunStep) = Unit

    override suspend fun decide(settings: Settings): Boolean = writes

    override val waitsForTap: Boolean = true

    override suspend fun awaitTap() {
        taps.first { it > tapsAtStart }
    }

    override fun ended(step: RunStep) = Unit

    override fun decidedWithoutCall() = Unit
}
