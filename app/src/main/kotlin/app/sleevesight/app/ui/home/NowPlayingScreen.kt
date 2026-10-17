package app.sleevesight.app.ui.home

import android.app.PendingIntent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the home screen reads, and where its actions go: the pipeline's view, the session layer's
 * track, playback and art, and the settings, all as published; a tap on Write goes to the pipeline
 * ([write]) with the key it was offered under and the track it was shown beside, and the pipeline
 * decides whether it writes; a tap on Stop goes to it ([stop]) with the key Stop was offered under.
 *
 * @property playerLabel a player's name, from its package.
 * @property playerActivity the shown player's own screen, read when the player row is tapped.
 */
@Suppress("LongParameterList") // What one screen reads, each published on its own.
class HomeFeed(
    val view: StateFlow<PipelineView>,
    val nowPlaying: StateFlow<NowPlaying?>,
    val playback: StateFlow<PlaybackInfo>,
    val art: StateFlow<Bitmap?>,
    val settings: Flow<Settings>,
    val normalizer: Normalizer,
    val playerLabel: (String) -> String,
    val playerActivity: () -> PendingIntent?,
    val write: (RunKey, NowPlaying) -> Unit,
    val stop: (RunKey) -> Unit,
)

/** The now-playing part of the home screen, from [feed]. */
@Composable
fun NowPlayingHome(feed: HomeFeed) {
    val view by feed.view.collectAsState()
    val playing by feed.nowPlaying.collectAsState()
    val playback by feed.playback.collectAsState()
    val art by feed.art.collectAsState()
    val settings by feed.settings.collectAsState(initial = null)
    NowPlayingContent(
        NowPlayingState(view, playing, playback, art, settings),
        NowPlayingActions(
            normalize = feed.normalizer::normalize,
            playerLabel = feed.playerLabel,
            playerActivity = feed.playerActivity,
            write = feed.write,
            stop = feed.stop,
        ),
    )
}

/** What the screen shows, as published: [settings] is null until the first read. */
internal class NowPlayingState(
    val view: PipelineView,
    val playing: NowPlaying?,
    val playback: PlaybackInfo = PlaybackInfo.NONE,
    val art: Bitmap? = null,
    val settings: Settings? = null,
)

/** How the screen names what it shows, and where its taps go. */
internal class NowPlayingActions(
    val normalize: (NowPlaying) -> NormalizedTrack,
    val playerLabel: (String) -> String = { it },
    val playerActivity: () -> PendingIntent? = { null },
    val write: (RunKey, NowPlaying) -> Unit = { _, _ -> },
    val stop: (RunKey) -> Unit = {},
)

/**
 * The home screen's content, top to bottom (design, *Home screen*): the player row, the art with
 * the playback position, the key data, the genre labels, *Why it's interesting* and *Sources*,
 * in the art's colours. Nothing playing in a followed player shows the idle line. A track the
 * pipeline has not reached yet shows at once, its evidence and insight to come.
 *
 * *Sounds like* waits for its card (Phase 2: `TrackState` has no similar artists yet), and so do
 * the detail cards, the transport controls and the handoff to the player (design, *Phase 2*).
 */
@Composable
internal fun NowPlayingContent(
    state: NowPlayingState,
    actions: NowPlayingActions,
) {
    val playing = state.playing
    if (playing == null) {
        NothingPlaying()
        return
    }
    val view = state.view
    val track = trackFor(view, playing)
    // The header from the pipeline's track (its latest report of the track) once it has one.
    val header = track?.nowPlaying ?: playing
    val normalized = remember(header) { actions.normalize(header) }
    val settings = state.settings
    val modelAllowed = settings?.isEnabled(SourceId.MODEL) == true
    val focus = rememberLineFocus(track?.evidence)
    val insight =
        InsightContext(
            track = track,
            aiReady = view.aiReady,
            insightsOn = settings?.writeInsights ?: true,
            modelAllowed = modelAllowed,
            offers =
                InsightOffers(
                    rewrite = view.rewrite && track != null,
                    retry = view.retry && track != null,
                    stop = view.stopKey != null && track != null,
                ),
            rewriteFailed = view.rewriteFailed.takeIf { track != null },
        )

    ArtTheme(state.art) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PlayerRow(playing.player, state.playback, actions)
                ArtAndProgress(state.art, state.playback, header.duration)
                KeyData(normalized, header, releaseYear(track))
                Genres(track, settings)
                // A tap carries what it was shown for: the run Write is offered for, beside this track.
                val onWrite: () -> Unit = { view.writeKey?.let { actions.write(it, playing) } }
                val onStop: () -> Unit = { view.stopKey?.let(actions.stop) }
                InsightCard(track?.insight ?: NOT_YET, insight, InsightActions(onWrite, onStop, focus::cite))
                SourcesSection(
                    track = track,
                    chips = track?.let { sourceChips(it, view.needsKey, modelAllowed) }.orEmpty(),
                    focus = focus,
                    cardKept = insight.offers.rewrite || insight.rewriteFailed != null,
                )
            }
        }
    }
}

/** Nothing playing in a player followed. */
@Composable
fun NothingPlaying() {
    Text(stringResource(R.string.home_placeholder), style = MaterialTheme.typography.bodyLarge)
}

// A track the pipeline has not reached yet.
private val NOT_YET = InsightState.Idle(canWrite = false)
