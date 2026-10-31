package app.sleevesight.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// The chips as the genre chips are drawn (#439, #558): Home's genre chips, *Sources*' chip (#623)
// and the letters of its table (#662, compact), and the settings' section chips (#649).

/**
 * A chip as the genre chips are drawn (#439, #558), which *Sources*' chip on Home (#623) and the
 * settings' section chips (#649) share: [content] in a row on [fill], in the chips' rounded shape
 * and padding ([CHIP_PADDING] at each side), with a 48 dp target, one node for a screen reader;
 * with [outline], a hairline of that colour around it. [modifier] goes outside the target (a tag, a
 * focus requester, a width); [action] inside the shape, so a press shows within it (a click, and
 * what a screen reader says of it). The items of [content] are centred in the row, unless they
 * align otherwise (by their baseline).
 */
@Composable
internal fun ChipFrame(
    fill: Color,
    modifier: Modifier = Modifier,
    outline: Color? = null,
    action: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) = CompactChipFrame(
    fill,
    modifier.minimumInteractiveComponentSize(),
    outline,
    Modifier.semantics(mergeDescendants = true) {}.then(action),
    content,
)

/**
 * A [ChipFrame] without its 48 dp target laid out (*Sources*' table, #662): the chip takes only its
 * own room, so rows of chips sit close. A click on it still answers within 48 dp, as Compose
 * extends a small control's touch area. It is not one node for a screen reader of itself: [action]
 * says what it is (a [ChipFrame] merges it; a chip that is no control, and clears what it says,
 * leaves no empty node for a screen reader to stop on).
 */
@Composable
internal fun CompactChipFrame(
    fill: Color,
    modifier: Modifier = Modifier,
    outline: Color? = null,
    action: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = CHIP_SHAPE
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(shape)
                .background(fill, shape)
                .let { chip -> outline?.let { chip.border(1.dp, it, shape) } ?: chip }
                .then(action)
                .padding(horizontal = CHIP_PADDING, vertical = CHIP_PADDING_V),
        content = content,
    )
}

/** The chips' corners' radius, and their rounded shape ([ChipFrame]). */
internal val CHIP_RADIUS = 16.dp
internal val CHIP_SHAPE = RoundedCornerShape(CHIP_RADIUS)

/** The space between a chip's edge and its content, at each side ([ChipFrame]). */
internal val CHIP_PADDING = 12.dp

/** The space between a chip's edge and its content, above and below ([ChipFrame]). */
internal val CHIP_PADDING_V = 6.dp

/**
 * A genre chip's fill and text colour, on Home and in the panel's table: a light fill in the text's
 * colour; [marked], the text's colour, with the box's colour for the text.
 */
@Composable
internal fun chipColours(marked: Boolean) =
    MaterialTheme.colorScheme.let { colours ->
        if (marked) {
            colours.onSurface to colours.surfaceContainer
        } else {
            colours.onSurface.copy(alpha = FILL) to colours.onSurface
        }
    }

private const val FILL = 0.08f
