package app.sleevesight.app.ui.home

import android.app.PendingIntent
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.sleevesight.app.R
import app.sleevesight.app.session.AdPlaying
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.session.Transport
import app.sleevesight.app.ui.logo.HeaderLogo
import app.sleevesight.app.ui.logo.LogoSpin
import app.sleevesight.app.ui.logo.headerRecordCentre
import app.sleevesight.app.ui.settings.InstalledPlayers
import app.sleevesight.app.ui.settings.PlayerApps
import app.sleevesight.app.ui.settings.playerIcon
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The followed player in Home's header (#1409, #1209, #1440): the record plays and pauses it, and the
// player symbol ([PlayerSymbol]) opens the player menu, which took the place of the player row above
// the cover.

/**
 * What Home's header reads of the followed player, and where its taps go (#1409, #1209, #1440): the shown
 * player's state ([playback], with the controls it advertises), each followed player with a session
 * and its state ([followed]), the settings for the players *Settings → Players* lists ([settings]),
 * the players' names ([label]), icons ([installed]) and ways in ([activity], a player's own screen,
 * its session's; [launch], a player's launcher entry); [send] sends a control to the shown player, and
 * [follow] makes Home show another followed player with a session. Home shows a track ([nowPlaying])
 * or an ad ([ad]), or it is idle: then the record plays no player ([shownPlayback]), though a session
 * be kept; the player symbol still opens the menu on it (#1440). The player last shown, kept after
 * its session ends ([lastPlayer], #1440), is the one the symbol's long press opens while none plays.
 */
@Suppress("LongParameterList") // What the header reads, each published on its own, and its two actions.
class HeaderPlayerFeed(
    val playback: StateFlow<PlaybackInfo>,
    val followed: StateFlow<Map<String, PlaybackStatus>>,
    val settings: StateFlow<Settings?>,
    val label: (String) -> String,
    val activity: (String) -> PendingIntent?,
    val launch: (String) -> Intent?,
    val installed: InstalledPlayers?,
    val send: (Transport) -> Unit,
    val follow: (String) -> Unit,
    val nowPlaying: StateFlow<NowPlaying?>,
    val ad: StateFlow<AdPlaying?>,
    val lastPlayer: StateFlow<String?> = MutableStateFlow(null),
)

/**
 * The shown player's state as the header goes by it (#1409, #1209): [PlaybackInfo.NONE] while Home
 * is idle, showing neither a track nor an ad (a session kept with no track, its metadata cleared), so
 * the record then has no button, as with no session at all.
 */
@Composable
internal fun HeaderPlayerFeed.shownPlayback(): PlaybackInfo {
    val state by playback.collectAsState()
    val track by nowPlaying.collectAsState()
    val playingAd by ad.collectAsState()
    return if (track == null && playingAd == null) PlaybackInfo.NONE else state
}

/**
 * The record's own button in Home's header (#1409), [TARGET] square, its centre at [centre] in the
 * box it is laid out in (the record's in the header logo; none yet, before the logo is placed). A tap
 * sends Play or Pause to the shown player ([PlaybackInfo.toggle]) when its state advertises it, and
 * a screen reader reads it as a button named for what the tap does, *Play* or *Pause*. With no
 * player, or one that advertises neither, a tap does nothing, and it is not a button: it takes the
 * tap still, so a tap on the record never scrolls Home, as one on the words does. A long press does
 * nothing (#1440). In a debug build the bug in the record's place takes the same tap.
 */
@Composable
internal fun RecordButton(
    playback: PlaybackInfo,
    send: (Transport) -> Unit,
    centre: () -> Offset?,
    modifier: Modifier = Modifier,
) {
    val control = playback.toggle
    val label =
        stringResource(
            if (control ==
                Transport.PAUSE
            ) {
                R.string.home_record_pause
            } else {
                R.string.home_record_play
            },
        )
    val placed =
        modifier.layout { measurable, constraints ->
            val side = TARGET.roundToPx()
            val placeable = measurable.measure(Constraints.fixed(side, side))
            layout(constraints.minWidth, constraints.minHeight) {
                centre()?.let { placeable.place(it.x.toInt() - side / 2, it.y.toInt() - side / 2) }
            }
        }
    val target =
        if (playback.allows(control)) {
            placed
                .testTag(RECORD_TAG)
                // Read as a button that plays or pauses, and nothing more: no long press (#1440).
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick {
                        send(control)
                        true
                    }
                }
                // A long press taken and dropped (#1440), not a tap on its release, as `clickable` has it.
                .combinedClickable(
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = TARGET / 2),
                    role = Role.Button,
                    hapticFeedbackEnabled = false,
                    onLongClick = {},
                ) { send(control) }
        } else {
            // Taken and dropped, and nothing to a screen reader.
            placed.testTag(RECORD_TAG).clearAndSetSemantics {}.pointerInput(Unit) { detectTapGestures {} }
        }
    Box(target)
}

/**
 * The header's logo (#383) and its record's own button ([RecordButton], #1409): the words a button
 * that scrolls Home to the top ([onScrollToTop]), read as [toTop]; the record, over them where the
 * logo draws it, a button of its own that plays and pauses the followed player ([player]), and with
 * none takes the tap and does nothing.
 */
@Suppress("LongParameterList") // The logo's spin and crawl, the words' tap, and the record's.
@Composable
internal fun LogoAndRecord(
    spin: LogoSpin,
    crawlSpace: () -> Rect?,
    toTop: String,
    onScrollToTop: () -> Unit,
    player: HeaderPlayerFeed?,
    modifier: Modifier = Modifier,
) {
    // The record's centre in this box, from where the logo was last placed.
    var centre by remember { mutableStateOf<Offset?>(null) }
    val outer = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val playback = player?.shownPlayback() ?: PlaybackInfo.NONE
    // Room after the logo for the record's target, which reaches past its edge.
    Box(modifier.onPlaced { outer[0] = it }.padding(end = RECORD_ROOM)) {
        Box(
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    // One button: the click and its role from `clickable`, the spoken label in
                    // place of the logo, which says nothing itself.
                    .clickable(role = Role.Button, onClick = onScrollToTop)
                    .clearAndSetSemantics { contentDescription = toTop }
                    .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // The one-line logo (#383), in place of the name, which the label still says.
            HeaderLogo(
                modifier =
                    Modifier.onGloballyPositioned { logo ->
                        val box = outer[0]?.takeIf { it.isAttached } ?: return@onGloballyPositioned
                        centre = box.localPositionOf(logo, headerRecordCentre(logo.size.toSize()))
                    },
                spin = spin,
                crawlSpace = crawlSpace,
            )
        }
        RecordButton(playback, player?.send ?: {}, { centre }, Modifier.matchParentSize())
    }
}

/**
 * [player]'s own launcher icon, [size] square ([playerIcon]; decorative); without one (not loaded
 * yet, or none the phone gives), the first letter of its [name] on a plain rounded square.
 */
@Composable
internal fun PlayerIcon(
    player: String,
    name: String,
    apps: PlayerApps,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val icon = playerIcon(player, apps)
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(size * CORNER_SHARE))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = LETTER_FILL)),
            contentAlignment = Alignment.Center,
        ) {
            // Fixed to the square, as the icon it stands in for is, whatever the font size.
            Text(
                name.take(1).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontSize = with(LocalDensity.current) { (size * LETTER_SHARE).toSp() },
                lineHeight = with(LocalDensity.current) { (size * LETTER_SHARE).toSp() },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The test tag of the record's own button in Home's header (#1409). */
const val RECORD_TAG: String = "home-record"

/** The record's touch target: Material's least. */
private val TARGET = 48.dp

/** The stand-in letter's size, as a share of its square. */
private const val LETTER_SHARE = 0.55f

/** The stand-in's corners, as a share of its side, as a launcher icon's. */
private const val CORNER_SHARE = 0.25f

/** How much of the text's colour fills a player's stand-in icon. */
private const val LETTER_FILL = 0.12f

/** The room after the logo for the record's touch target, which reaches past the logo's end. */
private val RECORD_ROOM = 4.dp
