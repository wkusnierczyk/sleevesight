package app.sleevesight.app.ui.home

import app.sleevesight.core.pipeline.AuthorsName
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState

/**
 * What a report of [item]'s insight says it is of (`insightReport`'s `about`), in English as the
 * report is, by the row it was opened from on [track], the track the report captured (#1276): the
 * Artist row "the performer's" and the Composer row as it is called ("the composer's", "the author's",
 * "the authors'") where [track]'s roles tell a composer apart ([splitOf]); else the item's own kind
 * ("the song's", "the artist's", "the album's"; a performer's or an author's chip "the artist's").
 * Only the words: the item and its card's keys stay as they are.
 */
internal fun reportedAbout(
    item: InsightItem,
    track: TrackState,
): String {
    val split = splitOf(track, track.normalized).takeIf { item == InsightItem.ARTIST || item == InsightItem.COMPOSER }
    val whose =
        when {
            item == InsightItem.COMPOSER ->
                when (split?.name) {
                    AuthorsName.AUTHOR -> "author's"
                    AuthorsName.AUTHORS -> "authors'"
                    else -> "composer's"
                }
            item == InsightItem.ARTIST && split != null -> "performer's"
            else -> "${item.kind.key}'s"
        }
    return "the $whose"
}
