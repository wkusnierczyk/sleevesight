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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.pipeline.TrackState

// A genre's body (#558, #786): what it is and why the track has it, then its tags and where they come
// from, in one grid; in a panel of its own, titled with its name, on the genres' page.

/**
 * The panel of [genre] on the genres' page under *Separate pages* (#558, #772): its name as a heading,
 * with no *Close* (the page's back arrow closes it), over its body ([GenreBody]). Nothing when no
 * row has [genre].
 */
@Composable
internal fun GenrePanel(
    track: TrackState,
    genre: String,
    notes: PanelNotes = PanelNotes(),
    search: PanelSearch = PanelSearch(),
    afterRetry: () -> Unit = {},
) {
    val name =
        track.genres.all
            .firstOrNull { it.genre.identity == genre }
            ?.genre
            ?.label ?: return
    Panel(AnnotatedString(name), GENRE_PANEL_TAG, onClose = null) {
        GenreBody(track, genre, notes, search, afterRetry)
    }
}

/**
 * What [genre] shows for [track] (#558, #786; `design/design.md`, *Genre labels*): what the genre is
 * and *Why is this …?*, from [notes], with *Search with AI* for a definition Wikipedia does not give
 * ([search]; [GenreAbout], whose failed lookup's *Retry* hands the focus to [afterRetry]); then its
 * tags and their sources ([TagsGrid]). Unfolded under *Genres*' chips on Home ([GenresBox]), with no
 * heading of its own; in a panel titled with its name on the genres' page ([GenrePanel]). [modifier]
 * goes on its column (a test tag). Nothing when no row has [genre].
 */
@Suppress("LongParameterList") // What the body shows, where its notes and search come from, and the focus.
@Composable
internal fun GenreBody(
    track: TrackState,
    genre: String,
    notes: PanelNotes,
    search: PanelSearch,
    afterRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (track.genres.all.none { it.genre.identity == genre }) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // What the genre is, and *Why is this …?* (#523), above the tags.
        GenreAbout(track, genre, notes, search, afterRetry)
        TagsGrid(track, genre)
    }
}

/**
 * What a genre's body shows above its tags ([GenreAbout]): [notes], where its definition and
 * reason come from; where a citation in the reason goes ([onCite]: on Home, *Sources* unfolded to
 * its line; under *Separate pages*, *Sources*' page at it); whether the model's own knowledge is
 * allowed now ([modelAllowed]); and what reads a written reason aloud ([readAloud], #325), null for
 * no *Read aloud*.
 */
internal class PanelNotes(
    val notes: GenreNotes = GenreNotes.None,
    val onCite: (String) -> Unit = {},
    val modelAllowed: Boolean = false,
    val readAloud: ReadAloud? = null,
)

/**
 * [track]'s genre tags and where they come from (#786), in one grid of two columns, so its first
 * column lines up across both parts: "Genre tags", a heading, then a row per level with tags, in
 * [GenreLevel] order, its name on the left, right-aligned (*Track*, *Artist*, *Album*; with several
 * credited artists *Artist 1*, *Artist 2*, … rather than their names), its tags as chips on the
 * right, [genre] marked wherever it shows ([Cell]); then "Tag sources", a heading, and the same rows,
 * each with the sources that gave any of its tags, each named and linked to the row's page there
 * ([sourceParts]), an artist's name on its own line above them with several artists; then, in the
 * right column, once, when MusicBrainz gave any tag, its credit and its licence, linked (D12).
 *
 * The first column is as wide as its widest name, measured in the names' style at the current font
 * size, so it holds at large font sizes. Each row is one item to a screen reader, its name then its
 * chips or its sources ("Track, Rock, Pop"; the chips are not buttons); the links in a sources' row
 * stay links.
 */
@Composable
private fun TagsGrid(
    track: TrackState,
    genre: String,
) {
    val rows = track.genres.rows
    val several = track.normalized.artists.size > 1
    val names = rowNames(rows, several)
    val nameStyle = MaterialTheme.typography.bodySmall
    val column = columnWidth(names, nameStyle)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.testTag(GENRE_TAGS_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GridHeading(stringResource(R.string.home_genre_tags))
        rows.forEachIndexed { index, row ->
            GridRow(names[index], column, nameStyle, Modifier.testTag(GENRES_ROW_PREFIX + index), CHIP_INSET) {
                Cells(row, genre)
            }
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

// Each row's name: *Track*, *Album*, *Artist*, or with [several] credited artists *Artist 1*, *Artist
// 2*, … in the rows' order.
@Composable
private fun rowNames(
    rows: List<GenreRow>,
    several: Boolean,
): List<String> {
    var artists = 0
    return rows.map { row ->
        when (row.level) {
            GenreLevel.TRACK -> stringResource(R.string.home_genres_track)
            GenreLevel.ALBUM -> stringResource(R.string.home_genres_album)
            GenreLevel.ARTIST ->
                if (several) {
                    stringResource(R.string.home_genre_artist_n, ++artists)
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

// A label in the grid: a smaller chip, not a button, in the chips' fills; [marked], the panel's genre.
@Composable
private fun Cell(
    placed: PlacedLabel,
    marked: Boolean,
) {
    val shape = RoundedCornerShape(14.dp)
    val (fill, content) = chipColours(marked)
    Text(
        placed.label.genre.label,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier =
            Modifier
                .testTag(tagOf(GENRE_CELL_PREFIX, placed))
                .clip(shape)
                .background(fill, shape)
                .let { if (marked) it.semantics { selected = true } else it }
                .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

// Each source that gave any of [row]'s labels, once, in [LabelSource] order, named as the settings
// name it ("Last.fm", "MusicBrainz") and linked to the row's page there where it has one, " · " between.
@Composable
private fun sourceParts(row: GenreRow): List<TextPart> {
    val credits = row.labels.flatMap { it.credits }
    return LabelSource.entries
        .mapNotNull { source ->
            val given = credits.filter { it.source == source }
            if (given.isEmpty()) return@mapNotNull null
            val name = sourceName(source.source)
            val url = given.firstNotNullOfOrNull { it.url }
            listOf(url?.let { TextPart.Link(name, it) } ?: TextPart.Words(name))
        }.reduceOrNull { all, next -> all + TextPart.Words(SEPARATOR) + next }
        .orEmpty()
}

/** The test tags of the genre's panel on the genres' page and its heading, as [Panel] makes them. */
const val GENRE_PANEL_TAG: String = "genre-panel"
const val GENRE_PANEL_HEADING_TAG: String = "genre-panel:heading"

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
private val COLUMN_GAP = 12.dp

// How far a row's name sits below the top of its chips: their vertical padding, so the two read level.
private val CHIP_INSET = 5.dp
