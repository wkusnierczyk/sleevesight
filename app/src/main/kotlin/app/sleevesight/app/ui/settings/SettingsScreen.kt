package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The settings screen (design, *Settings*): the AI service and its key, the players to follow,
 * where to look and the keyed sources' keys, the insight settings (D5, D13), and the lookup
 * settings (D6, D9). Each change is saved at once.
 *
 * [aiProviders] list a service's models for the model choice; a service missing from it offers
 * its default model only. [playerLabel] names a player by its package.
 */
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    secretStore: SecretStore,
    aiProviders: Map<ProviderId, AiProvider>,
    playerLabel: (String) -> String,
) {
    val settings by settingsStore.settings.collectAsState(initial = null)
    val secrets by secretStore.states.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val update: Update = { transform -> scope.launch { settingsStore.update(transform) } }

    Surface(modifier = Modifier.fillMaxSize()) {
        settings?.let { current ->
            SettingsContent(current, secrets, playerLabel, update) { dialog = it }
        }
    }

    val close = { dialog = null }
    when (val shown = dialog) {
        null -> {}

        is SettingsDialog.Choice<*> -> {
            ChoiceDialog(shown, onDismiss = close)
        }

        is SettingsDialog.Key -> {
            KeyDialog(
                id = shown.id,
                state = secrets[shown.id] ?: SecretState.ABSENT,
                onSave = { value -> scope.launch { secretStore.set(shown.id, Secret(value)) } },
                onRemove = { scope.launch { secretStore.clear(shown.id) } },
                onDismiss = close,
            )
        }

        is SettingsDialog.Model -> {
            ModelDialog(
                provider = shown.provider,
                current = settings?.aiModel,
                load = { loadModels(shown.provider, secretStore, aiProviders) },
                onSelect = { model -> update { it.copy(aiModel = model) } },
                onDismiss = close,
            )
        }
    }
}

/** Saves a change to the settings. */
private typealias Update = ((Settings) -> Settings) -> Unit

/** The dialogs the screen opens, one at a time. */
internal sealed interface SettingsDialog {
    /** One of [options], each a value with its label; [onSelect] saves the one chosen. */
    class Choice<T>(
        @StringRes val title: Int,
        val options: List<Pair<T, String>>,
        val selected: T,
        val onSelect: (T) -> Unit,
    ) : SettingsDialog

    class Key(
        val id: SecretId,
    ) : SettingsDialog

    class Model(
        val provider: ProviderId,
    ) : SettingsDialog
}

/**
 * The services offered in Phase 1: Anthropic, the one with a client (the plan's pending decision
 * *AI providers in the MVP* recommends Anthropic only); the others come with their clients.
 */
private val OFFERED_PROVIDERS: List<ProviderId> = listOf(ProviderId.ANTHROPIC)

/** The *Where to look* switches of the sources with an adapter; Discogs comes in Phase 3. */
private val SHOWN_SWITCHES =
    listOf(
        SourceSwitch.MUSICBRAINZ to R.string.settings_source_musicbrainz,
        SourceSwitch.WIKIPEDIA to R.string.settings_source_wikipedia,
        SourceSwitch.LASTFM to R.string.settings_source_lastfm,
        SourceSwitch.WEB to R.string.settings_source_web,
        SourceSwitch.MODEL to R.string.settings_source_model,
    )

/** The cache lifetimes of the sources with an adapter (D9); Discogs's comes with it. */
private val SHOWN_LIFETIMES =
    listOf(
        SourceId.MUSICBRAINZ to R.string.settings_source_musicbrainz,
        SourceId.WIKIDATA to R.string.settings_cache_wikidata,
        SourceId.WIKIPEDIA to R.string.settings_cache_wikipedia,
        SourceId.LASTFM to R.string.settings_source_lastfm,
        SourceId.WEB to R.string.settings_cache_web,
    )

/** The lifetimes offered, which include every default (D9); a stored one outside them is offered too. */
@Suppress("MagicNumber") // The values are the offer itself.
private val LIFETIME_DAYS = listOf(1, 3, 7, 14, 30, 90)

private val MAX_CROSSFADE_SECONDS =
    Crossfade.Fixed.MAX.inWholeSeconds
        .toInt()

@Composable
private fun SettingsContent(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    playerLabel: (String) -> String,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        SectionHeading(R.string.settings_section_ai)
        AiServiceRows(settings, secrets, update, open)
        AiModelRows(settings, update, open)
        PlayersSection(settings, playerLabel, update)
        SourcesSection(settings, secrets, update, open)
        InsightsSection(settings, update, open)
        LookupsSection(settings, update, open)
    }
}

/** The service and its key. */
@Composable
private fun AiServiceRows(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val none = stringResource(R.string.settings_ai_provider_none)
    val providers = listOf<ProviderId?>(null) + OFFERED_PROVIDERS
    val options = providers.map { it to (it?.let { p -> stringResource(providerName(p)) } ?: none) }
    ValueRow(
        title = stringResource(R.string.settings_ai_provider),
        value = options.firstOrNull { it.first == settings.aiProvider }?.second ?: settings.aiProvider?.name ?: none,
        summary = stringResource(R.string.settings_ai_providers_hint),
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_ai_provider, options, settings.aiProvider) { chosen ->
                // Another service's model would not exist there: back to the new service's default.
                update { if (it.aiProvider == chosen) it else it.copy(aiProvider = chosen, aiModel = null) }
            },
        )
    }

    val keyId = settings.aiProvider?.let { SecretId.forProvider(it) }
    ValueRow(
        title = stringResource(R.string.settings_ai_key),
        value = keyId?.let { keyState(secrets[it]) } ?: stringResource(R.string.settings_ai_model_none),
        onClick = keyId?.let { { open(SettingsDialog.Key(it)) } },
    )
}

/** The model and how much it may reason. */
@Composable
private fun AiModelRows(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val provider = settings.aiProvider
    ValueRow(
        title = stringResource(R.string.settings_ai_model),
        value =
            when {
                provider == null -> stringResource(R.string.settings_ai_model_none)
                settings.aiModel != null -> settings.aiModel.orEmpty()
                else -> settings.model?.let { stringResource(R.string.settings_ai_model_default, it) }.orEmpty()
            },
        onClick = provider?.let { { open(SettingsDialog.Model(it)) } },
    )

    val efforts =
        listOf<Pair<ReasoningEffort?, String>>(
            ReasoningEffort.LOW to stringResource(R.string.settings_effort_low),
            ReasoningEffort.MEDIUM to stringResource(R.string.settings_effort_medium),
            ReasoningEffort.HIGH to stringResource(R.string.settings_effort_high),
            null to stringResource(R.string.settings_effort_provider_default),
        )
    ValueRow(
        title = stringResource(R.string.settings_ai_effort),
        value = efforts.first { it.first == settings.aiEffort }.second,
        summary = stringResource(R.string.settings_ai_effort_summary),
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_ai_effort, efforts, settings.aiEffort) { effort ->
                update { it.copy(aiEffort = effort) }
            },
        )
    }
}

@Composable
private fun PlayersSection(
    settings: Settings,
    playerLabel: (String) -> String,
    update: Update,
) {
    SectionHeading(R.string.settings_section_players)
    Hint(R.string.settings_players_hint)
    for ((player, followed) in settings.followedPlayers.toSortedMap()) {
        SwitchRow(title = playerLabel(player), summary = player, checked = followed) { on ->
            update { it.copy(followedPlayers = it.followedPlayers + (player to on)) }
        }
    }
}

@Composable
private fun SourcesSection(
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
        sourceKey(switch)?.let { id ->
            ValueRow(
                title = stringResource(keyName(id)),
                value = keyState(secrets[id]),
                indent = true,
                onClick = { open(SettingsDialog.Key(id)) },
            )
        }
    }
}

@Composable
private fun InsightsSection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    SectionHeading(R.string.settings_section_insights)
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

    CrossfadeRow(settings, update, open)

    val preparations =
        listOf(
            PrepareNextTrack.OFF to stringResource(R.string.settings_prepare_off),
            PrepareNextTrack.EVIDENCE to stringResource(R.string.settings_prepare_evidence),
            PrepareNextTrack.EVIDENCE_AND_INSIGHT to stringResource(R.string.settings_prepare_evidence_and_insight),
        )
    ValueRow(
        title = stringResource(R.string.settings_prepare_next),
        value = preparations.first { it.first == settings.prepareNextTrack }.second,
        summary = stringResource(R.string.settings_prepare_next_summary),
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_prepare_next, preparations, settings.prepareNextTrack) { p ->
                update { it.copy(prepareNextTrack = p) }
            },
        )
    }
}

/** *Crossfade* (D5): autodetect, or a fixed 0 to 15 seconds. */
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

@Composable
private fun LookupsSection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    SectionHeading(R.string.settings_section_lookups)
    SwitchRow(
        title = stringResource(R.string.settings_background),
        summary = stringResource(R.string.settings_background_summary),
        checked = settings.lookUpInBackground,
    ) { on -> update { it.copy(lookUpInBackground = on) } }

    Hint(R.string.settings_cache_lifetimes)
    for ((source, name) in SHOWN_LIFETIMES) {
        val lifetime = settings.cacheLifetimes[source] ?: continue
        val options = (LIFETIME_DAYS.map { it.days } + lifetime).distinct().sorted().map { it to daysLabel(it) }
        ValueRow(title = stringResource(name), value = daysLabel(lifetime), indent = true) {
            open(
                SettingsDialog.Choice(name, options, lifetime) { chosen ->
                    update { it.copy(cacheLifetimes = it.cacheLifetimes + (source to chosen)) }
                },
            )
        }
    }
}
