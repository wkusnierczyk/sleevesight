package app.sleevesight.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.TrackState

// The genre's panel (#558): its heading, the table, and where the genre comes from.

/**
 * The panel of [genre] (#558; frame B of the agreed mock-ups): its name as a heading, with *Close*
 * when there is [onClose]; then the table, a row per level in [GenreLevel] order, each row's name
 * (the track's title, the artist's name, the album's title) on one line above its labels, [genre]
 * marked wherever it shows; then where [genre] comes from, a line per row that has it, each source
 * linked to its page there, and MusicBrainz's licence (D12). Nothing when no row has [genre].
 */
@Composable
internal fun GenrePanel(
    track: TrackState,
    genre: String,
    onClose: (() -> Unit)? = null,
) {
    val genres = track.genres
    val name =
        genres.all
            .firstOrNull { it.genre.identity == genre }
            ?.genre
            ?.label ?: return
    val shape = RoundedCornerShape(12.dp)
    val fill = MaterialTheme.colorScheme.onSurface.copy(alpha = PANEL_FILL)
    Column(
        modifier =
            Modifier
                .testTag(GENRE_PANEL_TAG)
                .fillMaxWidth()
                .clip(shape)
                .background(fill, shape)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp)
                        .testTag(GENRE_PANEL_HEADING_TAG)
                        .semantics { heading() },
            )
            onClose?.let {
                TextButton(onClick = it, modifier = Modifier.testTag(GENRE_PANEL_CLOSE_TAG)) {
                    Text(stringResource(R.string.page_close), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // TODO(#523): what the genre is, and *Why is this …?*, between the heading and the table.
        Column(modifier = Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = RULE))
            Table(track, genre)
            Credits(genres, genre, several = track.normalized.artists.size > 1)
        }
    }
}

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
 * Where [genre] comes from: a line for each row that has it, naming the level ("From the track's
 * tags:"; with [several] credited artists, the artist), then each source, linked to its page there,
 * and for MusicBrainz its licence.
 */
@Composable
private fun Credits(
    genres: TrackGenres,
    genre: String,
    several: Boolean,
) {
    val licence = stringResource(R.string.home_genre_licence)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in genres.rows) {
            val label = row.labels.firstOrNull { it.genre.identity == genre } ?: continue
            val from =
                when (row.level) {
                    GenreLevel.TRACK -> stringResource(R.string.home_genre_from_track)
                    GenreLevel.ALBUM -> stringResource(R.string.home_genre_from_album)
                    GenreLevel.ARTIST ->
                        row.artist?.takeIf { several }?.let { stringResource(R.string.home_genre_from_named, it) }
                            ?: stringResource(R.string.home_genre_from)
                }
            LinkedText(
                listOf(TextPart.Words("$from ")) + sourceParts(label, licence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Each source that gave [label], linked to its page there where it has one, then its licence, if any.
private fun sourceParts(
    label: TrackLabel,
    licence: String,
): List<TextPart> =
    label.credits
        .map { credit ->
            val name = credit.source.credit
            listOf(credit.url?.let { TextPart.Link(name, it) } ?: TextPart.Words(name)) +
                credit.source.licenceUrl
                    ?.let { listOf(TextPart.Words(SEPARATOR), TextPart.Link(licence, it)) }
                    .orEmpty()
        }.reduceOrNull { all, next -> all + TextPart.Words(SEPARATOR) + next }
        .orEmpty()

/** The test tags of the genre's panel, its heading and its *Close*. */
const val GENRE_PANEL_TAG: String = "genre-panel"
const val GENRE_PANEL_HEADING_TAG: String = "genre-panel:heading"
const val GENRE_PANEL_CLOSE_TAG: String = "genre-panel:close"

/** The test tag of a row's name in the panel's table, before its index. */
const val GENRES_ROW_PREFIX: String = "genres-row:"

private const val SEPARATOR = " · "
private const val PANEL_FILL = 0.05f
private const val RULE = 0.12f
