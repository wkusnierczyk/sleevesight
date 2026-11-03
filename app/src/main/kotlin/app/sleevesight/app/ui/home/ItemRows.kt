package app.sleevesight.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout

// The song's, the artist's and the album's insights under *Key insights* (#522): a row each, under
// the key insight and before *Sources*, unfolding in place under *Expandable boxes* or opening a page
// of its own under *Separate pages*.

/**
 * The rows under *Key insights* (#522), before *Sources*: [rows], with a line between them, each its
 * kind, then its name, clipped with an ellipsis, then its arrow; the kinds aligned with each other and
 * the names with each other, as two columns. Under *Expandable boxes* ([pages] false) a tap unfolds
 * the item under its row (▸, ▾), several at once, and another folds it; a screen reader reads a row as
 * "Artist, Ellen Hart, button, collapsed, double-tap to expand". Under *Separate pages* a tap opens
 * its page ([openPage], ›), "double-tap to open page".
 */
@Suppress("LongParameterList") // The rows, how they open, and what each shows.
@Composable
internal fun ItemRows(
    rows: List<ItemRow>,
    pages: Boolean,
    folds: ItemFolds,
    context: ItemContext,
    openPage: (InsightItem) -> Unit,
) {
    val style = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val kinds = rows.map { itemKind(it.item) }
    // The widest kind, so the names start in one column.
    val kindWidth =
        with(density) { kinds.maxOfOrNull { measurer.measure(it, style).size.width }?.toDp() ?: 0.dp }
    Column(Modifier.fillMaxWidth().testTag(ITEM_ROWS_TAG)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        rows.forEachIndexed { index, row ->
            val open = !pages && row.item in folds.open
            RowHeader(kinds[index], row.name, kindWidth, pages, open, row.item) {
                if (pages) openPage(row.item) else folds.toggle(row.item)
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ItemContent(row.item, context, tapped = folds.tapped(row.item))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// A row's header: the kind in its column, the name clipped, the arrow; one button to a screen reader.
@Suppress("LongParameterList") // What the row shows, and its tap.
@Composable
private fun RowHeader(
    kind: String,
    name: String,
    kindWidth: Dp,
    pages: Boolean,
    open: Boolean,
    item: InsightItem,
    onClick: () -> Unit,
) {
    val action =
        stringResource(
            when {
                pages -> R.string.home_page_open
                open -> R.string.home_item_collapse
                else -> R.string.home_item_expand
            },
        )
    val state = stringResource(if (open) R.string.home_fold_expanded else R.string.home_fold_collapsed)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(itemRowTag(item))
                .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = "$kind, $name"
                    if (!pages) stateDescription = state
                }.padding(horizontal = 2.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(kind, Modifier.width(kindWidth), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(16.dp))
        Text(
            name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(
                when {
                    pages -> R.string.home_item_page_arrow
                    open -> R.string.home_fold_unfolded
                    else -> R.string.home_fold_folded
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The rows on Home (#522), of [header], the track shown: under *Expandable boxes* unfolding in place,
 * kept in [memory] and starting on a new track as *Song, Artist and Album on a new track* says; under
 * *Separate pages* each opening its page. A citation goes to [cite], a report to [onReport]. Nothing
 * until the settings are read, as *Sources*.
 */
@Suppress("LongParameterList") // What the rows show, where they are kept, and where their taps go.
@Composable
internal fun HomeItems(
    state: NowPlayingState,
    shown: Showing,
    header: NowPlaying,
    memory: ItemFoldMemory,
    cite: (String) -> Unit,
    actions: NowPlayingActions,
    onReport: (TrackState, InsightState.Written, InsightItem) -> Unit,
) {
    val settings = state.settings ?: return
    val pages = settings.layout == Layout.SEPARATE_PAGES
    val folds = itemFolds(memory, shown.playing, settings.itemsOnNewTrack)
    val context = itemContext(state, shown, settings, actions, cite, onReport)
    val rows = itemRows(header, actions.normalize(header).primary)
    ItemRows(rows, pages, folds, context) { item -> actions.openPage(HomePage.Item(item.name)) }
}

/** The test tag of the rows under *Key insights*. */
const val ITEM_ROWS_TAG: String = "items"

/** The test tag of an item's line while it is written, and of a failed call's line with *Retry*. */
const val ITEM_WRITING_TAG: String = "item:writing"
const val ITEM_FAILED_TAG: String = "item:failed"

/** The test tag of [item]'s row. */
fun itemRowTag(item: InsightItem): String = "item-row:${item.kind.key}"

/** The test tag of [item]'s *Write insight*. */
fun itemWriteTag(item: InsightItem): String = "item-write:${item.kind.key}"

/** The test tag of [item]'s *Read aloud*. */
fun itemReadAloudTag(item: InsightItem): String = "item-read-aloud:${item.kind.key}"
