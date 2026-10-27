package app.sleevesight.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.sleevesight.app.R
import app.sleevesight.app.ui.settings.WRITING_MODES
import app.sleevesight.app.ui.settings.writingModeName
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * Each half's least height as drawn (#624): as tall as Home's chips; more where the label wraps.
 * Each answers a tap across 48 dp.
 */
internal val SPLIT_HEIGHT = 32.dp

/** The arrow's width, and the row's least height: the touch target Android's accessibility guidance asks for. */
internal val SPLIT_TARGET = 48.dp

/**
 * The room inside the leading half around its label: as in Home's genre chips, so a label of one
 * line makes it 32 dp high, as tall as they are, at any font size.
 */
internal val SPLIT_PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

/** The corner where the two halves meet, as Material 3's split button has it. */
internal val SPLIT_INNER_CORNER = CornerSize(4.dp)

/**
 * The outer corners: round at 32 dp high, as the genre chips' are; fixed, so a taller button, its
 * label wrapping, keeps them and its text clear of them.
 */
internal val SPLIT_OUTER_CORNER = CornerSize(16.dp)

/** The gap between the halves. */
internal val SPLIT_GAP = 2.dp

/** The alpha of a control's content that cannot be used, and of its container, as Material's. */
private const val DISABLED_CONTENT_ALPHA = 0.38f
private const val DISABLED_CONTAINER_ALPHA = 0.12f

/** The arrow turned up while the menu is open. */
internal const val SPLIT_OPEN_ROTATION = 180f

/**
 * *Reactive* (D5) as the split button's menu shows it (#615): the [mode] chosen; not [enabled]
 * while *Show insights* is off, as *Reactive* cannot be changed then. [onChoose] saves a mode
 * chosen, the one chosen already included (an automatic one starts automatic writing from the
 * next track, #625), its result whether it was saved: a save that failed is announced, as the
 * settings announce any change that could not be saved. [onDetails] opens the settings at
 * *Reactive*, where each mode is described.
 */
internal class WritingChoice(
    val mode: WriteAutomatically,
    val enabled: Boolean = true,
    val onChoose: (WriteAutomatically) -> Deferred<Boolean> = { CompletableDeferred(true) },
    val onDetails: () -> Unit = {},
)

/**
 * *Look again* in the split button's menu (#617), beside a written insight: a tap ([onLookAgain])
 * looks every source up afresh and writes the insight again only if they found something new;
 * [enabled] while it can be tapped.
 */
internal class LookAgainItem(
    val enabled: Boolean,
    val onLookAgain: () -> Unit = {},
)

/**
 * The insight card's split button (#615, #625), after Material 3's: the leading half is the card's
 * [offer] ([cardOffer]), *Write insight* or *Rewrite insight* ([onWrite]), or *Stop writing*
 * ([onStop]), disabled when a tap would do nothing; the trailing
 * half, an arrow, opens the menu of *Reactive*'s modes ([writing]) by name, the one chosen marked,
 * and after a divider *See Settings for details*, which opens the settings at *Reactive*. Filled
 * while it offers a Write, outlined otherwise, as the buttons before it were. While [writing] is
 * null, before the settings' first read, the arrow cannot be used.
 *
 * A screen reader reads the leading half as its action, the arrow as *Writing mode: <mode>*, a
 * drop-down list, and the menu's modes as one choice among
 * them, the chosen one selected. Both halves are drawn 32 dp high, as Home's chips are, or as high
 * as a label that wraps makes the leading one, and each answers a tap across 48 dp; the arrow is
 * 48 dp wide ([Halves]). *Write insight* and *Rewrite insight* end in the **AI** mark ([AiLabel], #624),
 * read as "<label>, uses AI"; *Stop writing* and the arrow, which make no call, do not. Choosing an
 * automatic mode other than the one chosen says that each new track's insight is a paid call;
 * choosing *On demand*, or the mode already chosen, says nothing.
 *
 * Beside a written insight ([lookAgain], #617), the menu begins with *Look again*, ending in the
 * **AI** mark, as it may make a call, then a divider; the arrow then reads *Writing mode: <mode>,
 * and Look again*.
 *
 * Material 3's own `SplitButtonLayout` is not in material3 1.4, the version the project's Compose
 * BOM gives, so the halves are drawn here with its shapes: round outer corners (16 dp, as the genre
 * chips' are), 4 dp inner ones and a 2 dp gap.
 */
@Composable
internal fun WriteSplitButton(
    offer: CardOffer,
    onWrite: () -> Unit,
    onStop: () -> Unit,
    writing: WritingChoice?,
    lookAgain: LookAgainItem? = null,
) {
    val filled = offer.invites
    Halves(Modifier.testTag(WRITE_HALVES_TAG)) {
        Leading(offer, filled, if (offer.stop) onStop else onWrite)
        ModeArrow(filled, writing, lookAgain)
    }
}

/**
 * The two halves, [content]: the leading one, then the arrow (#624); the home guide's *Dismiss*
 * (#627) is laid out by it too. The arrow's 48 dp width is
 * kept first, and the leading half takes the width left, its label wrapping in it where it must;
 * it is at least 32 dp high, and the arrow is drawn as high as it, so the halves are always of one
 * height. The row is at least 48 dp high, the halves centred in it: each answers a tap across 48 dp
 * (Compose widens a smaller tap target into the room around it), which Material's own
 * `minimumInteractiveComponentSize` would otherwise give the leading half alone, by a size the
 * arrow could not follow.
 */
@Composable
internal fun Halves(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Layout(content, modifier) { measurables, constraints ->
            val (leadingHalf, arrowHalf) = measurables
            val gap = SPLIT_GAP.roundToPx()
            val target = SPLIT_TARGET.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val least = SPLIT_HEIGHT.roundToPx().coerceAtMost(loose.maxHeight)
            val leading = leadingHalf.measure(loose.offset(horizontal = -(target + gap)).copy(minHeight = least))
            val arrow = arrowHalf.measure(Constraints.fixed(target, leading.height))
            val width = leading.width + gap + arrow.width
            val height = maxOf(leading.height, target)
            layout(width, height) {
                val top = (height - leading.height) / 2
                leading.placeRelative(0, top)
                arrow.placeRelative(leading.width + gap, top)
            }
        }
    }
}

@Composable
private fun Leading(
    offer: CardOffer,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SPLIT_OUTER_CORNER, SPLIT_INNER_CORNER, SPLIT_INNER_CORNER, SPLIT_OUTER_CORNER)
    val tag =
        when {
            !offer.enabled -> NO_ACTION_TAG
            offer.stop -> STOP_TAG
            else -> WRITE_TAG
        }
    // As high as [Halves] has it: 32 dp at least, more for a label that wraps.
    val modifier = Modifier.testTag(tag)
    val enabled = offer.enabled
    val text = stringResource(offer.label)
    // Every Write calls the AI service, a disabled one included; Stop does not (#624).
    val label: @Composable () -> Unit = { if (offer.usesAi) AiLabel(text) else Text(text) }
    if (filled) {
        Button(onClick, modifier, enabled, shape = shape, contentPadding = SPLIT_PADDING) { label() }
    } else {
        OutlinedButton(onClick, modifier, enabled, shape = shape, contentPadding = SPLIT_PADDING) { label() }
    }
}

// The arrow and its menu. Drawn rather than a Button, which would read as a button: a screen reader
// reads it as a drop-down list.
@Composable
private fun ModeArrow(
    filled: Boolean,
    writing: WritingChoice?,
    lookAgain: LookAgainItem?,
) {
    var open by remember { mutableStateOf(false) }
    // Outlives the menu, which closes as a mode is chosen, to hear how the save ended.
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val failed = stringResource(R.string.settings_change_failed)
    val enabled = writing?.enabled == true
    val shape = RoundedCornerShape(SPLIT_INNER_CORNER, SPLIT_OUTER_CORNER, SPLIT_OUTER_CORNER, SPLIT_INNER_CORNER)
    val colours = if (filled) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
    val container =
        when {
            !filled -> colours.containerColor
            enabled -> colours.containerColor
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA)
        }
    val content =
        if (enabled) colours.contentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
    val described = described(writing, lookAgain != null)
    Box {
        var arrow =
            Modifier
                .testTag(WRITE_MODE_TAG)
                // As wide and high as [Halves] has it: 48 dp, and the leading half's height.
                .fillMaxSize()
                .clip(shape)
                .background(container)
        if (!filled) arrow = arrow.border(ButtonDefaults.outlinedButtonBorder(enabled), shape)
        Box(
            modifier =
                arrow
                    .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                    .semantics { contentDescription = described },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_keyboard_arrow_down),
                contentDescription = null,
                tint = content,
                modifier = Modifier.rotate(if (open) SPLIT_OPEN_ROTATION else 0f),
            )
        }
        if (writing != null) {
            DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
                ModeMenu(writing, { open = false }, lookAgain) { saved ->
                    // A save that failed is said (#625): the menu keeps the mode the store holds.
                    scope.launch {
                        if (!saved.await()) Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

// *Writing mode: <mode>*, and *, and Look again* while the menu holds it (#617); *Writing mode*
// alone before the settings' first read.
@Composable
private fun described(
    writing: WritingChoice?,
    lookAgain: Boolean,
): String {
    if (writing == null) return stringResource(R.string.home_write_mode_unknown)
    val mode = stringResource(writingModeName(writing.mode))
    val line = if (lookAgain) R.string.home_write_mode_look_again else R.string.home_write_mode
    return stringResource(line, mode)
}

// *Look again* beside a written insight (#617); *Reactive*'s modes, one choice among them, by name,
// the chosen one checked; then the way to the settings at *Reactive*, where each is described.
@Composable
internal fun ModeMenu(
    writing: WritingChoice,
    close: () -> Unit,
    lookAgain: LookAgainItem? = null,
    saving: (Deferred<Boolean>) -> Unit = {},
) {
    lookAgain?.let { item ->
        DropdownMenuItem(
            // Read as "Look again, uses AI": a new insight is a call to the AI service.
            text = { AiLabel(stringResource(R.string.home_look_again)) },
            onClick = {
                close()
                item.onLookAgain()
            },
            enabled = item.enabled,
            leadingIcon = { Spacer(Modifier.size(24.dp)) },
            modifier = Modifier.testTag(LOOK_AGAIN_TAG),
        )
        HorizontalDivider()
    }
    val context = LocalContext.current
    val paid = stringResource(R.string.home_write_mode_paid)
    Column(Modifier.selectableGroup()) {
        for (entry in WRITING_MODES) {
            val chosen = entry.mode == writing.mode
            DropdownMenuItem(
                text = { Text(stringResource(entry.name)) },
                onClick = {
                    close()
                    // Said once, as the mode changes to one that pays for each track.
                    if (entry.mode != WriteAutomatically.NEVER && !chosen) {
                        Toast.makeText(context, paid, Toast.LENGTH_SHORT).show()
                    }
                    saving(writing.onChoose(entry.mode))
                },
                leadingIcon = {
                    if (chosen) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                    } else {
                        Spacer(Modifier.size(24.dp))
                    }
                },
                modifier =
                    Modifier.testTag(writingModeTag(entry.mode)).semantics {
                        role = Role.RadioButton
                        selected = chosen
                    },
            )
        }
    }
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.home_write_mode_details)) },
        onClick = {
            close()
            writing.onDetails()
        },
        leadingIcon = { Spacer(Modifier.size(24.dp)) },
        modifier = Modifier.testTag(WRITE_MODE_DETAILS_TAG),
    )
}

/** The test tag of the menu's item for [mode]. */
internal fun writingModeTag(mode: WriteAutomatically): String = "write-mode-${mode.name}"

/** The test tag of the split button's leading half while it offers a Write. */
const val WRITE_TAG: String = "write"

/** The test tag of *Stop writing*, the leading half while it offers it. */
const val STOP_TAG: String = "stop"

/** The test tag of the leading half while a tap would do nothing: disabled. */
const val NO_ACTION_TAG: String = "write-none"

/** The test tag of the split button's two halves, together. */
const val WRITE_HALVES_TAG: String = "write-halves"

/** The test tag of the split button's arrow, which opens the menu of *Reactive*'s modes. */
const val WRITE_MODE_TAG: String = "write-mode"

/** The test tag of the menu's *Look again* (#617). */
const val LOOK_AGAIN_TAG: String = "look-again"

/** The test tag of the menu's *See Settings for details*. */
const val WRITE_MODE_DETAILS_TAG: String = "write-mode-details"
