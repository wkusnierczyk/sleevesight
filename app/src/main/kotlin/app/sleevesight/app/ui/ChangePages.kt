package app.sleevesight.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.diagnostics.Diagnostics
import app.sleevesight.app.diagnostics.DiagnosticsText
import app.sleevesight.app.diagnostics.PageResult
import app.sleevesight.store.TrackChangeRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.toJavaInstant

// The diagnostics screen's track changes (D15): loaded a page at a time, newest first.

/** The track changes loaded so far, newest first, and how the last load went. */
internal class ChangePages(
    private val diagnostics: Diagnostics,
    private val scope: CoroutineScope,
) {
    val changes = mutableStateListOf<TrackChangeRecord>()

    /** Whether an older page may follow: true until a page says none does. */
    var more by mutableStateOf(true)
        private set

    var loading by mutableStateOf(false)
        private set

    var failed by mutableStateOf(false)
        private set

    private var next: Long? = null

    /** Loads the next, older page, unless one is loading or none is left. */
    fun loadMore() {
        if (loading || !more) return
        loading = true
        failed = false
        scope.launch {
            when (val result = diagnostics.changes(next)) {
                is PageResult.Read -> {
                    changes += result.page.changes
                    next = result.page.next
                    more = next != null
                }

                PageResult.Failed -> {
                    failed = true
                }
            }
            loading = false
        }
    }
}

/** The track changes loaded, each opening on its states, and the way to the older ones. */
@Composable
internal fun ChangesTab(pages: ChangePages) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pages.changes.isEmpty() && !pages.more) {
            item { Text(stringResource(R.string.diagnostics_no_changes)) }
        }
        items(pages.changes) { ChangeRow(it) }
        item {
            when {
                pages.failed -> {
                    Text(stringResource(R.string.diagnostics_changes_failed), color = MaterialTheme.colorScheme.error)
                }

                pages.loading -> {
                    Text(stringResource(R.string.diagnostics_loading))
                }

                else -> {}
            }
            if (pages.more && !pages.loading) {
                OutlinedButton(onClick = pages::loadMore) {
                    Text(stringResource(R.string.diagnostics_older_changes))
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(change: TrackChangeRecord) {
    var open by remember(change) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Text(DATE_TIME.format(change.changedAt.toJavaInstant()), style = MaterialTheme.typography.labelSmall)
        Mono(DiagnosticsText.change(change))
        if (open) {
            for (state in change.states) Mono(DiagnosticsText.state(state), indent = true)
        }
    }
}

private val DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
