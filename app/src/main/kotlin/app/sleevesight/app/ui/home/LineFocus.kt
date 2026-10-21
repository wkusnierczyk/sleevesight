package app.sleevesight.app.ui.home

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * line, even if its source's lines were hidden, scrolls to it and flashes it ([cite]); a chip
 * chosen while the list is folded shows its source's lines and scrolls to the first ([reveal]).
 * Both say where in the list the line now is, for the fold to unfold to ([SourcesFold.reach]).
 */
@Stable
internal class LineFocus(
    private val evidence: EvidenceSet?,
) {
    /** The evidence sources whose lines are hidden. */
    var hidden: Set<SourceId> by mutableStateOf(emptySet())
        private set

    /** The line scrolled to last, until it has flashed (or, not flashed, been scrolled to). */
    var flash: Flash? by mutableStateOf(null)
        private set

    private var taps = 0

    /** Each line's way into view. */
    val requesters: Map<String, BringIntoViewRequester> =
        evidence?.lines.orEmpty().associate { it.id to BringIntoViewRequester() }

    fun toggle(chip: SourceChip) {
        hidden = if (chip.filters.any { it in hidden }) hidden - chip.filters else hidden + chip.filters
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
     * Shows [chip]'s lines and scrolls to the first, unflashed; returns its place in the list as
     * it now shows, or null when the chip's sources have no line.
     */
    fun reveal(chip: SourceChip): Int? {
        hidden = hidden - chip.filters
        val first = evidence?.lines?.firstOrNull { it.source in chip.filters } ?: return null
        flash = Flash(first.id, ++taps, flashes = false)
        return indexOf(first.id)
    }

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
 * The [LineFocus] of [evidence], kept while the track's evidence is the same; a flash scrolls to
 * its line, then fades.
 */
@Composable
internal fun rememberLineFocus(evidence: EvidenceSet?): LineFocus {
    val focus = remember(evidence) { LineFocus(evidence) }
    val flash = focus.flash
    LaunchedEffect(focus, flash) {
        if (flash == null) return@LaunchedEffect
        // The line may have been hidden, or folded away, until this tap: it is laid out in the
        // next frame.
        withFrameNanos { }
        focus.requesters[flash.id]?.bringIntoView()
        if (flash.flashes) delay(FLASH)
        focus.flashed(flash)
    }
    return focus
}

private val FLASH = 1300.milliseconds
