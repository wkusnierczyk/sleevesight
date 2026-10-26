package app.sleevesight.app.ui.home

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The player's name and state; a tap opens the player's own screen (its session activity), else
 * the player from its launcher entry (#596), and says so only when there is neither.
 */
@Composable
internal fun PlayerRow(
    player: String,
    playback: PlaybackInfo,
    actions: NowPlayingActions,
) {
    val context = LocalContext.current
    val name = remember(player) { actions.playerLabel(player) }
    val state = stateText(playback.status.takeIf { playback.player == player } ?: PlaybackStatus.NONE)
    val open = stringResource(R.string.home_open_player, name)
    val none = stringResource(R.string.home_player_no_screen, name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .testTag(PLAYER_TAG)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = open, role = Role.Button) {
                    if (!openPlayer(context, actions.playerActivity(), actions.playerLaunch(player))) {
                        Toast.makeText(context, none, Toast.LENGTH_SHORT).show()
                    }
                }.padding(4.dp),
    ) {
        Text(
            if (state == null) name else stringResource(R.string.home_player_state, name, state),
            style = MaterialTheme.typography.titleSmall,
        )
        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

/**
 * Opens the player: its session's screen ([activity], [openPlayerScreen]), else its launcher entry
 * ([launch]), for a session that names no screen or whose intent the player has cancelled (#596).
 * False when neither opens.
 */
internal fun openPlayer(
    context: Context,
    activity: PendingIntent?,
    launch: Intent?,
): Boolean {
    if (openPlayerScreen(context, activity)) return true
    return launch != null &&
        try {
            // As the launcher starts it (`getLaunchIntentForPackage` sets a new task): the player as it was left.
            context.startActivity(launch)
            true
        } catch (_: ActivityNotFoundException) {
            // Uninstalled since the intent was read: nothing to open.
            false
        }
}

/**
 * Sends [intent], the player's own screen, as the app on screen: from Android 14 the sender must
 * say that its own right to start an activity goes with it. False when there is none to send, or
 * the player has cancelled it.
 */
internal fun openPlayerScreen(
    context: Context,
    intent: PendingIntent?,
): Boolean {
    if (intent == null) return false
    val options =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA ->
                ActivityOptions
                    .makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE,
                    ).toBundle()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                @Suppress("DEPRECATION") // Replaced by ALLOW_IF_VISIBLE in Android 16, above.
                ActivityOptions
                    .makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            else -> null
        }
    return try {
        intent.send(context, 0, null, null, null, null, options)
        true
    } catch (_: PendingIntent.CanceledException) {
        Log.w(TAG, "The player's screen could not be opened: its intent was cancelled")
        false
    }
}

/** The art, square, or a placeholder; under it the playback position, when the player gives the length. */
@Composable
internal fun ArtAndProgress(
    art: Bitmap?,
    playback: PlaybackInfo,
    duration: Duration?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val shape = RoundedCornerShape(16.dp)
        val square =
            Modifier
                .widthIn(max = ART_WIDTH)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
        if (art != null) {
            val image = remember(art) { art.asImageBitmap() }
            Image(image, stringResource(R.string.home_art), square, contentScale = ContentScale.Crop)
        } else {
            Box(square.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        if (duration != null && duration.isPositive()) {
            Progress(playback, duration, Modifier.widthIn(max = ART_WIDTH).fillMaxWidth())
        }
    }
}

@Composable
private fun Progress(
    playback: PlaybackInfo,
    duration: Duration,
    modifier: Modifier,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playback) {
        now = SystemClock.elapsedRealtime()
        while (playback.status == PlaybackStatus.PLAYING) {
            delay(TICK)
            now = SystemClock.elapsedRealtime()
        }
    }
    val position = (playback.displayPositionAt(now) ?: Duration.ZERO).coerceIn(Duration.ZERO, duration)
    val description = stringResource(R.string.home_progress)
    Column(modifier = modifier.semantics { contentDescription = description }) {
        LinearProgressIndicator(
            progress = { (position / duration).toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Small(clock(position))
            Small(clock(duration))
        }
    }
}

/**
 * The song's title, large and bold, then each artist, then the album in italics (#438), followed by
 * the year of the release being played when it is known, and under it, small, the year the
 * recording first came out when it is earlier or the album's is not known, "First released 1990"
 * (#556, [HeaderYears]). The title is as normalized, with the source work and the variant it named
 * under it; the artists are the credited ones, featured artists included. The album and its year
 * are one text, so a screen reader reads them as one line, and the first release as the next; with
 * no album reported, the first release stands in the album's place, and with neither, the line says
 * so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyData(
    normalized: NormalizedTrack,
    header: NowPlaying,
    years: HeaderYears,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            normalized.title.ifEmpty { header.title },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        val about =
            listOfNotNull(normalized.from?.let { stringResource(R.string.home_title_from, it) }, normalized.variant)
        if (about.isNotEmpty()) Small(about.joinToString(" · "))
        val artists = normalized.artists.ifEmpty { listOf(header.artists).filter(String::isNotBlank) }
        FlowRow {
            artists.forEachIndexed { i, artist ->
                Text(if (i < artists.lastIndex) "$artist, " else artist, style = MaterialTheme.typography.bodyLarge)
            }
        }
        val album = header.album?.takeIf(String::isNotBlank)
        val firstReleased = years.firstReleased?.let { stringResource(R.string.home_first_released, it) }
        if (album == null) {
            Text(firstReleased ?: stringResource(R.string.home_album_none), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(albumLine(album, years.album), style = MaterialTheme.typography.bodyLarge)
            firstReleased?.let { Small(it) }
        }
    }
}

/** The album in italics, and its year after it when known. */
@Composable
private fun albumLine(
    album: String,
    year: String?,
): AnnotatedString {
    // A mark in the album's place says where the format puts it, whatever the album and year hold.
    val line = if (year != null) stringResource(R.string.home_album_year, ALBUM_MARK, year) else ALBUM_MARK
    val at = line.indexOf(ALBUM_MARK)
    return buildAnnotatedString {
        // A format that leaves the album out is shown as it is, rather than failing.
        if (at < 0) {
            append(line)
        } else {
            append(line, 0, at)
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(album) }
            append(line, at + ALBUM_MARK.length, line.length)
        }
    }
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The test tag of the player row. */
const val PLAYER_TAG: String = "player"

private val ART_WIDTH = 360.dp
private val TICK = 1.seconds
private const val TAG = "Sleevesight"
private const val ALBUM_MARK = "\u0000"
