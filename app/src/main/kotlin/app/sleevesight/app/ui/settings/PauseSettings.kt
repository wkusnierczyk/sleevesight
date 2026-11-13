package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.ChoiceButtonGroup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically

/**
 * *Saver* (#203, #444): a switch, on while automatic writing pauses after a number of track
 * changes while Sleevesight is not on screen (`pauseAutoWriteAfter`), its description saying that
 * number, *n*, and under it, while on, a button group of the counts by their numbers alone
 * ([saverCounts], #1212, #1234), so no label wraps at any font size, with no name or line of its
 * own; a value stored that is not among the choices is offered too. A screen reader reads each
 * button after the setting's name ("Saver: 5", [ChoiceButtonGroup]'s `name`). Switched on, it
 * pauses after the default number ([Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER]), which the
 * description names while it is off. Fixed while nothing is written automatically (*Show
 * insights* off, or *Reactive* under *Demand*).
 */
@Composable
internal fun SaverRows(
    settings: Settings,
    update: Update,
) {
    val writes = settings.writeInsights && settings.writeAutomatically != WriteAutomatically.NEVER
    val count = settings.pauseAutoWriteAfter
    val title = stringResource(R.string.settings_pause_auto_write)
    SwitchRow(
        title = title,
        summary = saverSummary(count),
        checked = count != null,
        // Nothing is written automatically: *Saver* does not apply.
        applies = writes,
    ) { on -> update { it.copy(pauseAutoWriteAfter = if (on) Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER else null) } }
    if (count == null || !shownInSearch(title)) return
    ChoiceButtonGroup(
        saverCounts(count),
        count,
        { n -> update { it.copy(pauseAutoWriteAfter = n) } },
        Modifier.padding(bottom = 8.dp),
        name = title,
        // Nothing is written automatically: the count does not apply.
        enabled = writes,
    )
}

/** *Saver* and its counts, as the settings' search finds them (#649). */
@Composable
internal fun saverIndexRows(settings: Settings): List<IndexedRow> {
    val count = settings.pauseAutoWriteAfter
    val counts = count?.let { saverCounts(it).map { (_, label) -> label } }.orEmpty()
    val title = stringResource(R.string.settings_pause_auto_write)
    return listOf(IndexedRow(title, listOf(title, saverSummary(count)) + counts))
}

/** *Saver*'s description, naming [count], or the default count while it is off (null). */
@Composable
private fun saverSummary(count: Int?): String {
    val n = count ?: Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER
    return pluralStringResource(R.plurals.settings_pause_auto_write_summary, n, n)
}

/** *Saver*'s counts to choose from, [count] the one set among them. */
internal fun saverCounts(count: Int): List<Pair<Int, String>> =
    (Settings.PAUSE_AUTO_WRITE_CHOICES + count).distinct().sorted().map { it to pauseLabel(it) }

/** A choice of *Saver*'s count, as its button names it: the number alone (#1212). */
private fun pauseLabel(count: Int): String = count.toString()
