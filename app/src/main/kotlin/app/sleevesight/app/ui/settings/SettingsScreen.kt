package app.sleevesight.app.ui.settings

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.ModelCheck
import app.sleevesight.app.pipeline.UnavailableMark
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.ListedModelNames
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.ui.keyboard.BluetoothComputerKeyboard
import app.sleevesight.app.ui.keyboard.ComputerKeyboard
import app.sleevesight.app.ui.scan.CameraQrScanner
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The settings screen (design, *Settings*; #444): its sections, each folded under its heading and
 * short description, all folded when the screen opens ([SettingsSection], [FoldedSection]):
 * *Players*, *Sources*, *AI services* ([AiSection], #227), *Secrets*, *Genres* (D12, #84),
 * *Insights* (D5, D13), *History* (#148, D9), *Appearance* (#377), *About*, and *Developer*, shown
 * only in developer mode, which seven taps on *Version* turn on ([VersionRow]). Each change is saved
 * at once. The rows show the stored settings, so a change that cannot be saved (the settings file
 * cannot be read or written) leaves its row at the stored value, and the screen says so above the
 * list until a later change is saved.
 *
 * [aiProviders] list a service's models for the model choice; a service missing from it offers
 * its default model only. [clearHistory] clears the session history and the track changes, once
 * the user confirms, outside the screen's lifetime; the screen only waits for the result, and says
 * above the list that the history was cleared, or could not be. The saves (a setting, a key, a
 * removal of keys) run in [saves], which outlives the screen, as the clear does, so leaving the
 * screen, or a rotation, does not cut one short; the screen and its dialogs only wait for the
 * result, and a screen gone by then shows nothing of it. [onOpenCredits] opens the credits
 * screen, [onOpenDiagnostics] the diagnostics screen, offered while *Diagnostics* is on, and
 * [onOpenWhatLeaves] the first-run screen, from *About* (#441).
 * [scanner] reads an API key's QR code, or a setup code of several (#268): the camera, but for
 * tests; [keyboard] runs *Type on a computer* (#394): Bluetooth, but for tests. [playerLabel]
 * names a player by its package, [playerLaunch] gives the intent that opens it, if any, and
 * [installedPlayers] its icon, and which players are installed (#535); null lists every stored
 * player, without an icon.
 * [modelCheck] holds the services' models found unavailable (#540), which their rows say, and hears
 * each model list the model choice fetches.
 */
@Suppress("LongParameterList", "LongMethod") // The stores and actions it works on; a branch per dialog.
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    secretStore: SecretStore,
    aiProviders: Map<ProviderId, AiProvider>,
    clearHistory: () -> Deferred<Boolean>,
    saves: CoroutineScope,
    onOpenCredits: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenWhatLeaves: () -> Unit = {},
    scanner: QrScanner = CameraQrScanner,
    keyboard: ComputerKeyboard = BluetoothComputerKeyboard,
    playerLaunch: (String) -> Intent? = { null },
    installedPlayers: InstalledPlayers? = null,
    modelCheck: ModelCheck? = null,
    playerLabel: (String) -> String,
) {
    val settings by settingsStore.settings.collectAsState(initial = null)
    val marks by (modelCheck?.unavailable ?: NONE_UNAVAILABLE).collectAsState()
    val unavailable = marks.mapValues { it.value.model }
    val secrets by secretStore.states.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    // Whether the key store could not seal the last key saved: as with an unreadable key, *Purge
    // keys* says why to use it. Not a file that could not be written, which a purge would not cure.
    var sealFailed by remember { mutableStateOf(false) }
    // The changes in a row that could not be saved, 0 once one is: each one is announced.
    var changeFailures by remember { mutableIntStateOf(0) }
    val clearing = remember(scope) { Clearing(scope) }
    val update =
        scope.updater(settingsStore, saves) { failed ->
            changeFailures = if (failed) changeFailures + 1 else 0
        }
    val keys = KeysShown(secrets, offerReset = sealFailed || SecretState.UNREADABLE in secrets.values)
    val context = LocalContext.current
    val modelNames = remember(context) { ListedModelNames(context) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            // Above the list, so it shows wherever the list is scrolled to.
            Notices(changeFailures, clearing.ended)
            settings?.let { current ->
                val navigation =
                    Navigation(
                        PlayerApps(playerLabel, playerLaunch, installedPlayers),
                        modelNames::of,
                        onOpenCredits,
                        onOpenDiagnostics,
                        onOpenWhatLeaves,
                    )
                SettingsContent(current, keys, navigation, unavailable, update) { dialog = it }
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
                keyboard,
                showPhoneName = settings?.showPhoneName ?: false,
                onSealFailed = { sealFailed = it },
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
                load = {
                    // The list checks the selected model too (#540), with the key it was made with
                    // and the revisions as it was asked for.
                    val ticket = modelCheck?.ticket()
                    loadModels(shown.provider, secretStore, aiProviders) { key, models ->
                        modelNames.learn(models)
                        val selected = settings?.model(shown.provider)
                        if (ticket != null && selected != null) {
                            modelCheck?.listed(ticket, shown.provider, selected, models, key)
                        }
                    }
                },
                modelName = modelNames::of,
                onSelect = { model -> update { it.withModel(shown.provider, model) } },
                onDismiss = close,
            )
        }
    }
}

/** No model found unavailable, for a screen without a [ModelCheck]. */
private val NONE_UNAVAILABLE: StateFlow<Map<ProviderId, UnavailableMark>> = MutableStateFlow(emptyMap())

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

    /** *Purge keys*, offered while any key is stored, or after a failure: the screen (#446). */
    data object PurgeKeys : Keys

    /**
     * *Load keys*: the screen that reads a setup code (#268, #446), naming the CLI's `keys-qr` in
     * [developerMode].
     */
    class LoadKeys(
        val developerMode: Boolean,
    ) : Keys

    data object ClearHistory : SettingsDialog
}

/**
 * How the settings name a player and open it: [label] names it by its package; [launch] is the
 * intent that opens it from its launcher entry, or null for a player with none, which offers no
 * link; [installed] gives its icon, and whether it is installed (#535), or is null where that is
 * not known (tests, previews).
 */
internal class PlayerApps(
    val label: (String) -> String,
    val launch: (String) -> Intent? = { null },
    val installed: InstalledPlayers? = null,
)

/** How the screen names players and models, and where it leads. */
private class Navigation(
    val players: PlayerApps,
    val modelName: (String) -> String,
    val openCredits: () -> Unit,
    val openDiagnostics: () -> Unit,
    val openWhatLeaves: () -> Unit,
)

/**
 * The saved keys' states, and whether *Purge keys* says why to use it: a saved key cannot be read,
 * or the key store could not seal a key (once *Reset saved keys*).
 */
internal class KeysShown(
    val states: Map<SecretId, SecretState>,
    val offerReset: Boolean,
) {
    /** Whether any key is stored, readable or not: *Purge keys* is offered then. */
    val anyStored: Boolean get() = states.values.any { it != SecretState.ABSENT }
}

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

// One fold per section, in the order shown, and what the sections show and change.
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun SettingsContent(
    settings: Settings,
    keys: KeysShown,
    navigation: Navigation,
    unavailable: Map<ProviderId, String>,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val secrets = keys.states
    val folds = rememberSectionFolds()
    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        FoldedSection(SettingsSection.PLAYERS, folds) { PlayersSection(settings, navigation.players, update) }
        FoldedSection(SettingsSection.SOURCES, folds) { SourcesSection(settings, secrets, update) }
        FoldedSection(SettingsSection.AI_SERVICES, folds) {
            AiSection(settings, secrets, navigation.modelName, update, open, unavailable)
        }
        FoldedSection(SettingsSection.SECRETS, folds) { SecretsSection(settings, keys, update, open) }
        FoldedSection(SettingsSection.GENRES, folds) { GenresSection(settings, secrets, update) }
        FoldedSection(SettingsSection.INSIGHTS, folds) { InsightsSection(settings, update, open) }
        FoldedSection(SettingsSection.HISTORY, folds) { HistorySection(settings, update, open) }
        FoldedSection(SettingsSection.APPEARANCE, folds) { AppearanceSection(settings, update, open) }
        FoldedSection(SettingsSection.ABOUT, folds) {
            AboutSection(settings, update, navigation.openCredits, navigation.openWhatLeaves)
        }
        if (settings.developerMode) {
            FoldedSection(SettingsSection.DEVELOPER, folds) {
                DeveloperSection(settings, update, open, navigation.openDiagnostics)
            }
        }
    }
}
