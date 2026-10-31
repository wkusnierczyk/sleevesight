package app.sleevesight.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.sleevesight.app.R
import app.sleevesight.app.ui.logo.HeaderLogo
import app.sleevesight.app.ui.logo.LocalLogoIntro
import app.sleevesight.app.ui.theme.ChipFrame
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shantell Sans (#672), the notes' hand-drawn font, in place of Caveat (#627), whose small, narrow
 * letters were hard to read on a phone; bundled in `res/font`: Shantell Sans Normal Regular, the
 * static build in `arrowtype/shantell-sans` (`fonts/Shantell Sans/Desktop/Static/TTF/
 * Shantell_Sans-Normal-Regular.ttf`, at its release 1.011, commit `68f5eca`, the one `google/fonts`
 * takes Shantell Sans from), under the SIL Open Font License 1.1
 * (`assets/licences/shantell-sans-ofl.txt`, and the credits). *Normal*, without the bounce and the
 * informal letters, is the plainest to read and the smallest file; the variable font is six times
 * larger.
 */
internal val ShantellSans: FontFamily = FontFamily(Font(R.font.shantell_sans_regular))

/**
 * The guide's picture (#627): Home while a track plays, drawn small from Home's own parts with a
 * fixed example track ([MiniHome]), and on its right a note in a hand-drawn font ([ShantellSans]) for
 * each [GuidePart], with a drawn arrow to it ([guideLayout]).
 *
 * Two ways to see more (#673): a tap on the picture, or on the magnifier on its cover's corner
 * ([GuideMagnifierButton]), opens it full screen ([GuideMagnified]); and each note is a button
 * ([GuideNote]) whose tap outlines the part it points at and lays a panel about it over the
 * picture's phone screen ([GuideNotePanel], placed by [panelTop]), which a second tap on the note,
 * or the panel's *Close*, closes, *Close* handing the focus back to the note. A screen reader
 * reads each note as a button and the panel as it opens, the panel right after its note; the drawn
 * Home itself is not read, and its magnifier is the way to it.
 *
 * The notes follow the font size, and the picture narrows as it grows, so the notes keep room; the
 * picture itself is drawn at the default font size, so it stays a picture of Home.
 */
@Composable
internal fun GuidePicture(modifier: Modifier = Modifier) {
    var magnified by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<GuidePart?>(null) }
    val handoff = rememberFocusHandoff()
    val notes = remember { GuidePart.entries.map { FocusRequester() } }
    val picture = remember { PictureBox() }
    val parts = picture.parts
    val pen = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val hand = TextStyle(fontFamily = ShantellSans, fontSize = NOTE_SIZE, lineHeight = 1.05.em, color = pen)
    Layout(
        content = {
            MiniHome(
                Modifier
                    .layoutId(GuideSlot.PICTURE)
                    .onPlaced { picture.coordinates = it }
                    .testTag(GUIDE_DRAWN_TAG)
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) { detectTapGestures { magnified = true } },
                picture::report,
                picture::reportCover,
            )
            for (part in GuidePart.entries) GuideNoteText(part, hand, Modifier.layoutId(GuideNoteSlot(part)))
            for (part in GuidePart.entries) {
                GuideNote(
                    part,
                    Modifier
                        .layoutId(part)
                        .semantics { traversalIndex = part.ordinal.toFloat() }
                        .handoffTarget(handoff, notes[part.ordinal]),
                ) { open = if (open == part) null else part }
            }
            GuideMarks(picture.arrows, { open?.let { parts[it] } }, pen, Modifier.layoutId(GuideSlot.MARKS))
            GuideMagnifierButton(
                Modifier.layoutId(GuideSlot.MAGNIFIER).semantics { traversalIndex = -1f },
            ) { magnified = true }
            open?.let { part ->
                GuideNotePanel(
                    part,
                    // Read right after its note.
                    Modifier.layoutId(GuideSlot.PANEL).semantics { traversalIndex = part.ordinal + PANEL_AFTER_NOTE },
                ) {
                    open = null
                    handoff.moveTo(notes[part.ordinal])
                }
            }
        },
        modifier = modifier.testTag(GUIDE_PICTURE_TAG).semantics { isTraversalGroup = true },
    ) { measurables, constraints ->
        measureGuide(measurables, constraints, GuideMetrics.of(density), picture, open)
    }
    // The full screen's zoom, kept across a rotation, and afresh each time it opens.
    val zoom = rememberSaveable(magnified, saver = GuideZoom.Saver) { GuideZoom() }
    if (magnified) GuideMagnified(onClose = { magnified = false }, zoom = zoom)
}

/** The guide's children other than the notes, whose buttons go by their [GuidePart]. */
private enum class GuideSlot { PICTURE, MARKS, MAGNIFIER, PANEL }

/** The text of the note on [part], under its button. */
private data class GuideNoteSlot(
    val part: GuidePart,
)

/**
 * The guide laid out (#627, #673): the picture on the left, the notes on its right as [guideLayout]
 * places them, the pen's marks over both, the magnifier at the drawn cover's bottom right corner,
 * and the panel of the [open] note where [panelTop] puts it, the guide growing to hold it.
 */
private fun MeasureScope.measureGuide(
    measurables: List<Measurable>,
    constraints: Constraints,
    metrics: GuideMetrics,
    picture: PictureBox,
    open: GuidePart?,
): MeasureResult {
    val parts = picture.parts

    fun slot(id: Any) = measurables.firstOrNull { it.layoutId == id }
    val width = constraints.maxWidth
    val pictureWidth = metrics.pictureWidth(width)
    val mini = slot(GuideSlot.PICTURE)!!.measure(Constraints.fixedWidth(pictureWidth))
    val found = GuidePart.entries.mapNotNull { part -> parts[part]?.let { part to it } }
    if (found.size < GuidePart.entries.size) {
        // The parts are found once the picture is placed: the notes come a frame later.
        return layout(width, mini.height) { mini.place(0, 0) }
    }
    val noteWidth = (width - pictureWidth - metrics.gap).toInt().coerceAtLeast(0)
    val notes = GuidePart.entries.map { slot(GuideNoteSlot(it))!!.measure(Constraints(maxWidth = noteWidth)) }
    val shown = guideLayout(found.toMap(), notes.map { it.height.toFloat() }, mini.width, mini.height, metrics)
    // Each note's button over its text: as high as its target, as wide as the text, at least 48 dp.
    val buttons =
        GuidePart.entries.map { part ->
            val target = shown.targets[part.ordinal]
            val x = shown.notes[part.ordinal].x.roundToInt()
            val wide = max(notes[part.ordinal].width, metrics.target.roundToInt()).coerceAtMost(width - x)
            val high = target.endInclusive.roundToInt() - target.start.roundToInt()
            slot(part)!!.measure(Constraints.fixed(wide.coerceAtLeast(0), high.coerceAtLeast(0)))
        }
    val magnifier = slot(GuideSlot.MAGNIFIER)!!.measure(Constraints())
    val panel =
        open?.let { part ->
            val measurable = slot(GuideSlot.PANEL) ?: return@let null
            val note = shown.targets[part.ordinal]
            val rect = parts.getValue(part)
            // The part and its note's button, which the panel leaves in sight and to be tapped.
            val band = min(rect.top, note.start)..max(rect.bottom, note.endInclusive)
            // As wide as [GuideMetrics.panelWidth] says, or as the guide where that fits in the
            // picture neither under nor over them: wider, so shorter.
            val narrow = metrics.panelWidth(width)
            val fits = panelFits(band, measurable.maxIntrinsicHeight(narrow).toFloat(), mini.height, metrics.panelGap)
            val placeable = measurable.measure(Constraints.fixedWidth(if (fits) narrow else width))
            placeable to panelTop(band, placeable.height.toFloat(), mini.height, metrics.panelGap).roundToInt()
        }
    val height = max(shown.height, panel?.let { (it, top) -> (top + it.height).toFloat() } ?: 0f).roundToInt()
    val marks = slot(GuideSlot.MARKS)!!.measure(Constraints.fixed(width, height))
    val inset = (metrics.unit * MAGNIFIER_INSET).roundToInt()
    // At the cover's bottom right corner, clear of the arrows, which cross the picture's own corners.
    val corner = picture.cover.value ?: Rect(0f, 0f, pictureWidth.toFloat(), mini.height.toFloat())
    return layout(width, height) {
        mini.place(0, 0)
        shown.notes.forEachIndexed { i, at -> notes[i].place(at.x.roundToInt(), at.y.roundToInt()) }
        marks.place(0, 0)
        buttons.forEachIndexed { i, button ->
            button.place(shown.notes[i].x.roundToInt(), shown.targets[i].start.roundToInt())
        }
        magnifier.place(
            corner.right.roundToInt() - magnifier.width - inset,
            corner.bottom.roundToInt() - magnifier.height - inset,
        )
        panel?.let { (it, top) -> it.place(0, top) }
        if (picture.arrows.value != shown.arrows) picture.arrows.value = shown.arrows
    }
}

/**
 * The picture's own box, as last placed, which the parts' bounds are measured in; where each part is
 * in it, as last laid out ([parts]), and where the drawn cover is ([cover]), which the magnifier sits
 * on; and the [arrows], as the last layout placed the notes, read only where they are drawn.
 */
private class PictureBox {
    var coordinates: LayoutCoordinates? = null
    val parts = mutableStateMapOf<GuidePart, Rect>()
    val arrows = mutableStateOf<List<GuideArrow>>(emptyList())
    val cover = mutableStateOf<Rect?>(null)

    /** Where [part] is, from its [coordinates], in the picture's box. */
    fun report(
        part: GuidePart,
        coordinates: LayoutCoordinates,
    ) {
        bounds(coordinates)?.let { if (parts[part] != it) parts[part] = it }
    }

    /** Where the cover is, from its [coordinates], in the picture's box. */
    fun reportCover(coordinates: LayoutCoordinates) {
        bounds(coordinates)?.let { if (cover.value != it) cover.value = it }
    }

    private fun bounds(of: LayoutCoordinates): Rect? {
        val box = coordinates
        return if (box != null && box.isAttached && of.isAttached) box.localBoundingBoxOf(of) else null
    }
}

/**
 * Home while a track plays, as the guide shows it (#627): Home's parts at Home's width, scaled down
 * to the width it is given, at the default font size whatever the phone's, with a fixed example
 * track. The header's logo and the key data are Home's own ([HeaderLogo], [KeyData]); the rest are
 * plain copies of Home's parts, since Home's own need a run's state, take taps and carry their own
 * semantics: the player row, a fixed cover (beige, with no blue, so the pen's arrows stand out), the
 * position, *Key insights* with its citation and the Write button; the *Dates* and *Genres* buttons
 * (#724, #772) and *Sources*' chip are Home's own [ChipFrame], without a click. Each part the notes
 * name tells [report] where it is, and the cover tells [cover].
 * Left to right whatever the phone's language: a picture of Home as the notes, on its right, name it.
 */
@Composable
internal fun MiniHome(
    modifier: Modifier,
    report: (GuidePart, LayoutCoordinates) -> Unit,
    cover: (LayoutCoordinates) -> Unit = {},
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
        // The default font size, no logo intro to report to, and left to right, as the notes are
        // placed: a picture, not Home's header.
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, 1f),
            LocalLogoIntro provides null,
            LocalLayoutDirection provides LayoutDirection.Ltr,
        ) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MiniHeader(at(GuidePart.SETTINGS))
                MiniPlayer(at(GuidePart.PLAYER))
                MiniCover(Modifier.onGloballyPositioned(cover))
                KeyData(EXAMPLE_NORMALIZED, EXAMPLE_TRACK, HeaderYears(album = EXAMPLE_YEAR))
                MiniLine(at(GuidePart.GENRES))
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

// *Sources*, folded, as Home shows it (#623): its chip, drawn by Home's own [ChipFrame], [modifier]
// on the chip as drawn.
@Composable
private fun MiniSources(modifier: Modifier) {
    val (fill, content) = chipColours(marked = false)
    // Where the chip is drawn, inside its touch target, so the note's arrow ends at the chip itself.
    ChipFrame(fill, action = modifier) {
        Text(
            stringResource(R.string.home_sources_title),
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
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
private fun MiniCover(cover: Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            cover
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

// The line under the album, closed, as Home shows it (#724, #772): *Dates* and *Genres*, drawn by Home's
// own [ChipFrame], not buttons here; [genres] on *Genres*' as drawn, where the genres are now.
@Composable
private fun MiniLine(genres: Modifier) {
    val (fill, content) = chipColours(marked = false)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, at) in listOf(R.string.home_dates to Modifier, R.string.home_genres to genres)) {
            ChipFrame(fill, action = at) {
                Text(stringResource(name), style = MaterialTheme.typography.labelLarge, color = content)
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
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(SPLIT_GAP)) {
        val colours = MaterialTheme.colorScheme
        val leading = RoundedCornerShape(SPLIT_OUTER_CORNER, SPLIT_INNER_CORNER, SPLIT_INNER_CORNER, SPLIT_OUTER_CORNER)
        val trailing =
            RoundedCornerShape(SPLIT_INNER_CORNER, SPLIT_OUTER_CORNER, SPLIT_OUTER_CORNER, SPLIT_INNER_CORNER)
        Box(
            Modifier
                .heightIn(min = SPLIT_HEIGHT)
                .clip(leading)
                .background(colours.primary)
                .padding(SPLIT_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            // With the **AI** mark, as Home's Write has it (#624).
            CompositionLocalProvider(LocalContentColor provides colours.onPrimary) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) { AiLabel(stringResource(R.string.home_write)) }
            }
        }
        Box(
            Modifier
                .size(SPLIT_TARGET, SPLIT_HEIGHT)
                .clip(trailing)
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

/** The test tag of the drawn Home in it, which is not read. */
const val GUIDE_DRAWN_TAG: String = "home-guide-drawn"

/** Home's width in the picture, before it is scaled down: about a phone's. */
private val HOME_WIDTH = 360.dp

private val PICTURE_CORNER = 14.dp
private val NOTE_SIZE = 18.sp

/** Where a note's panel comes in a screen reader's order: right after its note. */
private const val PANEL_AFTER_NOTE = 0.5f

/** The magnifier's distance from the picture's bottom right corner, in dp. */
private const val MAGNIFIER_INSET = 2f

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
