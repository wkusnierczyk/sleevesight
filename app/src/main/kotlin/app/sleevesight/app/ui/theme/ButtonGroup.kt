package app.sleevesight.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import app.sleevesight.app.R

// A connected button group (#637), after Material 3's (m3.material.io/components/button-groups):
// its own `ButtonGroup` and `ToggleButton` come with material3 1.5, still alpha, and the project's
// Compose BOM gives 1.4, so the group is drawn here from the spec's shapes, until #621 replaces it
// with Material's. One group serves two uses: a choice of one value ([ChoiceButtonGroup], a setting
// with a few short choices) and a row of actions ([ActionButtonGroup], plain buttons each enabled
// or not, as a list's paging).

/** A button's least height as drawn, as tall as Home's chips and the insight card's split button. */
internal val GROUP_HEIGHT = 32.dp

/** The least height of the group's row, and of each button stacked: Android's 48 dp touch target. */
internal val GROUP_TARGET = 48.dp

/** The gap between the buttons, as Material's connected group has it. */
internal val GROUP_GAP = 2.dp

/** The group's outer corners, and every corner of the button chosen: round at [GROUP_HEIGHT]. */
internal val GROUP_OUTER_CORNER = CornerSize(16.dp)

/** The corners where two buttons meet: small, as Material's connected group has them at this size. */
internal val GROUP_INNER_CORNER = CornerSize(4.dp)

/** The room inside a button at each side of its label, as in the chips ([CHIP_PADDING]). */
internal val GROUP_PADDING = CHIP_PADDING

/** The check before the chosen button's label, and the gap after it. */
private val CHECK_SIZE = 18.dp
private val CHECK_GAP = 6.dp

/** The alpha of a button's content that cannot be used, and of its fill, as Material's. */
private const val DISABLED_CONTENT_ALPHA = 0.38f
private const val DISABLED_CONTAINER_ALPHA = 0.12f

/**
 * One of a few values to choose, as a connected button group (#637): [choices], each a value and
 * its label, in one row, the [selected] one filled in the text's colour, with a check before its
 * label and every corner round; a tap on another calls [onSelect] with its value. The group follows
 * [selected], so whatever changes the value elsewhere shows here at once. Not [enabled], none can
 * be chosen and all are dimmed.
 *
 * A screen reader reads the group as one group of radio buttons, the chosen one selected, each by
 * its label, after the name of what is chosen ([name], a setting's, so a button reached straight
 * away says what it sets: "Theme: Light"), and then what the value does, where [details] has it
 * (as the dialogs read a choice with its description, #550). Where the labels do not fit the row
 * side by side, as at a large font size, each takes a line of its own, full width, so nothing is
 * cut ([GroupArrangement]); unless every choice has a label in [short] (#803): the group then shows
 * those, in one row, never a stack, a label wrapping in its button if even they do not fit. A
 * screen reader reads the full label either way. With [oneRow] (#801), never a stack either, with
 * the full labels: the buttons share the row by their labels' widths, and a label too long for its
 * button wraps in it. A value in [disabled] cannot be chosen, and is dimmed, while the others can.
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
    oneRow: Boolean = false,
    short: Map<T, String> = emptyMap(),
) {
    val buttons =
        choices.map { (value, label) ->
            val read = listOfNotNull(name?.let { "$it: $label" } ?: label, details[value]).joinToString(". ")
            GroupButton(
                label,
                enabled && value !in disabled,
                selected = value == selected,
                read = read,
            ) { onSelect(value) }
        }
    val shorts = choices.mapNotNull { short[it.first] }.takeIf { it.isNotEmpty() && it.size == choices.size }
    ConnectedButtons(buttons, modifier.selectableGroup(), checks = true, shorts = shorts, oneRow = oneRow)
}

/**
 * A row of actions, as a connected button group (#637): each of [actions] a plain button, by its
 * label, which a tap runs while it is enabled; none is ever chosen, so none is filled or checked.
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
    ConnectedButtons(buttons, modifier, checks = false, compact)
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
 * How a group's buttons are laid out in the width it has: side by side, all as wide as the widest
 * needs ([EQUAL], as Material's connected groups are), or, where that does not fit, each as wide as
 * its label needs and the room left shared out in proportion to that ([PROPORTIONAL]); where even
 * that does not fit, one under another, each full width ([STACKED]), its label wrapping in it if it
 * must; or, for a group kept to one row, still side by side in proportion, each label wrapping in
 * its button.
 */
internal enum class GroupArrangement { EQUAL, PROPORTIONAL, STACKED }

/**
 * How buttons [needing] these widths, [gap] apart, are laid out in [room] (all in pixels); with
 * [oneRow], never [GroupArrangement.STACKED].
 */
internal fun groupArrangement(
    needing: List<Int>,
    gap: Int,
    room: Int,
    oneRow: Boolean = false,
): GroupArrangement {
    val free = room - gap * (needing.size - 1)
    return when {
        needing.isEmpty() -> GroupArrangement.EQUAL
        needing.max() * needing.size <= free -> GroupArrangement.EQUAL
        needing.sum() <= free || oneRow -> GroupArrangement.PROPORTIONAL
        else -> GroupArrangement.STACKED
    }
}

/**
 * How buttons whose labels need [full] widths are laid out, [gap] apart, in [room] (null when the
 * width is not bounded: side by side), and whether their [short] labels show in place of the full
 * ones (#803): they do, where there are short labels, when the full ones would stack, and then in a
 * row whether or not they fit, a label wrapping in its button if it must.
 */
internal fun groupLayout(
    full: List<Int>,
    short: List<Int>?,
    gap: Int,
    room: Int?,
    oneRow: Boolean = false,
): Pair<GroupArrangement, Boolean> {
    // Kept to one row (#801), the full labels never stack, so the short ones are not needed.
    val arrangement = room?.let { groupArrangement(full, gap, it, oneRow) } ?: GroupArrangement.EQUAL
    return when {
        room == null || short == null || arrangement != GroupArrangement.STACKED -> arrangement to false
        groupArrangement(short, gap, room) == GroupArrangement.EQUAL -> GroupArrangement.EQUAL to true
        else -> GroupArrangement.PROPORTIONAL to true
    }
}

/**
 * [buttons] laid out by [groupArrangement], from the widths their labels need on one line in the
 * buttons' style (in the app's language, font and font size), their padding, and, in a choice
 * ([checks]), room for the check in every one, so the buttons keep their widths whichever is
 * chosen. In a row, the buttons are of one height, at least [GROUP_HEIGHT], in a row at least
 * [GROUP_TARGET] high, each answering a tap across 48 dp (Compose widens a smaller target into the
 * room around it); stacked, each is at least [GROUP_TARGET] high. With [compact], buttons that fit
 * as wide as the widest needs ([GroupArrangement.EQUAL]) are that wide, and the row is placed at
 * that side; otherwise they share the width as without it. Where they would stack and there are
 * [shorts], a label for every button, those are measured and shown instead, in a row in any case
 * ([groupLayout]).
 */
@Composable
private fun ConnectedButtons(
    buttons: List<GroupButton>,
    modifier: Modifier,
    checks: Boolean,
    compact: Alignment.Horizontal? = null,
    oneRow: Boolean = false,
    shorts: List<String>? = null,
) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = buttons.map { it.label }
    val needingFull =
        remember(labels, style, density, measurer, checks) { labelWidths(labels, measurer, style, density, checks) }
    val needingShort =
        remember(shorts, style, density, measurer, checks) {
            shorts?.let { labelWidths(it, measurer, style, density, checks) }
        }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = with(density) { GROUP_GAP.roundToPx() }
        val bounded = constraints.hasBoundedWidth
        val (arrangement, useShort) =
            groupLayout(needingFull, needingShort, gap, constraints.maxWidth.takeIf { bounded }, oneRow)
        val needing = if (useShort) needingShort.orEmpty() else needingFull
        val shown = if (useShort) shorts.orEmpty() else labels
        if (arrangement == GroupArrangement.STACKED) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
                buttons.forEachIndexed { index, button ->
                    GroupButtonView(
                        button,
                        groupShape(index, buttons.size, button.selected == true, stacked = true),
                        Modifier.fillMaxWidth().heightIn(min = GROUP_TARGET),
                    )
                }
            }
        } else {
            // Compact only where every button fits as wide as the widest label needs.
            val side = compact.takeIf { bounded && arrangement == GroupArrangement.EQUAL && needing.isNotEmpty() }
            val widest = with(density) { (needing.maxOrNull() ?: 0).toDp() }
            Box(Modifier.fillMaxWidth().heightIn(min = GROUP_TARGET), contentAlignment = rowPlace(side)) {
                Row(
                    (if (side == null) Modifier.fillMaxWidth() else Modifier).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(GROUP_GAP),
                ) {
                    buttons.forEachIndexed { index, button ->
                        val share = if (arrangement == GroupArrangement.EQUAL) 1f else needing[index].toFloat()
                        val width = buttonWidth(if (side != null) widest else null, bounded, share)
                        GroupButtonView(
                            button,
                            groupShape(index, buttons.size, button.selected == true, stacked = false),
                            width.fillMaxHeight(),
                            label = shown[index],
                        )
                    }
                }
            }
        }
    }
}

/**
 * The widths [labels] need on one line in [style], with the buttons' padding and, with [checks],
 * room for the check in every one; a pixel over, so that a label measured to fit is not wrapped by
 * rounding.
 */
private fun labelWidths(
    labels: List<String>,
    measurer: TextMeasurer,
    style: TextStyle,
    density: Density,
    checks: Boolean,
): List<Int> =
    with(density) {
        val extra = 2 * GROUP_PADDING.roundToPx() + if (checks) (CHECK_SIZE + CHECK_GAP).roundToPx() else 0
        labels.map { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width + extra + 1 }
    }

/** A row's button's width: [fixed] in a compact row, else its [share] of a [bounded] row, or its own. */
private fun RowScope.buttonWidth(
    fixed: Dp?,
    bounded: Boolean,
    share: Float,
): Modifier =
    when {
        fixed != null -> Modifier.width(fixed)
        bounded -> Modifier.weight(share)
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
 * The shape of the button at [index] of [count]: round at the group's ends, its outer corners, and
 * small where it meets its neighbours; round all round when it is the one chosen ([selected]), as
 * Material's connected group shows the button checked. A row's ends are its start and end, a
 * stack's its top and bottom.
 */
internal fun groupShape(
    index: Int,
    count: Int,
    selected: Boolean,
    stacked: Boolean,
): Shape {
    val outer = GROUP_OUTER_CORNER
    val inner = GROUP_INNER_CORNER
    if (selected || count == 1) return RoundedCornerShape(outer)
    val first = if (index == 0) outer else inner
    val last = if (index == count - 1) outer else inner
    return if (stacked) {
        RoundedCornerShape(topStart = first, topEnd = first, bottomEnd = last, bottomStart = last)
    } else {
        RoundedCornerShape(topStart = first, topEnd = last, bottomEnd = last, bottomStart = first)
    }
}

/**
 * One button: its label on a fill as the chips' ([chipColours]), the text's colour when chosen, a
 * light fill of it otherwise; dimmed when it cannot be used. One node for a screen reader: a radio
 * button in a choice, a button in a row of actions.
 */
@Composable
private fun GroupButtonView(
    button: GroupButton,
    shape: Shape,
    modifier: Modifier,
    label: String = button.label,
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
        if (chosen) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(CHECK_SIZE),
                tint = content,
            )
            Spacer(Modifier.width(CHECK_GAP))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
