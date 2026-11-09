package app.sleevesight.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.ui.logo.animatorDurationScale
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.MbTrack
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The cover that turns over to show the album's tracklist on its back (#984).

/**
 * What the cover's back needs: the [album] it follows ([albumIdentity]: the back stays while it is
 * the same, and the cover shows again when it changes), what the back shows ([back]), the album's
 * tracklist to [look] up on a flip, [key] (null while MusicBrainz is off or the album is not known), the
 * [retry] *Retry* runs when MusicBrainz could not be reached, the way to *Settings → Sources*
 * ([openSources]), and the app's own [colors], since the back takes nothing from the art. For a
 * track *Clear history* holds under *On request* (#1112), [lookUp] asks for it to be looked up again,
 * as *Look up again* does: a turn of the cover over asks, and the back fills once the sources answer;
 * null otherwise.
 */
@Suppress("LongParameterList") // What the back shows, what it asks, and where its buttons go.
internal class CoverTurn(
    val album: String?,
    val back: CoverBack,
    val key: TracklistKey?,
    val look: (TracklistKey) -> Unit,
    val retry: (() -> Unit)?,
    val openSources: () -> Unit,
    val colors: ColorScheme,
    val lookUp: (() -> Unit)? = null,
)

/**
 * The cover's back for the track [shown] beside [header] (#984), from [actions]' tracklists, with
 * [settings] (MusicBrainz taken as on until their first read) and the app's own colours, not the
 * art's ([LocalAppColors]). *Retry*
 * asks again for the album's tracklist, or, when it was the track's own lookup that failed, for the
 * track's MusicBrainz, as *Dates*' does.
 */
@Composable
internal fun coverTurn(
    shown: Showing,
    header: NowPlaying,
    settings: Settings?,
    actions: NowPlayingActions,
): CoverTurn {
    val colors = LocalAppColors.current ?: MaterialTheme.colorScheme
    val states by actions.tracklists.states.collectAsState()
    val track = shown.track
    val on = settings?.isEnabled(SourceId.MUSICBRAINZ) != false
    val back = coverBack(track, header, on, states)
    val retry =
        (back as? CoverBack.Unreachable)?.let { unreachable ->
            val key = unreachable.key
            if (key != null) {
                { actions.tracklists.retry(key) }
            } else {
                Retries(shown.view, shown.playing, actions.askAgain).of(SourceId.MUSICBRAINZ)
            }
        }
    return CoverTurn(
        album = albumIdentity(header),
        back = back,
        key = if (on) tracklistKey(track, header) else null,
        look = actions.tracklists::look,
        retry = retry,
        openSources = { actions.openSettingsAt(SettingsTarget.sources) },
        colors = colors,
        lookUp = { actions.lookUpAgain(shown.playing) }.takeIf { shown.cleared },
    )
}

/**
 * The cover, [front], in [square] clipped to [shape], that a tap turns over, a 3D turn about its
 * vertical axis in [FLIP_MS] (at once under *Remove animations*), to show the album's tracklist on
 * its back ([CoverBackFace]), and a tap anywhere on the back turns back; a scroll or a swipe on it
 * does not, nor a tap on its buttons. The first turn asks for the album's tracklist ([CoverTurn.look]),
 * and so does a back shown when the album becomes known. For a track *Clear history* holds, a turn of
 * the cover over asks for it to be looked up again ([CoverTurn.lookUp], #1112), once while the hold
 * lasts: turning back and over again asks no more. The turn asks, not the back shown (#1148): a back
 * left turned over from before the clear, kept with Home ([rememberSaveable]) while *Settings* clears,
 * asks nothing when Home comes back, and says the track was cleared, as every other field does, until
 * the cover is turned over again or *Look up again* is tapped. The back stays while [CoverTurn.album] is
 * the same, the playing song's mark moving with the track, and gives way to the cover on another
 * album. A screen reader reads the cover as a button, "Album art", "Double-tap to show tracklist",
 * and the back as "Tracklist", "Double-tap to show cover", its tracks a list in it.
 */
@Composable
internal fun FlippingCover(
    square: Modifier,
    shape: Shape,
    turn: CoverTurn,
    front: @Composable () -> Unit,
) {
    // The album whose back shows, saved with it, so a back restored after another album has come
    // (the process gone meanwhile) is not shown on it; null for the cover.
    val album = turn.album.orEmpty()
    var backOf by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(album) { if (backOf != album) backOf = null }
    val flipped = backOf == album
    LaunchedEffect(flipped, turn.key) {
        if (flipped) turn.key?.let(turn.look)
    }
    val lookUp = lookUpOnTurn(album, turn.lookUp)
    val toggle = {
        val over = backOf != album
        backOf = if (over) album else null
        if (over) lookUp.ask()
    }
    val still = animatorDurationScale() == 0f
    val target = if (flipped) HALF_TURN else 0f
    val turning = remember { Animatable(target) }
    // The album the angle is of: on another album the cover shows at once, not the new album's back
    // as it turns away.
    var turnedFor by remember { mutableStateOf(album) }
    LaunchedEffect(target, album) {
        if (still || turnedFor != album) turning.snapTo(target) else turning.animateTo(target, tween(FLIP_MS))
        turnedFor = album
    }
    val angle = if (turnedFor == album) turning.value else 0f
    val name = stringResource(if (flipped) R.string.home_tracklist else R.string.home_art)
    val action = stringResource(if (flipped) R.string.home_tracklist_show_cover else R.string.home_art_show_tracklist)
    val density = LocalDensity.current.density
    Box(
        square
            .testTag(COVER_TAG)
            .graphicsLayer {
                rotationY = angle
                cameraDistance = CAMERA_DISTANCE * density
            }.clip(shape)
            // A tap, not a press that scrolls or swipes, and not one a button on the back takes.
            .pointerInput(album) { detectTapGestures { toggle() } }
            .semantics {
                contentDescription = name
                role = Role.Button
                onClick(action) {
                    toggle()
                    true
                }
            },
    ) {
        if (angle <= HALF_TURN / 2) {
            front()
        } else {
            // Turned once more, so the back reads the right way round once the cover has turned.
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = HALF_TURN }) {
                MaterialTheme(colorScheme = turn.colors) { CoverBackFace(turn, lookUp.unasked) }
            }
        }
    }
}

/**
 * For a track a clear holds (#1112), the look-up a turn of the cover over asks for, [ask]; [unasked]
 * while the hold lasts and nothing was asked, so a back left turned over says the track was cleared.
 */
private class TurnLookUp(
    val ask: () -> Unit,
    val unasked: Boolean,
)

/**
 * The look-up [lookUp] for the [album] shown, asked once for each hold (#1112): a new clear's hold
 * asks again. Asked by the turn itself, never by a back already showing (#1148), and read when the
 * turn is made, since the tap's handler outlives the composition it was made in.
 */
@Composable
private fun lookUpOnTurn(
    album: String,
    lookUp: (() -> Unit)?,
): TurnLookUp {
    var asked by remember(album, lookUp != null) { mutableStateOf(false) }
    val ask by rememberUpdatedState {
        if (lookUp != null && !asked) {
            asked = true
            lookUp()
        }
    }
    return TurnLookUp({ ask() }, unasked = lookUp != null && !asked)
}

/**
 * The back: the tracklist on the app's `surfaceContainerHigh`, a single disc as one list, several a
 * page each ([DiscPages]); else why there is none, as *Dates* says it ([NoDatesText]), but for an
 * album MusicBrainz has no tracklist of, or several albums of its name. For a track a clear holds
 * (#1112), a turn over asks for it to be looked up, so the back says it is looking; only a back left
 * turned over from before the clear, which asked nothing ([cleared], #1148), says "Cleared with the
 * history.", as every other field does.
 */
@Composable
private fun CoverBackFace(
    turn: CoverTurn,
    cleared: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.surfaceContainerHigh).testTag(COVER_BACK_TAG)) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, LocalTrackCleared provides cleared) {
            when (val back = turn.back) {
                is CoverBack.Found ->
                    if (back.discs.size == 1) {
                        DiscTracks(back.discs.single().tracks, back.playing?.track, Modifier.fillMaxSize())
                    } else {
                        DiscPages(back)
                    }
                CoverBack.NotFound ->
                    Why {
                        Text(stringResource(R.string.home_tracklist_none), style = MaterialTheme.typography.bodyLarge)
                    }
                CoverBack.Ambiguous ->
                    Why {
                        Text(
                            stringResource(R.string.home_tracklist_ambiguous),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                CoverBack.Looking -> Why { NoDatesText(NoDates.LOOKING, null, turn.openSources) }
                CoverBack.Off -> Why { NoDatesText(NoDates.OFF, null, turn.openSources) }
                is CoverBack.Unreachable -> Why { NoDatesText(NoDates.UNREACHABLE, turn.retry, turn.openSources) }
            }
        }
    }
}

// Why the back has no tracklist, in its middle.
@Composable
private fun Why(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/**
 * The discs of an album of several, a page each, swiped sideways, opening on the playing song's and
 * going to it when the song moves to another; each page's heading ("Disc 2", read "Disc 2 of 2"),
 * by the disc's number in the release, fixed above its scrolling tracks; under them a dot per disc,
 * which shows that disc.
 */
@Composable
private fun DiscPages(back: CoverBack.Found) {
    val count = back.discs.size
    val playing = back.playing
    val pager = rememberPagerState(initialPage = playing?.disc ?: 0) { count }
    val still = animatorDurationScale() == 0f
    val scope = rememberCoroutineScope()
    LaunchedEffect(playing?.disc) {
        playing?.disc?.takeIf { it != pager.currentPage }?.let { pager.scrollToPage(it) }
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(Modifier.fillMaxSize()) {
                val disc = back.discs[page].position
                val of = stringResource(R.string.home_tracklist_disc_of, disc, back.media)
                Text(
                    stringResource(R.string.home_tracklist_disc, disc),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier =
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics {
                            heading()
                            contentDescription = of
                        },
                )
                DiscTracks(
                    back.discs[page].tracks,
                    playing?.takeIf { it.disc == page }?.track,
                    Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        // One group of tabs to a screen reader, each dot a 48 dp target around its 7 dp mark.
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.Center) {
            repeat(count) { page ->
                val current = page == pager.currentPage
                val name = stringResource(R.string.home_tracklist_disc, back.discs[page].position)
                Box(
                    Modifier
                        .size(DOT_TARGET)
                        .clip(CircleShape)
                        .selectable(current, role = Role.Tab) {
                            scope.launch { if (still) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
                        }.semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    val dot = if (current) colors.primary else colors.onSurfaceVariant.copy(alpha = DOT_ALPHA)
                    Box(Modifier.size(DOT).background(dot, CircleShape))
                }
            }
        }
    }
}

/**
 * A disc's [tracks], a list that scrolls, opening with the [playing] one in view, and brought into
 * view when the song moves to another of them: the whole row, with up to two tracks above it as fit.
 */
@Composable
private fun DiscTracks(
    tracks: List<MbTrack>,
    playing: Int?,
    modifier: Modifier,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = ((playing ?: 0) - LEAD).coerceAtLeast(0))
    LaunchedEffect(playing) {
        if (playing != null && !list.wholly(playing)) {
            // Fewer tracks above it until the row fits (each scroll measures the list again).
            for (lead in LEAD downTo 0) {
                list.scrollToItem((playing - lead).coerceAtLeast(0))
                if (list.wholly(playing)) break
            }
        }
    }
    LazyColumn(modifier.testTag(TRACKLIST_TAG), state = list, contentPadding = PaddingValues(vertical = 6.dp)) {
        itemsIndexed(tracks) { i, track -> TrackRow(track, i == playing) }
    }
}

// Whether the row [index] is laid out wholly inside the list's viewport.
private fun LazyListState.wholly(index: Int): Boolean {
    val info = layoutInfo
    return info.visibleItemsInfo.any {
        it.index == index && it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset
    }
}

/**
 * A track: its position, its title and its length; the playing one on `primaryContainer`. Text, not
 * a button: a tap on it turns the cover back. Read "3, Blue in Green, 5 minutes 37 seconds, playing".
 */
@Composable
private fun TrackRow(
    track: MbTrack,
    playing: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val length = track.lengthMs?.takeIf { it > 0 }?.milliseconds
    val spoken =
        listOfNotNull(
            track.position.toString(),
            track.title,
            length?.let { spokenLength(it) },
            if (playing) stringResource(R.string.home_tracklist_playing) else null,
        ).joinToString(", ")
    val ink = if (playing) colors.onPrimaryContainer else colors.onSurface
    val quiet = if (playing) colors.onPrimaryContainer else colors.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium
    val weight = if (playing) FontWeight.SemiBold else null
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (playing) Modifier.background(colors.primaryContainer) else Modifier)
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            track.position.toString(),
            Modifier.widthIn(min = NUMBER_WIDTH),
            color = quiet,
            style = style,
            textAlign = TextAlign.End,
        )
        Text(track.title, Modifier.weight(1f), color = ink, style = style, fontWeight = weight)
        length?.let { Text(clock(it), color = quiet, style = style) }
    }
}

// [length] as a screen reader says it: "5 minutes 37 seconds"; the seconds alone under a minute, the
// minutes alone on the minute.
@Composable
private fun spokenLength(length: Duration): String {
    val seconds = length.inWholeSeconds
    val minutes = (seconds / SECONDS_PER_MINUTE).toInt()
    val rest = (seconds % SECONDS_PER_MINUTE).toInt()
    return listOfNotNull(
        minutes.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.home_tracklist_minutes, it, it) },
        rest.takeIf { it > 0 || minutes == 0 }?.let { pluralStringResource(R.plurals.home_tracklist_seconds, it, it) },
    ).joinToString(" ")
}

/** The test tag of the cover that turns over (#984). */
const val COVER_TAG: String = "cover"

/** The test tag of the cover's back. */
const val COVER_BACK_TAG: String = "cover_back"

/** The test tag of a disc's list of tracks on the back. */
const val TRACKLIST_TAG: String = "tracklist"

/** The turn's length, as picked: about half a second. */
internal const val FLIP_MS: Int = 500

private const val HALF_TURN = 180f

// Far enough that the turning cover does not loom over the screen as it turns.
private const val CAMERA_DISTANCE = 12f

// Tracks above the playing one when the list opens on it.
private const val LEAD = 2
private const val SECONDS_PER_MINUTE = 60
private const val DOT_ALPHA = 0.4f
private val DOT = 7.dp
private val DOT_TARGET = 48.dp
private val NUMBER_WIDTH = 24.dp
