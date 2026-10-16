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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The settings screen (design, *Settings*): the AI service and its key, the players to follow,
 * where to look, the web search engines and the keyed sources' keys, the genre label sources (D12,
 * #84), the insight settings (D5, D13), the lookup settings (D6, D9), and the history's (#148); at
 * the end, the way to the credits. Each change is saved at once. The rows show the stored settings,
 * so a change that cannot be saved (the settings file cannot be read or written) leaves its row at
 * the stored value, and the screen says so above the list until a later change is saved.
 *
 * [aiProviders] list a service's models for the model choice; a service missing from it offers
 * its default model only. [clearHistory] clears the session history and the track changes, once
 * the user confirms, outside the screen's lifetime; the screen only waits for the result, and says
 * above the list that the history was cleared, or could not be. [onOpenCredits] opens the credits
 * screen. [playerLabel] names a player by its package.
 */
@Suppress("LongParameterList") // The stores and actions the screen works on, each its own.
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    secretStore: SecretStore,
    aiProviders: Map<ProviderId, AiProvider>,
    clearHistory: () -> Deferred<Boolean>,
    onOpenCredits: () -> Unit = {},
    playerLabel: (String) -> String,
) {
    val settings by settingsStore.settings.collectAsState(initial = null)
    val secrets by secretStore.states.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    // Whether the last save of a key failed: with an unreadable key, it offers *Reset saved keys*.
    var saveFailed by remember { mutableStateOf(false) }
    // Whether the last change could not be saved.
    var changeFailed by remember { mutableStateOf(false) }
    val clearing = remember(scope) { Clearing(scope) }
    val update = scope.updater(settingsStore) { changeFailed = it }
    val keys = KeysShown(secrets, offerReset = saveFailed || SecretState.UNREADABLE in secrets.values)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            // Above the list, so it shows wherever the list is scrolled to.
            Notices(changeFailed, clearing.ended)
            settings?.let { current ->
                SettingsContent(current, keys, Navigation(playerLabel, onOpenCredits), update) { dialog = it }
            }
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
                onSave = { value -> secretStore.set(shown.id, Secret(value)).also { saveFailed = !it } },
                onRemove = { secretStore.clear(shown.id) },
                onDismiss = close,
            )
        }

        SettingsDialog.ClearHistory -> {
            ClearHistoryDialog(
                onClear = { clearing.start(clearHistory) },
                onDismiss = close,
            )
        }

        SettingsDialog.ResetKeys -> {
            ResetKeysDialog(
                onReset = { secretStore.reset().also { if (it) saveFailed = false } },
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
internal typealias Update = ((Settings) -> Settings) -> Unit

/** Saves each change to [store] in this scope, telling [failed] whether it could not be saved. */
private fun CoroutineScope.updater(
    store: SettingsStore,
    failed: (Boolean) -> Unit,
): Update =
    { transform ->
        launch {
            failed(
                try {
                    store.update(transform)
                    false
                } catch (_: IOException) {
                    // Only an I/O error: cancellation and any other failure propagate.
                    true
                },
            )
        }
    }

/**
 * How the last *Clear history* ended: [ended] is null until one does. Its result is awaited in
 * [scope], the screen's: the clear itself is made whatever happens to the screen, but if the screen
 * goes, or is recreated (a rotation), before the clear ends, its notice is not shown. Nothing shows
 * while a clear waits in the history's queue.
 */
internal class Clearing(
    private val scope: CoroutineScope,
) {
    var ended by mutableStateOf<Boolean?>(null)
        private set

    /** Starts [clear], which runs outside the screen's scope, and waits for its result in [scope]. */
    fun start(clear: () -> Deferred<Boolean>) {
        ended = null
        val result = clear()
        scope.launch { ended = result.await() }
    }
}

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

    data object ResetKeys : SettingsDialog

    data object ClearHistory : SettingsDialog
}

/** How the screen names players, and where it leads. */
private class Navigation(
    val playerLabel: (String) -> String,
    val openCredits: () -> Unit,
)

/** The saved keys' states, and whether to offer *Reset saved keys*. */
internal class KeysShown(
    val states: Map<SecretId, SecretState>,
    val offerReset: Boolean,
)

/**
 * The services offered in Phase 1: Anthropic, the one with a client (the plan's pending decision
 * *AI providers in the MVP* recommends Anthropic only); the others come with their clients.
 */
private val OFFERED_PROVIDERS: List<ProviderId> = listOf(ProviderId.ANTHROPIC)

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

/**
 * Says above the list that the last change could not be saved, and how the last *Clear history*
 * ended ([cleared]; null for none). Never an error's text, which may name a path.
 */
@Composable
private fun Notices(
    changeFailed: Boolean,
    cleared: Boolean?,
) {
    val messages =
        listOfNotNull(
            R.string.settings_change_failed.takeIf { changeFailed },
            R.string.settings_clear_history_failed.takeIf { cleared == false },
            R.string.settings_clear_history_done.takeIf { cleared == true },
        )
    for (message in messages) {
        val failure = message != R.string.settings_clear_history_done
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun SettingsContent(
    settings: Settings,
    keys: KeysShown,
    navigation: Navigation,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val secrets = keys.states
    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        if (keys.offerReset) {
            ValueRow(
                title = stringResource(R.string.settings_reset_keys),
                value = stringResource(R.string.settings_reset_keys_value),
                summary = stringResource(R.string.settings_reset_keys_summary),
                onClick = { open(SettingsDialog.ResetKeys) },
            )
        }
        SectionHeading(R.string.settings_section_ai)
        AiServiceRows(settings, secrets, update, open)
        AiModelRows(settings, update, open)
        PlayersSection(settings, navigation.playerLabel, update)
        SourcesSection(settings, secrets, update, open)
        LabelsSection(settings, secrets, update)
        InsightsSection(settings, update, open)
        LookupsSection(settings, update, open)
        HistorySection(settings, update, open)
        SectionHeading(R.string.settings_section_about)
        ValueRow(
            title = stringResource(R.string.credits_title),
            value = stringResource(R.string.settings_credits_value),
            onClick = navigation.openCredits,
        )
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
