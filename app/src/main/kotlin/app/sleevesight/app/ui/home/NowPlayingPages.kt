package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.report.MailHandoff
import app.sleevesight.app.report.PhoneMail
import app.sleevesight.app.ui.report.InsightReportDialog
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState

// Home's pages (#447), and what Home and its pages read alike of the state.

/**
 * A page of Home's, [page], under *Layout*'s *Separate pages*: the item's component on a page of
 * its own ([PageFrame]), in the album art's colours, from the same [state] as Home, for the track
 * playing, with Home's [actions]; [onBack] goes back. Each page opens fresh: *Sources* at its
 * first screenful with every source's lines shown, whatever *Keep Sources as you left them* says.
 * A citation on the insight's page opens *Sources*' page at its line.
 */
@Composable
internal fun NowPlayingPageContent(
    page: HomePage,
    state: NowPlayingState,
    actions: NowPlayingActions,
    onBack: () -> Unit,
) {
    val report = rememberReport(actions.mail)
    val playing = state.playing
    val shown = playing?.let { Showing(state, it) }
    ArtTheme(state.art) {
        PageFrame(pageTitle(page, shown?.track), onBack) {
            if (playing == null || shown == null) {
                NothingPlaying()
                return@PageFrame
            }
            when (page) {
                is HomePage.Sources -> SourcesPage(shown, playing, page.cite, actions.openSettings)
                HomePage.Genres -> GenresPage(shown.track, state.settings, actions.openPage)
                is HomePage.Genre -> GenrePage(shown.track, page.key)
                HomePage.Insight -> {
                    val cite = { id: String -> actions.openPage(HomePage.Sources(cite = id)) }
                    InsightBody(
                        shown.insight,
                        shown.insightContext(),
                        shown.insightActions(actions, cite, report),
                        shown.details,
                    )
                }
            }
        }
    }
}

/** A page's title: the item's name, or for a genre label's, the label as it shows on its chip. */
@Composable
private fun pageTitle(
    page: HomePage,
    track: TrackState?,
): String =
    when (page) {
        is HomePage.Sources -> stringResource(R.string.home_sources_title)
        HomePage.Genres -> stringResource(R.string.home_genres)
        is HomePage.Genre ->
            track
                ?.let { labelOf(it.genres, page.key) }
                ?.label
                ?.genre
                ?.label
                ?: stringResource(R.string.home_genre_title)
        HomePage.Insight -> stringResource(R.string.home_insight_title)
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
 * What Home and its pages read of [state] for the track [playing]: the pipeline's track beside it,
 * the settings, and from them what *Sources* and the insight show.
 */
internal class Showing(
    state: NowPlayingState,
    private val playing: NowPlaying,
) {
    private val view = state.view
    private val settings = state.settings
    private val modelAllowed = settings?.isEnabled(SourceId.MODEL) == true

    /** The pipeline's track beside [playing], or null until the pipeline reaches it. */
    val track: TrackState? = trackFor(view, playing)

    /** *Show grounding details* (#437, #440). */
    val details: Boolean = settings?.showGroundingDetails == true

    /** The insight, or Idle for a track the pipeline has not reached yet. */
    val insight: InsightState get() = track?.insight ?: NOT_YET

    /** What *Sources* shows of [track]. */
    fun sources() =
        SourcesShown(
            track = track,
            chips = track?.let { sourceChips(it, view.needsKey, modelAllowed) }.orEmpty(),
            cardKept = view.cardShown && track != null,
            offline = offlineShown(view, track),
            details = details,
        )

    /** What the insight needs besides the insight, from the pipeline's view of [track] and the settings. */
    fun insightContext() =
        InsightContext(
            track = track,
            aiReady = view.aiReady,
            insightsOn = settings?.writeInsights ?: true,
            modelAllowed = modelAllowed,
            offers =
                InsightOffers(
                    rewrite = view.rewrite && track != null,
                    retry = view.retry && track != null,
                    stop = view.stopCall != null && track != null,
                    paused = view.lapsed && track != null,
                ),
            rewriteFailed = view.rewriteFailed.takeIf { track != null },
        )

    /**
     * Where the insight's taps go: a tap carries what it was shown for, the run Write is offered
     * for, beside this track, and the call Stop is offered for; a citation to [cite], a report to
     * [report].
     */
    fun insightActions(
        actions: NowPlayingActions,
        cite: (String) -> Unit,
        report: (TrackState, InsightState.Written) -> Unit,
    ) = InsightActions(
        onWrite = { view.writeKey?.let { actions.write(it, playing) } },
        onStop = { view.stopCall?.let(actions.stop) },
        onCite = cite,
        onReport = report,
    )
}

/**
 * The way to *Report a problem* (#390): the report's dialog, while one is open, handed to [mail],
 * or to the phone's own mail apps when null; returns what opens it, of an insight and the track it
 * was written for, whatever plays meanwhile.
 */
@Composable
internal fun rememberReport(mail: MailHandoff?): (TrackState, InsightState.Written) -> Unit {
    var reporting by remember { mutableStateOf<Reported?>(null) }
    reporting?.let { reported ->
        val context = LocalContext.current
        val handoff = mail ?: remember(context) { PhoneMail(context) }
        InsightReportDialog(reported.track, reported.insight, handoff) { reporting = null }
    }
    return { track, written -> reporting = Reported(track, written) }
}

// What a report is of: the insight, and the track it was written for.
private class Reported(
    val track: TrackState,
    val insight: InsightState.Written,
)

// A track the pipeline has not reached yet.
private val NOT_YET = InsightState.Idle(canWrite = false)
