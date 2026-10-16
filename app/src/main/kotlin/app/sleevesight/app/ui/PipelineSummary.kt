package app.sleevesight.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState

/**
 * The pipeline's state for the current track, in plain text: the track, each source's state (a
 * keyed source without its key says so, rather than failing), and the insight, with *Write* when a
 * tap would write it. A stand-in until the home screen (#52) renders the state properly; model
 * prose is shown as plain text only.
 */
@Composable
fun PipelineSummary(
    view: PipelineView,
    onWrite: () -> Unit,
) {
    val track = view.track
    if (track == null) {
        Text(stringResource(R.string.home_placeholder), style = MaterialTheme.typography.bodyLarge)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.home_track, track.nowPlaying.title, track.nowPlaying.artists),
            style = MaterialTheme.typography.titleMedium,
        )
        for ((source, state) in track.sources) {
            Text(stringResource(R.string.home_source_state, sourceName(source), sourceState(state)))
        }
        for (source in view.needsKey) {
            Text(
                stringResource(
                    R.string.home_source_state,
                    sourceName(source),
                    stringResource(R.string.home_source_needs_key),
                ),
            )
        }
        Insight(track.insight, view.aiReady, onWrite)
    }
}

@Composable
private fun Insight(
    insight: InsightState,
    aiReady: Boolean,
    onWrite: () -> Unit,
) {
    when (insight) {
        is InsightState.Idle -> {
            if (insight.canWrite) {
                Button(onClick = onWrite, modifier = Modifier.testTag(WRITE_TAG)) {
                    Text(stringResource(R.string.home_write))
                }
            } else if (!aiReady) {
                Text(stringResource(R.string.home_insight_unavailable))
            }
        }

        is InsightState.Writing -> Text(insight.partial.ifEmpty { stringResource(R.string.home_insight_writing) })
        is InsightState.Written -> Text(insight.grounded.text, style = MaterialTheme.typography.bodyLarge)
        InsightState.NoEvidence -> Text(stringResource(R.string.home_insight_no_evidence))
        is InsightState.Failed -> Text(stringResource(R.string.home_insight_failed, insight.reason))
    }
}

@Composable
private fun sourceName(source: SourceId): String =
    when (source) {
        SourceId.MUSICBRAINZ -> stringResource(R.string.settings_source_musicbrainz)
        SourceId.WIKIPEDIA -> stringResource(R.string.settings_source_wikipedia)
        SourceId.LASTFM -> stringResource(R.string.settings_source_lastfm)
        SourceId.WEB -> stringResource(R.string.settings_source_web)
        else -> source.name
    }

@Composable
private fun sourceState(state: SourceState): String =
    when (state) {
        SourceState.Pending -> stringResource(R.string.home_source_pending)
        is SourceState.Failed -> stringResource(R.string.home_source_failed)
        is SourceState.Done -> {
            val outcome = outcomeLabel(state.result.outcome)
            if (state.cached) stringResource(R.string.home_source_cached, outcome) else outcome
        }
    }

@Composable
private fun outcomeLabel(outcome: Outcome): String =
    stringResource(
        when (outcome) {
            Outcome.SongArticle -> R.string.home_source_found_song
            Outcome.ArtistArticle -> R.string.home_source_found_artist
            Outcome.OriginalSong -> R.string.home_source_found_original
            is Outcome.Rejected -> R.string.home_source_rejected
            Outcome.None -> R.string.home_source_none
            Outcome.SourceOff -> R.string.home_source_off
            Outcome.Unreachable -> R.string.home_source_unreachable
        },
    )

/** The *Write* button's test tag. */
const val WRITE_TAG: String = "write"
