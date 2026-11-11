package app.sleevesight.app.ui.home

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Which evidence lines show, and the one a citation points at: display only, for one track's
 * [evidence]. A source's chip hides or shows its lines ([toggle]); a citation tapped shows its
 * line, even if its source's lines were hidden, scrolls to it and flashes it ([cite]). A citation,
 * and a chip that shows its lines again, says where in the list the line now is, for the fold to
 * unfold to ([SourcesFold.reach]).
 */
@Stable
internal class LineFocus(
    private val evidence: EvidenceSet?,
    hiddenState: MutableState<Set<SourceId>> = mutableStateOf(emptySet()),
) {
    /**
     * The evidence sources whose lines are hidden: held by [hiddenState], which outlives a focus
     * rebuilt for the same track's lines as they change (the album's coming in, #522).
     */
    var hidden: Set<SourceId> by hiddenState
        private set

    /** The line scrolled to last, until it has flashed (or, not flashed, been scrolled to). */
    var flash: Flash? by mutableStateOf(null)
        private set

    private var taps = 0

    /** Each line's way into view. */
    val requesters: Map<String, BringIntoViewRequester> =
        evidence?.lines.orEmpty().associate { it.id to BringIntoViewRequester() }

    // Each unverified line's way into view (#951), by its entry's key, while its row is composed: its
    // row registers it from an effect ([register]), outside composition. Only read when a scroll
    // runs, so not snapshot state.
    private val unverified = mutableMapOf<String, BringIntoViewRequester>()

    /**
     * Registers [requester] as the way into view of the unverified line of entry [key] (#951); returns
     * what forgets it again, unless another row has since registered its own.
     */
    fun register(
        key: String,
        requester: BringIntoViewRequester,
    ): () -> Unit {
        unverified[key] = requester
        return { if (unverified[key] === requester) unverified.remove(key) }
    }

    /** Scrolls to the unverified line of entry [key], unflashed: a chip that showed it again (#951). */
    fun reveal(key: String) {
        flash = Flash(key, ++taps, flashes = false)
    }

    /** The way into view of the line or unverified entry [id]. */
    fun requester(id: String): BringIntoViewRequester? = requesters[id] ?: unverified[id]

    /**
     * Hides [chip]'s lines, or shows them if any were hidden; on showing them, scrolls to the
     * first, unflashed, and returns its place in the list as it now shows, for the fold to unfold
     * to, else null.
     */
    fun toggle(chip: SourceChip): Int? {
        val showing = chip.filters.any { it in hidden }
        hidden = if (showing) hidden - chip.filters else hidden + chip.filters
        val first = if (showing) evidence?.lines?.firstOrNull { it.source in chip.filters } else null
        return first?.let {
            flash = Flash(it.id, ++taps, flashes = false)
            indexOf(it.id)
        }
    }

    /** Shows every source's lines: the chips hide none. */
    fun showAll() {
        hidden = emptySet()
    }

    /**
     * Shows the line [id], scrolls to it and flashes it; returns its place in the list as it now
     * shows, or null for a line [evidence] does not have.
     */
    fun cite(id: String): Int? {
        evidence?.get(id)?.let { hidden = hidden - it.source }
        flash = Flash(id, ++taps)
        return indexOf(id)
    }

    /**
     * Shows the line [id] again where it now is, scrolled to with no new flash (a citation followed as
     * the lines change under it, #522); returns its place in the list as it now shows, or null for a
     * line [evidence] does not have.
     */
    fun follow(id: String): Int? {
        val line = evidence?.get(id) ?: return null
        hidden = hidden - line.source
        flash = Flash(id, ++taps, flashes = false)
        return indexOf(id)
    }

    /** Where the line [id] is among the lines shown, or null if it is not one of them. */
    fun position(id: String): Int? = indexOf(id)

    /** Where the line [id] is among the lines shown, or null if it is not one of them. */
    private fun indexOf(id: String): Int? =
        evidence
            ?.lines
            .orEmpty()
            .filter { it.source !in hidden }
            .indexOfFirst { it.id == id }
            .takeIf { it >= 0 }

    fun flashed(done: Flash) {
        if (flash == done) flash = null
    }
}

/**
 * A line scrolled to: its id, and a count, so the same citation tapped again flashes again; a
 * citation's line [flashes], a chip's first line is only scrolled to.
 */
internal data class Flash(
    val id: String,
    val count: Int,
    val flashes: Boolean = true,
)

/**
 * The [LineFocus] of [evidence], kept while the evidence is the same, the sources hidden kept while
 * [owner] (the track's identity) is; a flash scrolls to its line, then fades.
 */
@Composable
internal fun rememberLineFocus(
    evidence: EvidenceSet?,
    owner: Any?,
): LineFocus {
    val hidden = remember(owner) { mutableStateOf(emptySet<SourceId>()) }
    val focus = remember(evidence, hidden) { LineFocus(evidence, hidden) }
    val flash = focus.flash
    LaunchedEffect(focus, flash) {
        if (flash == null) return@LaunchedEffect
        // The line may have been hidden, or folded away, until this tap: it is laid out in the
        // next frame.
        withFrameNanos { }
        focus.requester(flash.id)?.bringIntoView()
        if (flash.flashes) delay(FLASH)
        focus.flashed(flash)
    }
    return focus
}

private val FLASH = 1300.milliseconds
