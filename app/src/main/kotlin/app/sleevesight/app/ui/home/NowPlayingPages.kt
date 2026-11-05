package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.NewTrackFold

// Home's pages (#447), and what Home and its pages read alike of the state.

/**
 * A page of Home's, [page], under *Layout*'s *Separate pages*: the item's component on a page of
 * its own ([PageFrame]), in the album art's colours, from the same [state] as Home, for the track
 * playing, with Home's [actions]; [onBack] goes back. Each page opens fresh: *Sources* at its
 * first screenful with every source's lines shown, whatever *Sources on a new track* says.
 * With nothing playing, a line says so ([PageNothingPlaying]).
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
    // The back arrow, where a control on the page that goes once tapped hands the focus (#772).
    val handoff = rememberFocusHandoff()
    val back = remember { FocusRequester() }
    ArtTheme(state.art) {
        PageFrame(
            pageTitle(page),
            onBack,
            containerColor = ground,
            backModifier = Modifier.handoffTarget(handoff, back),
        ) {
            if (playing == null || shown == null) {
                PageNothingPlaying()
                return@PageFrame
            }
            when (page) {
                is HomePage.Sources -> SourcesPage(shown, playing, page.cite, actions)
                HomePage.Genres, is HomePage.GenresAt -> {
                    val retries = Retries(shown.view, playing, actions.askAgain)
                    GenresPage(
                        shown.track,
                        state.settings,
                        genreNotes(shown, actions),
                        GenresWays(retries::of, actions.openSettingsAt) { handoff.moveTo(back) },
                        // *Search with AI* under no genre (#773), as in the panel on Home.
                        PanelSearch(actions.aiSearches, shown.track, shown.track?.nowPlaying ?: playing),
                    )
                }

                is HomePage.Item -> ItemPageOf(page, state, shown, playing, actions)
            }
        }
    }
}

// What the genres' page shows above its table (#523): a citation in a reason opens *Sources*' page at its line.
private fun genreNotes(
    shown: Showing,
    actions: NowPlayingActions,
) = PanelNotes(
    actions.genreNotes,
    { id -> actions.openPage(HomePage.Sources(cite = id)) },
    shown.modelAllowed,
    actions.readAloud,
    actions.openSettingsAt,
)

/**
 * A page once nothing plays (#627): a line that says so, and no more. Not the idle Home
 * ([IdleHome]): the page is titled for an item of a track, and the players and the guide are
 * Home's, a back arrow away, where the players' state is kept.
 */
@Composable
private fun PageNothingPlaying() {
    Text(
        stringResource(R.string.page_nothing_playing),
        Modifier.testTag(PAGE_NOTHING_PLAYING_TAG),
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** The test tag of the line a page shows once nothing plays. */
const val PAGE_NOTHING_PLAYING_TAG: String = "page-nothing-playing"

/** A page's title: the item's name. */
@Composable
private fun pageTitle(page: HomePage): String =
    when (page) {
        is HomePage.Sources -> stringResource(R.string.home_sources_title)
        HomePage.Genres, is HomePage.GenresAt -> stringResource(R.string.home_genres)
        is HomePage.Item -> itemKind(itemOf(page))
    }

// The item [page] shows; the song's for a name no item has (a back stack another version saved).
private fun itemOf(page: HomePage.Item): InsightItem =
    InsightItem.entries.firstOrNull { it.name == page.item } ?: InsightItem.SONG

/**
 * The song's, the artist's or the album's page (#522): its name, then its insight, for the track
 * playing; a citation opens *Sources*' page at its line; *Report a problem* opens its report here.
 * Nothing until the settings are read.
 */
@Composable
private fun ItemPageOf(
    page: HomePage.Item,
    state: NowPlayingState,
    shown: Showing,
    playing: NowPlaying,
    actions: NowPlayingActions,
) {
    val settings = state.settings ?: return
    var reporting by remember { mutableStateOf<Reported?>(null) }
    reporting?.let { ReportOpen(it, actions.mail) { reporting = null } }
    val item = itemOf(page)
    val header = shown.track?.nowPlaying ?: playing
    val cite: (String) -> Unit = { id -> actions.openPage(HomePage.Sources(cite = id)) }
    val context =
        itemContext(
            state,
            shown,
            settings,
            actions,
            cite,
            sourcesLines(actions.itemNotes, shown.track).evidence,
        ) { track, written, of ->
            reporting =
                Reported(track, written, of)
        }
    // The track the page was opened on: its opening is a tap for that track only (#522), kept through
    // a rotation and a page opened on top, and forgotten once another plays, so it never comes back.
    val openedOn = rememberSaveable(saver = OPENED_ON) { mutableStateOf<NowPlaying?>(playing) }
    val tapped = openedOn.value?.let { TrackIdentity.playsOn(it, playing) } == true
    SideEffect { if (!tapped) openedOn.value = null }
    // Its opening's write (#948), asked for once, on that track only: not again after a rotation.
    var due by rememberSaveable { mutableStateOf(true) }
    val opening = if (tapped && due) OpeningWrite(due = true) { due = false } else OpeningWrite.NONE
    ItemPage(item, itemName(item, header, actions.normalize(header).primary), context, tapped, opening)
}

// The track an item page was opened on, saved as plain values ([savedTrack]).
private val OPENED_ON: Saver<MutableState<NowPlaying?>, Any> =
    listSaver(save = { savedTrack(it.value) }, restore = { mutableStateOf(restoredTrack(it)) })

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
    actions: NowPlayingActions,
) {
    // Its own evidence, with its album's lines once its insight gathered them (#522).
    val lines = sourcesLines(actions.itemNotes, shown.track)
    val focus = rememberLineFocus(lines.evidence, shown.track?.normalized)
    val fold = sourcesFold(rememberFoldMemory(), playing, onNewTrack = NewTrackFold.FOLDED, start = Fold.Pages(1))
    // The track the citation was opened on: it is that track's line, never another's of the same id.
    val citedOn = rememberSaveable(saver = OPENED_ON) { mutableStateOf<NowPlaying?>(playing) }
    val current = citedOn.value?.let { TrackIdentity.playsOn(it, playing) } == true
    SideEffect { if (!current) citedOn.value = null }
    CitedLine(cite?.takeIf { current }, lines.evidence, focus, fold)
    SourcesBody(shown.sources(lines), focus, fold, actions.openSettings)
}

/**
 * The line [cite] on *Sources*' page, flashed once its line is there (not again after a rotation),
 * and followed while its place changes under it as the lines change (an album's lines, #522, come
 * once they are read, after the track's, and may be the line cited, or move it further down): each
 * change of its place unfolds to it again, not as the listener's action. Following ends once the
 * listener has acted on the list: the fold changed, or its source hidden with its chip.
 */
@Composable
private fun CitedLine(
    cite: String?,
    evidence: EvidenceSet?,
    focus: LineFocus,
    fold: SourcesFold,
) {
    var flashed by rememberSaveable { mutableStateOf(false) }
    var released by rememberSaveable { mutableStateOf(false) }
    // Where the line was last followed to, and the fold then (as text, to be saved): kept through a
    // rotation, so one does not follow it again.
    var followedAt by rememberSaveable { mutableStateOf<Int?>(null) }
    var followedFold by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(focus, cite) {
        val line = cite?.let { evidence?.get(it) }
        val moved = followedFold != null && fold.fold.toString() != followedFold
        if (line != null && (line.source in focus.hidden || moved)) released = true
        val at = line?.let { focus.position(it.id) }
        when {
            cite == null || line == null || released -> Unit
            !flashed -> {
                flashed = true
                focus.cite(cite)?.let { fold.reach(it) }
                followedAt = focus.position(cite)
                followedFold = fold.fold.toString()
            }
            at != null && at != followedAt -> {
                focus.follow(cite)
                fold.follow(at)
                followedAt = at
                followedFold = fold.fold.toString()
            }
        }
    }
}

/**
 * What Home and its pages read of [state] for the track [playing]: the pipeline's [view], the
 * pipeline's track beside it, the settings, and from them what *Sources* shows.
 */
internal class Showing(
    state: NowPlayingState,
    val playing: NowPlaying,
) {
    /** The pipeline's state, as published. */
    val view = state.view
    private val settings = state.settings

    /** Whether the model's own knowledge may be used (#437), as the settings say. */
    val modelAllowed: Boolean = settings?.isEnabled(SourceId.MODEL) == true

    /** The pipeline's track beside [playing], or null until the pipeline reaches it. */
    val track: TrackState? = trackFor(view, playing)

    /**
     * The sources looked up only with the user's key that are switched off (#667), which have no
     * adapter, and so no state in the track, without their key: *Sources* says they are off.
     */
    private val keyedOff: Set<SourceId> =
        settings?.let { now -> PipelineHost.KEYED_SOURCES.filterNot { now.isEnabled(it) }.toSet() }.orEmpty()

    /** *Show grounding details* (#437, #440). */
    val details: Boolean = settings?.showGroundingDetails == true

    /** *Lyrics links* (#728), on until the settings are read, as by default. */
    val lyricsLinks: Boolean = settings?.lyricsLinks ?: true

    /** *Search Genius when there is no link* (#728), on until the settings are read, as by default. */
    val lyricsSearch: Boolean = settings?.lyricsSearch ?: true

    /** What *Sources* shows of [track], its lines [lines] (its evidence, with its album's, #522). */
    fun sources(lines: SourcesLines = SourcesLines(track?.evidence)) =
        SourcesShown(
            track = track,
            chips = track?.let { sourceChips(it, view.needsKey, modelAllowed, keyedOff) }.orEmpty(),
            cardKept = view.cardShown && track != null,
            offline = offlineShown(view, track),
            details = details,
            lines = lines,
        )
}
