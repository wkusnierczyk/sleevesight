package app.sleevesight.app.ui.home

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The player's name and state; a tap opens the player's own screen (its session activity). */
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
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = open) {
                    if (!openPlayerScreen(context, actions.playerActivity())) {
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
    val position = (playback.positionAt(now) ?: Duration.ZERO).coerceIn(Duration.ZERO, duration)
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
 * The song's title, then each artist, then the album, then the year of the first release, when
 * MusicBrainz gave one. The title is as normalized, with the source work and the variant it
 * named under it; the artists are the credited ones, featured artists included.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyData(
    normalized: NormalizedTrack,
    header: NowPlaying,
    year: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(normalized.title.ifEmpty { header.title }, style = MaterialTheme.typography.headlineSmall)
        val about =
            listOfNotNull(normalized.from?.let { stringResource(R.string.home_title_from, it) }, normalized.variant)
        if (about.isNotEmpty()) Small(about.joinToString(" · "))
        val artists = normalized.artists.ifEmpty { listOf(header.artists).filter(String::isNotBlank) }
        FlowRow {
            artists.forEachIndexed { i, artist ->
                Text(if (i < artists.lastIndex) "$artist, " else artist, style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            header.album?.takeIf(String::isNotBlank) ?: stringResource(R.string.home_album_none),
            style = MaterialTheme.typography.bodyLarge,
        )
        year?.let { Small(stringResource(R.string.home_released, it)) }
    }
}

/**
 * The genre labels, in their families' hues, said to be the artists'. A tap on one shows where it
 * came from: each label source, linking the artist's page there, with MusicBrainz's licence
 * (D12); a label only MusicBrainz gives opens nothing more. The genre cards are Phase 2.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Genres(
    track: TrackState?,
    settings: Settings?,
) {
    if (track == null || !track.gathered) return
    val labels = track.genres
    var open by remember(labels) { mutableStateOf<TrackLabel?>(null) }
    val off = settings != null && LabelSource.entries.none { settings.givesLabels(it) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            labels.isNotEmpty() -> Small(stringResource(R.string.home_genres))
            off -> Small(stringResource(R.string.home_genres_off))
            else -> Small(stringResource(R.string.home_genres_none))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (label in labels) {
                GenreChip(label, selected = open == label) {
                    open =
                        if (open ==
                            label
                        ) {
                            null
                        } else {
                            label
                        }
                }
            }
        }
        open?.let { Credits(it) }
    }
}

@Composable
private fun GenreChip(
    label: TrackLabel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < HALF
    val hue = label.genre.hue.toFloat()
    val container = Color.hsl(hue, CHIP_SATURATION, if (dark) DARK_CONTAINER else LIGHT_CONTAINER)
    val content = Color.hsl(hue, CHIP_SATURATION, if (dark) DARK_CONTENT else LIGHT_CONTENT)
    val shape = RoundedCornerShape(16.dp)
    Text(
        label.genre.label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier =
            Modifier
                .testTag(GENRE_PREFIX + label.genre.identity)
                .clip(shape)
                .background(container, shape)
                .clickable(onClick = onClick)
                .then(if (selected) Modifier.background(content.copy(alpha = SELECTED_ALPHA), shape) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Credits(label: TrackLabel) {
    val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    val licence = stringResource(R.string.home_genre_licence)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Small(stringResource(R.string.home_genre_from, label.genre.label))
        for (credit in label.credits) {
            val text =
                buildAnnotatedString {
                    val url = credit.url
                    if (url !=
                        null
                    ) {
                        withLink(LinkAnnotation.Url(url, linkStyle)) { append(credit.source.credit) }
                    } else {
                        append(credit.source.credit)
                    }
                    credit.source.licenceUrl?.let {
                        append(" · ")
                        withLink(LinkAnnotation.Url(it, linkStyle)) { append(licence) }
                    }
                }
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The test tag of the player row. */
const val PLAYER_TAG: String = "player"

/** The test tag of a genre label, before its identity. */
const val GENRE_PREFIX: String = "genre:"

private val ART_WIDTH = 360.dp
private val TICK = 1.seconds
private const val TAG = "Sleevesight"
private const val HALF = 0.5f
private const val CHIP_SATURATION = 0.5f
private const val LIGHT_CONTAINER = 0.88f
private const val LIGHT_CONTENT = 0.22f
private const val DARK_CONTAINER = 0.26f
private const val DARK_CONTENT = 0.86f
private const val SELECTED_ALPHA = 0.16f
