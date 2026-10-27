package app.sleevesight.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.pipeline.TrackState

// The genre's panel (#558): its heading, the table, and where the genre comes from.

/**
 * The panel of [genre] (#558; frame B of the agreed mock-ups): its name as a heading, with *Close*
 * when there is [onClose]; then what the genre is and *Why is this …?*, from [notes] (#523,
 * [GenreAbout]); then the table, a row per level in [GenreLevel] order, each row's name
 * (the track's title, the artist's name, the album's title) on one line above its labels, [genre]
 * marked wherever it shows; then where the table's labels come from, a line per row, each source
 * linked to its page there, and MusicBrainz's licence (D12). Nothing when no row has [genre].
 */
@Composable
internal fun GenrePanel(
    track: TrackState,
    genre: String,
    onClose: (() -> Unit)? = null,
    notes: PanelNotes = PanelNotes(),
) {
    val genres = track.genres
    val name =
        genres.all
            .firstOrNull { it.genre.identity == genre }
            ?.genre
            ?.label ?: return
    HomePanel(name, GENRE_PANEL_TAG, onClose) {
        // What the genre is, and *Why is this …?* (#523), above the table.
        GenreAbout(track, genre, notes)
        Table(track, genre)
        Credits(genres, several = track.normalized.artists.size > 1)
    }
}

/**
 * What a genre's panel shows above its table ([GenreAbout]): [notes], where its definition and
 * reason come from; where a citation in the reason goes ([onCite]: on Home, *Sources* unfolded to
 * its line; under *Separate pages*, *Sources*' page at it); and whether the model's own knowledge is
 * allowed now ([modelAllowed]).
 */
internal class PanelNotes(
    val notes: GenreNotes = GenreNotes.None,
    val onCite: (String) -> Unit = {},
    val modelAllowed: Boolean = false,
)

/**
 * The table of [track]'s genres: a row per level with labels, in [GenreLevel] order, each row's
 * name on one line, clipped with an ellipsis when longer (a screen reader reads it whole), above
 * its labels; [genre] marked wherever it shows, which a screen reader says is selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Table(
    track: TrackState,
    genre: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        track.genres.rows.forEachIndexed { index, row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The track's title a little stronger, so the rows read as track, then who, then where.
                val isTrack = row.level == GenreLevel.TRACK
                Text(
                    rowName(track, row),
                    style = if (isTrack) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    fontWeight = if (isTrack) FontWeight.Medium else null,
                    color = MaterialTheme.colorScheme.run { if (isTrack) onSurface else onSurfaceVariant },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(GENRES_ROW_PREFIX + index),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (label in row.labels) Cell(PlacedLabel(row, label), marked = label.genre.identity == genre)
                }
            }
        }
    }
}

// A row's name: the track's title, the artist's name, the album's title; the level's name where the
// player gave none.
@Composable
private fun rowName(
    track: TrackState,
    row: GenreRow,
): String =
    when (row.level) {
        GenreLevel.TRACK ->
            track.normalized.title
                .ifBlank { track.nowPlaying.title }
                .ifBlank { stringResource(R.string.home_genres_track) }
        GenreLevel.ARTIST -> row.artist?.takeIf(String::isNotBlank) ?: stringResource(R.string.home_genres_artist_row)
        GenreLevel.ALBUM ->
            track.nowPlaying.album?.takeIf(String::isNotBlank) ?: stringResource(R.string.home_genres_album)
    }

// A label in the table: a smaller chip, not a button, in the chips' fills; [marked], the panel's genre.
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

/**
 * Where the table's labels come from (D12: each label from MusicBrainz's tags credits it, with its
 * licence): a line for each row, naming the level ("From the track's tags:"; with [several]
 * credited artists, the artist), then each source that gave any of the row's labels, once, linked
 * to the row's page there, and for MusicBrainz its licence.
 */
@Composable
private fun Credits(
    genres: TrackGenres,
    several: Boolean,
) {
    val licence = stringResource(R.string.home_genre_licence)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in genres.rows) {
            val parts = sourceParts(row, licence)
            if (parts.isEmpty()) continue
            val from =
                when (row.level) {
                    GenreLevel.TRACK -> stringResource(R.string.home_genre_from_track)
                    GenreLevel.ALBUM -> stringResource(R.string.home_genre_from_album)
                    GenreLevel.ARTIST ->
                        row.artist?.takeIf { several }?.let { stringResource(R.string.home_genre_from_named, it) }
                            ?: stringResource(R.string.home_genre_from)
                }
            LinkedText(
                listOf(TextPart.Words("$from ")) + parts,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Each source that gave any of [row]'s labels, once, in [LabelSource] order, linked to the row's page
// there where it has one, then its licence, if any.
private fun sourceParts(
    row: GenreRow,
    licence: String,
): List<TextPart> {
    val credits = row.labels.flatMap { it.credits }
    return LabelSource.entries
        .mapNotNull { source ->
            val given = credits.filter { it.source == source }
            if (given.isEmpty()) return@mapNotNull null
            val url = given.firstNotNullOfOrNull { it.url }
            listOf(url?.let { TextPart.Link(source.credit, it) } ?: TextPart.Words(source.credit)) +
                source.licenceUrl
                    ?.let { listOf(TextPart.Words(SEPARATOR), TextPart.Link(licence, it)) }
                    .orEmpty()
        }.reduceOrNull { all, next -> all + TextPart.Words(SEPARATOR) + next }
        .orEmpty()
}

/** The test tags of the genre's panel, its heading and its *Close*, as [HomePanel] makes them. */
const val GENRE_PANEL_TAG: String = "genre-panel"
const val GENRE_PANEL_HEADING_TAG: String = "genre-panel:heading"
const val GENRE_PANEL_CLOSE_TAG: String = "genre-panel:close"

/** The test tag of a row's name in the panel's table, before its index. */
const val GENRES_ROW_PREFIX: String = "genres-row:"

private const val SEPARATOR = " · "
