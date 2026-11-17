package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.core.model.NowPlaying
import kotlin.math.min

// Home split in two from [SPLIT_WIDTH] wide (#1097): the cover and the playback position on the left,
// in a pane of their own that does not scroll, and on the right the header over what scrolls, the
// player row first, as in portrait.

/**
 * The cover's side of Home split in two (#1097): what the track on Home puts there ([CoverInPane]),
 * the cover and the playback position, drawn by Home's frame ([CoverSlot]) beside the side that
 * scrolls. Empty while no track shows (nothing playing, an ad: one column, as in portrait).
 */
@Stable
internal class CoverPane {
    /** What the pane shows; null while nothing is put there. */
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    internal fun show(content: (@Composable () -> Unit)?) {
        this.content = content
    }
}

/** The cover's side while Home is split in two (#1097); null in portrait, where the cover is inline. */
internal val LocalCoverPane = staticCompositionLocalOf<CoverPane?> { null }

/**
 * Puts [content] in [pane] instead of in place, with the composition locals it would have had here
 * (the art's colours, a cleared track's notes), for as long as this stays composed. Set while it is
 * composed, not after, so the pane, composed after what scrolls, shows it in the same frame.
 */
@Composable
internal fun CoverInPane(
    pane: CoverPane,
    content: @Composable () -> Unit,
) {
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val latest by rememberUpdatedState(content)
    val shown: @Composable () -> Unit = remember(pane) { { CompositionLocalProvider(locals) { latest() } } }
    // The same lambda once set: no change, so nothing recomposes for it.
    pane.show(shown)
    DisposableEffect(pane, shown) {
        onDispose { if (pane.content === shown) pane.show(null) }
    }
}

/**
 * The cover's side as Home's frame draws it: what [pane] holds, inside the pane's margins, read by a
 * screen reader as one group after the player row and before the title ([COVER_TRAVERSAL]); nothing,
 * no margins either, while it holds nothing.
 */
@Composable
internal fun CoverSlot(pane: CoverPane) {
    val content = pane.content ?: return
    Box(
        Modifier
            .testTag(COVER_PANE_TAG)
            .semantics {
                isTraversalGroup = true
                traversalIndex = COVER_TRAVERSAL
            }.padding(horizontal = PANE_MARGIN_H, vertical = PANE_MARGIN_V),
    ) {
        content()
    }
}

/**
 * The player row of [playing] and, under it, the art with the playback position ([ArtAndProgress]),
 * turned over as [turn] says, from [state], as long as [header] says. Home split in two
 * ([LocalCoverPane], #1097), the art and the position go to the cover's side, the art as large as it
 * fits there, and the player row stays first, read before them as in portrait.
 */
@Composable
internal fun PlayerAndCover(
    state: NowPlayingState,
    playing: NowPlaying,
    header: NowPlaying,
    turn: CoverTurn,
    actions: NowPlayingActions,
) {
    val pane = LocalCoverPane.current
    val duration = header.duration
    if (pane == null) {
        PlayerRow(playing.player, state.playback, actions)
        ArtAndProgress(state.art, state.playback, duration, turn)
    } else {
        PlayerRow(playing.player, state.playback, actions, Modifier.semantics { traversalIndex = PLAYER_ROW_TRAVERSAL })
        CoverInPane(pane) { ArtAndProgress(state.art, state.playback, duration, turn, fitHeight = true) }
    }
}

/**
 * The art, square, as large as the space given fits it with [progress] under it, [gap] apart: as wide
 * as that space at most, and as tall as it less the position's height at the art's own width (a
 * narrower art can put the position's times on two lines); the position as wide as the art.
 */
@Composable
internal fun FittedArt(
    cover: @Composable (Modifier) -> Unit,
    progress: (@Composable (Modifier) -> Unit)?,
    gap: Dp,
) {
    Layout(
        contents = listOf({ cover(Modifier.fillMaxSize()) }, { progress?.invoke(Modifier.fillMaxWidth()) }),
    ) { (covers, positions), constraints ->
        val space = gap.roundToPx()
        val position = positions.firstOrNull()
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        // The largest side whose art and position under it fit the height. The position never grows as
        // the side grows (its times go side by side once they fit), but it can shrink by steps, so the
        // fitting sides are not one range: from the widest, the side steps down to what the height
        // leaves under its own position until it fits, which is the largest side that fits.
        val under = { side: Int -> position?.let { space + it.maxIntrinsicHeight(side) } ?: 0 }
        var side = min(width, height)
        var steps = 0
        while (steps++ < MAX_FIT_STEPS) {
            val next = min(side, height - under(side)).coerceAtLeast(0)
            if (next == side) break
            side = next
        }
        val art = covers.first().measure(Constraints.fixed(side, side))
        val bar = position?.measure(Constraints(minWidth = side, maxWidth = side))
        layout(side, side + (bar?.let { space + it.height } ?: 0)) {
            art.placeRelative(0, 0)
            bar?.placeRelative(0, side + space)
        }
    }
}

/** At most this many steps to the cover's side ([FittedArt]); one or two do on Home. */
private const val MAX_FIT_STEPS = 32

/** The width from which Home splits in two (#1097): a phone held sideways, a tablet, an unfolded foldable. */
internal val SPLIT_WIDTH: Dp = 600.dp

/** How wide Home's one column is at most while the split has no cover to show (#1097: nothing playing). */
internal val ONE_COLUMN_MAX_WIDTH: Dp = 600.dp

/** The cover pane's margins, beside and above and below the cover. */
private val PANE_MARGIN_H = 16.dp
private val PANE_MARGIN_V = 12.dp

/**
 * The screen reader's order on Home split in two (#1097), as in portrait: the header, the player row,
 * the cover and the position, then the rest (at 0, by where it is).
 */
internal const val HEADER_TRAVERSAL: Float = -3f
internal const val PLAYER_ROW_TRAVERSAL: Float = -2f
internal const val COVER_TRAVERSAL: Float = -1f

/** The test tag of the cover's side of Home split in two (#1097). */
const val COVER_PANE_TAG: String = "cover-pane"
