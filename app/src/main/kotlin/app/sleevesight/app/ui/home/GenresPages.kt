package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings

// The genres' page under *Layout*'s *Separate pages* (#447, #558; frame C of the agreed mock-ups).

/**
 * The genres' page ([HomePage.GenresAt], or [HomePage.Genres] from a back stack an earlier version
 * saved): the chips Home shows, of [track], the one of the genre open marked, and that genre's
 * panel ([GenrePanel]), with no *Close*: the back arrow closes the page; what the genre is and *Why is
 * this …?* from [notes] (#523). It opens at [genre], the
 * chip tapped on Home; a chip switches the panel to its genre, and the marked one is not a button.
 * With no [genre], or one the track does not have (a new track since), it opens at the first
 * chip's. While the track's lookups run, and with *Show genres* off in [settings], it says so.
 */
@Composable
internal fun GenresPage(
    track: TrackState?,
    settings: Settings?,
    genre: String?,
    notes: PanelNotes = PanelNotes(),
) {
    val genres = track?.genres
    when {
        settings?.showGenres == false -> QuietLine(stringResource(R.string.home_genres_hidden))
        track == null || !track.gathered || genres == null -> QuietLine(stringResource(R.string.home_evidence_looking))
        genres.isEmpty && labelsOff(settings) -> QuietLine(stringResource(R.string.home_genres_off))
        genres.isEmpty -> QuietLine(stringResource(R.string.home_genres_none))
        else -> GenresPageBody(track, genre, notes)
    }
}

@Composable
private fun GenresPageBody(
    track: TrackState,
    start: String?,
    notes: PanelNotes,
) {
    val genres = track.genres
    // Back to the chip tapped on Home on each new track, which falls back to the first chip.
    var chosen by rememberSaveable(track.normalized.lookupKey) { mutableStateOf(start) }
    val open =
        chosen?.takeIf { id -> genres.all.any { it.genre.identity == id } }
            ?: genres.folded
                .first()
                .label.genre.identity
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Chips(genres.folded, open, close = false) { chosen = it.label.genre.identity }
        GenrePanel(track, open, notes = notes)
    }
}

/** A quiet line of the genres: small, in the muted colour. */
@Composable
internal fun QuietLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
