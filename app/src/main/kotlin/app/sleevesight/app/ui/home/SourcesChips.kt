package app.sleevesight.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.sleevesight.app.R
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.theme.CHIP_PADDING
import app.sleevesight.app.ui.theme.CHIP_SHAPE
import app.sleevesight.app.ui.theme.ChipFrame
import app.sleevesight.app.ui.theme.CompactChipFrame
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.app.ui.theme.developerMarkInset
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId

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
    PanelChip(
        stringResource(R.string.home_sources_title),
        if (opensPage) SOURCES_PAGE_LINK_TAG else SOURCES_CHIP_TAG,
        modifier = modifier,
        action = stringResource(R.string.home_page_open).takeIf { opensPage },
        collapsed = !opensPage,
        heading = true,
        onClick = onClick,
    )
}

/**
 * A chip on Home that opens a panel in its place, or a page, drawn as a genre chip is
 * ([ChipFrame]): *Sources*' ([SourcesChip], #623) and *Dates*' (#724). Named [name], tagged [tag]; a
 * screen reader reads it as a button, named [description] ([name] unless given: the song's row's
 * "Song, Paper Boats", #894), a [heading] when it is one, collapsed while [collapsed] (its panel
 * opens in its place, so it is only ever collapsed), its action named [action] when given. With
 * [width], it is that wide, its name centred in it (the rows of the song, the artist and the album,
 * #894), with the **AI** mark after it when [ai] (#948, [AiLabel]; [description] then says "uses AI").
 * [modifier] goes on the chip's outside: a focus requester.
 */
@Suppress("LongParameterList") // What the chip says and is, each its own.
@Composable
internal fun PanelChip(
    name: String,
    tag: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    collapsed: Boolean = true,
    heading: Boolean = false,
    description: String = name,
    width: Dp? = null,
    ai: Boolean = false,
    onClick: () -> Unit,
) {
    val folded = stringResource(R.string.home_fold_collapsed)
    val (fill, content) = chipColours(marked = false)
    ChipFrame(
        fill,
        modifier = modifier.testTag(tag).then(width?.let { Modifier.width(it) } ?: Modifier),
        action =
            Modifier
                .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
                .clearAndSetSemantics {
                    if (heading) heading()
                    contentDescription = description
                    if (collapsed) stateDescription = folded
                },
    ) {
        if (width == null) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = content)
        } else if (ai) {
            // The mark after the name, centred with it; read in [description], with the rest.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides content) {
                    ProvideTextStyle(MaterialTheme.typography.labelLarge) { AiLabel(name, described = description) }
                }
            }
        } else {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * What needs the listener, in one note (#440): each source that needs its key, "Last.fm needs your
 * key, in Settings.", the settings a link ([openSettings]); each one that could not be reached or
 * failed. Those that could not be reached in English are named together, in one sentence where the
 * first was ("Last.fm and MusicBrainz could not be reached.", #1121), with *Retry* beside the note
 * ([retry] of them, null while it is not offered), which asks them again and then runs
 * [afterRetry]; a lookup in the track's language (#902), which *Retry* does not ask, keeps a
 * sentence of its own.
 */
@Composable
internal fun Notes(
    notes: List<SourceNote>,
    openSettings: () -> Unit,
    retry: (Collection<SourceId>) -> (() -> Unit)? = { null },
    afterRetry: () -> Unit = {},
) {
    if (notes.isEmpty()) return
    val settings = stringResource(R.string.home_source_note_settings)
    val unreachable = notes.filter { it is SourceNote.Unreachable && it.language == null }.map { it.source }
    val together =
        unreachable.takeIf { it.isNotEmpty() }?.let { sources ->
            namedTogether(sources.map { chipName(it) })
        }
    val sentences =
        notes.mapNotNull { note ->
            if (note is SourceNote.Unreachable && note.language == null) {
                // Once, where the first of them was.
                together?.takeIf { note.source == unreachable.first() }?.let {
                    stringResource(R.string.home_source_note_unreachable, it) to false
                }
            } else {
                noteSentence(note, settings) to (note is SourceNote.NeedsKey)
            }
        }
    val parts = linkedNote(sentences, settings, openSettings)
    val onRetry = together?.let { retry(unreachable) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkedText(
            parts,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag(SOURCES_NOTE_TAG),
        )
        if (together != null && onRetry != null) {
            LineButton(
                stringResource(R.string.home_retry),
                stringResource(R.string.home_retry_action, together),
                Modifier.testTag(SOURCES_RETRY_TAG),
            ) {
                onRetry()
                afterRetry()
            }
        }
    }
}

// [note]'s sentence, naming its source, [settings] the words of the link in a key's.
@Composable
private fun noteSentence(
    note: SourceNote,
    settings: String,
): String {
    val name = rowName(note.source, note.language)
    return when (note) {
        is SourceNote.NeedsKey -> stringResource(R.string.home_source_note_key, name, settings)
        is SourceNote.Unreachable -> stringResource(R.string.home_source_note_unreachable, name)
        is SourceNote.Failed -> stringResource(R.string.home_source_note_failed, name)
    }
}

// The note's [sentences] one after another, in a sentence that is [linked] its [settings] a link.
private fun linkedNote(
    sentences: List<Pair<String, Boolean>>,
    settings: String,
    openSettings: () -> Unit,
): List<TextPart> =
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

/**
 * *Sources*' table (#662): a row for each of [rows], in two columns, so a citation's letter can be
 * matched to its source. On the left, right-aligned in a column as wide as the widest, a chip with
 * the letter the source's lines and citations use (`SourceId.tag`, the one place it is set); on the
 * right, the source's name on one line and what it found on the next ([statusText]: from the
 * scopes of its own [lines], *Found the track and the artist*, #768). A lookup in the track's
 * language has a row of its own after its source's (#902: *Wikipedia in Hindi*), from its own lines;
 * its letter shows and hides its source's lines with the source's own row's. The chip
 * shows, at a glance, what a tap on its row does to its source's [lines] ([focus]): filled while
 * they show, outlined while they are hidden, a tap showing them again (unfolded to by [fold]);
 * dimmed for a source that gave none, with nothing to show or hide, and its row no button. Each row
 * that filters is one switch, tapped anywhere (#1223), at least 48 dp tall. With grounding
 * [details], the rows of the sources that are off and the statuses that are diagnostics show too,
 * the whole table in the developer mark (#536). A screen reader reads a row that filters as a
 * switch, its source's name and what it found, with its lines shown or hidden; a dimmed one's row
 * as text, its name and what it found. A source whose lines are all [unverified] (#951) has a chip as one with
 * lines has, filled grey while they show ([LetterState.UNVERIFIED]), its state saying they are
 * unverified, and its row says what it found with *(unverified)* after it. Under the table, outside
 * the mark, its legend ([Legend], #723, #951), in the table's columns.
 */
@Suppress("LongParameterList") // What the table shows, each its own.
@Composable
internal fun SourceTable(
    rows: List<SourceChip>,
    details: Boolean,
    lines: List<EvidenceLine>,
    focus: LineFocus,
    fold: SourcesFold,
    unverified: List<EvidenceDraft> = emptyList(),
) {
    if (rows.isEmpty()) return
    val width = chipColumnWidth(rows.map { it.source.tag })
    // Set in as the mark sets the table in, while it is marked, so the legend keeps its columns.
    val inset = if (details) developerMarkInset() else 0.dp
    if (details) {
        DeveloperMark { Table(rows, width, true, lines, unverified, focus, fold) }
    } else {
        Table(rows, width, false, lines, unverified, focus, fold)
    }
    Legend(width, COLUMN_GAP, Modifier.padding(horizontal = inset))
}

@Suppress("LongParameterList") // What the table shows, each its own.
@Composable
private fun Table(
    rows: List<SourceChip>,
    width: Dp,
    details: Boolean,
    lines: List<EvidenceLine>,
    unverified: List<EvidenceDraft>,
    focus: LineFocus,
    fold: SourcesFold,
) {
    // The rows' padding taken back at the table's top and bottom: its first and last rows' contents
    // where they were with a gap between rows, each row still its whole 48 dp touch area (#1223).
    val edge =
        Modifier.layout { measurable, constraints ->
            val inset = (ROW_GAP / 2).roundToPx()
            val placeable = measurable.measure(constraints.offset(vertical = 2 * inset))
            val height = (placeable.height - 2 * inset).coerceAtLeast(0)
            layout(placeable.width, height) { placeable.place(0, -inset) }
        }
    Column(modifier = Modifier.testTag(SOURCES_TABLE_TAG).then(edge)) {
        for (row in rows) {
            val verified = row.own(lines).isNotEmpty()
            val onlyUnverified = !verified && row.ownUnverified(unverified).isNotEmpty()
            val shown = row.filters.none { it in focus.hidden }
            val state =
                when {
                    !verified && !onlyUnverified -> LetterState.DIMMED
                    !shown -> LetterState.HIDDEN
                    verified -> LetterState.SHOWN
                    else -> LetterState.UNVERIFIED
                }
            // Lines shown again are unfolded to, wherever they fall in the list; a source's
            // unverified lines, after every evidence line shown, when it has no other (#951).
            SourceRow(row, width, state, onlyUnverified, lines, details) {
                val reached = focus.toggle(row)
                if (reached != null) {
                    fold.reach(reached)
                } else {
                    // Unfolded to the first, and scrolled to it (#951).
                    firstUnverified(row, lines, unverified, focus.hidden)?.let { (place, key) ->
                        fold.reach(place)
                        focus.reveal(key)
                    }
                }
            }
        }
    }
}

/**
 * Whether a source's lookup failed or its match was rejected; not one rejected only for want of
 * MusicBrainz while it could not be reached (#1069), which says so in the row's own colour.
 */
private val ChipStatus.wentWrong: Boolean
    get() =
        this == ChipStatus.Failed ||
            (this as? ChipStatus.Done)?.let { it.outcome is Outcome.Rejected && !it.withoutMusicBrainz } == true

/**
 * One row of the table: [chip]'s letter, right-aligned in a column [width] wide, its name and what
 * it found, from its own lines among [lines] (#768), marked *(unverified)* when they are all
 * [unverified] (#951). A row whose chip filters is one switch, the whole row (#1223), as the
 * legend's line is one button: a tap anywhere on it, the chip, the name or what it found, shows or
 * hides its source's lines ([onToggle]), and a screen reader reads it once, its name, what it found
 * and whether its lines show (and that they are unverified when they are). A dimmed chip's row is
 * read as one text, its name and what it found, and does nothing on a tap.
 */
@Suppress("LongParameterList") // The row, its letter's column and state, what it found from, and what a tap does.
@Composable
private fun SourceRow(
    chip: SourceChip,
    width: Dp,
    state: LetterState,
    unverified: Boolean,
    lines: List<EvidenceLine>,
    details: Boolean,
    onToggle: () -> Unit,
) {
    val name = rowName(chip.source, chip.language)
    val action =
        if (state == LetterState.DIMMED) {
            Modifier.semantics(mergeDescendants = true) {}
        } else {
            val label = stringResource(R.string.home_source_filter, name)
            val shown = state != LetterState.HIDDEN
            val said =
                stringResource(
                    when {
                        unverified && shown -> R.string.home_source_unverified_shown
                        unverified -> R.string.home_source_unverified_hidden
                        shown -> R.string.home_source_lines_shown
                        else -> R.string.home_source_lines_hidden
                    },
                )
            Modifier
                .clickable(onClickLabel = label, role = Role.Switch, onClick = onToggle)
                .semantics {
                    stateDescription = said
                    toggleableState = ToggleableState(shown)
                }
        }
    // At least [ROW_TARGET] tall, a touch area, the row's content [ROW_GAP] / 2 in from its top and
    // bottom: rows as far apart as with a gap between them, and a touch between two rows goes to one.
    Row(
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .testTag(ROW_PREFIX + chip.key)
                .fillMaxWidth()
                .heightIn(min = ROW_TARGET)
                .then(action)
                .padding(vertical = ROW_GAP / 2),
    ) {
        Box(Modifier.width(width), contentAlignment = Alignment.CenterEnd) {
            LetterChip(chip, state)
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // With grounding details, a failure and a rejected match in the error colour, as the
            // status chips had them (#440).
            val colors = MaterialTheme.colorScheme
            val status = statusText(chip, lines, details, unverified)
            val style = MaterialTheme.typography.bodySmall
            val tag = Modifier.testTag(STATUS_PREFIX + chip.key)
            val color = if (details && chip.status.wentWrong) colors.error else colors.onSurfaceVariant
            // A lookup under way with the turning record before its *Looking…* (#785); the status the
            // same node, and the row as tall, once it ends.
            Busy(status, style, color = color, textModifier = tag, record = chip.status == ChipStatus.Pending)
        }
    }
}

/**
 * A source's letter as a chip, drawn as a genre chip is but without its 48 dp laid out
 * ([CompactChipFrame]), in its [state]'s look ([look]): filled while its lines show (grey while they
 * are all unverified, #951), outlined while hidden, and dimmed with none. Not a button itself, nor a
 * node a screen reader stops on: its row is the switch (#1223), which says what the chip shows.
 */
@Composable
private fun LetterChip(
    chip: SourceChip,
    state: LetterState,
) {
    val look = state.look()
    // The outline drawn over the chip, in its shape.
    val outline = look.outline?.let { Modifier.border(it, CHIP_SHAPE) } ?: Modifier
    CompactChipFrame(
        look.fill,
        modifier = Modifier.testTag(CHIP_PREFIX + chip.key).then(outline),
        action = Modifier.clearAndSetSemantics {},
    ) {
        Text(
            chip.source.tag,
            style = MaterialTheme.typography.labelLarge,
            color = look.ink,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A column of chips as wide as the widest of [labels] as a chip draws them ([LetterChip], [PanelChip]):
 * its text, on one line, with the **AI** mark after it as [AiLabel] sets it when [mark] (#948), and
 * [CHIP_PADDING] at each side, each side rounded as it is laid out. The letters' column of *Sources*'
 * table (#662); the buttons of the song's, the artist's and the album's rows (#894).
 */
@Composable
internal fun chipColumnWidth(
    labels: List<String>,
    mark: Boolean = false,
): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val small = MaterialTheme.typography.labelSmall
    val ai = stringResource(R.string.home_ai_mark)
    val density = LocalDensity.current
    return remember(labels, mark, style, small, ai, density) {
        val widest = labels.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
        val gap = with(density) { MARK_GAP.roundToPx() }
        val marked = if (mark) measurer.markWidth(ai, small) + gap else 0
        with(density) { (widest + marked + 2 * CHIP_PADDING.roundToPx()).toDp() }
    }
}

// The space between two rows' contents, and between the table's columns; a row's least height, its
// touch area (#1223).
private val ROW_GAP = 6.dp
private val COLUMN_GAP = 12.dp
private val ROW_TARGET = 48.dp

// The note's link to the settings.
private const val SETTINGS_LINK = "settings"

/** The test tag of *Sources*' chip on Home under *Expandable boxes*, which opens its panel. */
const val SOURCES_CHIP_TAG: String = "sources-chip"

/** The test tag of the note of what needs the listener. */
const val SOURCES_NOTE_TAG: String = "sources-note"

/** The test tag of *Retry* beside *Sources*' note, which asks the sources that could not be reached again (#1121). */
const val SOURCES_RETRY_TAG: String = "sources-note:retry"

/** The test tag of a source's chip, before its tag (`MB`, `W`; `W:hi` in the track's language, #902). */
const val CHIP_PREFIX: String = "chip:"

/** The test tag of what a source's row says it found, before its tag (`MB`, `W`; `W:hi`, #902). */
const val STATUS_PREFIX: String = "source-status:"

/** The test tag of a source's row of the table, before its tag (`MB`, `W`; `W:hi`, #902). */
const val ROW_PREFIX: String = "source-row:"

/** The test tag of *Sources*' table of the sources. */
const val SOURCES_TABLE_TAG: String = "sources-table"
