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
 * *Genres*' panel does ([GenresContent]): still looking, a source not reached with *Retry*, none
 * found, or every genre source off with a link to the settings ([ways]). With *Show genres* off in
 * [settings], it says so; before the settings' first read, nothing.
 */
@Composable
internal fun GenresPage(
    track: TrackState?,
    settings: Settings?,
    notes: PanelNotes = PanelNotes(),
    ways: GenresWays = GenresWays(),
) {
    when {
        settings == null -> Unit
        !settings.showGenres -> QuietLine(stringResource(R.string.home_genres_hidden))
        else ->
            GenresContent(
                track,
                settings,
                notes,
                ways.retry,
                ways.openSettingsAt,
                ways.afterRetry,
                everyGenre = true,
            )
    }
}

/**
 * The genres' page's ways out when it has no genre to show (#772): [retry] of a label source not
 * reached, [openSettingsAt] where the genre sources are switched on, and [afterRetry], where the
 * focus goes as *Retry* gives way to "Looking on …" (the page's back arrow), or a keyboard's or a
 * screen reader's would fall to the top of the screen.
 */
internal class GenresWays(
    val retry: (SourceId) -> (() -> Unit)? = { null },
    val openSettingsAt: (SettingsTarget) -> Unit = {},
    val afterRetry: () -> Unit = {},
)

/** A quiet line of the genres: small, in the muted colour. */
@Composable
internal fun QuietLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
