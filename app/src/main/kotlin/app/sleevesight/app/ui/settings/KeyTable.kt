package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.appendCode
import app.sleevesight.app.ui.theme.codeStyle

// The key screens' table of keys (#542): *API Key* and *Ending*, a row a key.

/**
 * The keys [rows] as a table of two columns, *API Key* and *Ending*, each as wide as its widest
 * cell and aligned to the start: a row names the key's service ([keyService]), with, under it, a
 * note where there is one (new, replacing a saved key, or no longer readable), and its last
 * characters as code ([codeStyle]). A screen reader reads each row as one item ([spokenRow]):
 * "Anthropic, ending a 1 B 2"; the headers once.
 */
@Composable
internal fun KeyTable(rows: List<KeyRow>) {
    val body = MaterialTheme.typography.bodyMedium
    val header = MaterialTheme.typography.labelLarge
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val code = codeStyle()
    KeyGrid {
        Text(stringResource(R.string.key_table_name), style = header, color = quiet)
        Text(stringResource(R.string.key_table_ending), style = header, color = quiet)
        for (row in rows) {
            val name = stringResource(keyService(row.key.id))
            val spoken = spokenRow(row, name)
            Column(modifier = Modifier.clearAndSetSemantics { text = spoken }) {
                Text(name, style = body)
                noteOf(row)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = quiet) }
            }
            // The row's first cell says it all to a screen reader.
            Text(
                buildAnnotatedString { row.key.ending?.let { appendCode(it, code) } },
                style = body,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
        }
    }
}

/** What a screen reader says of [row], whose service is [name]: its ending read character by character. */
@Composable
private fun spokenRow(
    row: KeyRow,
    name: String,
): AnnotatedString {
    val ending = row.key.ending ?: return AnnotatedString(stringResource(R.string.key_unreadable, name))
    val line =
        when (row.replaces) {
            null -> R.string.key_ending
            true -> R.string.setup_replaces
            false -> R.string.setup_new
        }
    return keyLine(line, name, ending)
}

/** The note under [row]'s service, if any. */
@StringRes
private fun noteOf(row: KeyRow): Int? =
    when {
        row.key.ending == null -> R.string.key_table_unreadable
        row.replaces == true -> R.string.load_keys_replaces
        row.replaces == false -> R.string.load_keys_new
        else -> null
    }

/**
 * Its children, cells in pairs, as rows of two columns: each column as wide as its widest cell (the
 * first narrowed to leave the second its room on a narrow screen), the two cells of a row on one
 * baseline.
 */
@Composable
private fun KeyGrid(content: @Composable () -> Unit) {
    Layout(content) { cells, constraints ->
        val gap = COLUMN_GAP.roundToPx()
        val rowGap = ROW_GAP.roundToPx()
        val firsts = cells.filterIndexed { i, _ -> i % 2 == 0 }
        val seconds = cells.filterIndexed { i, _ -> i % 2 == 1 }
        val secondWidth = seconds.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val firstWanted = firsts.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val firstWidth =
            if (constraints.hasBoundedWidth) {
                firstWanted.coerceAtMost((constraints.maxWidth - gap - secondWidth).coerceAtLeast(0))
            } else {
                firstWanted
            }
        val placed =
            cells.mapIndexed { i, cell ->
                cell.measure(Constraints.fixedWidth(if (i % 2 == 0) firstWidth else secondWidth))
            }
        val rows = placed.chunked(2).map { (first, second) -> GridRow(first, second) }
        val height = rows.sumOf { it.height } + rowGap * (rows.size - 1).coerceAtLeast(0)
        val width = constraints.constrainWidth(firstWidth + gap + secondWidth)
        layout(width, constraints.constrainHeight(height)) {
            var y = 0
            for (row in rows) {
                row.first.place(0, y + row.firstDrop)
                row.second.place(firstWidth + gap, y + row.secondDrop)
                y += row.height + rowGap
            }
        }
    }
}

/** A row of [KeyGrid]: its two cells, each dropped so their first lines share a baseline. */
private class GridRow(
    val first: Placeable,
    val second: Placeable,
) {
    private val firstBaseline = first[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
    private val secondBaseline = second[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
    val firstDrop = (secondBaseline - firstBaseline).coerceAtLeast(0)
    val secondDrop = (firstBaseline - secondBaseline).coerceAtLeast(0)
    val height = maxOf(first.height + firstDrop, second.height + secondDrop)
}

/** The room between the table's columns. */
private val COLUMN_GAP = 24.dp

/** The room between its rows. */
private val ROW_GAP = 6.dp
