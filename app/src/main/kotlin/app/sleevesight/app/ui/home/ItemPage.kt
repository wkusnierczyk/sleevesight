package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightItem

// The song's, the artist's or the album's insight on a page of its own (#522), under *Layout*'s
// *Separate pages*.

/**
 * [item]'s page: its name first ([name], the track's title, its primary artist, or the player's
 * album name, whole, as under the cover), then what its row unfolds under *Expandable boxes*
 * ([ItemContent]). It shows the track playing, so it follows a track change; opening it is a tap
 * ([tapped]) for the track it was opened on only, so that track's is written at once, in every mode
 * (#948; under *On demand* while [opening] is due), and a later track's only as one unfolded at
 * its start is, by itself under an automatic mode. With no name for it on the track playing, it says
 * there is nothing to write it from.
 */
@Composable
internal fun ItemPage(
    item: InsightItem,
    name: String?,
    context: ItemContext,
    tapped: Boolean,
    opening: OpeningWrite = OpeningWrite.NONE,
) {
    if (name == null) {
        // Nothing to write about on this track (no album it names): said as for no evidence.
        ItemQuiet(stringResource(noEvidence(item)))
        return
    }
    Text(name, Modifier.testTag(ITEM_PAGE_NAME_TAG), style = MaterialTheme.typography.titleLarge)
    ItemContent(item, context, tapped, opening = opening)
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
