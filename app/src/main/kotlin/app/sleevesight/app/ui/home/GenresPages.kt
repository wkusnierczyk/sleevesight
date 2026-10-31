package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings

// The genres' page under *Layout*'s *Separate pages* (#447, #558, #772).

/**
 * The genres' page ([HomePage.Genres], from *Genres* on Home, #772; or [HomePage.GenresAt], from a
 * back stack an earlier version saved, whose genre it no longer needs): with no chips, every genre
 * the chips would show, of [track], in their order, each in a panel of its own titled with its name
 * ([GenrePanel], with no *Close*: the back arrow closes the page), holding what the genre's box in
 * place holds, what it is and *Why is this …?* from [notes] (#523). With none, it says why as
 * *Genres*' panel does ([GenresContent]): still looking, a source not reached with *Retry* ([retry]),
 * none found, or every genre source off with a link to the settings ([openSettingsAt]). With *Show
 * genres* off in [settings], it says so; before the settings' first read, nothing.
 */
@Composable
internal fun GenresPage(
    track: TrackState?,
    settings: Settings?,
    notes: PanelNotes = PanelNotes(),
    retry: (SourceId) -> (() -> Unit)? = { null },
    openSettingsAt: (SettingsTarget) -> Unit = {},
) {
    when {
        settings == null -> Unit
        !settings.showGenres -> QuietLine(stringResource(R.string.home_genres_hidden))
        else -> GenresContent(track, settings, notes, retry, openSettingsAt, everyGenre = true)
    }
}

/** A quiet line of the genres: small, in the muted colour. */
@Composable
internal fun QuietLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
