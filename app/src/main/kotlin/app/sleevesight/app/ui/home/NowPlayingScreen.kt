package app.sleevesight.app.ui.home

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sleevesight.app.pipeline.CallId
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.report.MailHandoff
import app.sleevesight.app.report.PhoneMail
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.settings.Dismissals
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.speech.Reading
import app.sleevesight.app.ui.report.InsightReportDialog
import app.sleevesight.app.ui.settings.InstalledPlayers
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the home screen reads, and where its actions go: the pipeline's view, the session layer's
 * track, playback and art, and the settings, all as published; a tap on Write goes to the pipeline
 * ([write]) with the key it was offered under and the track it was shown beside, and the pipeline
 * decides whether it writes; a tap on Stop goes to it ([stop]) with the call Stop was offered for.
 *
 * @property playerLabel a player's name, from its package.
 * @property playerActivity the shown player's own screen, read when the player row is tapped.
 * @property playerLaunch the intent that opens a player from its launcher entry, by its package:
 *   the row's way in when the session gives no usable screen (#596).
 * @property unavailableModel the switched-on AI service's model, while it is found unavailable
 *   (#540): no insight is written with it, and the card says so.
 * @property chooseWritingMode saves a mode chosen in the insight card's menu of *Reactive*'s modes
 *   (#615), as *Reactive*, and tells the pipeline, where an automatic mode is the first action (#625);
 *   its result says whether it was saved.
 * @property installedPlayers the players' icons, whether the phone shows them, and the players seen,
 *   for the players listed while nothing plays (#627); null lists every stored player, without an
 *   icon.
 * @property guideHidden whether the guide to Home is hidden for now (#627, #759: *Hide once*), which
 *   [hideGuideOnce] does, until Home is left ([homeLeft]: for another screen, or the app going to
 *   the background) or a track plays.
 * @property dismissals the guide's *Dismiss*, for good, and the *Undo* in the message after it
 *   (#759).
 * @property genreNotes what a genre's panel shows above its table (#523): what the genre is, and
 *   *Why is this …?*.
 * @property lookAgain a tap on *Look again* (#617), with the key it was offered under and the track
 *   it was shown beside, as [write] takes a tap on Write.
 * @property readAloud *Read aloud* (#325), beside *Key insights* (#725) and under a genre's reason; null shows
 *   no button.
 * @property askAgain a tap on *Retry* beside a source that could not be reached (#724), with the key
 *   it was offered under, the source, and the track it was shown beside: that source alone is asked
 *   again (`PipelineHost.askAgain`), free.
 */
@Suppress("LongParameterList") // What one screen reads, each published on its own.
class HomeFeed(
    val view: StateFlow<PipelineView>,
    val nowPlaying: StateFlow<NowPlaying?>,
    val playback: StateFlow<PlaybackInfo>,
    val art: StateFlow<Bitmap?>,
    val settings: StateFlow<Settings?>,
    val normalizer: Normalizer,
    val playerLabel: (String) -> String,
    val playerActivity: () -> PendingIntent?,
    val write: (RunKey, NowPlaying) -> Unit,
    val stop: (CallId) -> Unit,
    val playerLaunch: (String) -> Intent? = { null },
    val unavailableModel: StateFlow<UnavailableModel?> = MutableStateFlow(null),
    val chooseWritingMode: (WriteAutomatically) -> Deferred<Boolean> = { CompletableDeferred(true) },
    val installedPlayers: InstalledPlayers? = null,
    val guideHidden: StateFlow<Boolean> = MutableStateFlow(false),
    val hideGuideOnce: () -> Unit = {},
    val homeLeft: () -> Unit = {},
    val dismissals: Dismissals = Dismissals.NONE,
    val genreNotes: GenreNotes = GenreNotes.None,
    val lookAgain: (RunKey, NowPlaying) -> Unit = { _, _ -> },
    val readAloud: ReadAloud? = null,
    val askAgain: (RunKey, SourceId, NowPlaying) -> Unit = { _, _, _ -> },
)

/**
 * The now-playing part of the home screen, from [feed]; a link to the settings goes to
 * [openSettings], or, to a setting, to [openSettingsAt] (#581), and an item opened on a page of its
 * own (*Layout*'s *Separate pages*, #447) to [openPage]. Home left ([OnHomeLeft]) is told to
 * [HomeFeed.homeLeft] (#759).
 */
@Composable
fun NowPlayingHome(
    feed: HomeFeed,
    openSettings: () -> Unit = {},
    openPage: (HomePage) -> Unit = {},
    openSettingsAt: (SettingsTarget) -> Unit = { openSettings() },
) {
    OnHomeLeft(feed.homeLeft)
    NowPlayingContent(nowPlayingState(feed), feed.actions(openSettings, openPage, openSettingsAt))
}

/**
 * A page of Home's ([page], #447), from the same [feed] as Home, for the track playing; [onBack]
 * goes back, and the page's links go where Home's do.
 */
@Composable
fun NowPlayingPage(
    feed: HomeFeed,
    page: HomePage,
    openSettings: () -> Unit = {},
    openPage: (HomePage) -> Unit = {},
    onBack: () -> Unit = {},
) {
    NowPlayingPageContent(page, nowPlayingState(feed), feed.actions(openSettings, openPage), onBack)
}

// What [feed] publishes, as it changes. The settings are held for the process, so a screen coming
// back (Home, from a page) has them from its first frame, and is laid out as it was left. On a cold
// start they are null until their first read, and Home draws neither *Sources* nor the genres until
// then, as the layout is not known yet.
@Composable
private fun nowPlayingState(feed: HomeFeed): NowPlayingState {
    val view by feed.view.collectAsState()
    val playing by feed.nowPlaying.collectAsState()
    val playback by feed.playback.collectAsState()
    val art by feed.art.collectAsState()
    val settings by feed.settings.collectAsState()
    val unavailable by feed.unavailableModel.collectAsState()
    val guideHidden by feed.guideHidden.collectAsState()
    return NowPlayingState(view, playing, playback, art, settings, unavailable, guideHidden)
}

private fun HomeFeed.actions(
    openSettings: () -> Unit,
    openPage: (HomePage) -> Unit,
    // Home's pages show no note that links to a setting: only Home's insight card does (#581).
    openSettingsAt: (SettingsTarget) -> Unit = { openSettings() },
) = NowPlayingActions(
    normalize = normalizer::normalize,
    playerLabel = playerLabel,
    playerActivity = playerActivity,
    playerLaunch = playerLaunch,
    write = write,
    stop = stop,
    lookAgain = lookAgain,
    chooseWritingMode = chooseWritingMode,
    installedPlayers = installedPlayers,
    hideGuideOnce = hideGuideOnce,
    dismissals = dismissals,
    genreNotes = genreNotes,
    openSettings = openSettings,
    openPage = openPage,
    openSettingsAt = openSettingsAt,
    readAloud = readAloud,
    askAgain = askAgain,
)

/**
 * What the screen shows, as published: [settings] is null until the first read; [unavailable] is
 * the switched-on AI service's model while it is found unavailable (#540); [guideHidden], whether
 * the guide to Home shown while nothing plays is hidden for now (#627, #759: *Hide once*).
 */
@Suppress("LongParameterList") // What the screen shows, each published on its own.
internal class NowPlayingState(
    val view: PipelineView,
    val playing: NowPlaying?,
    val playback: PlaybackInfo = PlaybackInfo.NONE,
    val art: Bitmap? = null,
    val settings: Settings? = null,
    val unavailable: UnavailableModel? = null,
    val guideHidden: Boolean = false,
)

/**
 * How the screen names what it shows, and where its taps go. The player row opens [playerActivity],
 * else [playerLaunch] for the player's package (#596). [mail] takes a report of a problem
 * with the insight (#390); null is the phone's own mail apps ([PhoneMail]). [openSettings] opens
 * the settings, from *Sources*' note of a source that needs its key (#440), and [openSettingsAt]
 * the settings at a setting, from the note of a model found unavailable (#581). [openPage] opens
 * an item on a page of its own, under *Layout*'s *Separate pages* (#447). [chooseWritingMode] saves
 * a mode chosen in the insight card's menu of *Reactive*'s modes (#615). [genreNotes] gives what a
 * genre's panel shows above its table (#523); [lookAgain] takes a tap on *Look again* (#617), as
 * [write] takes one on Write. While nothing plays (#627), the players are listed with
 * [installedPlayers]' icons, and the guide's buttons go to [hideGuideOnce] (*Hide once*) or
 * to [dismissals] (*Dismiss*, and its *Undo*; #759). [readAloud] reads the insight, or a genre's
 * reason, aloud (#325); null shows no *Read aloud*. [askAgain] takes a tap on *Retry* beside a
 * source that could not be reached (#724), as [write] takes one on Write.
 */
@Suppress("LongParameterList") // Each of the screen's ways out, on its own.
internal class NowPlayingActions(
    val normalize: (NowPlaying) -> NormalizedTrack,
    val playerLabel: (String) -> String = { it },
    val playerActivity: () -> PendingIntent? = { null },
    val playerLaunch: (String) -> Intent? = { null },
    val write: (RunKey, NowPlaying) -> Unit = { _, _ -> },
    val stop: (CallId) -> Unit = {},
    val mail: MailHandoff? = null,
    val openSettings: () -> Unit = {},
    val openPage: (HomePage) -> Unit = {},
    val openSettingsAt: (SettingsTarget) -> Unit = { openSettings() },
    val chooseWritingMode: (WriteAutomatically) -> Deferred<Boolean> = { CompletableDeferred(true) },
    val installedPlayers: InstalledPlayers? = null,
    val hideGuideOnce: () -> Unit = {},
    val dismissals: Dismissals = Dismissals.NONE,
    val genreNotes: GenreNotes = GenreNotes.None,
    val lookAgain: (RunKey, NowPlaying) -> Unit = { _, _ -> },
    val readAloud: ReadAloud? = null,
    val askAgain: (RunKey, SourceId, NowPlaying) -> Unit = { _, _, _ -> },
)

/**
 * The home screen's content, top to bottom (design, *Home screen*): the player row, the art with
 * the playback position, the key data, the *Dates* and *Genres* buttons or panels (#724, #772), *Key
 * insights* and *Sources*,
 * straight on Home's background, their accents in the art's colours (#609). Nothing playing in a
 * followed player shows the players to start and the guide to Home ([IdleHome], #627). A track the
 * pipeline has not reached yet shows at once, its evidence and insight to come.
 *
 * *Sounds like* waits for its card (Phase 2: `TrackState` has no similar artists yet), and so do
 * the song, artist and album insights (#522, Phase 1.5), and the rest of the detail content, the
 * transport controls and the handoff to the player (design, *Phase 2*).
 *
 * *Layout* (#447): under *Expandable boxes*, the default, *Sources*' chip opens its panel in place
 * (#623), and a citation unfolds *Sources* to its line. Under *Separate pages* each opens on a page
 * of its own ([NowPlayingActions.openPage]): *Sources*' chip, and a citation, which opens *Sources*
 * at its line. Under either, the line under the album ([InPlaceLine]: *Dates*, #724, and *Genres*,
 * #772) opens its panels in place, a genre opening in its box inside *Genres*' panel.
 * The insight shows in full on Home either way.
 */
@Composable
internal fun NowPlayingContent(
    state: NowPlayingState,
    actions: NowPlayingActions,
) {
    val playing = state.playing
    // Above the return, so a report being written outlives the player's session ending (#390); of
    // the insight and track it was opened on, whatever plays meanwhile.
    var reporting by remember { mutableStateOf<Reported?>(null) }
    reporting?.let { ReportOpen(it, actions.mail) { reporting = null } }
    // Above the return, so the fold and the panels open under the album outlive a while with nothing playing.
    val foldMemory = rememberFoldMemory()
    val inPlace = rememberInPlaceMemory()
    if (playing == null) {
        IdleHome(state.settings, state.guideHidden, actions)
        return
    }
    val shown = Showing(state, playing)
    val view = shown.view
    val track = shown.track
    // The header from the pipeline's track (its latest report of the track) once it has one.
    val header = track?.nowPlaying ?: playing
    val settings = state.settings
    val pages = settings?.layout == Layout.SEPARATE_PAGES
    val focus = rememberLineFocus(track?.evidence)
    val fold = sourcesFold(foldMemory, playing, keep = settings?.keepSourcesUnfolded)
    // A citation tapped unfolds the list far enough to show its line, or opens it there on its page.
    val cite: (String) -> Unit =
        if (pages) {
            { id -> actions.openPage(HomePage.Sources(cite = id)) }
        } else {
            { id -> focus.cite(id)?.let(fold::reach) }
        }

    // Straight on Home's background, with no panel around it (#609): the art's scheme colours the
    // parts drawn in its roles (the position bar, the chips, the buttons), not a ground of its own.
    ArtTheme(state.art) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerRow(playing.player, state.playback, actions)
            ArtAndProgress(state.art, state.playback, header.duration)
            val notes = PanelNotes(actions.genreNotes, cite, shown.modelAllowed, actions.readAloud)
            TrackHeader(shown, header, UnderAlbumLine(inPlace, settings, notes), actions)
            // A tap carries what it was shown for: the run Write is offered for, beside this track.
            val onWrite: () -> Unit = { view.writeKey?.let { actions.write(it, playing) } }
            val onStop: () -> Unit = { view.stopCall?.let(actions.stop) }
            val onLookAgain: () -> Unit = { view.lookAgainKey?.let { actions.lookAgain(it, playing) } }
            // The note of a model found unavailable opens *AI services* at that model's row (#581).
            val onModelSettings: () -> Unit = {
                state.unavailable?.let { actions.openSettingsAt(SettingsTarget.model(it.provider)) }
                    ?: actions.openSettings()
            }
            InsightCard(
                track?.insight ?: NOT_YET,
                insightContext(view, track, settings, shown.modelAllowed, state.unavailable),
                InsightActions(onWrite, onStop, cite, onModelSettings, onLookAgain) { reportedTrack, written ->
                    reporting = Reported(reportedTrack, written)
                },
                shown.details,
                settings?.let { writingChoice(it, actions) },
                insightReading(actions.readAloud, track),
            )
            if (settings != null) SourcesItem(pages, shown, focus, fold, actions)
        }
    }
}

// *Read aloud* beside *Key insights* (#325, #725): it reads the insight shown, for the track it is shown beside.
@Composable
private fun insightReading(
    readAloud: ReadAloud?,
    track: TrackState?,
): ReadAloudControl? =
    readAloudControl(readAloud, Reading.INSIGHT) {
        val written = track?.insight as? InsightState.Written
        if (track != null && written != null) readAloud?.readInsight(track, written)
    }

// *Sources* on Home: its chip, the way to its page, under *Separate pages* ([pages]), else the box (#623).
@Composable
private fun SourcesItem(
    pages: Boolean,
    shown: Showing,
    focus: LineFocus,
    fold: SourcesFold,
    actions: NowPlayingActions,
) {
    if (pages) {
        SourcesChip(opensPage = true) { actions.openPage(HomePage.Sources()) }
    } else {
        SourcesSection(shown.sources(), focus, fold, actions.openSettings)
    }
}

// What the insight card needs besides the insight, from the pipeline's [view] of [track] and the [settings].
private fun insightContext(
    view: PipelineView,
    track: TrackState?,
    settings: Settings?,
    modelAllowed: Boolean,
    unavailable: UnavailableModel?,
) = InsightContext(
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
            followed = view.followed && track != null,
            lookAgain = view.lookAgainKey != null && track != null,
            lookingAgain = view.lookingAgain && track != null,
            nothingNew = view.nothingNew && track != null,
            somethingNew = view.somethingNew && track != null,
        ),
    unavailable = unavailable.takeIf { settings?.writeInsights != false },
    mode = settings?.writeAutomatically,
    waiting = view.waiting,
    cardShown = view.cardShown && track != null,
    rewriteFailed = view.rewriteFailed.takeIf { track != null },
)

// The insight card's menu of *Reactive*'s modes (#615): the mode chosen, fixed while *Show
// insights* is off; its last item opens the settings at *Reactive*.
private fun writingChoice(
    settings: Settings,
    actions: NowPlayingActions,
) = WritingChoice(
    mode = settings.writeAutomatically,
    enabled = settings.writeInsights,
    onChoose = actions.chooseWritingMode,
    onDetails = { actions.openSettingsAt(SettingsTarget.reactive) },
)

// What a report is of: the insight, and the track it was written for.
private class Reported(
    val track: TrackState,
    val insight: InsightState.Written,
)

// The report's dialog, handed to [mail], or to the phone's own mail apps when null.
@Composable
private fun ReportOpen(
    reported: Reported,
    mail: MailHandoff?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val handoff = mail ?: remember(context) { PhoneMail(context) }
    InsightReportDialog(reported.track, reported.insight, handoff, onDismiss)
}

// A track the pipeline has not reached yet.
private val NOT_YET = InsightState.Idle(canWrite = false)
