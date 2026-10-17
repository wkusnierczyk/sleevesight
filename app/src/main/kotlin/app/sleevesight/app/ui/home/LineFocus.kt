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
 * line, even if its source's lines were hidden, scrolls to it and flashes it ([cite]).
 */
@Stable
internal class LineFocus(
    private val evidence: EvidenceSet?,
) {
    /** The evidence sources whose lines are hidden. */
    var hidden: Set<SourceId> by mutableStateOf(emptySet())
        private set

    /** The citation tapped last, until its line has flashed. */
    var flash: Flash? by mutableStateOf(null)
        private set

    private var taps = 0

    /** Each line's way into view. */
    val requesters: Map<String, BringIntoViewRequester> =
        evidence?.lines.orEmpty().associate { it.id to BringIntoViewRequester() }

    fun toggle(chip: SourceChip) {
        hidden = if (chip.filters.any { it in hidden }) hidden - chip.filters else hidden + chip.filters
    }

    fun cite(id: String) {
        evidence?.get(id)?.let { hidden = hidden - it.source }
        flash = Flash(id, ++taps)
    }

    fun flashed(done: Flash) {
        if (flash == done) flash = null
    }
}

/** A citation tapped: its line's id, and a count, so the same citation tapped again flashes again. */
internal data class Flash(
    val id: String,
    val count: Int,
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
        // The line may have been hidden until this tap: it is laid out in the next frame.
        withFrameNanos { }
        focus.requesters[flash.id]?.bringIntoView()
        delay(FLASH)
        focus.flashed(flash)
    }
    return focus
}

private val FLASH = 1300.milliseconds
