package app.sleevesight.core.pipeline

import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

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

    /** A step that was granted ended: done, failed or cancelled. Reported from a `finally`. */
    fun ended(step: RunStep)

    /** The insight was decided without a model call: nothing to write from, no AI service set up, or insights off. */
    fun decidedWithoutCall()

    /** The insight was served from the synthesis cache: written, with no call. */
    fun cardShown() = decidedWithoutCall()

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
