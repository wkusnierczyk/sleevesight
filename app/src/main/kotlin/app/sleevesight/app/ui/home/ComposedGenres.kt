package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.VocalsShown
import app.sleevesight.app.pipeline.withAi
import app.sleevesight.core.genres.instrumental
import app.sleevesight.core.genres.withVocals
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.VocalsState
import kotlinx.coroutines.flow.flowOf

// The genres *Genres* shows, composed from what the run gathered and what the panel adds (#772, #998).

/**
 * The genres *Genres* shows for [playing] (#772), as [GenresContent] composes them: the genres the
 * artists' descriptions name (#1000), the AI's genre list (#762), the panel's LRCLIB answer (#997),
 * the check of the track's vocals (#998) and the lyrics *Find the lyrics* found (#996), in that order;
 * with the panel's LRCLIB *Retry* ([libraryRetry]) and the check as known ([vocals]).
 */
internal class ComposedGenres(
    val track: TrackState?,
    val libraryRetry: (() -> Unit)? = null,
    val vocals: VocalsShown? = null,
)

/** [playing]'s genres as *Genres* shows them ([ComposedGenres]), from [notes] and [search]. */
@Composable
internal fun composedGenres(
    playing: TrackState?,
    notes: PanelNotes,
    search: PanelSearch,
): ComposedGenres {
    val described = withDescribed(playing, notes)
    val joined = described?.withAi(aiGenres(described, notes.notes))
    // Weighed against the run's own genres, which an artist's description never joins, so the panel
    // asks LRCLIB for an Instrumental only that description names (#998).
    val weighed = withLibrary(playing, joined, notes.notes)
    val vocals = vocalsOf(weighed.track, notes.notes)
    val checked = weighed.track?.let { withVocals(it, vocals, notes.notes) }
    val track = withLyricsFound(checked, search)
    return ComposedGenres(track, weighed.retry, vocals)
}

/**
 * The check of [track]'s vocals as [notes] give it (#998, [GenreNotes.vocals]): null while none is
 * known. A new subscription only when what its key is made of changes (the track, its length and
 * album, its play, its evidence in), not on every state of the track.
 */
@Composable
internal fun vocalsOf(
    track: TrackState?,
    notes: GenreNotes,
): VocalsShown? =
    remember(
        notes,
        track?.normalized,
        track?.nowPlaying?.duration,
        track?.nowPlaying?.album,
        track?.nowPlaying?.play,
        track?.gathered,
        track?.evidence != null,
    ) { track?.let(notes::vocals) ?: flowOf(null) }.collectAsState(null).value

/**
 * [track] with the check of its vocals answered as [shown] says (#998, `TrackGenres.withVocals`): an
 * Instrumental the check found instrumental lifted, or added, marked AI, where no row had it (the
 * work's composers alone), the vocabulary's label of it from [notes]; one it found vocals on dropped,
 * unless a tap asked it on this play ([VocalsShown.tapped]: the chip stays weak, with the answer's
 * line); else [track].
 */
internal fun withVocals(
    track: TrackState,
    shown: VocalsShown?,
    notes: GenreNotes,
): TrackState {
    val checked = shown?.state as? VocalsState.Checked ?: return track
    val instrumental = notes.vocabulary?.instrumental()
    val genres = track.genres.withVocals(checked.answer, shown.model, instrumental, drops = !shown.tapped)
    return if (genres === track.genres) track else track.copy(genres = genres)
}
