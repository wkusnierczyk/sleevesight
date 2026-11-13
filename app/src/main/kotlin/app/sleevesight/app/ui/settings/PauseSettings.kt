package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically

/**
 * *Saver* (#203, #444): a switch, on while automatic writing pauses after a number of track
 * changes while Sleevesight is not on screen (`pauseAutoWriteAfter`), and under it, while on, that
 * number, *n*: a line saying what it counts, in place of a name, over a button group of the counts
 * by their numbers alone ([saverCounts], #947, #1212), so no label wraps at any font size; a value
 * stored that is not among the choices is offered too. A screen reader reads each button after the
 * line ([ChoiceButtonGroup]'s `name`). Switched on, it pauses after the default number
 * ([Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER]). Fixed while nothing is written automatically (*Show
 * insights* off, or *Reactive* under *Demand*). What the number chosen does shows under the
 * buttons in full, not folded (#874), and is read with each.
 */
@Composable
internal fun SaverRows(
    settings: Settings,
    update: Update,
) {
    val writes = settings.writeInsights && settings.writeAutomatically != WriteAutomatically.NEVER
    val count = settings.pauseAutoWriteAfter
    SwitchRow(
        title = stringResource(R.string.settings_pause_auto_write),
        summary = stringResource(R.string.settings_pause_auto_write_summary),
        checked = count != null,
        // Nothing is written automatically: *Saver* does not apply.
        applies = writes,
    ) { on -> update { it.copy(pauseAutoWriteAfter = if (on) Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER else null) } }
    if (count == null) return
    val options = saverCounts(count)
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_pause_auto_write_after),
        summary = null,
        choices = options,
        selected = count,
        details = pauseSummaries(options.map { it.first }, settings.lookUpInBackground),
        // Nothing is written automatically: the count does not apply.
        applies = writes,
    ) { n -> update { it.copy(pauseAutoWriteAfter = n) } }
}

/** *Saver* and, while it is on, its count, as the settings' search finds them (#649). */
@Composable
internal fun saverIndexRows(settings: Settings): List<IndexedRow> {
    val saver =
        indexRow(
            stringResource(R.string.settings_pause_auto_write),
            stringResource(R.string.settings_pause_auto_write_summary),
        )
    val count = settings.pauseAutoWriteAfter ?: return listOf(saver)
    val options = saverCounts(count)
    val stopAfter =
        choiceRow(
            stringResource(R.string.settings_pause_auto_write_after),
            null,
            pauseLabel(count),
            options.map { it.second } + pauseSummaries(options.map { it.first }, settings.lookUpInBackground).values,
        )
    return listOf(saver, stopAfter)
}

/** What each of [counts] does while chosen ([pauseSummary]), by count. */
@Composable
private fun pauseSummaries(
    counts: List<Int>,
    background: Boolean,
): Map<Int, String> = counts.associateWith { pauseSummary(it, background) }

/**
 * What the count chosen, [count], does. With *Look up in the background* off ([background] false),
 * nothing is written away, so the count decides whether writing goes on once the app is back.
 */
@Composable
private fun pauseSummary(
    count: Int,
    background: Boolean,
): String {
    val plural =
        if (background) {
            R.plurals.settings_pause_auto_write_summary_count
        } else {
            R.plurals.settings_pause_auto_write_summary_count_foreground
        }
    return pluralStringResource(plural, count, count)
}

/** *Saver*'s counts to choose from, [count] the one set among them. */
internal fun saverCounts(count: Int): List<Pair<Int, String>> =
    (Settings.PAUSE_AUTO_WRITE_CHOICES + count).distinct().sorted().map { it to pauseLabel(it) }

/** A choice of *Saver*'s count, as its button names it: the number alone (#1212). */
private fun pauseLabel(count: Int): String = "%d".format(count)
