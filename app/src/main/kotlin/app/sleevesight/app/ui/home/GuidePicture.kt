package app.sleevesight.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.sleevesight.app.R
import app.sleevesight.app.ui.logo.HeaderLogo
import app.sleevesight.app.ui.logo.LocalLogoIntro
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Caveat (#627), the notes' hand-written font, bundled in `res/font`: Caveat Regular, the static
 * build in `googlefonts/caveat` (`fonts/ttf/Caveat-Regular.ttf`, at commit `59745e8`, the one
 * `google/fonts` takes Caveat from), under the SIL Open Font License 1.1
 * (`assets/licences/caveat-ofl.txt`, and the credits). The smallest file of it: the repository has
 * no static Medium, and the variable font is larger by a third.
 */
internal val Caveat: FontFamily = FontFamily(Font(R.font.caveat_regular))

/**
 * The guide's picture (#627): Home while a track plays, drawn small from Home's own parts with a
 * fixed example track ([MiniHome]), and on its right a note in a hand-written font ([Caveat]) for
 * each [GuidePart], with a drawn arrow to it ([guideLayout]). Not interactive; a screen reader reads
 * it as one image, *Home while a track plays:* and then the notes, in order.
 *
 * The notes follow the font size, and the picture narrows as it grows, so the notes keep room; the
 * picture itself is drawn at the default font size, so it stays a picture of Home.
 */
@Composable
internal fun GuidePicture(modifier: Modifier = Modifier) {
    val notes = GuidePart.entries.map { stringResource(it.note) }
    val described =
        stringResource(
            R.string.home_guide_picture,
            notes.joinToString(stringResource(R.string.home_guide_note_separator)),
        )
    // Where each part is in the picture, as last laid out: its bounds in the picture's own box.
    val parts = remember { mutableStateMapOf<GuidePart, Rect>() }
    val picture = remember { PictureBox() }
    // The arrows, as the last layout placed the notes: read only where they are drawn.
    val arrows = remember { mutableStateOf<List<GuideArrow>>(emptyList()) }
    val pen = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val hand = TextStyle(fontFamily = Caveat, fontSize = NOTE_SIZE, lineHeight = 1.05.em, color = pen)
    val report: (GuidePart, LayoutCoordinates) -> Unit = { part, coordinates ->
        val box = picture.coordinates
        if (box != null && box.isAttached && coordinates.isAttached) {
            val bounds = box.localBoundingBoxOf(coordinates)
            if (parts[part] != bounds) parts[part] = bounds
        }
    }
    Layout(
        content = {
            MiniHome(Modifier.onPlaced { picture.coordinates = it }, report)
            for (note in notes) Text(note, style = hand)
        },
        modifier =
            modifier
                .testTag(GUIDE_PICTURE_TAG)
                .clearAndSetSemantics { contentDescription = described }
                .drawWithContent {
                    drawContent()
                    val stroke = Stroke(width = PEN.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    for (arrow in arrows.value) drawArrow(arrow, pen, stroke)
                },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val metrics = GuideMetrics.of(density)
        val pictureWidth = metrics.pictureWidth(width)
        val mini = measurables[0].measure(Constraints.fixedWidth(pictureWidth))
        val noteWidth = (width - pictureWidth - metrics.gap).toInt().coerceAtLeast(0)
        val placeables = measurables.drop(1).map { it.measure(Constraints(maxWidth = noteWidth)) }
        val found = GuidePart.entries.mapNotNull { part -> parts[part]?.let { part to it } }
        if (found.size < GuidePart.entries.size) {
            // The parts are found once the picture is placed: the notes come a frame later.
            layout(width, mini.height) { mini.place(0, 0) }
        } else {
            val shown =
                guideLayout(found.toMap(), placeables.map { it.height.toFloat() }, mini.width, mini.height, metrics)
            layout(width, shown.height.roundToInt()) {
                mini.place(0, 0)
                shown.notes.forEachIndexed { i, at -> placeables[i].place(at.x.roundToInt(), at.y.roundToInt()) }
                if (arrows.value != shown.arrows) arrows.value = shown.arrows
            }
        }
    }
}

/** The picture's own box, as last placed, which the parts' bounds are measured in. */
private class PictureBox {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Home while a track plays, as the guide shows it (#627): Home's parts at Home's width, scaled down
 * to the width it is given, at the default font size whatever the phone's, with a fixed example
 * track. The header's logo and the key data are Home's own ([HeaderLogo], [KeyData]); the rest are
 * plain copies of Home's parts, since Home's own need a run's state, take taps and carry their own
 * semantics: the player row, a fixed cover (beige, with no blue, so the pen's arrows stand out), the
 * position, the genre chips, *Key insights* with its citation, the Write button and *Sources* with
 * two lines. Each part the notes name tells [report] where it is.
 */
@Composable
private fun MiniHome(
    modifier: Modifier,
    report: (GuidePart, LayoutCoordinates) -> Unit,
) {
    val density = LocalDensity.current
    val at = { part: GuidePart -> Modifier.onGloballyPositioned { report(part, it) } }
    val frame = RoundedCornerShape(PICTURE_CORNER)
    Box(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, frame)
            .clip(frame)
            .background(MaterialTheme.colorScheme.surface)
            .layout { measurable, constraints ->
                val inner = measurable.measure(Constraints.fixedWidth(HOME_WIDTH.roundToPx()))
                val scale = constraints.maxWidth.toFloat() / inner.width
                layout(constraints.maxWidth, (inner.height * scale).roundToInt()) {
                    inner.placeWithLayer(0, 0) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            },
    ) {
        // The default font size, and no logo intro to report to: a picture, not Home's header.
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, 1f),
            LocalLogoIntro provides null,
        ) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MiniHeader(at(GuidePart.SETTINGS))
                MiniPlayer(at(GuidePart.PLAYER))
                MiniCover()
                KeyData(EXAMPLE_NORMALIZED, EXAMPLE_TRACK, HeaderYears(album = EXAMPLE_YEAR))
                MiniChips(at(GuidePart.GENRES))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_insight_title), style = MaterialTheme.typography.titleMedium)
                    MiniInsight(at(GuidePart.INSIGHT), at(GuidePart.CITE))
                    MiniWrite(at(GuidePart.WRITE))
                }
                MiniSources(at(GuidePart.SOURCES))
            }
        }
    }
}

// Home's header: the logo, Home's own, and the cogwheel ([cogwheel] where it is).
@Composable
private fun MiniHeader(cogwheel: Modifier) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderLogo()
        Icon(
            painterResource(R.drawable.ic_settings),
            contentDescription = null,
            modifier = Modifier.padding(12.dp).then(cogwheel),
        )
    }
}

// *Sources*, its heading and two lines, the whole block where [modifier] says.
@Composable
private fun MiniSources(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.home_sources_title), style = MaterialTheme.typography.titleSmall)
        val lines = listOf(R.string.home_guide_example_source_wikipedia, R.string.home_guide_example_source_musicbrainz)
        for (line in lines) {
            Text(stringResource(line), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// The player row, as [PlayerRow] draws it: the player's name and state, and the open-in-new icon.
@Composable
private fun MiniPlayer(modifier: Modifier) {
    Row(
        modifier.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.home_player_state, EXAMPLE_PLAYER, stringResource(R.string.home_state_playing)),
            style = MaterialTheme.typography.titleSmall,
        )
        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

// The cover and the position under it, as [ArtAndProgress] draws them, with a fixed cover.
@Composable
private fun MiniCover() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            drawRect(COVER_GROUND)
            val stripe = size.width / COVER_STRIPES
            val top = size.height * BAND_TOP
            val height = size.height * (BAND_BOTTOM - BAND_TOP)
            for (i in 0 until COVER_STRIPES) {
                drawRect(
                    COVER_BAND[i % COVER_BAND.size],
                    topLeft = Offset(i * stripe, top),
                    size = Size(stripe, height),
                )
            }
            val unit = size.width / COVER_UNITS
            drawRect(
                COVER_TITLE,
                topLeft = Offset(TITLE_LEFT * unit, TITLE_TOP * unit),
                size = Size(TITLE_WIDTH * unit, TITLE_HEIGHT * unit),
            )
        }
        LinearProgressIndicator(progress = { EXAMPLE_PROGRESS }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (time in listOf(EXAMPLE_POSITION, EXAMPLE_LENGTH)) {
                Text(
                    time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// The genre chips, as Home's look ([chipColours]), at their touch target's height.
@Composable
private fun MiniChips(modifier: Modifier) {
    val (fill, content) = chipColours(marked = false)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (genre in EXAMPLE_GENRES) {
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Text(
                    genre,
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(fill)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// The insight, with its citation at the end, a chip in the text.
@Composable
private fun MiniInsight(
    modifier: Modifier,
    cite: Modifier,
) {
    val text =
        buildAnnotatedString {
            append(stringResource(R.string.home_guide_example_insight))
            append(" ")
            appendInlineContent(CITE_ID, EXAMPLE_CITE)
        }
    val chip =
        InlineTextContent(Placeholder(2.4.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
            Box(
                cite
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    EXAMPLE_CITE,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    Text(text, modifier, style = MaterialTheme.typography.bodyLarge, inlineContent = mapOf(CITE_ID to chip))
}

// The Write button, as [WriteSplitButton] draws it while it offers a Write: filled, in two halves.
@Composable
private fun MiniWrite(modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(GAP)) {
        val colours = MaterialTheme.colorScheme
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(OUTER_CORNER, INNER_CORNER, INNER_CORNER, OUTER_CORNER))
                .background(colours.primary)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.home_write),
                style = MaterialTheme.typography.labelLarge,
                color = colours.onPrimary,
            )
        }
        Box(
            Modifier
                .size(48.dp, 40.dp)
                .clip(RoundedCornerShape(INNER_CORNER, OUTER_CORNER, OUTER_CORNER, INNER_CORNER))
                .background(colours.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_keyboard_arrow_down),
                contentDescription = null,
                tint = colours.onPrimary,
            )
        }
    }
}

/** The test tag of the guide's picture. */
const val GUIDE_PICTURE_TAG: String = "home-guide-picture"

/** Home's width in the picture, before it is scaled down: about a phone's. */
private val HOME_WIDTH = 360.dp

private val PICTURE_CORNER = 14.dp
private val PEN = 1.5.dp
private val NOTE_SIZE = 18.sp

// The example track.
private const val EXAMPLE_PLAYER = "Spotify"
private const val EXAMPLE_TITLE = "Blue in Green"
private const val EXAMPLE_ARTIST = "Miles Davis"
private const val EXAMPLE_ALBUM = "Kind of Blue"
private const val EXAMPLE_YEAR = "1959"
private const val EXAMPLE_POSITION = "2:14"
private const val EXAMPLE_LENGTH = "5:37"
private const val EXAMPLE_PROGRESS = 0.4f
private const val EXAMPLE_CITE = "W1"
private const val CITE_ID = "cite"
private val EXAMPLE_GENRES = listOf("Modal jazz", "Cool jazz")
private val EXAMPLE_TRACK = NowPlaying(EXAMPLE_TITLE, EXAMPLE_ARTIST, EXAMPLE_ALBUM, player = EXAMPLE_PLAYER)
private val EXAMPLE_NORMALIZED =
    NormalizedTrack(EXAMPLE_TITLE, listOf(EXAMPLE_ARTIST), EXAMPLE_ARTIST, null, null, emptyList(), "")

// The cover: fixed colours, not the theme's, with no blue, so the pen's arrows stand out.
@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val COVER_GROUND = Color(0xFFE4D8BD)

@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val COVER_BAND = listOf(Color(0xFFB5653F), Color(0xFF8A8B55), Color(0xFFD4A24C))

@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val COVER_TITLE = Color(0xFF6F6556)
private const val COVER_STRIPES = 15
private const val BAND_TOP = 0.58f
private const val BAND_BOTTOM = 0.80f

// The title bar, in the mock-up's units, the cover 328 wide.
private const val COVER_UNITS = 328f
private const val TITLE_LEFT = 18f
private const val TITLE_TOP = 20f
private const val TITLE_WIDTH = 150f
private const val TITLE_HEIGHT = 26f
