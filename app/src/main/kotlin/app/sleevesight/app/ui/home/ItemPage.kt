package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightItem

// The song's, the artist's or the album's insight on a page of its own (#522), under *Layout*'s
// *Separate pages*.

/**
 * [item]'s page: its name first ([name], the track's title, its primary artist, or the player's
 * album name, whole, as under the cover), then what its row unfolds under *Expandable boxes*
 * ([ItemContent]): opening it is a tap, so under an automatic mode it is written at once. It shows
 * the track playing, so it follows a track change.
 */
@Composable
internal fun ItemPage(
    item: InsightItem,
    name: String?,
    context: ItemContext,
) {
    name?.let { Text(it, Modifier.testTag(ITEM_PAGE_NAME_TAG), style = MaterialTheme.typography.titleLarge) }
    ItemContent(item, context, tapped = true)
}

/**
 * The name [item] goes by on [header], the track shown, as its row names it; null for an album the
 * player does not name.
 */
internal fun itemName(
    item: InsightItem,
    header: NowPlaying,
    primary: String,
): String? = itemRows(header, primary).firstOrNull { it.item == item }?.name

/** The test tag of an item's page's first line, its name. */
const val ITEM_PAGE_NAME_TAG: String = "item-page:name"
