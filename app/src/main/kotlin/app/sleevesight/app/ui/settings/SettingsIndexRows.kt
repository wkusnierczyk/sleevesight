package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.R
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically

// The rows of each section as the settings' search finds them (#649), for [settingsIndex].

@Composable
internal fun aiRows(
    settings: Settings,
    modelName: (String) -> String,
    unavailable: Map<ProviderId, String>,
): List<IndexedRow> =
    OFFERED_PROVIDERS.flatMap { provider ->
        val name = stringResource(providerName(provider))
        // As the row shows it: its value, the default marked so, and that the model is unavailable.
        val models = listOfNotNull(settings.aiModels[provider], Settings.DEFAULT_MODELS[provider]).distinct()
        val model =
            choiceRow(
                stringResource(R.string.settings_ai_model_of, name),
                modelUnavailable(name, goneModel(provider, settings, unavailable), modelName),
                modelValue(provider, settings, modelName),
                models.map(modelName),
            )
        val efforts =
            efforts(provider)?.let { options ->
                choiceRow(
                    stringResource(R.string.settings_ai_effort_of, name),
                    stringResource(R.string.settings_ai_effort_summary),
                    options.first { it.first == settings.effort(provider) }.second,
                    options.map { it.second },
                )
            }
        listOfNotNull(indexRow(name), model, efforts)
    }

@Composable
internal fun secretRows(keys: KeysShown): List<IndexedRow> {
    val loadKeys =
        indexRow(
            stringResource(R.string.settings_scan_keys),
            stringResource(R.string.settings_scan_keys_value),
            stringResource(R.string.settings_scan_keys_docs),
        )
    val purge =
        if (keys.anyStored || keys.offerReset) {
            val why =
                if (keys.offerReset) R.string.settings_reset_keys_value else R.string.settings_remove_all_keys_value
            indexRow(stringResource(R.string.settings_remove_all_keys), stringResource(why))
        } else {
            null
        }
    val keyRows = SHOWN_KEYS.map { id -> indexRow(stringResource(keyName(id)), keyState(keys.states[id])) }
    val phoneName =
        indexRow(
            stringResource(R.string.settings_show_phone_name),
            stringResource(R.string.settings_show_phone_name_summary),
        )
    return listOfNotNull(loadKeys, purge) + keyRows + phoneName
}

@Composable
internal fun genreRows(): List<IndexedRow> =
    listOf(
        indexRow(stringResource(R.string.settings_show_genres), stringResource(R.string.settings_show_genres_summary)),
    ) +
        LabelSource.entries.map {
            indexRow(
                stringResource(labelSourceName(it)),
                stringResource(labelSourceSummary(it)),
            )
        }

@Composable
internal fun insightRows(settings: Settings): List<IndexedRow> {
    val modes = WRITING_MODES.map { it.mode to stringResource(it.name) }
    val reactive =
        choiceRow(
            stringResource(R.string.settings_write_automatically),
            stringResource(R.string.settings_write_automatically_summary),
            modes.first { it.first == settings.writeAutomatically }.second,
            modes.map { it.second } + WRITING_MODES.map { stringResource(it.detail) },
        )
    val crossfade =
        if (settings.writeAutomatically == WriteAutomatically.WHEN_I_SKIP) {
            val options = crossfades()
            choiceRow(
                stringResource(R.string.settings_crossfade),
                stringResource(R.string.settings_crossfade_summary),
                crossfadeLabel(settings.crossfade),
                options.map { it.second },
            )
        } else {
            null
        }
    val saverCount =
        settings.pauseAutoWriteAfter?.let { count ->
            choiceRow(
                stringResource(R.string.settings_pause_auto_write_after),
                null,
                pauseLabel(count),
                saverCounts(count).map { it.second },
            )
        }
    val gap = stringResource(R.string.settings_session_gap)
    val history = stringResource(R.string.settings_section_history)
    return listOfNotNull(
        indexRow(
            stringResource(R.string.settings_write_insights),
            stringResource(R.string.settings_write_insights_summary),
        ),
        reactive,
        crossfade,
        indexRow(
            stringResource(R.string.settings_prepare_next),
            stringResource(R.string.settings_prepare_next_summary),
        ),
        indexRow(
            stringResource(R.string.settings_pause_auto_write),
            stringResource(R.string.settings_pause_auto_write_summary),
        ),
        saverCount,
        indexRow(stringResource(R.string.settings_background), stringResource(R.string.settings_background_summary)),
        indexRow(
            stringResource(R.string.settings_artist_facts_once),
            stringResource(R.string.settings_artist_facts_once_summary, gap, history),
        ),
    )
}

@Composable
internal fun historyRows(settings: Settings): List<IndexedRow> {
    val lifetimes = historyLifetimes(settings.historyLifetime)
    val gaps = sessionGaps(settings.sessionGap)
    val kept = stringResource(R.string.settings_cache_lifetimes)
    val caches =
        SHOWN_LIFETIMES.mapNotNull { (source, name) ->
            settings.cacheLifetimes[source]?.let { lifetime ->
                choiceRow(stringResource(name), kept, daysLabel(lifetime), cacheLifetimes(lifetime).map { it.second })
            }
        }
    return listOf(
        choiceRow(
            stringResource(R.string.settings_keep_history),
            stringResource(R.string.settings_keep_history_summary),
            lifetimeLabel(settings.historyLifetime),
            lifetimes.map { it.second },
        ),
        indexRow(
            stringResource(R.string.settings_clear_history),
            stringResource(R.string.settings_clear_history_value),
        ),
        choiceRow(
            stringResource(R.string.settings_session_gap),
            stringResource(R.string.settings_session_gap_summary),
            durationLabel(settings.sessionGap),
            gaps.map { it.second },
        ),
    ) + caches +
        indexRow(stringResource(R.string.settings_keep_artists), stringResource(R.string.settings_keep_artists_summary))
}

@Composable
internal fun appearanceRows(): List<IndexedRow> =
    listOf(
        choices(R.string.settings_theme, R.string.settings_theme_summary, themes().map { it.second }),
        choices(R.string.settings_font, R.string.settings_font_summary, fonts().map { it.second }),
        choices(R.string.settings_layout, R.string.settings_layout_summary, layouts().map { it.second }),
        indexRow(
            stringResource(R.string.settings_keep_sources),
            stringResource(R.string.settings_keep_sources_summary),
        ),
        indexRow(stringResource(R.string.settings_intro), stringResource(R.string.settings_intro_summary)),
        indexRow(stringResource(R.string.settings_home_guide), stringResource(R.string.settings_home_guide_summary)),
    )

/** A row whose value is one of [choices] (its current value among them), titled and described by the ids given. */
@Composable
private fun choices(
    title: Int,
    summary: Int,
    choices: List<String>,
): IndexedRow = IndexedRow(stringResource(title), listOf(stringResource(title), stringResource(summary)) + choices)

@Composable
internal fun aboutRows(): List<IndexedRow> =
    listOf(
        indexRow(
            stringResource(R.string.settings_version),
            stringResource(R.string.settings_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        ),
        indexRow(stringResource(R.string.credits_title), stringResource(R.string.settings_credits_value)),
        indexRow(stringResource(R.string.settings_what_leaves), stringResource(R.string.settings_what_leaves_value)),
        indexRow(stringResource(R.string.settings_docs), stringResource(R.string.settings_docs_summary)),
        indexRow(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_summary)),
        indexRow(stringResource(R.string.settings_source_code), stringResource(R.string.settings_source_code_summary)),
        indexRow(stringResource(R.string.settings_licence), stringResource(R.string.settings_licence_summary)),
    )

@Composable
internal fun developerRows(settings: Settings): List<IndexedRow> {
    val reportButtons = reportButtons()
    return listOfNotNull(
        indexRow(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_summary)),
        if (settings.showDiagnostics) {
            indexRow(
                stringResource(R.string.settings_diagnostics_open),
                stringResource(R.string.settings_diagnostics_open_value),
            )
        } else {
            null
        },
        indexRow(
            stringResource(R.string.settings_grounding_details),
            stringResource(R.string.settings_grounding_details_summary),
        ),
        choices(
            R.string.settings_report_button,
            R.string.settings_report_button_summary,
            reportButtons.map { it.second } + reportButtonDetails().values,
        ),
        choices(R.string.settings_link_style, R.string.settings_link_style_summary, linkStyles().map { it.second }),
        indexRow(
            stringResource(R.string.settings_mark_developer),
            stringResource(R.string.settings_mark_developer_summary),
        ),
        indexRow(
            stringResource(R.string.settings_developer_mode),
            stringResource(R.string.settings_developer_mode_summary),
        ),
    )
}
