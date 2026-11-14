package app.sleevesight.app.ui.home

import app.sleevesight.app.R
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState

/**
 * What a report of [item]'s insight says it is of (`insightReport`'s `about`), in English as the
 * report is, as the row it was opened from names it on [track], the track the report captured
 * (#1276): the row as [itemRow] makes it, so one rule names both the button and the report. "the
 * performer's" for the Artist row where [track]'s roles tell a composer apart, "the composer's",
 * "the author's" or "the authors'" for the Composer row; else the item's own kind ("the song's",
 * "the artist's", "the album's"; a performer's or an author's chip, which has no row, "the
 * artist's"). Only the words: the item and its card's keys stay as they are.
 */
internal fun reportedAbout(
    item: InsightItem,
    track: TrackState,
): String {
    val whose =
        when (itemRow(item, track.nowPlaying, track.normalized, track)?.kind) {
            R.string.home_item_performer -> "performer's"
            R.string.home_item_author -> "author's"
            R.string.home_item_authors -> "authors'"
            R.string.home_item_composer -> "composer's"
            else -> "${item.kind.key}'s"
        }
    return "the $whose"
}
