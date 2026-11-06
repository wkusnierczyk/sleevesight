package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlin.time.Duration.Companion.seconds

// *Insights* (#444): *Show insights*, *Proactive*, *Look up in the background*, *Reactive* with
// *Crossfade*, *Saver* (#947), *Don't repeat artist facts* (#407, #546), *AI web search* (#801)
// and *Ask AI automatically* (#773, #802), then reading aloud (#325).

private val MAX_CROSSFADE_SECONDS =
    Crossfade.Fixed.MAX.inWholeSeconds
        .toInt()

/**
 * The insights' settings: *Show insights* (`writeInsights`), the master switch; *Proactive*
 * (`prepareNextTrack`, D13), the next track's evidence ahead; *Look up in the background* (D6);
 * *Reactive* (`writeAutomatically`, D5), which track changes write an insight without a tap, with
 * *Crossfade* under it while it is *Skip*, the only choice that uses it; *Saver*
 * (`pauseAutoWriteAfter`, #203) right after it, since it limits what *Reactive* writes (#947); and
 * *Don't repeat artist facts* (`artistFactsOncePerSession`, #407; once *Tell an artist's facts once
 * per session*), what an insight's prompt holds of the artist's facts, its description linking to
 * *New session idle trigger* under *History* (#581); *Ask AI automatically* (`searchWithAi`, #773,
 * named so by #802), which starts Home's *Ask AI* by itself as soon as a source has nothing, a paid
 * call, off by default, its description in two paragraphs, the first naming *AI web search* as a
 * link to it, after *AI web search* (#801, [AiWebSearchRows]). *Reactive* is a button group of its
 * modes by their short names (#947), described in a table ([reactiveDescription], #945), the chosen
 * one's line under it; a mode tapped, the one chosen already included, goes to [chooseMode], which
 * saves it and tells the pipeline (#625: an automatic one starts automatic writing from the next
 * track), or, without one, is saved alone. The rows about writing cannot be changed while *Show
 * insights* is off, and sit together under *Proactive* and *Look up in the background*, which can,
 * as they write nothing and what they look up serves more than insights; so can the read-aloud rows
 * at the end ([ReadAloudRows], #325).
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

    SwitchRow(
        title = stringResource(R.string.settings_prepare_next),
        summary = stringResource(R.string.settings_prepare_next_summary),
        checked = settings.prepareNextTrack == PrepareNextTrack.EVIDENCE,
    ) { on -> update { it.copy(prepareNextTrack = if (on) PrepareNextTrack.EVIDENCE else PrepareNextTrack.OFF) } }

    SwitchRow(
        title = stringResource(R.string.settings_background),
        summary = stringResource(R.string.settings_background_summary),
        checked = settings.lookUpInBackground,
    ) { on -> update { it.copy(lookUpInBackground = on) } }

    // The same modes as the insight card's menu (#615), by their short names (#947); what each
    // does is said only here, where that menu links to.
    LandingSpot(SettingsTarget.reactive) {
        ChoiceButtonsRow(
            title = stringResource(R.string.settings_write_automatically),
            summary = null,
            summaryTable = reactiveDescription(),
            choices = WRITING_MODES.map { it.mode to stringResource(it.short) },
            selected = settings.writeAutomatically,
            details = WRITING_MODES.associate { it.mode to stringResource(it.detail) },
            // With *Show insights* off nothing is written: the setting does not apply.
            applies = settings.writeInsights,
        ) { mode ->
            if (chooseMode != null) {
                chooseMode(mode)
            } else {
                update { it.copy(writeAutomatically = mode) }
            }
        }
    }
    if (settings.writeAutomatically == WriteAutomatically.WHEN_I_SKIP) CrossfadeRow(settings, update, open)

    SaverRows(settings, update)

    ArtistFactsRow(settings, update)

    // A paid call, as writing is: not to be changed while *Show insights* is off, as the rows above.
    // *AI web search* first, then the toggle whose text names it (#801's revised pick).
    AiWebSearchRows(settings, update)
    val web = stringResource(R.string.settings_ai_web_search)
    SwitchRow(
        title = stringResource(R.string.settings_search_with_ai),
        summary = stringResource(R.string.settings_search_with_ai_summary, web),
        checked = settings.searchWithAi,
        summaryLink = settingsLink(web, SettingsTarget.aiWebSearch),
        // With *Show insights* off nothing is written: the setting does not apply.
        applies = settings.writeInsights,
    ) { on -> update { it.copy(searchWithAi = on) } }

    ReadAloudRows(settings, update)
}

/**
 * *Reactive*'s description (#945): what it sets, then a table of its modes by their short names,
 * each with what it means, then that the automatic ones begin from the next track.
 */
@Composable
internal fun reactiveDescription(): DescriptionTable =
    DescriptionTable(
        stringResource(R.string.settings_write_automatically_summary),
        WRITING_MODES.map { stringResource(it.short) to stringResource(it.means) },
        stringResource(R.string.settings_write_automatically_summary_end),
    )

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
        applies = settings.writeInsights,
    ) { on -> update { it.copy(artistFactsOncePerSession = on) } }
}

/** *Crossfade*'s choices: autodetect, or a fixed 0 to 15 seconds. */
@Composable
internal fun crossfades(): List<Pair<Crossfade, String>> =
    listOf<Pair<Crossfade, String>>(Crossfade.Autodetect to crossfadeLabel(Crossfade.Autodetect)) +
        (0..MAX_CROSSFADE_SECONDS).map { s -> Crossfade.Fixed(s.seconds).let { it to crossfadeLabel(it) } }

/**
 * *Crossfade* (D5): autodetect, or a fixed 0 to 15 seconds, for telling a skip from a natural end
 * under *Skip*; indented under *Reactive*, as it belongs to that choice.
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
