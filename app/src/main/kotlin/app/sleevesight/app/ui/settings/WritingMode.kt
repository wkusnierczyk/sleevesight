package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.core.settings.WriteAutomatically

/**
 * One of *Reactive*'s modes as it is shown: its [name], in the insight card's menu; its [short]
 * name, on its button in the settings and in *Reactive*'s description (#947); what it means there
 * ([means], #945); and the line under the buttons that says what it does while chosen ([detail]).
 */
internal class WritingMode(
    val mode: WriteAutomatically,
    @StringRes val name: Int,
    @StringRes val short: Int,
    @StringRes val means: Int,
    @StringRes val detail: Int,
)

/**
 * *Reactive*'s modes (D5), in the order shown, one vocabulary (#615) for its button group in the
 * settings, by their short names (#947), and the insight card's menu beside *Write insight*, which
 * shows the long names alone: in order of how much is written automatically (#625), *On demand*,
 * *On track skip (auto)*, *On track change (auto)*.
 */
internal val WRITING_MODES: List<WritingMode> =
    listOf(
        WritingMode(
            WriteAutomatically.NEVER,
            R.string.settings_write_never,
            R.string.settings_write_never_short,
            R.string.settings_write_never_means,
            R.string.settings_write_never_detail,
        ),
        WritingMode(
            WriteAutomatically.WHEN_I_SKIP,
            R.string.settings_write_when_skip,
            R.string.settings_write_when_skip_short,
            R.string.settings_write_when_skip_means,
            R.string.settings_write_when_skip_detail,
        ),
        WritingMode(
            WriteAutomatically.ON_EVERY_TRACK_CHANGE,
            R.string.settings_write_every_change,
            R.string.settings_write_every_change_short,
            R.string.settings_write_every_change_means,
            R.string.settings_write_every_change_detail,
        ),
    )

/** The shown name of [mode]. */
@StringRes
internal fun writingModeName(mode: WriteAutomatically): Int = WRITING_MODES.first { it.mode == mode }.name
