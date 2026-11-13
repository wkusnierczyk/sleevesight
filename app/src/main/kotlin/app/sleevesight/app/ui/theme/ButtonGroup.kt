package app.sleevesight.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// A connected button group (#637), after Material 3's (m3.material.io/components/button-groups):
// its own `ButtonGroup` and `ToggleButton` come with material3 1.5, still alpha, and the project's
// Compose BOM gives 1.4, so the group is drawn here from the spec's shapes, until #621 replaces it
// with Material's. One group serves two uses: a choice of one value ([ChoiceButtonGroup], a setting
// with a few short choices) and a row of actions ([ActionButtonGroup], plain buttons each enabled
// or not, as a list's paging).

/** A button's least height as drawn, as tall as Home's chips and the insight card's split button. */
internal val GROUP_HEIGHT = 32.dp

/** The least height of the group's row: Android's 48 dp touch target. */
internal val GROUP_TARGET = 48.dp

/** The gap between the buttons, as Material's connected group has it. */
internal val GROUP_GAP = 2.dp

/** The group's outer corners, and every corner of the button chosen: round at [GROUP_HEIGHT]. */
internal val GROUP_OUTER_CORNER = CornerSize(16.dp)

/** The corners where two buttons meet: small, as Material's connected group has them at this size. */
internal val GROUP_INNER_CORNER = CornerSize(4.dp)

/** The room inside a button at each side of its label, as in the chips ([CHIP_PADDING]). */
internal val GROUP_PADDING = CHIP_PADDING

/** The alpha of a button's content that cannot be used, and of its fill, as Material's. */
private const val DISABLED_CONTENT_ALPHA = 0.38f
private const val DISABLED_CONTAINER_ALPHA = 0.12f

/**
 * One of a few values to choose, as a connected button group (#637): [choices], each a value and
 * its label, in one row, the [selected] one filled in the text's colour and round at every
 * corner, with no check mark (#1234): the fill alone marks it, so its label needs no more room
 * than the others'; a tap on another calls [onSelect] with its value. The group follows
 * [selected], so whatever changes the value elsewhere shows here at once. Not [enabled], none
 * can be chosen and all are dimmed.
 *
 * A screen reader reads the group as one group of radio buttons, the chosen one selected, each by
 * its label, after the name of what is chosen ([name], a setting's, so a button reached straight
 * away says what it sets: "Theme: Light"), and then what the value does, where [details] has it
 * (as the dialogs read a choice with its description, #550). The buttons stay in one row at any
 * font size (#821, [groupShares]): where the labels do not fit it on one line, as at a large font
 * size, each wraps in its button, between its words. A value in [disabled] cannot be chosen, and
 * is dimmed, while the others can. A value in [spoken] is read by that in place of its label, for a
 * label a screen reader would not say as meant ("33⅓ rpm" as "33 and a third rpm", #848).
 */
@Suppress("LongParameterList") // What the group shows and says, each a value of its own, and what a tap does.
@Composable
internal fun <T> ChoiceButtonGroup(
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    name: String? = null,
    details: Map<T, String> = emptyMap(),
    enabled: Boolean = true,
    disabled: Set<T> = emptySet(),
    spoken: Map<T, String> = emptyMap(),
) {
    val buttons =
        choices.map { (value, label) ->
            val said = spoken[value] ?: label
            val read = listOfNotNull(name?.let { "$it: $said" } ?: said, details[value]).joinToString(". ")
            GroupButton(
                label,
                enabled && value !in disabled,
                selected = value == selected,
                read = read,
            ) { onSelect(value) }
        }
    ConnectedButtons(buttons, modifier.selectableGroup())
}

/**
 * A row of actions, as a connected button group (#637): each of [actions] a plain button, by its
 * label, which a tap runs while it is enabled; none is ever chosen, so none is filled.
 * A screen reader reads each as a button, a disabled one as disabled. Laid out as
 * [ChoiceButtonGroup] is, across the width it has; with [compact] (#759), only as wide as its
 * labels need, at that side of the width, while side by side they fit, as wide as the widest
 * needs, and across the width as before once they do not.
 */
@Composable
internal fun ActionButtonGroup(
    actions: List<GroupAction>,
    modifier: Modifier = Modifier,
    compact: Alignment.Horizontal? = null,
) {
    val buttons =
        actions.map {
            GroupButton(
                it.label,
                it.enabled,
                selected = null,
                read = null,
                modifier = it.modifier,
                onClick = it.onClick,
            )
        }
    ConnectedButtons(buttons, modifier, compact)
}

/**
 * One action of an [ActionButtonGroup]: its [label], whether it can be used ([enabled]), a
 * [modifier] for its button (a test tag, or a target the focus can be handed to), and what a tap
 * does.
 */
internal class GroupAction(
    val label: String,
    val enabled: Boolean = true,
    val modifier: Modifier = Modifier,
    val onClick: () -> Unit,
)

/**
 * A button of the group: chosen or not ([selected]) in a choice, null in a row of actions; [read],
 * what a screen reader reads for it in place of its label, if not the label alone; [modifier], set
 * on the button before its click, so a focus requester in it reaches the button.
 */
private class GroupButton(
    val label: String,
    val enabled: Boolean,
    val selected: Boolean?,
    val read: String?,
    val modifier: Modifier = Modifier,
    val onClick: () -> Unit,
)

/**
 * How a group's buttons share the row, always one row (#821): all as wide as the widest needs
 * ([EQUAL], as Material's connected groups are), or, where that does not fit, by their labels'
 * widths ([PROPORTIONAL], [groupShares]).
 */
internal enum class GroupArrangement { EQUAL, PROPORTIONAL }

/** How buttons [needing] these widths, [gap] apart, share [room] (all in pixels). */
internal fun groupArrangement(
    needing: List<Int>,
    gap: Int,
    room: Int,
): GroupArrangement =
    if (needing.isEmpty() || needing.max() * needing.size <= room - gap * (needing.size - 1)) {
        GroupArrangement.EQUAL
    } else {
        GroupArrangement.PROPORTIONAL
    }

/** Whether labels [needing] these widths on one line, [gap] apart, fit [room] side by side. */
private fun fits(
    needing: List<Int>,
    gap: Int,
    room: Int,
): Boolean = needing.sum() <= room - gap * (needing.size - 1)

/**
 * Each button's share of [room], [gap] apart (all in pixels), for labels needing [needing] widths on
 * one line and their longest words [words] (#821): equal shares where they fit so; where not, each
 * as wide as its label needs on one line, the room left shared in proportion to that; where the row
 * is too narrow even for that, each its longest word's width, and the room left shared in
 * proportion to what each still needs to take its label on one line, so a label wraps between its
 * words, never inside one; where even the longest words do not fit, in proportion to them.
 */
internal fun groupShares(
    needing: List<Int>,
    words: List<Int>,
    gap: Int,
    room: Int,
): List<Float> {
    val free = room - gap * (needing.size - 1)
    val still = needing.zip(words) { label, word -> label - word }
    return when {
        groupArrangement(needing, gap, room) == GroupArrangement.EQUAL -> needing.map { 1f }
        fits(needing, gap, room) -> needing.map(Int::toFloat)
        // The labels need more than `free` and their words no more, so `still` sums to more than 0.
        fits(words, gap, room) ->
            words.zip(still) { word, more -> word + (free - words.sum()) * more.toFloat() / still.sum() }
        else -> words.map(Int::toFloat)
    }
}

/**
 * [buttons] in one row, sharing it by [groupShares], from the widths their labels, and their
 * longest words, need on one line in the buttons' style (in the app's language, font and font
 * size) and their padding, so the buttons keep their widths whichever is chosen: the chosen one
 * shows no check mark (#1234), and its label needs no more room than when it is not chosen. The
 * buttons are of one height, at least [GROUP_HEIGHT], in a row at least [GROUP_TARGET] high, each
 * answering a tap across 48 dp (Compose widens a smaller target into the room around it). With
 * [compact], buttons that fit as wide as the widest needs ([GroupArrangement.EQUAL]) are that
 * wide, and the row is placed at that side; otherwise they share the width as without it.
 */
@Composable
private fun ConnectedButtons(
    buttons: List<GroupButton>,
    modifier: Modifier,
    compact: Alignment.Horizontal? = null,
) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = buttons.map { it.label }
    val (needing, words) =
        remember(labels, style, density, measurer) { Widths.measured(labels, measurer, style, density) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = with(density) { GROUP_GAP.roundToPx() }
        val bounded = constraints.hasBoundedWidth
        val room = constraints.maxWidth
        val shares = if (bounded) groupShares(needing, words, gap, room) else null
        // Compact only where every button fits as wide as the widest label needs.
        val equal = bounded && groupArrangement(needing, gap, room) == GroupArrangement.EQUAL
        val side = compact.takeIf { equal && needing.isNotEmpty() }
        val widest = with(density) { (needing.maxOrNull() ?: 0).toDp() }
        Box(Modifier.fillMaxWidth().heightIn(min = GROUP_TARGET), contentAlignment = rowPlace(side)) {
            Row(
                (if (side == null) Modifier.fillMaxWidth() else Modifier).height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(GROUP_GAP),
            ) {
                buttons.forEachIndexed { index, button ->
                    val width = buttonWidth(if (side != null) widest else null, shares?.get(index))
                    GroupButtonView(
                        button,
                        groupShape(index, buttons.size, button.selected == true),
                        width.fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** The widths buttons need for their labels on one line, [labels], and for their longest [words]. */
private data class Widths(
    val labels: List<Int>,
    val words: List<Int>,
) {
    companion object {
        /** [labels]' widths, each label's and its longest word's, measured by [oneLine]. */
        fun measured(
            labels: List<String>,
            measurer: TextMeasurer,
            style: TextStyle,
            density: Density,
        ): Widths {
            val extra = with(density) { 2 * GROUP_PADDING.roundToPx() }
            return Widths(
                oneLine(labels, measurer, style, extra),
                labels.map { label ->
                    val words = label.split(WORD_BREAK).filter(String::isNotEmpty)
                    oneLine(words, measurer, style, extra).maxOrNull() ?: 0
                },
            )
        }

        /**
         * The widths [texts] need on one line in [style], with [extra] for the buttons' padding; a
         * pixel over, so that a label measured to fit is not wrapped by rounding.
         */
        private fun oneLine(
            texts: List<String>,
            measurer: TextMeasurer,
            style: TextStyle,
            extra: Int,
        ): List<Int> = texts.map { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width + extra + 1 }
    }
}

/** Where a label may wrap: at a space, as Android's line breaker breaks a button's label. */
private val WORD_BREAK = Regex("\\s+")

/** A row's button's width: [fixed] in a compact row, else its [share] of a bounded row, or its own. */
private fun RowScope.buttonWidth(
    fixed: Dp?,
    share: Float?,
): Modifier =
    when {
        fixed != null -> Modifier.width(fixed)
        share != null -> Modifier.weight(share)
        else -> Modifier
    }

/** Where a compact row sits in the width, [side] of it ([ConnectedButtons]); at the start otherwise. */
private fun rowPlace(side: Alignment.Horizontal?): Alignment =
    when (side) {
        Alignment.End -> Alignment.CenterEnd
        Alignment.CenterHorizontally -> Alignment.Center
        else -> Alignment.CenterStart
    }

/**
 * The shape of the button at [index] of [count]: round at the row's ends, its start and end, and
 * small where it meets its neighbours; round all round when it is the one chosen ([selected]), as
 * Material's connected group shows the button checked.
 */
internal fun groupShape(
    index: Int,
    count: Int,
    selected: Boolean,
): Shape {
    val outer = GROUP_OUTER_CORNER
    val inner = GROUP_INNER_CORNER
    if (selected || count == 1) return RoundedCornerShape(outer)
    val first = if (index == 0) outer else inner
    val last = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = first, topEnd = last, bottomEnd = last, bottomStart = first)
}

/**
 * One button: its label on a fill as the chips' ([chipColours]), the text's colour when chosen, a
 * light fill of it otherwise, with no check mark (#1234); dimmed when it cannot be used. One node
 * for a screen reader: a radio button in a choice, a button in a row of actions.
 */
@Composable
private fun GroupButtonView(
    button: GroupButton,
    shape: Shape,
    modifier: Modifier,
) {
    val chosen = button.selected == true
    val colours = MaterialTheme.colorScheme
    val (chipFill, chipContent) = chipColours(chosen)
    val fill = if (button.enabled || !chosen) chipFill else colours.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA)
    val content = if (button.enabled) chipContent else colours.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
    val action =
        if (button.selected == null) {
            Modifier.clickable(enabled = button.enabled, role = Role.Button, onClick = button.onClick)
        } else {
            Modifier.selectable(
                selected = chosen,
                enabled = button.enabled,
                role = Role.RadioButton,
                onClick = button.onClick,
            )
        }
    Row(
        modifier =
            modifier
                .heightIn(min = GROUP_HEIGHT)
                .clip(shape)
                .background(fill, shape)
                .then(button.modifier)
                .then(action)
                .let { view -> button.read?.let { read -> view.semantics { contentDescription = read } } ?: view }
                .padding(horizontal = GROUP_PADDING, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            button.label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
