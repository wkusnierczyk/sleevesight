package app.sleevesight.app.diagnostics

import app.sleevesight.core.pipeline.PipelineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The pipeline's diagnostics events, kept in memory for the diagnostics screen
 * (`design/architecture.md`, *Diagnostics*): the last [capacity], oldest first, each with when it
 * arrived by [clock]. Nothing is written to disk: the log goes with the process, and leaves the
 * phone only in an export the user asks for.
 *
 * It follows the events from the start of the process, whether or not *Diagnostics* is on, so the
 * screen has them when it is first opened; they take some 100 KB at most.
 */
class DiagnosticsLog(
    private val capacity: Int = CAPACITY,
    private val clock: Clock = Clock.System,
) {
    init {
        require(capacity > 0) { "The log keeps at least one event, was $capacity" }
    }

    private val kept = MutableStateFlow<List<LoggedEvent>>(emptyList())

    /** The events kept, oldest first. */
    val entries: StateFlow<List<LoggedEvent>> = kept.asStateFlow()

    /** Keeps [event], dropping the oldest past [capacity]. */
    fun record(event: PipelineEvent) {
        val entry = LoggedEvent(clock.now(), event)
        kept.update { (it + entry).takeLast(capacity) }
    }

    /** Drops every event kept, for *Clear history* (#547): the steps name the tracks. */
    fun clear() {
        kept.value = emptyList()
    }

    /** Keeps each of [events] as it comes, until cancelled. */
    suspend fun follow(events: Flow<PipelineEvent>) {
        events.collect(::record)
    }

    companion object {
        /**
         * 500: "the last few hundred" (design, *Diagnostics*). A track's run logs some 10 to 20
         * events, so this covers the last 25 tracks or more, the span a problem is looked into over.
         */
        const val CAPACITY: Int = 500
    }
}

/** A diagnostics [event], with when the log received it. */
data class LoggedEvent(
    val at: Instant,
    val event: PipelineEvent,
)
