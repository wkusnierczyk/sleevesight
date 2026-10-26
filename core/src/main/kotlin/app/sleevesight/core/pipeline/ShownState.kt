package app.sleevesight.core.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What [Pipeline.state] shows, with *Clear history*'s count (#547): a run started before the last
 * [forget] publishes nothing after it. The state and the count change together, in one atomic
 * update of [published]; [state] then copies the latest, again until it is still the latest after
 * the copy, so a copy that lost a race to a [forget] is overwritten, and a state cleared is never
 * shown again. [beforeCopy] is for the tests, which run a [forget] between the two steps.
 */
internal class ShownState(
    private val beforeCopy: () -> Unit = {},
) {
    // Not a data class, so each update is a new value, told apart by identity.
    private class Published(
        val forgets: Long,
        val state: TrackState?,
    )

    private val published = MutableStateFlow(Published(0, null))
    private val shown = MutableStateFlow<TrackState?>(null)

    /** The state shown. */
    val state: StateFlow<TrackState?> = shown.asStateFlow()

    /** How many times it has been forgotten, for work to note when it starts. */
    val forgets: Long get() = published.value.forgets

    /** Shows [state] if [current] says so and nothing was forgotten since [since]. */
    fun publish(
        state: TrackState?,
        since: Long,
        current: () -> Boolean,
    ) {
        published.update { if (current() && it.forgets == since) Published(since, state) else it }
        copy()
    }

    /** Shows nothing, and keeps what was published before from being shown. */
    fun forget() {
        published.update { Published(it.forgets + 1, null) }
        copy()
    }

    // Copies the latest into [state], until it is still the latest after the copy.
    private fun copy() {
        do {
            val latest = published.value
            beforeCopy()
            shown.value = latest.state
        } while (published.value !== latest)
    }
}
