package app.sleevesight.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
 * cut ([GroupArrangement]).
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
) {
    val buttons =
        choices.map { (value, label) ->
            val read = listOfNotNull(name?.let { "$it: $label" } ?: label, details[value]).joinToString(". ")
            GroupButton(label, enabled, selected = value == selected, read = read) { onSelect(value) }
        }
    ConnectedButtons(buttons, modifier.selectableGroup(), checks = true)
}

/**
 * A row of actions, as a connected button group (#637): each of [actions] a plain button, by its
 * label, which a tap runs while it is enabled; none is ever chosen, so none is filled or checked.
 * A screen reader reads each as a button, a disabled one as disabled. Laid out as
 * [ChoiceButtonGroup] is.
 */
@Composable
internal fun ActionButtonGroup(
    actions: List<GroupAction>,
    modifier: Modifier = Modifier,
) {
    val buttons =
        actions.map {
            GroupButton(it.label, it.enabled, selected = null, read = null, tag = it.tag, onClick = it.onClick)
        }
    ConnectedButtons(buttons, modifier, checks = false)
}

/**
 * One action of an [ActionButtonGroup]: its [label], whether it can be used ([enabled]), a test
 * [tag] for its button if it has one, and what a tap does.
 */
internal class GroupAction(
    val label: String,
    val enabled: Boolean = true,
    val tag: String? = null,
    val onClick: () -> Unit,
)

/**
 * A button of the group: chosen or not ([selected]) in a choice, null in a row of actions; [read],
 * what a screen reader reads for it in place of its label, if not the label alone; [tag], its test
 * tag, if any.
 */
private class GroupButton(
    val label: String,
    val enabled: Boolean,
    val selected: Boolean?,
    val read: String?,
    val tag: String? = null,
    val onClick: () -> Unit,
)

/**
 * How a group's buttons are laid out in the width it has: side by side, all as wide as the widest
 * needs ([EQUAL], as Material's connected groups are), or, where that does not fit, each as wide as
 * its label needs and the room left shared out in proportion to that ([PROPORTIONAL]); where even
 * that does not fit, one under another, each full width ([STACKED]), its label wrapping in it if it
 * must.
 */
internal enum class GroupArrangement { EQUAL, PROPORTIONAL, STACKED }

/** How buttons [needing] these widths, [gap] apart, are laid out in [room] (all in pixels). */
internal fun groupArrangement(
    needing: List<Int>,
    gap: Int,
    room: Int,
): GroupArrangement {
    val free = room - gap * (needing.size - 1)
    return when {
        needing.isEmpty() -> GroupArrangement.EQUAL
        needing.max() * needing.size <= free -> GroupArrangement.EQUAL
        needing.sum() <= free -> GroupArrangement.PROPORTIONAL
        else -> GroupArrangement.STACKED
    }
}

/**
 * [buttons] laid out by [groupArrangement], from the widths their labels need on one line in the
 * buttons' style (in the app's language, font and font size), their padding, and, in a choice
 * ([checks]), room for the check in every one, so the buttons keep their widths whichever is
 * chosen. In a row, the buttons are of one height, at least [GROUP_HEIGHT], in a row at least
 * [GROUP_TARGET] high, each answering a tap across 48 dp (Compose widens a smaller target into the
 * room around it); stacked, each is at least [GROUP_TARGET] high.
 */
@Composable
private fun ConnectedButtons(
    buttons: List<GroupButton>,
    modifier: Modifier,
    checks: Boolean,
) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = buttons.map { it.label }
    val needing =
        remember(labels, style, density, measurer, checks) {
            with(density) {
                val extra = 2 * GROUP_PADDING.roundToPx() + if (checks) (CHECK_SIZE + CHECK_GAP).roundToPx() else 0
                // A pixel over, so that a label measured to fit is not wrapped by rounding.
                labels.map { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width + extra + 1 }
            }
        }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = with(density) { GROUP_GAP.roundToPx() }
        val bounded = constraints.hasBoundedWidth
        val arrangement = if (bounded) groupArrangement(needing, gap, constraints.maxWidth) else GroupArrangement.EQUAL
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
            Box(Modifier.fillMaxWidth().heightIn(min = GROUP_TARGET), contentAlignment = Alignment.CenterStart) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(GROUP_GAP),
                ) {
                    buttons.forEachIndexed { index, button ->
                        val share = if (arrangement == GroupArrangement.EQUAL) 1f else needing[index].toFloat()
                        val width = if (bounded) Modifier.weight(share) else Modifier
                        GroupButtonView(
                            button,
                            groupShape(index, buttons.size, button.selected == true, stacked = false),
                            width.fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
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
                .then(action)
                .let { view -> button.tag?.let { view.testTag(it) } ?: view }
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
            button.label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
