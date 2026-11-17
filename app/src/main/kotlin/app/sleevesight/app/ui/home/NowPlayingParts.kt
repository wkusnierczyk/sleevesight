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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.ArtistSplit
import app.sleevesight.core.pipeline.FoundAs
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val name = remember(player) { actions.playerLabel(player) }
    // As the header logo's record goes by it, so it turns while this says *Playing* (#848).
    val state = stateText(playbackStatus(player, playback))
    val open = stringResource(R.string.home_open_player, name)
    val none = stringResource(R.string.home_player_no_screen, name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
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

/**
 * The art, square, or a placeholder; under it the playback position, when the player gives the length.
 * With [turn], a tap turns the art over to show the album's tracklist on its back ([FlippingCover],
 * #984); without (an ad's), it is the art alone. With [fitHeight] (Home split in two, #1097), the art
 * is as large as the space it is given lets it be, the position under it included, rather than at
 * most [ART_WIDTH] wide.
 */
@Composable
internal fun ArtAndProgress(
    art: Bitmap?,
    playback: PlaybackInfo,
    duration: Duration?,
    turn: CoverTurn? = null,
    fitHeight: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    val cover: @Composable (Modifier) -> Unit = { square ->
        if (turn == null) {
            Art(art, square.clip(shape), stringResource(R.string.home_art))
        } else {
            // The cover that turns names itself, as the art or as the tracklist.
            FlippingCover(square, shape, turn) { Art(art, Modifier.fillMaxSize(), description = null) }
        }
    }
    val progress: (@Composable (Modifier) -> Unit)? =
        if (duration != null && duration.isPositive()) {
            { modifier -> Progress(playback, duration, modifier) }
        } else {
            null
        }
    if (fitHeight) {
        FittedArt(cover, progress, ART_GAP)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ART_GAP),
    ) {
        cover(
            Modifier
                .widthIn(max = ART_WIDTH)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        progress?.invoke(Modifier.widthIn(max = ART_WIDTH).fillMaxWidth())
    }
}

// [art] in [modifier], cropped to it, read as [description]; or the placeholder, a note on the surface.
@Composable
private fun Art(
    art: Bitmap?,
    modifier: Modifier,
    description: String?,
) {
    if (art != null) {
        val image = remember(art) { art.asImageBitmap() }
        Image(image, description, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
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
        // The position at the start and the length at the end, on one line, or the length under the
        // position when they do not fit beside each other (a large font, a narrow cover, #1097).
        Layout(
            content = {
                Small(clock(position), tabular = true)
                Small(clock(duration), tabular = true)
            },
            modifier = Modifier.fillMaxWidth(),
            measurePolicy = TimesPolicy,
        )
    }
}

/**
 * The song's title, large and bold, then each artist, then the album in italics (#438), alone on its
 * line, and under it, quieter, the record label of the release being played and the album's year
 * (#729, [ReleaseAbout]): "Parlophone · 1965", of [labels] and [years] ([HeaderYears], #556), either
 * alone when only it is known, no line when neither is. The title is as
 * normalized, with the source work and the variant it named under it; the artists are the credited
 * ones, featured artists included. A screen reader reads the album and the line under it as one, a
 * sentence: "Rubber Soul, released by Parlophone in 1965."; with no album reported, the line says so.
 * No year when the album's name gives its
 * edition's ([albumLineYear], #1116: "Material Girl (2024 Remaster)"). When the recording first came out is
 * in the *Dates* panel under it ([DatesPanel], #724), never in the album's place. While
 * [labelHeld], the labels still looked up on MusicBrainz, an empty line under the album, its height
 * kept, rather than the year alone and the label after it.
 *
 * With [split], the roles telling the composer apart (#1199), the performers on one line (an arranger
 * the title names is in the title), then the composer, or every author, on a line of its own, after a quieter label
 * ("Composer: Wolfgang Amadeus Mozart"), as the row before the artist's names them; when it names
 * anyone the player does not credit (#1298), MusicBrainz's line on the work among [notes]' lines as a
 * citation chip at its end ([authorsCite]), a tap on which goes where [notes]' citations go.
 *
 * While [held] (#1355, [peopleNamed]), neither: an empty line in the artists' place, its height kept.
 *
 * With the sources asked in another spelling ([foundAs], #1293), a quiet line under the title says
 * it ("Found as “Nessun dorma”", [foundAsLine]), read after the title as shown; the title stays the
 * player's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList") // The track, its years and labels, its people, and where the authors' mark goes.
@Composable
internal fun KeyData(
    normalized: NormalizedTrack,
    header: NowPlaying,
    years: HeaderYears,
    split: ArtistSplit? = null,
    held: Boolean = false,
    notes: PanelNotes = PanelNotes(),
    foundAs: FoundAs? = null,
    labels: AlbumLabels = AlbumLabels.Known(),
    labelHeld: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            normalized.title.ifEmpty { header.title },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        foundAs?.let {
            Text(
                foundAsLine(it),
                Modifier.testTag(FOUND_AS_TAG),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val about =
            listOfNotNull(normalized.from?.let { stringResource(R.string.home_title_from, it) }, normalized.variant)
        if (about.isNotEmpty()) Small(about.joinToString(" · "))
        if (held) {
            // Held back until the people are named (#1355): a line's height kept, so the album's
            // line does not move when it fills; nothing a screen reader stops on.
            Text(
                " ",
                Modifier.testTag(ARTISTS_HELD_TAG).clearAndSetSemantics {},
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            val credited = normalized.artists.ifEmpty { listOf(header.artists).filter(String::isNotBlank) }
            val artists = split?.performers ?: credited
            FlowRow {
                artists.forEachIndexed { i, artist ->
                    Text(if (i < artists.lastIndex) "$artist, " else artist, style = MaterialTheme.typography.bodyLarge)
                }
            }
            split?.let {
                val cite = remember(it, notes.lines) { authorsCite(it, notes.lines) }
                AuthorsLine(it, cite, notes.onCite)
            }
        }
        val album = header.album?.takeIf(String::isNotBlank)
        if (album == null) {
            Text(stringResource(R.string.home_album_none), style = MaterialTheme.typography.bodyLarge)
        } else {
            // Read once per album, year and labels, not at every recomposition (#1147).
            val line = remember(album, years.album, labels) { releaseLine(album, years.album, labels) }
            ReleaseAbout(album, line.takeUnless { labelHeld }, labelHeld)
        }
    }
}

/**
 * [line], a format given [ALBUM_MARK] in the album's place, with [album] there in italics: the mark
 * says where the format puts it, whatever the album and the rest hold. A format that leaves the
 * album out is shown as it is, rather than failing. For the album line and the *Dates* panel's
 * sentences (#724), and the same for a weak genre's line, its genre in italics (#875).
 */
internal fun withAlbum(
    line: String,
    album: String,
): AnnotatedString {
    val at = line.indexOf(ALBUM_MARK)
    return buildAnnotatedString {
        if (at < 0) {
            append(line)
        } else {
            append(line, 0, at)
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(album) }
            append(line, at + ALBUM_MARK.length, line.length)
        }
    }
}

// With [tabular], digits of one width: for the times under the art, so the position takes no more room
// than the length as it plays (#1097).
@Composable
private fun Small(
    text: String,
    tabular: Boolean = false,
) {
    val small = MaterialTheme.typography.bodySmall
    Text(
        text,
        style = if (tabular) small.copy(fontFeatureSettings = TABULAR) else small,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The test tag of the line under the title naming the spelling the track was found as (#1293). */
const val FOUND_AS_TAG: String = "found-as"

/** The test tag of the line under the album: the release's labels and the album's year (#729). */
const val RELEASE_LINE_TAG: String = "release-line"

/** The test tag of the line under the album while the release's labels are held back (#729). */
const val RELEASE_HELD_TAG: String = "release-held"

/** The test tag of the line under the title while its artists are held back (#1355). */
const val ARTISTS_HELD_TAG: String = "artists-held"

/** The test tag of the player row. */
const val PLAYER_TAG: String = "player"

private val ART_WIDTH = 360.dp

/** The space between the art and the playback position under it. */
private val ART_GAP = 8.dp
private val TICK = 1.seconds

/** Digits of one width (OpenType `tnum`), for times that change as they play. */
private const val TABULAR = "tnum"

/**
 * The playback position's two times, the position at the start and the length at the end: on one
 * line when two lengths would fit beside each other, else the length on a line under the position.
 * Decided by the length, not the position, so it holds for the whole track: in digits of one width
 * ([TABULAR]), the position is never wider than the length, and a line is not taken or given back as
 * it plays (9:59 to 10:00). Its intrinsic height says which, for the width asked, so the cover's side
 * can size the cover to what is left.
 */
private object TimesPolicy : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val (start, end) = measurables.map { it.measure(loose) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 2 * end.width
        val beside = 2 * end.width <= width
        val height = if (beside) maxOf(start.height, end.height) else start.height + end.height
        return layout(width, height) {
            start.placeRelative(0, 0)
            end.placeRelative(width - end.width, if (beside) 0 else start.height)
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int {
        val (start, end) = measurables
        val beside = 2 * end.maxIntrinsicWidth(Constraints.Infinity) <= width
        val (a, b) = start.maxIntrinsicHeight(width) to end.maxIntrinsicHeight(width)
        return if (beside) maxOf(a, b) else a + b
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = maxIntrinsicHeight(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = 2 * measurables.last().maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = measurables.maxOf { it.minIntrinsicWidth(height) }
}

private const val TAG = "Sleevesight"

/** What a format is given in the album's place, for [withAlbum] to set the album there in italics. */
internal const val ALBUM_MARK: String = "\u0000"
