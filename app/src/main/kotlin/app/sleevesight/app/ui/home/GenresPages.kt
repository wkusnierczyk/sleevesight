package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings

// The genre labels under *Layout*'s *Separate pages* (#447): on Home, the folded chips and the way to
// the table's page; the table's page; and a label's page, where it comes from.

/**
 * On Home, under *Separate pages*: the folded chips of [genres] (the most specific level's), after
 * `Genres ›`, which opens the table's page, when there is more than one level; each chip opens its
 * label's page. With no labels, what the box says ([labelsOff] in [settings], or none found).
 */
@Composable
internal fun GenresLinks(
    genres: TrackGenres,
    settings: Settings?,
    openPage: (HomePage) -> Unit,
) {
    val open = { label: PlacedLabel -> openPage(HomePage.Genre(label.key)) }
    when {
        genres.isEmpty && labelsOff(settings) -> QuietLine(stringResource(R.string.home_genres_off))
        genres.isEmpty -> QuietLine(stringResource(R.string.home_genres_none))
        genres.oneLevel -> Chips(genres.folded, open = null, select = open, opensPage = true)
        else ->
            Chips(genres.folded, open = null, select = open, opensPage = true) {
                PageLink(
                    stringResource(R.string.home_genres),
                    GENRES_PAGE_LINK_TAG,
                    MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                ) { openPage(HomePage.Genres) }
            }
    }
}

/**
 * The table's page ([HomePage.Genres]): the genre labels of [track] as the box's table, a row per
 * level (or one level's chips), each chip opening its label's page. While the track's lookups run,
 * and with *Show genres* off in [settings], it says so.
 */
@Composable
internal fun GenresPage(
    track: TrackState?,
    settings: Settings?,
    openPage: (HomePage) -> Unit,
) {
    val genres = track?.genres
    val open = { label: PlacedLabel -> openPage(HomePage.Genre(label.key)) }
    when {
        settings?.showGenres == false -> QuietLine(stringResource(R.string.home_genres_hidden))
        track == null || !track.gathered || genres == null -> QuietLine(stringResource(R.string.home_evidence_looking))
        genres.isEmpty && labelsOff(settings) -> QuietLine(stringResource(R.string.home_genres_off))
        genres.isEmpty -> QuietLine(stringResource(R.string.home_genres_none))
        genres.oneLevel -> Chips(genres.folded, open = null, select = open, opensPage = true)
        else -> Table(genres, open = null, select = open, opensPage = true)
    }
}

/**
 * A label's page ([HomePage.Genre]): where the label kept by [key] comes from, as under its chip in
 * the box ([Credits]); a label the playing [track] does not have (another track has started since)
 * says so.
 */
@Composable
internal fun GenrePage(
    track: TrackState?,
    key: String,
) {
    val placed = track?.let { labelOf(it.genres, key) }
    if (track == null || placed == null) {
        QuietLine(stringResource(R.string.home_genre_gone))
        return
    }
    Credits(placed, several = track.normalized.artists.size > 1)
}

/** The label of [genres] kept by [key], on any level, or null. */
internal fun labelOf(
    genres: TrackGenres,
    key: String,
): PlacedLabel? =
    genres.rows
        .flatMap { row -> row.labels.map { PlacedLabel(row, it) } }
        .firstOrNull { it.key == key }

/** A quiet line of the genres: small, in the muted colour. */
@Composable
internal fun QuietLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
