package app.sleevesight.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.CHIP_PADDING
import app.sleevesight.app.ui.theme.ChipFrame
import app.sleevesight.app.ui.theme.CompactChipFrame
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.core.evidence.EvidenceLine

// *Sources*' chip on Home (#623), its table of the sources (#662) and its note (#440).

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
 * *Sources*' table (#662): a row for each of [rows], in two columns, so a citation's letter can be
 * matched to its source. On the left, right-aligned in a column as wide as the widest, a chip with
 * the letter the source's lines and citations use (`SourceId.tag`, the one place it is set); on the
 * right, the source's name on one line and what it found on the next ([statusText]). The chip
 * shows, at a glance, what a tap does to its source's [lines] ([focus]): filled while they show,
 * outlined while they are hidden, a tap showing them again (unfolded to by [fold]); dimmed for a
 * source that gave none, with nothing to show or hide, and no button. The rows sit close, the
 * chips' 48 dp targets not laid out ([CompactChipFrame]). With grounding [details], the rows
 * of the sources that are off and the statuses that are diagnostics show too, the whole table in
 * the developer mark (#536). A screen reader reads a chip that filters as a switch, its source's
 * name, with its lines shown or hidden, and then what it found; a dimmed one's row as text, its
 * name and what it found.
 */
@Composable
internal fun SourceTable(
    rows: List<SourceChip>,
    details: Boolean,
    lines: List<EvidenceLine>,
    focus: LineFocus,
    fold: SourcesFold,
) {
    if (rows.isEmpty()) return
    if (details) {
        DeveloperMark { Table(rows, true, lines, focus, fold) }
    } else {
        Table(rows, false, lines, focus, fold)
    }
}

@Composable
private fun Table(
    rows: List<SourceChip>,
    details: Boolean,
    lines: List<EvidenceLine>,
    focus: LineFocus,
    fold: SourcesFold,
) {
    val width = letterColumnWidth(rows.map { it.source.tag })
    Column(
        modifier = Modifier.testTag(SOURCES_TABLE_TAG),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        for (row in rows) {
            val state =
                when {
                    lines.none { it.source in row.filters } -> LetterState.DIMMED
                    row.filters.none { it in focus.hidden } -> LetterState.SHOWN
                    else -> LetterState.HIDDEN
                }
            // Lines shown again are unfolded to, wherever they fall in the list.
            SourceRow(row, width, state, details) { focus.toggle(row)?.let(fold::reach) }
        }
    }
}

/** What a source's chip in the table shows: its lines shown or hidden, or none to show. */
private enum class LetterState { SHOWN, HIDDEN, DIMMED }

/** One row of the table: [chip]'s letter, right-aligned in a column [width] wide, its name and what it found. */
@Composable
private fun SourceRow(
    chip: SourceChip,
    width: Dp,
    state: LetterState,
    details: Boolean,
    onToggle: () -> Unit,
) {
    val name = chipName(chip.source)
    val filters = state != LetterState.DIMMED
    Row(
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(ROW_PREFIX + chip.source.tag),
    ) {
        Box(Modifier.width(width), contentAlignment = Alignment.CenterEnd) {
            LetterChip(chip, name, state, onToggle)
        }
        // A dimmed chip's row is read as one text, its name and what it found; a switch's says its
        // name itself, so the name here is not read again.
        Column(Modifier.weight(1f).then(if (filters) Modifier else Modifier.semantics(mergeDescendants = true) {})) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (filters) Modifier.clearAndSetSemantics {} else Modifier,
            )
            Text(
                statusText(chip.status, details),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A source's letter as a chip, drawn as a genre chip is but without its 48 dp laid out
 * ([CompactChipFrame]): [LetterState.SHOWN], filled as a genre marked in its panel is
 * (`chipColours(marked = true)`); [LetterState.HIDDEN], a solid outline and no fill;
 * [LetterState.DIMMED], a faint outline, the letter faded, and not a button.
 */
@Composable
private fun LetterChip(
    chip: SourceChip,
    name: String,
    state: LetterState,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (marked, onMarked) = chipColours(marked = true)
    val fill = if (state == LetterState.SHOWN) marked else Color.Transparent
    val outline = if (state == LetterState.DIMMED) colors.outlineVariant else colors.onSurface
    val letter =
        when (state) {
            LetterState.SHOWN -> onMarked
            LetterState.HIDDEN -> colors.onSurface
            LetterState.DIMMED -> colors.outline
        }
    val action =
        if (state == LetterState.DIMMED) {
            Modifier.clearAndSetSemantics {}
        } else {
            val label = stringResource(R.string.home_source_filter, name)
            val shown = state == LetterState.SHOWN
            val said =
                stringResource(if (shown) R.string.home_source_lines_shown else R.string.home_source_lines_hidden)
            Modifier
                .clickable(onClickLabel = label, role = Role.Switch, onClick = onToggle)
                .clearAndSetSemantics {
                    contentDescription = name
                    stateDescription = said
                    toggleableState = ToggleableState(shown)
                }
        }
    CompactChipFrame(
        fill,
        modifier = Modifier.testTag(CHIP_PREFIX + chip.source.tag),
        outline = outline,
        action = action,
    ) {
        Text(
            chip.source.tag,
            style = MaterialTheme.typography.labelLarge,
            color = letter,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The letters' column: as wide as the widest of [tags] as [LetterChip] draws them, its text and
 * [CHIP_PADDING] at each side, each side rounded as it is laid out.
 */
@Composable
private fun letterColumnWidth(tags: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    return remember(tags, style, density) {
        val widest = tags.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0
        with(density) { (widest + 2 * CHIP_PADDING.roundToPx()).toDp() }
    }
}

// The space between two rows of the table, and between its columns.
private val ROW_GAP = 6.dp
private val COLUMN_GAP = 12.dp

// The note's link to the settings.
private const val SETTINGS_LINK = "settings"

/** The test tag of *Sources*' chip on Home under *Expandable boxes*, which opens its panel. */
const val SOURCES_CHIP_TAG: String = "sources-chip"

/** The test tag of the note of what needs the listener. */
const val SOURCES_NOTE_TAG: String = "sources-note"

/** The test tag of a source's chip, before its tag (`MB`, `W`). */
const val CHIP_PREFIX: String = "chip:"

/** The test tag of a source's row of the table, before its tag (`MB`, `W`). */
const val ROW_PREFIX: String = "source-row:"

/** The test tag of *Sources*' table of the sources. */
const val SOURCES_TABLE_TAG: String = "sources-table"
