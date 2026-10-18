package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** A control whose grants and decision the test gives, recording what the run asked and reported. */
internal class Granted(
    private val write: Boolean = true,
) : RunControl {
    val grants = RunStep.entries.associateWith { CompletableDeferred<Unit>() }
    val asked = mutableListOf<RunStep>()
    val ended = mutableListOf<RunStep>()
    var decisions = 0
    var withoutCall = 0
    var cardsShown = 0
    val cardsWritten = mutableListOf<String>()

    override suspend fun await(step: RunStep) {
        asked += step
        grants.getValue(step).await()
    }

    override suspend fun decide(settings: Settings): Boolean {
        decisions++
        if (write) grants.getValue(RunStep.MODEL_CALL).await()
        return write
    }

    override val waitsForTap: Boolean = true

    override suspend fun awaitTap() = grants.getValue(RunStep.MODEL_CALL).await()

    /** The answers to the run's questions whether to call again, in order; and how many it asked. */
    val retries = Channel<Boolean>(Channel.UNLIMITED)
    var retriesAsked = 0

    override suspend fun awaitRetry(): Boolean {
        retriesAsked++
        return retries.receive()
    }

    /** Completed to stop the call under way; replaced for the next call. */
    var stop = CompletableDeferred<Unit>()

    override suspend fun awaitStop() = stop.await()

    override fun ended(step: RunStep) {
        ended += step
    }

    /** What the run's sources ended with, each time it reported it. */
    val gathered = mutableListOf<Gathered>()

    override fun gathered(gathered: Gathered) {
        this.gathered += gathered
    }

    override fun decidedWithoutCall() {
        withoutCall++
    }

    val cardsShownEmpty = mutableListOf<Unit>()

    override fun cardShown(
        card: InsightState.Written?,
        evidence: EvidenceSet,
    ) {
        cardsShown++
        if (card == null) cardsShownEmpty += Unit
    }

    override fun cardWritten(
        card: InsightState.Written,
        evidence: EvidenceSet,
    ) {
        cardsWritten += card.grounded.text
    }
}
