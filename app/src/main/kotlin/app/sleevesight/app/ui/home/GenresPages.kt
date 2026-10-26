package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings

// The genre labels under *Layout*'s *Separate pages* (#447): on Home, the folded chips and the way to
// the table's page; and the table's page.

/**
 * On Home, under *Separate pages*: the folded chips of [genres] (the most specific level's), after
 * `Genres ›` when there is more than one level; `Genres ›` and each chip open the table's page.
 * With one level there is no `Genres ›`, and each chip opens the page all the same (#548): a chip
 * that looks like a button and opens nothing reads as broken. With no labels, what the box says
 * ([labelsOff] in [settings], or none found).
 */
@Composable
internal fun GenresLinks(
    genres: TrackGenres,
    settings: Settings?,
    openPage: (HomePage) -> Unit,
) {
    when {
        genres.isEmpty && labelsOff(settings) -> QuietLine(stringResource(R.string.home_genres_off))
        genres.isEmpty -> QuietLine(stringResource(R.string.home_genres_none))
        else ->
            Chips(genres.folded, open = null, select = { openPage(HomePage.Genres) }, opensPage = true) {
                if (!genres.oneLevel) {
                    PageLink(
                        stringResource(R.string.home_genres),
                        GENRES_PAGE_LINK_TAG,
                        MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    ) { openPage(HomePage.Genres) }
                }
            }
    }
}

/**
 * The table's page ([HomePage.Genres]): the genre labels of [track] as the box's table, a row per
 * level, as plain labels; with one level, the table's one row (or a row per artist). While the
 * track's lookups run, and with *Show genres* off in [settings], it says so.
 */
@Composable
internal fun GenresPage(
    track: TrackState?,
    settings: Settings?,
) {
    val genres = track?.genres
    when {
        settings?.showGenres == false -> QuietLine(stringResource(R.string.home_genres_hidden))
        track == null || !track.gathered || genres == null -> QuietLine(stringResource(R.string.home_evidence_looking))
        genres.isEmpty && labelsOff(settings) -> QuietLine(stringResource(R.string.home_genres_off))
        genres.isEmpty -> QuietLine(stringResource(R.string.home_genres_none))
        else -> Table(genres, open = null, select = null)
    }
}

/** A quiet line of the genres: small, in the muted colour. */
@Composable
internal fun QuietLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
