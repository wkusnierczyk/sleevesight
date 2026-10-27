package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.core.settings.WriteAutomatically

/** One of *Reactive*'s modes as it is shown: its [name], and the line under it that says what it does ([detail]). */
internal class WritingMode(
    val mode: WriteAutomatically,
    @StringRes val name: Int,
    @StringRes val detail: Int,
)

/**
 * *Reactive*'s modes (D5), in the order shown, one vocabulary (#615) for its choice dialog in the
 * settings and the insight card's menu beside *Write insight*, which shows the names alone: in
 * order of how much is written automatically (#625), *On demand*, *On track skip (auto)*, *On track
 * change (auto)*.
 */
internal val WRITING_MODES: List<WritingMode> =
    listOf(
        WritingMode(WriteAutomatically.NEVER, R.string.settings_write_never, R.string.settings_write_never_detail),
        WritingMode(
            WriteAutomatically.WHEN_I_SKIP,
            R.string.settings_write_when_skip,
            R.string.settings_write_when_skip_detail,
        ),
        WritingMode(
            WriteAutomatically.ON_EVERY_TRACK_CHANGE,
            R.string.settings_write_every_change,
            R.string.settings_write_every_change_detail,
        ),
    )

/** The shown name of [mode]. */
@StringRes
internal fun writingModeName(mode: WriteAutomatically): Int = WRITING_MODES.first { it.mode == mode }.name
