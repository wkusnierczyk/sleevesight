package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * *Key insights* as Home shows it for [track] (#1131): its insight, but while the song's, the artist's
 * or the album's insight is being written ([ItemNotes.writes]), a track whose own sources gave nothing
 * to narrate waits, rather than say so, since the lines that write may find decide it again; once
 * none is, it says so. Null for a track the pipeline has not reached, or one waiting so.
 */
@Composable
internal fun keyInsight(
    notes: ItemNotes,
    track: TrackState?,
): InsightState? {
    val items by remember(notes, track?.normalized, track?.resolution, track?.sources, track?.nowPlaying?.album) {
        track?.let { shown ->
            combine(InsightItem.entries.map { notes.writes(shown, it) }) { writes -> writes.any { it } }
        } ?: flowOf(false)
    }.collectAsState(false)
    val insight = track?.insight
    return insight.takeUnless { it == InsightState.NoEvidence && items }
}
