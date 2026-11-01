package app.sleevesight.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.pipeline.TrackState

// A genre's tags and where they come from (#786): one grid of two columns, its first column shared.

/**
 * [track]'s genre tags and where they come from (#786), in one grid of two columns, so its first
 * column lines up across both parts: "Genre tags", a heading, then a row per level with tags, in
 * [GenreLevel] order, its name on the left, right-aligned (*Track*, *Artist*, *Album*; with several
 * credited artists *Artist 1*, *Artist 2*, … by their place in the credit, rather than their names),
 * its tags as chips on the right, [genre] marked wherever it shows ([Cell]); then "Tag sources", a
 * heading, and the same rows, each with the sources that gave any of its tags, each named and linked
 * to the row's page there ([sourceParts]), an artist's name on its own line above them with several
 * artists; then, in the right column, once, when MusicBrainz gave any tag, its credit and its
 * licence, linked (D12).
 *
 * The first column is as wide as its widest name, measured in the names' style at the current font
 * size, so it holds at large font sizes. Each row is one item to a screen reader. A row of tags has
 * one reading, its name then its tags, [genre]'s marked as selected ("Track, Rock, selected, Pop":
 * the chips are not buttons), and no selected state of its own, so it says which tag is marked
 * ([tagsReading]); a row of sources reads its name and sources, each source's link still a link of
 * its own, which a screen reader reaches and opens.
 */
@Composable
internal fun TagsGrid(
    track: TrackState,
    genre: String,
) {
    val rows = track.genres.rows
    val credit = track.normalized.artists
    val several = credit.size > 1
    val names = rowNames(rows, several, credit)
    val nameStyle = MaterialTheme.typography.bodySmall
    val column = columnWidth(names, nameStyle)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.testTag(GENRE_TAGS_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GridHeading(stringResource(R.string.home_genre_tags))
        val selected = stringResource(R.string.home_genre_tag_selected)
        val added = stringResource(R.string.genre_ai_added)
        rows.forEachIndexed { index, row ->
            val reading = tagsReading(names[index], row, genre, selected, added)
            val tagged = Modifier.testTag(GENRES_ROW_PREFIX + index).semantics { contentDescription = reading }
            GridRow(names[index], column, nameStyle, tagged, CHIP_INSET) { Cells(row, genre) }
        }
        GridHeading(stringResource(R.string.home_genre_tag_sources))
        rows.forEachIndexed { index, row ->
            val parts = sourceParts(row)
            if (parts.isEmpty()) return@forEachIndexed
            GridRow(names[index], column, nameStyle, Modifier.testTag(GENRE_SOURCES_ROW_PREFIX + index)) {
                Column {
                    row.artist?.takeIf { several && row.level == GenreLevel.ARTIST }?.let { artist ->
                        Text(artist, style = nameStyle, color = MaterialTheme.colorScheme.onSurface)
                    }
                    LinkedText(parts, style = nameStyle, color = muted)
                }
            }
        }
        val musicBrainz = rows.any { row -> row.labels.any { label -> label.credits.any { it.source == MB } } }
        MB.licenceUrl?.takeIf { musicBrainz }?.let { licence ->
            GridRow(null, column, nameStyle, Modifier.testTag(GENRE_TAGS_CREDIT_TAG)) {
                Column {
                    Text(stringResource(R.string.home_genre_tags_credit), style = nameStyle, color = muted)
                    LinkedText(
                        listOf(TextPart.Link(stringResource(R.string.home_genre_tags_licence), licence)),
                        style = nameStyle,
                        color = muted,
                    )
                }
            }
        }
    }
}

/**
 * How a screen reader reads a row of tags (#786): its [name], then its tags in order, [genre]'s as
 * [selected] words it ("Rock, selected"), all one item: "Track, Rock, selected, Pop". A tag the AI's
 * genre list added (#762) reads with the model's name, as [added] words it ("Blues, from Claude
 * Haiku 5.5").
 */
internal fun tagsReading(
    name: String,
    row: GenreRow,
    genre: String,
    selected: String,
    added: String = "%1\$s",
): String =
    (
        listOf(name) +
            row.labels.map { label ->
                val named = label.aiModel?.takeIf { label.aiAdded }?.let { added.format(label.genre.label, it) }
                (named ?: label.genre.label).let { if (label.genre.identity == genre) selected.format(it) else it }
            }
    ).joinToString(", ")

// Each row's name: *Track*, *Album*, *Artist*, or with [several] credited artists *Artist 1*, *Artist
// 2*, … by the artist's place in [credit], as the rows name it, whether or not the artists before it
// have tags; one the credit does not name takes the next number past it.
@Composable
private fun rowNames(
    rows: List<GenreRow>,
    several: Boolean,
    credit: List<String>,
): List<String> {
    var past = credit.size
    return rows.map { row ->
        when (row.level) {
            GenreLevel.TRACK -> stringResource(R.string.home_genres_track)
            GenreLevel.ALBUM -> stringResource(R.string.home_genres_album)
            GenreLevel.ARTIST ->
                if (several) {
                    val place = credit.indexOf(row.artist) + 1
                    stringResource(R.string.home_genre_artist_n, if (place > 0) place else ++past)
                } else {
                    stringResource(R.string.home_genres_artist_row)
                }
        }
    }
}

// The width of the widest of [names] in [style], as the current font size sets it.
@Composable
private fun columnWidth(
    names: List<String>,
    style: TextStyle,
): Dp {
    val measurer = rememberTextMeasurer()
    val widest = names.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
    // A pixel to spare, so a name measured to the pixel's edge never wraps.
    return with(LocalDensity.current) { (widest + 1).toDp() }
}

// A heading of the grid, across both columns: bold, a heading to a screen reader.
@Composable
private fun GridHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * A row of the grid: [name] (none for the credit under the sources) on the left, right-aligned in a
 * column [width] wide, [inset] from the top to sit level with the first line on the right; [content]
 * on the right, left-aligned, in the rest. One item to a screen reader.
 */
@Suppress("LongParameterList") // The row's name, its column, its style and inset, and what it holds.
@Composable
private fun GridRow(
    name: String?,
    width: Dp,
    style: TextStyle,
    modifier: Modifier,
    inset: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Row(
        modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(width).padding(top = inset), contentAlignment = Alignment.TopEnd) {
            if (name != null) {
                Text(
                    name,
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

// A row's tags as chips, [genre] marked.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Cells(
    row: GenreRow,
    genre: String,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (label in row.labels) Cell(PlacedLabel(row, label), marked = label.genre.identity == genre)
    }
}

// The style of the **AI** mark after a tag the AI's genre list added, or after the model among the
// sources (#762), as on the buttons that call the AI service ([AiLabel]): [small]'s size, at
// [MARK_ALPHA] of [ink], on the line's baseline.
private fun markStyle(
    ink: Color,
    small: TextStyle,
): SpanStyle = SpanStyle(fontSize = small.fontSize, color = ink.copy(alpha = ink.alpha * MARK_ALPHA))

// A label in the grid: a smaller chip, not a button, in the chips' fills; [marked], the panel's genre,
// which its row's reading names as selected ([tagsReading]). One the AI's genre list added (#762)
// carries the **AI** mark after its name, as its chip does.
@Composable
private fun Cell(
    placed: PlacedLabel,
    marked: Boolean,
) {
    val shape = RoundedCornerShape(14.dp)
    val (fill, content) = chipColours(marked)
    val mark = stringResource(R.string.home_ai_mark)
    val markStyle = markStyle(content, MaterialTheme.typography.labelSmall)
    val text =
        buildAnnotatedString {
            append(placed.label.genre.label)
            if (placed.label.aiAdded) {
                append(MARK_SPACE)
                withStyle(markStyle) { append(mark) }
            }
        }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier =
            Modifier
                .testTag(tagOf(GENRE_CELL_PREFIX, placed))
                .clip(shape)
                .background(fill, shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

// Each source that gave any of [row]'s labels, once, in [LabelSource] order, named as the settings
// name it ("Last.fm", "MusicBrainz") and linked to the row's page there where it has one, " · " between;
// then the model whose genre list gave any (#762), by name, not linked, marked **AI** ([markStyle]).
@Composable
private fun sourceParts(row: GenreRow): List<TextPart> {
    val credits = row.labels.flatMap { it.credits }
    val model = row.labels.firstNotNullOfOrNull { it.aiModel }
    val mark = stringResource(R.string.home_ai_mark)
    val markStyle = markStyle(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.typography.labelSmall)
    val sources =
        LabelSource.entries.mapNotNull { source ->
            val given = credits.filter { it.source == source }
            if (given.isEmpty()) return@mapNotNull null
            val name = sourceName(source.source)
            val url = given.firstNotNullOfOrNull { it.url }
            listOf(url?.let { TextPart.Link(name, it) } ?: TextPart.Words(name))
        }
    val ai = model?.let { listOf(TextPart.Words(it + MARK_SPACE), TextPart.Styled(mark, markStyle)) }
    return (sources + listOfNotNull(ai))
        .reduceOrNull { all, next -> all + TextPart.Words(SEPARATOR) + next }
        .orEmpty()
}

/** The test tag of a genre's tags and sources' grid (#786). */
const val GENRE_TAGS_TAG: String = "genre-tags"

/** The test tag of a row of tags in the grid, before its index. */
const val GENRES_ROW_PREFIX: String = "genres-row:"

/** The test tag of a row of sources in the grid, before the index of its row of tags (#786). */
const val GENRE_SOURCES_ROW_PREFIX: String = "genre-sources-row:"

/** The test tag of MusicBrainz's credit and licence under the sources (#786). */
const val GENRE_TAGS_CREDIT_TAG: String = "genre-tags:credit"

private val MB = LabelSource.MUSICBRAINZ
private const val SEPARATOR = " · "

// Between a name and its **AI** mark: a narrow space that does not break, so the mark never wraps alone.
private const val MARK_SPACE = "\u202F"
private val COLUMN_GAP = 12.dp

// How far a row's name sits below the top of its chips: their vertical padding, so the two read level.
private val CHIP_INSET = 5.dp
