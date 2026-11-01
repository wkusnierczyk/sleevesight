package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.core.pipeline.TrackState

// A genre's body (#558, #786): what it is and why the track has it, then its tags and where they come
// from ([TagsGrid], `GenreTagsGrid.kt`); in a panel of its own, titled with its name, on the genres' page.

/**
 * The panel of [genre] on the genres' page under *Separate pages* (#558, #772): its name as a heading,
 * with no *Close* (the page's back arrow closes it), over its body ([GenreBody]). Nothing when no
 * row has [genre].
 */
@Composable
internal fun GenrePanel(
    track: TrackState,
    genre: String,
    notes: PanelNotes = PanelNotes(),
    search: PanelSearch = PanelSearch(),
    afterRetry: () -> Unit = {},
) {
    val name =
        track.genres.all
            .firstOrNull { it.genre.identity == genre }
            ?.genre
            ?.label ?: return
    Panel(AnnotatedString(name), GENRE_PANEL_TAG, onClose = null) {
        GenreBody(track, genre, notes, search, afterRetry)
    }
}

/**
 * What [genre] shows for [track] (#558, #786; `design/design.md`, *Genre labels*): what the genre is
 * and *Why is this …?*, from [notes], with *Search with AI* for a definition Wikipedia does not give
 * ([search]; [GenreAbout], whose failed lookup's *Retry* hands the focus to [afterRetry]); then its
 * tags and their sources ([TagsGrid]). Unfolded under *Genres*' chips on Home ([GenresBox]), with no
 * heading of its own; in a panel titled with its name on the genres' page ([GenrePanel]). [modifier]
 * goes on its column (a test tag). Nothing when no row has [genre].
 */
@Suppress("LongParameterList") // What the body shows, where its notes and search come from, and the focus.
@Composable
internal fun GenreBody(
    track: TrackState,
    genre: String,
    notes: PanelNotes,
    search: PanelSearch,
    afterRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (track.genres.all.none { it.genre.identity == genre }) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // What the genre is, and *Why is this …?* (#523), above the tags.
        GenreAbout(track, genre, notes, search, afterRetry)
        TagsGrid(track, genre)
    }
}

/**
 * What a genre's body shows above its tags ([GenreAbout]): [notes], where its definition and
 * reason come from; where a citation in the reason goes ([onCite]: on Home, *Sources* unfolded to
 * its line; under *Separate pages*, *Sources*' page at it); whether the model's own knowledge is
 * allowed now ([modelAllowed]); what reads a written reason aloud ([readAloud], #325), null for
 * no *Read aloud*; and where a link under a disabled *Why is this …?* opens the settings
 * ([openSettingsAt], #762).
 */
internal class PanelNotes(
    val notes: GenreNotes = GenreNotes.None,
    val onCite: (String) -> Unit = {},
    val modelAllowed: Boolean = false,
    val readAloud: ReadAloud? = null,
    val openSettingsAt: (SettingsTarget) -> Unit = {},
)

/** The test tags of the genre's panel on the genres' page and its heading, as [Panel] makes them. */
const val GENRE_PANEL_TAG: String = "genre-panel"
const val GENRE_PANEL_HEADING_TAG: String = "genre-panel:heading"
