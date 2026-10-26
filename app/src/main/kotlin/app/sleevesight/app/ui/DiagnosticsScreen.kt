package app.sleevesight.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.diagnostics.Diagnostics
import app.sleevesight.app.diagnostics.DiagnosticsText
import app.sleevesight.app.diagnostics.ExportResult
import app.sleevesight.app.diagnostics.ExportState
import app.sleevesight.app.diagnostics.LoggedEvent
import app.sleevesight.app.ui.home.PageBar
import app.sleevesight.core.pipeline.EventLine
import app.sleevesight.store.CacheStats
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * The diagnostics screen (`design/design.md`, *Diagnostics*), behind the *Diagnostics* developer
 * setting, in three tabs: the pipeline's events kept in memory, newest first, under the caches'
 * sizes; the track changes recorded with their features (D15), newest first, a page at a time,
 * each opening on the playback states before it; and what skip detection has learned, with a reset
 * behind a confirmation. *Export* writes it all, every track change included, to a file the user
 * chooses; nothing else of it leaves the phone. Opened from the settings, it has a bar with the back
 * arrow, which goes back to them ([onBack]), as Android's back does, the title, and *Export* (#549);
 * no *Close* at the end, since each tab is a list of its own.
 */
@Composable
fun DiagnosticsScreen(
    diagnostics: Diagnostics,
    onBack: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(DiagnosticsTab.EVENTS) }
    val scope = rememberCoroutineScope()
    val changes = remember(diagnostics) { ChangePages(diagnostics, scope) }
    val exportState by diagnostics.exportState.collectAsState()
    // The application's resolver: the export outlives this screen, and must not hold the activity.
    val resolver = LocalContext.current.applicationContext.contentResolver
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_TYPE)) { uri ->
            // `wt`: a provider may otherwise leave the end of a longer file it replaces.
            if (uri != null) diagnostics.export { resolver.openOutputStream(uri, "wt") }
        }
    LaunchedEffect(changes) { changes.loadMore() }

    val surface = MaterialTheme.colorScheme.surface
    Scaffold(
        containerColor = surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            PageBar(stringResource(R.string.diagnostics_title), onBack, surface) {
                TextButton(
                    onClick = { export.launch(exportName(Clock.System.now())) },
                    enabled = exportState != ExportState.Running,
                ) {
                    Text(stringResource(R.string.diagnostics_export))
                }
            }
        },
    ) { bars ->
        Column(modifier = Modifier.padding(bars).consumeWindowInsets(bars)) {
            ExportNotice(exportState)
            PrimaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.padding(top = 8.dp)) {
                for (shown in DiagnosticsTab.entries) {
                    Tab(
                        selected = tab == shown,
                        onClick = { tab = shown },
                        text = { Text(stringResource(shown.title)) },
                    )
                }
            }
            when (tab) {
                DiagnosticsTab.EVENTS -> EventsTab(diagnostics)
                DiagnosticsTab.CHANGES -> ChangesTab(changes)
                DiagnosticsTab.LEARNED -> LearnedTab(diagnostics)
            }
        }
    }
}

private enum class DiagnosticsTab(
    val title: Int,
) {
    EVENTS(R.string.diagnostics_tab_events),
    CHANGES(R.string.diagnostics_tab_changes),
    LEARNED(R.string.diagnostics_tab_learned),
}

/** The export under way, or how the last one since the process started ended. */
@Composable
private fun ExportNotice(state: ExportState) {
    val (message, failure) =
        when (state) {
            ExportState.None -> return
            ExportState.Running -> R.string.diagnostics_exporting to false
            is ExportState.Ended ->
                when (state.result) {
                    ExportResult.WRITTEN -> R.string.diagnostics_exported to false
                    ExportResult.INCOMPLETE -> R.string.diagnostics_exported_incomplete to true
                    ExportResult.FAILED -> R.string.diagnostics_export_failed to true
                }
        }
    Text(
        stringResource(message),
        style = MaterialTheme.typography.bodyMedium,
        color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The caches' sizes, then the events kept, newest first. */
@Composable
private fun EventsTab(diagnostics: Diagnostics) {
    val events by diagnostics.log.entries.collectAsState()
    val caches by produceState<CachesShown>(CachesShown.Loading, diagnostics) {
        value = diagnostics.cacheStats()?.let(CachesShown::Read) ?: CachesShown.Failed
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Caches(caches) }
        if (events.isEmpty()) {
            item { Text(stringResource(R.string.diagnostics_no_events)) }
        }
        items(events.asReversed()) { EventRow(it) }
    }
}

private sealed interface CachesShown {
    data object Loading : CachesShown

    data object Failed : CachesShown

    data class Read(
        val stats: CacheStats,
    ) : CachesShown
}

@Composable
private fun Caches(caches: CachesShown) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(stringResource(R.string.diagnostics_caches), style = MaterialTheme.typography.titleSmall)
        when (caches) {
            CachesShown.Loading -> {}

            CachesShown.Failed -> {
                Text(stringResource(R.string.diagnostics_caches_failed), color = MaterialTheme.colorScheme.error)
            }

            is CachesShown.Read -> {
                for ((name, stats) in listOf(
                    "retrieval" to caches.stats.retrieval,
                    "artists" to caches.stats.artists,
                    "artist results" to caches.stats.artistResults,
                    "synthesis" to caches.stats.synthesis,
                )) {
                    Mono(DiagnosticsText.cache(name, stats))
                }
            }
        }
    }
}

@Composable
private fun EventRow(logged: LoggedEvent) {
    Column {
        Text(TIME.format(logged.at.toJavaInstant()), style = MaterialTheme.typography.labelSmall)
        Mono(EventLine.of(logged.event))
    }
}

/** What skip detection has learned, per player, and its reset. */
@Composable
private fun LearnedTab(diagnostics: Diagnostics) {
    val learning = diagnostics.skipLearning
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.diagnostics_learned_hint), style = MaterialTheme.typography.bodySmall)
        if (learning == null) {
            Text(stringResource(R.string.diagnostics_not_learning))
            return@Column
        }
        val learned by learning.learnedCrossfades.collectAsState()
        if (learned.isEmpty()) {
            Text(stringResource(R.string.diagnostics_nothing_learned))
        } else {
            learned.toSortedMap().forEach { (player, crossfade) -> Mono(DiagnosticsText.learned(player, crossfade)) }
        }
        var confirm by remember { mutableStateOf(false) }
        // How the last reset ended, kept through a rotation; null for none.
        var resetDone by rememberSaveable { mutableStateOf<Boolean?>(null) }
        val scope = rememberCoroutineScope()
        OutlinedButton(onClick = { confirm = true }, enabled = learned.isNotEmpty()) {
            Text(stringResource(R.string.diagnostics_reset))
        }
        resetDone?.let { done ->
            Text(
                stringResource(if (done) R.string.diagnostics_reset_done else R.string.diagnostics_reset_failed),
                color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (confirm) {
            ResetDialog(
                onReset = {
                    confirm = false
                    resetDone = null
                    // Not cut short by leaving the screen.
                    scope.launch { resetDone = withContext(NonCancellable) { diagnostics.resetLearning() } }
                },
                onDismiss = { confirm = false },
            )
        }
    }
}

@Composable
private fun ResetDialog(
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics_reset_title)) },
        text = { Text(stringResource(R.string.diagnostics_reset_explained)) },
        confirmButton = { TextButton(onClick = onReset) { Text(stringResource(R.string.diagnostics_reset)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
internal fun Mono(
    text: String,
    indent: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = if (indent) Modifier.padding(start = 16.dp) else Modifier,
    )
}

private const val EXPORT_TYPE = "text/plain"

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private val FILE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

/** The file name the export suggests. */
internal fun exportName(at: Instant): String = "sleevesight-diagnostics-${FILE_TIME.format(at.toJavaInstant())}.txt"
