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
// the background*, *Don't repeat artist facts* (#407, #546) and *Search with AI* (#773), then reading aloud (#325).

private val MAX_CROSSFADE_SECONDS =
    Crossfade.Fixed.MAX.inWholeSeconds
        .toInt()

/**
 * The insights' settings: *Show insights* (`writeInsights`), the master switch; *Reactive*
 * (`writeAutomatically`, D5), which track changes write an insight without a tap, with *Crossfade*
 * under it while it is *On track skip (auto)*, the only choice that uses it; *Proactive* (`prepareNextTrack`,
 * D13), the next track's evidence ahead; *Saver* (`pauseAutoWriteAfter`, #203); and *Look up in the
 * background* (D6) beside it, since both govern what happens while Sleevesight is away; and *Don't
 * repeat artist facts* (`artistFactsOncePerSession`, #407; once *Tell an artist's facts once per
 * session*), what an insight's prompt holds of the artist's facts, its description linking to *New
 * session idle trigger* under *History* (#581); and *Search with AI* (`searchWithAi`, #773), which
 * starts Home's *Search with AI* by itself as soon as a source has nothing, a paid call, off by
 * default, its description in two paragraphs. *Reactive*'s choices are
 * described in its dialog (#546); a mode chosen there, the one chosen already included, goes to
 * [chooseMode], which saves it and tells the pipeline (#625: an automatic one starts automatic
 * writing from the next track), or, without one, is saved alone. The rows about writing cannot be
 * changed while *Show insights* is off; *Proactive* can, as it writes nothing, and so can the
 * read-aloud rows at the end ([ReadAloudRows], #325).
 */
@Composable
internal fun InsightsSection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
    chooseMode: ((WriteAutomatically) -> Unit)? = null,
) {
    SwitchRow(
        title = stringResource(R.string.settings_write_insights),
        summary = stringResource(R.string.settings_write_insights_summary),
        checked = settings.writeInsights,
    ) { on -> update { it.copy(writeInsights = on) } }

    // The same modes and names as the insight card's menu (#615); what each does is said only here.
    val modes = WRITING_MODES.map { it.mode to stringResource(it.name) }
    val modeDetails = WRITING_MODES.associate { it.mode to stringResource(it.detail) }
    // Where the insight card's menu of its modes links to (#615).
    LandingSpot(SettingsTarget.reactive) {
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
                            details = modeDetails,
                        ) { mode ->
                            if (chooseMode != null) {
                                chooseMode(mode)
                            } else {
                                update { it.copy(writeAutomatically = mode) }
                            }
                        },
                    )
                }.takeIf { settings.writeInsights },
        )
    }
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

    ArtistFactsRow(settings, update)

    // A paid call, as writing is: not to be changed while *Show insights* is off, as the rows above.
    SwitchRow(
        title = stringResource(R.string.settings_search_with_ai),
        summary = stringResource(R.string.settings_search_with_ai_summary),
        checked = settings.searchWithAi,
        onChange = { on: Boolean -> update { it.copy(searchWithAi = on) } }.takeIf { settings.writeInsights },
    )

    ReadAloudRows(settings, update)
}

/**
 * *Don't repeat artist facts* (#407, #546), its description naming *New session idle trigger*, a
 * link to that row under *History* (#581).
 */
@Composable
private fun ArtistFactsRow(
    settings: Settings,
    update: Update,
) {
    val gap = stringResource(R.string.settings_session_gap)
    SwitchRow(
        title = stringResource(R.string.settings_artist_facts_once),
        summary =
            stringResource(
                R.string.settings_artist_facts_once_summary,
                gap,
                stringResource(R.string.settings_section_history),
            ),
        checked = settings.artistFactsOncePerSession,
        summaryLink = settingsLink(gap, SettingsTarget.sessionGap),
        onChange =
            { on: Boolean -> update { it.copy(artistFactsOncePerSession = on) } }.takeIf { settings.writeInsights },
    )
}

/** *Crossfade*'s choices: autodetect, or a fixed 0 to 15 seconds. */
@Composable
internal fun crossfades(): List<Pair<Crossfade, String>> =
    listOf<Pair<Crossfade, String>>(Crossfade.Autodetect to crossfadeLabel(Crossfade.Autodetect)) +
        (0..MAX_CROSSFADE_SECONDS).map { s -> Crossfade.Fixed(s.seconds).let { it to crossfadeLabel(it) } }

/**
 * *Crossfade* (D5): autodetect, or a fixed 0 to 15 seconds, for telling a skip from a natural end
 * under *On track skip (auto)*; indented under *Reactive*, as it belongs to that choice.
 */
@Composable
private fun CrossfadeRow(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val options = crossfades()
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
