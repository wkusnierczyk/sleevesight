package app.sleevesight.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.model.Outcome

// *Sources*' chip on Home (#623), its filter chips and its note (#440).

/**
 * *Sources* on Home (#623): a chip, drawn as a genre chip is ([ChipFrame]), named *Sources*. Under
 * *Expandable boxes* it opens the section's panel ([SourcesSection]), and a screen reader reads it
 * as the heading *Sources*, a button, collapsed; under *Separate pages* ([opensPage], #447) it opens
 * *Sources*' page, and a screen reader names its action "open page", with no state, as it does a
 * genre chip's there. [modifier] goes on the chip's outside: a focus requester.
 */
@Composable
internal fun SourcesChip(
    opensPage: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val name = stringResource(R.string.home_sources_title)
    val collapsed = stringResource(R.string.home_fold_collapsed)
    val open = stringResource(R.string.home_page_open)
    val (fill, content) = chipColours(marked = false)
    ChipFrame(
        fill,
        modifier = modifier.testTag(if (opensPage) SOURCES_PAGE_LINK_TAG else SOURCES_CHIP_TAG),
        action =
            Modifier
                .clickable(role = Role.Button, onClickLabel = open.takeIf { opensPage }, onClick = onClick)
                .clearAndSetSemantics {
                    heading()
                    contentDescription = name
                    if (!opensPage) stateDescription = collapsed
                },
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/**
 * What needs the listener, in one note (#440): each source that needs its key, "Last.fm needs your
 * key, in Settings.", the settings a link ([openSettings]); each one that could not be reached or
 * failed.
 */
@Composable
internal fun Notes(
    notes: List<SourceNote>,
    openSettings: () -> Unit,
) {
    if (notes.isEmpty()) return
    val settings = stringResource(R.string.home_source_note_settings)
    val sentences =
        notes.map { note ->
            val name = chipName(note.source)
            when (note) {
                is SourceNote.NeedsKey -> stringResource(R.string.home_source_note_key, name, settings)
                is SourceNote.Unreachable -> stringResource(R.string.home_source_note_unreachable, name)
                is SourceNote.Failed -> stringResource(R.string.home_source_note_failed, name)
            } to (note is SourceNote.NeedsKey)
        }
    val parts =
        sentences.flatMapIndexed { index, (sentence, linked) ->
            val gap = if (index > 0) listOf(TextPart.Words(" ")) else emptyList()
            val at = sentence.lastIndexOf(settings)
            gap +
                if (!linked || at < 0) {
                    listOf(TextPart.Words(sentence))
                } else {
                    listOf(
                        TextPart.Words(sentence.substring(0, at)),
                        TextPart.Action(settings, SETTINGS_LINK, openSettings),
                        TextPart.Words(sentence.substring(at + settings.length)),
                    )
                }
        }
    LinkedText(
        parts,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(SOURCES_NOTE_TAG),
    )
}

/**
 * The chips of *Sources*, [chips] (none, no row), each showing or hiding its source's [lines] by
 * [focus], those shown again unfolded to by [fold]; with grounding [details] each with its status,
 * else by name. The chips of a row are centred on one line.
 */
@Composable
internal fun SourceChips(
    chips: List<SourceChip>,
    details: Boolean,
    lines: List<EvidenceLine>,
    focus: LineFocus,
    fold: SourcesFold,
) {
    if (chips.isEmpty()) return
    // Every source's chip with its status shows only in developer mode, marked as such (#536).
    if (details) {
        DeveloperMark { ChipRow(chips, true, lines, focus, fold) }
    } else {
        ChipRow(chips, false, lines, focus, fold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    chips: List<SourceChip>,
    details: Boolean,
    lines: List<EvidenceLine>,
    focus: LineFocus,
    fold: SourcesFold,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for (chip in chips) {
            val shown = chip.filters.none { it in focus.hidden }
            // Lines shown again are unfolded to, wherever they fall in the list.
            val toggle = { focus.toggle(chip)?.let(fold::reach) }
            if (details) {
                StatusChip(chip, filtering = lines.any { it.source in chip.filters }, shown = shown) { toggle() }
            } else {
                NameChip(chip, shown = shown) { toggle() }
            }
        }
    }
}

/**
 * A source's chip under *Show grounding details*: its letter and its status ("W the song"), as the
 * chips were before #440. One whose source has lines ([filtering]) shows or hides them ([shown]).
 */
@Composable
private fun StatusChip(
    chip: SourceChip,
    filtering: Boolean,
    shown: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.home_source_chip, chip.source.tag, statusText(chip.status))
    val bad = chip.status == ChipStatus.Failed || (chip.status as? ChipStatus.Done)?.outcome is Outcome.Rejected
    val shape = RoundedCornerShape(8.dp)
    // The chip's own text is what it says; the filter is its action and its state.
    val action = stringResource(R.string.home_source_filter, chipName(chip.source))
    val state = stringResource(if (shown) R.string.home_source_lines_shown else R.string.home_source_lines_hidden)
    val toggle =
        if (filtering) {
            Modifier
                .semantics {
                    stateDescription = state
                    toggleableState = ToggleableState(shown)
                }.clickable(onClickLabel = action, role = Role.Switch, onClick = onToggle)
        } else {
            Modifier
        }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color =
            if (bad) {
                colors.error
            } else if (filtering && !shown) {
                colors.outline
            } else {
                colors.onSurfaceVariant
            },
        modifier =
            Modifier
                .testTag(CHIP_PREFIX + chip.source.tag)
                .then(if (filtering) Modifier.minimumInteractiveComponentSize() else Modifier)
                .background(if (filtering && shown) colors.secondaryContainer else Color.Transparent, shape)
                .border(1.dp, colors.outlineVariant, shape)
                .then(toggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * A source's chip (#440), drawn as a genre chip is ([ChipFrame], #623): the letter its citations
 * use, small and quieter, before its name ("W Wikipedia"), on the name's baseline; it shows or hides
 * the source's lines. While they show ([shown]), the genre chip's light fill; hidden, outlined, its
 * name struck through and faded. A screen reader reads it as a switch, the source's name, and
 * whether its lines show.
 */
@Composable
private fun NameChip(
    chip: SourceChip,
    shown: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val name = chipName(chip.source)
    val action = stringResource(R.string.home_source_filter, name)
    val state = stringResource(if (shown) R.string.home_source_lines_shown else R.string.home_source_lines_hidden)
    val (fill, content) = chipColours(marked = false)
    val color = if (shown) content else colors.outline
    ChipFrame(
        if (shown) fill else Color.Transparent,
        modifier = Modifier.testTag(CHIP_PREFIX + chip.source.tag),
        outline = colors.outline.takeUnless { shown },
        action =
            Modifier
                .clickable(onClickLabel = action, role = Role.Switch, onClick = onToggle)
                .clearAndSetSemantics {
                    contentDescription = name
                    stateDescription = state
                    toggleableState = ToggleableState(shown)
                },
    ) {
        // Quieter than the name while its lines show; hidden, the name is already the quiet outline
        // colour, and a letter fainter still would fall below a legible contrast.
        val letter = if (shown) color.copy(alpha = MARK_ALPHA) else color
        Text(
            chip.source.tag,
            style = MaterialTheme.typography.labelSmall,
            color = letter,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            textDecoration = if (shown) null else TextDecoration.LineThrough,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

// The note's link to the settings.
private const val SETTINGS_LINK = "settings"

/** The test tag of *Sources*' chip on Home under *Expandable boxes*, which opens its panel. */
const val SOURCES_CHIP_TAG: String = "sources-chip"

/** The test tag of the note of what needs the listener. */
const val SOURCES_NOTE_TAG: String = "sources-note"

/** The test tag of a source's chip, before its tag (`MB`, `W`). */
const val CHIP_PREFIX: String = "chip:"
