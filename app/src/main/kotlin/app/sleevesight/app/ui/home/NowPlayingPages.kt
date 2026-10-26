package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState

// Home's pages (#447), and what Home and its pages read alike of the state.

/**
 * A page of Home's, [page], under *Layout*'s *Separate pages*: the item's component on a page of
 * its own ([PageFrame]), in the album art's colours, from the same [state] as Home, for the track
 * playing, with Home's [actions]; [onBack] goes back. Each page opens fresh: *Sources* at its
 * first screenful with every source's lines shown, whatever *Keep Sources as you left them* says.
 */
@Composable
internal fun NowPlayingPageContent(
    page: HomePage,
    state: NowPlayingState,
    actions: NowPlayingActions,
    onBack: () -> Unit,
) {
    val playing = state.playing
    val shown = playing?.let { Showing(state, it) }
    // The app's background, read outside the art's scheme, so the page's ground is Home's (#609).
    val ground = MaterialTheme.colorScheme.surface
    ArtTheme(state.art) {
        PageFrame(pageTitle(page), onBack, containerColor = ground) {
            if (playing == null || shown == null) {
                NothingPlaying()
                return@PageFrame
            }
            when (page) {
                is HomePage.Sources -> SourcesPage(shown, playing, page.cite, actions.openSettings)
                HomePage.Genres -> GenresPage(shown.track, state.settings, genre = null)
                is HomePage.GenresAt -> GenresPage(shown.track, state.settings, page.genre)
            }
        }
    }
}

/** A page's title: the item's name. */
@Composable
private fun pageTitle(page: HomePage): String =
    when (page) {
        is HomePage.Sources -> stringResource(R.string.home_sources_title)
        HomePage.Genres, is HomePage.GenresAt -> stringResource(R.string.home_genres)
    }

/**
 * *Sources*' page: [SourcesBody], the component the box unfolds, with a fold and chips of its own,
 * at its first screenful and every source's lines shown; with [cite], once, unfolded as far as
 * that line, scrolled to it and flashed, as a citation does on Home.
 */
@Composable
private fun SourcesPage(
    shown: Showing,
    playing: NowPlaying,
    cite: String?,
    openSettings: () -> Unit,
) {
    val focus = rememberLineFocus(shown.track?.evidence)
    val fold = sourcesFold(rememberFoldMemory(), playing, keep = false, start = Fold.Pages(1))
    // Once, not again as the evidence grows or after a rotation.
    var cited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focus) {
        if (cited || cite == null) return@LaunchedEffect
        cited = true
        focus.cite(cite)?.let(fold::reach)
    }
    SourcesBody(shown.sources(), focus, fold, openSettings)
}

/**
 * What Home and its pages read of [state] for the track [playing]: the pipeline's [view], the
 * pipeline's track beside it, the settings, and from them what *Sources* shows.
 */
internal class Showing(
    state: NowPlayingState,
    playing: NowPlaying,
) {
    /** The pipeline's state, as published. */
    val view = state.view
    private val settings = state.settings

    /** Whether the model's own knowledge may be used (#437), as the settings say. */
    val modelAllowed: Boolean = settings?.isEnabled(SourceId.MODEL) == true

    /** The pipeline's track beside [playing], or null until the pipeline reaches it. */
    val track: TrackState? = trackFor(view, playing)

    /** *Show grounding details* (#437, #440). */
    val details: Boolean = settings?.showGroundingDetails == true

    /** What *Sources* shows of [track]. */
    fun sources() =
        SourcesShown(
            track = track,
            chips = track?.let { sourceChips(it, view.needsKey, modelAllowed) }.orEmpty(),
            cardKept = view.cardShown && track != null,
            offline = offlineShown(view, track),
            details = details,
        )
}
