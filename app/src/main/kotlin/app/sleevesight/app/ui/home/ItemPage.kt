package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import app.sleevesight.app.R
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState

// The song's, the artist's or the album's insight on a page of its own (#522), under *Layout*'s
// *Separate pages*.

/**
 * [item]'s page: its name first ([row]'s, the track's title, its primary artist, or the player's
 * album name, whole, as under the cover), for the song the version its title named under it, as in
 * its panel's heading ("Version: 2024 Remaster", #1160), read with the name as one stop; then what
 * its row unfolds under *Expandable boxes*
 * ([ItemContent]). It shows the track playing, so it follows a track change; opening it is a tap
 * ([tapped]) for the track it was opened on only, so that track's is written at once, in every mode
 * (#948; under *On demand* while [opening] is due), and a later track's only as one unfolded at
 * its start is, by itself under an automatic mode. With no name for it on the track playing, it says
 * there is nothing to write it from. [afterRetry] hands the focus on after *Retry* beside sources not
 * reached (#1163), as [ItemContent] says.
 */
@Suppress("LongParameterList") // The item, its row, how it was opened, and where the focus goes after *Retry*.
@Composable
internal fun ItemPage(
    item: InsightItem,
    row: ItemRow?,
    context: ItemContext,
    tapped: Boolean,
    opening: OpeningWrite = OpeningWrite.NONE,
    afterRetry: () -> Unit = {},
) {
    if (row == null) {
        // Nothing to write about on this track (no album it names): said as for no evidence.
        ItemQuiet(stringResource(noEvidence(item, context.track)))
        return
    }
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(rowName(row), Modifier.testTag(ITEM_PAGE_NAME_TAG), style = MaterialTheme.typography.titleLarge)
        row.version?.let {
            Text(
                stringResource(R.string.home_item_version, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    ItemContent(item, context, tapped, opening = opening, afterRetry = afterRetry)
}

/**
 * [item]'s row on [header], the track shown, as [normalized] reads it, with the roles [track] knows
 * ([splitOf], #1199): the name it goes by, and the song's version; null for an album the player does
 * not name, and for the composer on a track whose roles tell none.
 */
internal fun itemRow(
    item: InsightItem,
    header: NowPlaying,
    normalized: NormalizedTrack,
    track: TrackState? = null,
): ItemRow? = itemRows(header, normalized, splitOf(track, normalized)).firstOrNull { it.item == item }

/**
 * What [item]'s row is called on [track], the track the insight is of ([itemRow]'s [ItemRow.kind]),
 * a string's id; null with no track or no row.
 */
internal fun rowKind(
    item: InsightItem,
    track: TrackState?,
): Int? = track?.let { itemRow(item, it.nowPlaying, it.played, it)?.kind }

/** The test tag of an item's page's first line, its name. */
const val ITEM_PAGE_NAME_TAG: String = "item-page:name"
