package app.sleevesight.app.ui.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout

// The song's, the artist's and the album's insights under *Key insights* (#522): a row each, under
// the key insight and before *Sources*, its button opening a panel in the row's place under
// *Expandable boxes* (#894) or a page of its own under *Separate pages*.

/**
 * The rows under *Key insights* (#522, #894), before *Sources*: [rows] as a table of two columns, no
 * lines between them. On the left each kind (*Song*, *Artist*, *Album*) as a button drawn as *Dates*'
 * is ([PanelChip]), all as wide as the widest, so both their edges line up and the names start in
 * one column; on the right its name, on one line clipped with an ellipsis, a tap showing it whole and
 * another clipping it again ([NameText]). A screen reader reads a row as one stop, its button: "Song,
 * Paper Boats. Button. Double-tap to show the song's insight", the whole name, clipped or not.
 *
 * Under *Expandable boxes* ([pages] false) a button opens its item in a panel in its row's place
 * ([Panel]: the kind as its title, the name under it, *Close*), the row hidden while it is open;
 * several may be open, in the rows' order; *Close* folds it back into its row. Which are open is
 * [folds]', so *Song, Artist and Album on a new track* applies. Under *Separate pages* a button opens
 * its page ([openPage]) and stays, "double-tap to open page".
 *
 * The focus moves as the line under the album's does ([InPlaceLine]): to a panel's *Close* when its
 * button opens it, back to its button when *Close* closes it, and to its button when a new track
 * closes a panel while the focus is in it, if the new track has that row.
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
    val handoff = rememberFocusHandoff()
    val buttons = remember { mutableMapOf<InsightItem, FocusRequester>() }
    val closes = remember { mutableMapOf<InsightItem, FocusRequester>() }
    val buttonOf = { item: InsightItem -> buttons.getOrPut(item) { FocusRequester() } }
    val closeOf = { item: InsightItem -> closes.getOrPut(item) { FocusRequester() } }
    val open = if (pages) emptySet() else rows.map { it.item }.filter { it in folds.open }.toSet()
    // Which panels hold the input focus, as last told, and which were open: plain, read as a panel goes.
    val inside = remember { mutableSetOf<InsightItem>() }
    val before = remember { mutableSetOf<InsightItem>().apply { addAll(open) } }
    // A panel gone by other than *Close* (a new track) while the focus was in it: back to its button,
    // or a keyboard's focus would fall to the top of the screen. Read before the panel goes.
    val handBack = before.firstOrNull { it !in open && it in inside }
    SideEffect {
        before.clear()
        before.addAll(open)
        // No panel, no focus in it: a panel that goes may not be told it lost the focus.
        inside.retainAll(open)
        if (handBack != null) handoff.moveTo(buttonOf(handBack))
    }
    val kinds = rows.map { itemKind(it.item) }
    // The widest kind sets every button's width, whichever rows are open, so nothing moves on opening.
    val width = chipColumnWidth(kinds)
    Column(Modifier.fillMaxWidth().testTag(ITEM_ROWS_TAG), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        rows.forEachIndexed { index, row ->
            val item = row.item
            // Keyed by the item, not its place: a row opened keeps the others' state where they are.
            key(item) {
                if (item in open) {
                    ItemPanel(
                        kinds[index],
                        row,
                        Modifier.handoffTarget(handoff, closeOf(item)),
                        focused = { if (it) inside += item else inside -= item },
                        close = {
                            folds.toggle(item)
                            handoff.moveTo(buttonOf(item))
                        },
                    ) { ItemContent(item, context, tapped = folds.tapped(item)) }
                } else {
                    ItemRowLine(
                        kinds[index],
                        row,
                        width,
                        pages,
                        whole = folds.whole(item),
                        onName = { folds.toggleWhole(item) },
                        button = Modifier.handoffTarget(handoff, buttonOf(item)),
                    ) {
                        if (pages) {
                            openPage(item)
                        } else {
                            folds.toggle(item)
                            handoff.moveTo(closeOf(item))
                        }
                    }
                }
            }
        }
    }
}

// [row]'s panel, open in its place: [kind] as its title, the name under it; told whether the input
// focus is in it ([focused]); its *Close*, under [closeModifier] (a focus requester), [close]s it.
@Suppress("LongParameterList") // What the panel shows, and its focus and close.
@Composable
private fun ItemPanel(
    kind: String,
    row: ItemRow,
    closeModifier: Modifier,
    focused: (Boolean) -> Unit,
    close: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.padding(vertical = PANEL_GAP).onFocusChanged { focused(it.hasFocus) }) {
        Panel(
            AnnotatedString(kind),
            itemPanelTag(row.item),
            onClose = close,
            closeModifier = closeModifier,
            subtitle = row.name,
        ) { content() }
    }
}

// A row: [kind]'s button, [width] wide, under [button]'s modifier (a focus requester), read with
// [row]'s name as one stop; the name beside it, [whole] or clipped, [onName] on a tap that changes it.
@Suppress("LongParameterList") // What the row shows, and its taps.
@Composable
private fun ItemRowLine(
    kind: String,
    row: ItemRow,
    width: Dp,
    pages: Boolean,
    whole: Boolean,
    onName: () -> Unit,
    button: Modifier,
    onClick: () -> Unit,
) {
    val action = stringResource(if (pages) R.string.home_page_open else itemShowAction(row.item))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PanelChip(
            kind,
            itemRowTag(row.item),
            modifier = button,
            action = action,
            collapsed = false,
            description = "$kind, ${row.name}",
            width = width,
            onClick = onClick,
        )
        Spacer(Modifier.width(COLUMN_GAP))
        NameText(row.name, whole, onName, Modifier.weight(1f).testTag(itemNameTag(row.item)))
    }
}

// A row's [name]: on one line clipped with an ellipsis, or [whole], wrapped; a tap ([onTap]) on one
// clipped shows it whole, on one whole clips it again, on one that fits does nothing. For touch only:
// its button reads the whole name, so a screen reader and a keyboard pass it by.
@Composable
private fun NameText(
    name: String,
    whole: Boolean,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    // Whether the name, clipped, did not fit, as last laid out.
    var clipped by remember(name) { mutableStateOf(false) }
    val tap by rememberUpdatedState { if (whole || clipped) onTap() }
    Text(
        name,
        modifier
            .clearAndSetSemantics {}
            .pointerInput(Unit) { detectTapGestures { tap() } },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (whole) Int.MAX_VALUE else 1,
        overflow = if (whole) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { if (!whole) clipped = it.hasVisualOverflow },
    )
}

// The space between a row's button and its name; between two rows, so buttons taller than their
// 48 dp target at a large font stay apart; and more above and below an open panel.
private val COLUMN_GAP = 14.dp
private val ROW_GAP = 6.dp
private val PANEL_GAP = 2.dp

/**
 * The rows on Home (#522), of [header], the track shown: under *Expandable boxes* opening in place,
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
    val context =
        itemContext(
            state,
            shown,
            settings,
            actions,
            cite,
            sourcesLines(actions.itemNotes, shown.track).evidence,
            onReport,
        )
    val rows = itemRows(header, actions.normalize(header).primary)
    ItemRows(rows, pages, folds, context) { item -> actions.openPage(HomePage.Item(item.name)) }
}

/** The test tag of the rows under *Key insights*. */
const val ITEM_ROWS_TAG: String = "items"

/** The test tag of an item's line while it is written, and of a failed call's line with *Retry*. */
const val ITEM_WRITING_TAG: String = "item:writing"
const val ITEM_FAILED_TAG: String = "item:failed"

/** The test tag of [item]'s row's button, the row's one stop for a screen reader. */
fun itemRowTag(item: InsightItem): String = "item-row:${item.kind.key}"

/** The test tag of [item]'s name in its row. */
fun itemNameTag(item: InsightItem): String = "item-name:${item.kind.key}"

/** The test tag of [item]'s panel, as [Panel] makes it, with `:heading`, `:subtitle` and `:close`. */
fun itemPanelTag(item: InsightItem): String = "item-panel:${item.kind.key}"

/** The test tag of [item]'s *Write insight*. */
fun itemWriteTag(item: InsightItem): String = "item-write:${item.kind.key}"

/** The test tag of [item]'s *Read aloud*. */
fun itemReadAloudTag(item: InsightItem): String = "item-read-aloud:${item.kind.key}"
