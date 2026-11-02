package app.sleevesight.app.ui.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The tables of *Load keys* and the summary after a key is saved (#763): columns as wide as their
// widest cell, the first taking what is left; a row read by a screen reader as one item.

/** Where a column's cells sit in it; its header always at the start. */
internal enum class CellAlign { START, END, CENTER }

/**
 * A row's checkbox or radio: whether it is [on], drawn as a radio when [radio], and what a tap
 * sets; only while [enabled] (not while the page's *Next* runs).
 */
internal class RowChoice(
    val on: Boolean,
    val radio: Boolean,
    val enabled: Boolean = true,
    val onChange: (Boolean) -> Unit,
)

/** A row of a [ChoiceTable]. */
internal sealed interface TableRow {
    /**
     * A row of [cells], one for each column; a screen reader reads it as [spoken], with its
     * [choice], if any, which a tap on its first cell sets too.
     */
    class Cells(
        val spoken: AnnotatedString,
        val cells: List<@Composable () -> Unit>,
        val choice: RowChoice? = null,
    ) : TableRow

    /** A line across the table, [text], dimmed: *Already configured*. */
    class Across(
        val text: String,
    ) : TableRow
}

/**
 * A table of [rows] under [headers], each column's cells placed as [aligns] says, the headers at
 * the start. Each row's first cell carries the row to a screen reader ([TableRow.Cells.spoken],
 * with its checkbox or radio); the other cells it skips. A cell of a [RowChoice] is its checkbox,
 * or its radio, which can be unselected.
 */
@Composable
internal fun ChoiceTable(
    headers: List<String>,
    aligns: List<CellAlign>,
    rows: List<TableRow>,
    modifier: Modifier = Modifier,
) {
    val header = MaterialTheme.typography.labelLarge
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val across = rows.map { it is TableRow.Across }
    TableGrid(columns = headers.size, aligns = aligns, across = across, modifier = modifier) {
        for (text in headers) Text(text, style = header, color = quiet)
        for (row in rows) {
            when (row) {
                is TableRow.Across -> {
                    Text(row.text, style = MaterialTheme.typography.titleSmall, color = quiet)
                }

                is TableRow.Cells -> {
                    row.cells.forEachIndexed { i, cell ->
                        if (i == 0) FirstCell(row, cell) else Box(Modifier.clearAndSetSemantics { }) { cell() }
                    }
                }
            }
        }
    }
}

/** The first cell of [row], [cell], which a screen reader reads the whole row by, and a tap sets its choice. */
@Composable
private fun FirstCell(
    row: TableRow.Cells,
    cell: @Composable () -> Unit,
) {
    val choice = row.choice
    val semantics =
        Modifier.clearAndSetSemantics {
            text = row.spoken
            if (choice != null) {
                if (choice.radio) {
                    role = Role.RadioButton
                    selected = choice.on
                } else {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(choice.on)
                }
                if (choice.enabled) {
                    onClick {
                        choice.onChange(!choice.on)
                        true
                    }
                } else {
                    disabled()
                }
            }
        }
    // A tap, with no semantics of its own: the row's are set above.
    val tap =
        if (choice == null || !choice.enabled) {
            Modifier
        } else {
            Modifier.pointerInput(choice) { detectTapGestures { choice.onChange(!choice.on) } }
        }
    Column(modifier = semantics.then(tap)) { cell() }
}

/** The cell of a row's [choice]: its checkbox, or its radio, a tap on which, selected, unselects it. */
@Composable
internal fun ChoiceCell(choice: RowChoice) {
    if (choice.radio) {
        RadioButton(selected = choice.on, onClick = { choice.onChange(!choice.on) }, enabled = choice.enabled)
    } else {
        Checkbox(checked = choice.on, onCheckedChange = choice.onChange, enabled = choice.enabled)
    }
}

/**
 * Its children as a table of [columns]: the headers, then each row's cells, or, for a row
 * [across], one child the table's width. Each column but the first is as wide as its widest cell;
 * the first takes what is left. A row's cells share the first line: a text's first baseline, and
 * the middle of a cell with none (a checkbox), set a little above it, at the text's middle.
 */
@Composable
private fun TableGrid(
    columns: Int,
    aligns: List<CellAlign>,
    across: List<Boolean>,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val middle = MaterialTheme.typography.bodyMedium.fontSize * MIDDLE_ABOVE_BASELINE
    Layout(content, modifier) { cells, constraints ->
        val rowGap = ROW_GAP.roundToPx()
        val lift = if (middle.isSp) middle.toPx().toInt() else DEFAULT_LIFT.sp.roundToPx()
        // The children by row: the headers, then each row's cells or its one line across.
        val rows = mutableListOf(cells.subList(0, columns))
        var at = columns
        for (isAcross in across) {
            val size = if (isAcross) 1 else columns
            rows += cells.subList(at, at + size)
            at += size
        }
        val gridRows = rows.filter { it.size == columns }
        val widths =
            (1 until columns).map { c -> gridRows.maxOf { it[c].maxIntrinsicWidth(Constraints.Infinity) } }
        val wide = COLUMN_GAP.roundToPx()
        val full =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                gridRows.maxOf { it[0].maxIntrinsicWidth(Constraints.Infinity) } + widths.sum() + wide * (columns - 1)
            }
        // Where the first column would break a word (a large font), the gaps narrow first, to a
        // minimum, so that its longest word keeps to one line where it can.
        val longestWord = gridRows.maxOf { it[0].minIntrinsicWidth(Constraints.Infinity) }
        val room = (full - longestWord - widths.sum()) / (columns - 1).coerceAtLeast(1)
        val gap = room.coerceIn(NARROW_GAP.roundToPx(), wide)
        val first = (full - widths.sum() - gap * (columns - 1)).coerceAtLeast(0)
        val columnWidths = listOf(first) + widths
        val starts = columnWidths.runningFold(0) { x, w -> x + w + gap }
        val placedRows =
            rows.mapIndexed { r, row ->
                if (row.size == 1 && columns > 1) {
                    listOf(row[0].measure(Constraints(maxWidth = full)))
                } else {
                    row.mapIndexed { c, cell -> cell.measure(Constraints(maxWidth = columnWidths[c])) }
                } to (r == 0)
            }
        val laid = placedRows.map { (placed, isHeader) -> LaidRow(placed, lift, isHeader) }
        val height = laid.sumOf { it.height } + rowGap * (laid.size - 1).coerceAtLeast(0)
        layout(full, constraints.constrainHeight(height)) {
            var y = 0
            for (row in laid) {
                row.placed.forEachIndexed { c, cell ->
                    val align = if (row.header || row.placed.size == 1) CellAlign.START else aligns[c]
                    val x =
                        when (align) {
                            CellAlign.START -> starts[c]
                            CellAlign.END -> starts[c] + columnWidths[c] - cell.width
                            CellAlign.CENTER -> starts[c] + (columnWidths[c] - cell.width) / 2
                        }
                    cell.placeRelative(x, y + row.tops[c])
                }
                y += row.height + rowGap
            }
        }
    }
}

/**
 * A row of [TableGrid], [placed], its cells dropped so their first lines line up: a text's first
 * baseline, a cell with none centred [lift] above that line.
 */
private class LaidRow(
    val placed: List<Placeable>,
    lift: Int,
    val header: Boolean,
) {
    private val anchors =
        placed.map { cell ->
            cell[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: (cell.height / 2 + lift)
        }
    private val line = anchors.max()
    val tops = anchors.map { line - it }
    val height = placed.indices.maxOf { tops[it] + placed[it].height }
}

/** Where the middle of a line of text is above its baseline, as a share of the font's size. */
private const val MIDDLE_ABOVE_BASELINE = 0.35f

/** That lift, in sp, when the font's size is not in sp. */
private const val DEFAULT_LIFT = 5

/** The room between the table's columns. */
private val COLUMN_GAP = 16.dp

/** The least room between them, where the first column's words would otherwise break. */
private val NARROW_GAP = 6.dp

/** The room between its rows. */
private val ROW_GAP = 4.dp
