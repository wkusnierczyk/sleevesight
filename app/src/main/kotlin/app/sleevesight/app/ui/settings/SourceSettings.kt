package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine

// *Where to look*, with the web search engines and the keys, and *Genre labels* (D12, #84).

/** The *Where to look* switches of the sources with an adapter; Discogs comes in Phase 3. */
private val SHOWN_SWITCHES =
    listOf(
        SourceSwitch.MUSICBRAINZ to R.string.settings_source_musicbrainz,
        SourceSwitch.WIKIPEDIA to R.string.settings_source_wikipedia,
        SourceSwitch.LASTFM to R.string.settings_source_lastfm,
        SourceSwitch.WEB to R.string.settings_source_web,
        SourceSwitch.MODEL to R.string.settings_source_model,
    )

@Composable
internal fun SourcesSection(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    SectionHeading(R.string.settings_section_sources)
    Hint(R.string.settings_sources_hint)
    val modelSummary = stringResource(R.string.settings_source_model_summary)
    for ((switch, name) in SHOWN_SWITCHES) {
        SwitchRow(
            title = stringResource(name),
            summary = modelSummary.takeIf { switch == SourceSwitch.MODEL },
            checked = switch in settings.enabledSources,
        ) { on ->
            update { it.copy(enabledSources = if (on) it.enabledSources + switch else it.enabledSources - switch) }
        }
        sourceKey(switch)?.let { id -> KeyRow(id, secrets, open) }
        if (switch == SourceSwitch.WEB) EngineRows(settings, secrets, update, open)
    }
}

/** The web search engines under *Web search* (D11), each a switch with its key. */
@Composable
private fun EngineRows(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    for (engine in WebEngine.entries) {
        SwitchRow(
            title = stringResource(engineName(engine)),
            summary = null,
            checked = engine in settings.webEngines,
            indent = true,
            onChange =
                { on: Boolean ->
                    update { it.copy(webEngines = if (on) it.webEngines + engine else it.webEngines - engine) }
                }.takeIf { SourceSwitch.WEB in settings.enabledSources },
        )
        KeyRow(engineKey(engine), secrets, open)
    }
}

@Composable
private fun KeyRow(
    id: SecretId,
    secrets: Map<SecretId, SecretState>,
    open: (SettingsDialog) -> Unit,
) {
    ValueRow(
        title = stringResource(keyName(id)),
        value = keyState(secrets[id]),
        indent = true,
        onClick = { open(SettingsDialog.Key(id)) },
    )
}

/**
 * *Genre labels* (D12, #84): which sources' tags give labels. A source gives labels only while it
 * is on under *Where to look*, and Last.fm only with its key saved, so until then its switch shows
 * off and cannot be moved, and says why.
 */
@Composable
internal fun LabelsSection(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    update: Update,
) {
    SectionHeading(R.string.settings_section_labels)
    Hint(R.string.settings_labels_hint)
    for (source in LabelSource.entries) {
        val looked = settings.isEnabled(source.source)
        val keyed = source != LabelSource.LASTFM || secrets[SecretId.LASTFM] == SecretState.SAVED
        val why =
            when {
                !looked -> R.string.settings_labels_source_off
                !keyed -> R.string.settings_labels_needs_key
                else -> null
            }
        SwitchRow(
            title = stringResource(labelSourceName(source)),
            summary = why?.let { stringResource(it) } ?: labelSourceSummary(source)?.let { stringResource(it) },
            checked = why == null && source in settings.labelSources,
            onChange =
                { on: Boolean ->
                    update { it.copy(labelSources = if (on) it.labelSources + source else it.labelSources - source) }
                }.takeIf { why == null },
        )
    }
}

@StringRes
private fun labelSourceName(source: LabelSource): Int =
    when (source) {
        LabelSource.LASTFM -> R.string.settings_source_lastfm
        LabelSource.MUSICBRAINZ -> R.string.settings_source_musicbrainz
    }

@StringRes
private fun labelSourceSummary(source: LabelSource): Int? =
    when (source) {
        LabelSource.LASTFM -> null
        LabelSource.MUSICBRAINZ -> R.string.settings_labels_musicbrainz_summary
    }
