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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import app.sleevesight.app.ui.scan.CameraQrScanner
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.keys.SetupRead
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The settings screen (design, *Settings*): the AI services, each with its switch, key, model and
 * reasoning effort ([AiSection], #227), the players to follow, where to look, the web search
 * engines and the keyed sources' keys, the genre label sources (D12, #84), the insight settings
 * (D5, D13), the lookup settings (D6, D9), and the history's (#148); at the end, *Scan keys* and,
 * while any key is stored, *Remove all API keys*, the way to the credits, and the developer setting
 * *Diagnostics*. Each change is saved at once. The rows show the stored settings, so a change that
 * cannot be saved (the settings file cannot be read or written) leaves its row at the stored value,
 * and the screen says so above the list until a later change is saved.
 *
 * [aiProviders] list a service's models for the model choice; a service missing from it offers
 * its default model only. [clearHistory] clears the session history and the track changes, once
 * the user confirms, outside the screen's lifetime; the screen only waits for the result, and says
 * above the list that the history was cleared, or could not be. The saves (a setting, a key, a
 * removal of keys) run in [saves], which outlives the screen, as the clear does, so leaving the
 * screen, or a rotation, does not cut one short; the screen and its dialogs only wait for the
 * result, and a screen gone by then shows nothing of it. [onOpenCredits] opens the credits
 * screen, and [onOpenDiagnostics] the diagnostics screen, offered while *Diagnostics* is on.
 * [scanner] reads an API key's QR code, or a setup code of several (#268): the camera, but for
 * tests. [playerLabel] names a player by its package.
 */
@Suppress("LongParameterList") // The stores and actions the screen works on, each its own.
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    secretStore: SecretStore,
    aiProviders: Map<ProviderId, AiProvider>,
    clearHistory: () -> Deferred<Boolean>,
    saves: CoroutineScope,
    onOpenCredits: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    scanner: QrScanner = CameraQrScanner,
    playerLabel: (String) -> String,
) {
    val settings by settingsStore.settings.collectAsState(initial = null)
    val secrets by secretStore.states.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    // Whether the key store could not seal the last key saved: as with an unreadable key, it offers
    // *Reset saved keys*. Not a file that could not be written, which a reset would not cure.
    var sealFailed by remember { mutableStateOf(false) }
    // The changes in a row that could not be saved, 0 once one is: each one is announced.
    var changeFailures by remember { mutableIntStateOf(0) }
    val clearing = remember(scope) { Clearing(scope) }
    val update =
        scope.updater(settingsStore, saves) { failed ->
            changeFailures = if (failed) changeFailures + 1 else 0
        }
    val keys = KeysShown(secrets, offerReset = sealFailed || SecretState.UNREADABLE in secrets.values)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            // Above the list, so it shows wherever the list is scrolled to.
            Notices(changeFailures, clearing.ended)
            settings?.let { current ->
                SettingsContent(current, keys, Navigation(playerLabel, onOpenCredits, onOpenDiagnostics), update) {
                    dialog = it
                }
            }
        }
    }

    val close = { dialog = null }
    when (val shown = dialog) {
        null -> {}

        is SettingsDialog.Choice<*> -> {
            ChoiceDialog(shown, onDismiss = close)
        }

        is SettingsDialog.Keys -> {
            KeyDialogs(
                shown,
                secrets,
                secretStore,
                saves,
                scanner,
                onSealFailed = { sealFailed = it },
                open = { dialog = it },
                onDismiss = close,
            )
        }

        SettingsDialog.ClearHistory -> {
            ClearHistoryDialog(
                onClear = { clearing.start(clearHistory) },
                onDismiss = close,
            )
        }

        is SettingsDialog.Model -> {
            ModelDialog(
                provider = shown.provider,
                current = settings?.aiModels?.get(shown.provider),
                load = { loadModels(shown.provider, secretStore, aiProviders) },
                onSelect = { model -> update { it.withModel(shown.provider, model) } },
                onDismiss = close,
            )
        }
    }
}

/** Saves a change to the settings. */
internal typealias Update = ((Settings) -> Settings) -> Unit

/**
 * Saves each change to [store] in [saves], which outlives the screen, and waits for it in this
 * scope, the screen's, telling [failed] whether it could not be saved.
 */
private fun CoroutineScope.updater(
    store: SettingsStore,
    saves: CoroutineScope,
    failed: (Boolean) -> Unit,
): Update =
    { transform ->
        val saved =
            saves.async {
                try {
                    store.update(transform)
                    true
                } catch (_: IOException) {
                    // Only an I/O error: cancellation and any other failure propagate.
                    false
                }
            }
        launch { failed(!saved.await()) }
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
    /**
     * One of [options], each a value with its label; [onSelect] saves the one chosen. The [title]
     * takes [titleArg], when given, as its one argument (a provider's name).
     */
    class Choice<T>(
        @StringRes val title: Int,
        val options: List<Pair<T, String>>,
        val selected: T,
        val titleArg: String? = null,
        val onSelect: (T) -> Unit,
    ) : SettingsDialog

    class Model(
        val provider: ProviderId,
    ) : SettingsDialog

    /** The saved keys' dialogs ([KeyDialogs]). */
    sealed interface Keys : SettingsDialog

    /** Enters, replaces or removes the key [id]. */
    class Key(
        val id: SecretId,
    ) : Keys

    /** Removes the key [id], once confirmed. */
    class RemoveKey(
        val id: SecretId,
    ) : Keys

    /** *Reset saved keys*, offered after a failure. */
    data object ResetKeys : Keys

    /** *Remove all API keys*, offered while any key is stored; the same removal as [ResetKeys]. */
    data object RemoveAllKeys : Keys

    /** *Scan keys*: the camera, reading a setup code (#268). */
    data object ScanKeys : Keys

    /** What the setup code scanned holds, [read], to confirm before anything is saved. */
    class SetupFound(
        val read: SetupRead,
    ) : Keys

    data object ClearHistory : SettingsDialog
}

/** How the screen names players, and where it leads. */
private class Navigation(
    val playerLabel: (String) -> String,
    val openCredits: () -> Unit,
    val openDiagnostics: () -> Unit,
)

/** The saved keys' states, and whether to offer *Reset saved keys*. */
internal class KeysShown(
    val states: Map<SecretId, SecretState>,
    val offerReset: Boolean,
) {
    /** Whether any key is stored, readable or not: *Remove all API keys* is offered then. */
    val anyStored: Boolean get() = states.values.any { it != SecretState.ABSENT }
}

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
 * Says above the list that the last change could not be saved ([changeFailures], the failures in a
 * row), and how the last *Clear history* ended ([cleared]; null for none). Never an error's text,
 * which may name a path.
 */
@Composable
private fun Notices(
    changeFailures: Int,
    cleared: Boolean?,
) {
    // Each notice is announced by a screen reader when it shows.
    @Composable
    fun Notice(
        @StringRes message: Int,
        failure: Boolean,
    ) {
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

    // Drawn afresh for each failure in a row, so a screen reader announces each, not only the first.
    if (changeFailures > 0) key(changeFailures) { Notice(R.string.settings_change_failed, failure = true) }
    when (cleared) {
        null -> {}
        false -> Notice(R.string.settings_clear_history_failed, failure = true)
        true -> Notice(R.string.settings_clear_history_done, failure = false)
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
        AiSection(settings, secrets, update, open)
        PlayersSection(settings, navigation.playerLabel, update)
        SourcesSection(settings, secrets, update, open)
        LabelsSection(settings, secrets, update)
        InsightsSection(settings, update, open)
        LookupsSection(settings, update, open)
        HistorySection(settings, update, open)
        SavedKeysSection(keys, open)
        SectionHeading(R.string.settings_section_about)
        ValueRow(
            title = stringResource(R.string.credits_title),
            value = stringResource(R.string.settings_credits_value),
            onClick = navigation.openCredits,
        )
        DeveloperSection(settings, update, navigation.openDiagnostics)
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

    PauseAutoWriteRow(settings, update, open)
    CrossfadeRow(settings, update, open)

    val preparations =
        listOf(
            PrepareNextTrack.OFF to stringResource(R.string.settings_prepare_off),
            PrepareNextTrack.EVIDENCE to stringResource(R.string.settings_prepare_evidence),
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
