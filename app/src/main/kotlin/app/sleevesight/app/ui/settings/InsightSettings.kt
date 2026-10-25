package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlin.time.Duration.Companion.seconds

// *Insights* (#444): *Show insights*, *Reactive* with *Crossfade*, *Proactive*, *Saver*, *Look up in
// the background* and *Tell an artist's facts once per session* (#407).

private val MAX_CROSSFADE_SECONDS =
    Crossfade.Fixed.MAX.inWholeSeconds
        .toInt()

/**
 * The insights' settings: *Show insights* (`writeInsights`), the master switch; *Reactive*
 * (`writeAutomatically`, D5), which track changes write an insight without a tap, with *Crossfade*
 * under it while it is *On skip*, the only choice that uses it; *Proactive* (`prepareNextTrack`,
 * D13), the next track's evidence ahead; *Saver* (`pauseAutoWriteAfter`, #203); and *Look up in the
 * background* (D6) beside it, since both govern what happens while Sleevesight is away; and *Tell an
 * artist's facts once per session* (`artistFactsOncePerSession`, #407), what an insight's prompt
 * holds of the artist's facts. The rows about writing cannot be changed while *Show insights* is
 * off; *Proactive* can, as it writes nothing.
 */
@Composable
internal fun InsightsSection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    SwitchRow(
        title = stringResource(R.string.settings_write_insights),
        summary = stringResource(R.string.settings_write_insights_summary),
        checked = settings.writeInsights,
    ) { on -> update { it.copy(writeInsights = on) } }

    val modes =
        listOf(
            WriteAutomatically.ON_EVERY_TRACK_CHANGE to stringResource(R.string.settings_write_every_change),
            WriteAutomatically.WHEN_I_SKIP to stringResource(R.string.settings_write_when_skip),
            WriteAutomatically.NEVER to stringResource(R.string.settings_write_never),
        )
    ValueRow(
        title = stringResource(R.string.settings_write_automatically),
        value = modes.first { it.first == settings.writeAutomatically }.second,
        summary = stringResource(R.string.settings_write_automatically_summary),
        onClick =
            {
                open(
                    SettingsDialog.Choice(
                        R.string.settings_write_automatically,
                        modes,
                        settings.writeAutomatically,
                    ) { mode ->
                        update { it.copy(writeAutomatically = mode) }
                    },
                )
            }.takeIf { settings.writeInsights },
    )
    if (settings.writeAutomatically == WriteAutomatically.WHEN_I_SKIP) CrossfadeRow(settings, update, open)

    SwitchRow(
        title = stringResource(R.string.settings_prepare_next),
        summary = stringResource(R.string.settings_prepare_next_summary),
        checked = settings.prepareNextTrack == PrepareNextTrack.EVIDENCE,
    ) { on -> update { it.copy(prepareNextTrack = if (on) PrepareNextTrack.EVIDENCE else PrepareNextTrack.OFF) } }

    SaverRows(settings, update, open)

    SwitchRow(
        title = stringResource(R.string.settings_background),
        summary = stringResource(R.string.settings_background_summary),
        checked = settings.lookUpInBackground,
    ) { on -> update { it.copy(lookUpInBackground = on) } }

    SwitchRow(
        title = stringResource(R.string.settings_artist_facts_once),
        summary = stringResource(R.string.settings_artist_facts_once_summary),
        checked = settings.artistFactsOncePerSession,
        onChange =
            { on: Boolean -> update { it.copy(artistFactsOncePerSession = on) } }.takeIf { settings.writeInsights },
    )
}

/**
 * *Crossfade* (D5): autodetect, or a fixed 0 to 15 seconds, for telling a skip from a natural end
 * under *On skip*; indented under *Reactive*, as it belongs to that choice.
 */
@Composable
private fun CrossfadeRow(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val options =
        listOf<Pair<Crossfade, String>>(Crossfade.Autodetect to crossfadeLabel(Crossfade.Autodetect)) +
            (0..MAX_CROSSFADE_SECONDS).map { s -> Crossfade.Fixed(s.seconds).let { it to crossfadeLabel(it) } }
    ValueRow(
        title = stringResource(R.string.settings_crossfade),
        value = crossfadeLabel(settings.crossfade),
        summary = stringResource(R.string.settings_crossfade_summary),
        indent = true,
        onClick =
            {
                open(
                    SettingsDialog.Choice(R.string.settings_crossfade, options, settings.crossfade) { c ->
                        update { it.copy(crossfade = c) }
                    },
                )
            }.takeIf { settings.writeInsights },
    )
}
