package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind

// The genre sources that are off, in *Genres*' panel or page (#766), and *Ask AI*'s genres, which
// hold them back: nothing is added while genres show.

// Whether *Ask AI*'s genres for [search]'s track show in the panel ([GenresSearch], #773): its answer
// names a genre. Read only (`AiSearchFeed.answered`), so a kept answer is not told to the log as served.
@Composable
internal fun aiGenresShown(search: PanelSearch): Boolean {
    val track = search.track ?: return false
    val state by remember(search.feed, track.normalized, track.nowPlaying.duration, track.nowPlaying.album) {
        search.feed.answered(track, SearchKind.GENRES)
    }.collectAsState(AiSearch.None)
    return ((state as? AiSearch.Answered)?.answer as? SearchAnswer.Genres)?.genres?.isNotEmpty() == true
}

/**
 * [off]'s lines in *Genres*' panel or page (#766, [genresOffParts]), in [style]; none while *Ask AI*'s
 * genres show ([aiShown]): nothing is added while genres show.
 */
@Composable
internal fun GenresOffLines(
    off: GenresOff,
    aiShown: Boolean,
    openSettingsAt: (SettingsTarget) -> Unit,
    style: TextStyle,
) {
    if (!aiShown) OffLines(genresOffParts(off), openSettingsAt, style)
}
