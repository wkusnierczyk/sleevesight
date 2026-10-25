package app.sleevesight.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings

/**
 * The genre labels (#439; `design/design.md`, *Genre labels*), of the track, its artists and its
 * album. Labels of more than one level are a fold: folded, `Genres ▸` and the chips of the most
 * specific level ([TrackGenres.folded]); unfolded, `Genres ▾` and a table, a row per level with
 * labels, its name in a right-aligned column, an artist's row naming the artist before its chips.
 * Labels of one level show as its chips, with no fold. A tap on a chip shows where it came from:
 * the level, and each label source, linking the page there, with MusicBrainz's licence (D12); a
 * label only MusicBrainz gives opens nothing more. The genre cards are Phase 2. Chips are plain,
 * told apart by their fill, not by a hue per genre family.
 */
@Composable
internal fun Genres(
    track: TrackState?,
    settings: Settings?,
) {
    // *Show genres* off (#444): no genres are asked for, and none shown, not even that they are off.
    if (track == null || !track.gathered || settings?.showGenres == false) return
    val genres = track.genres
    // Folded again, and nothing selected, on each new track. The selection is kept by its level,
    // artist and genre, so it outlasts labels that land later, and the fold.
    var unfolded by rememberSaveable(track.normalized.lookupKey) { mutableStateOf(false) }
    var selected by rememberSaveable(track.normalized.lookupKey) { mutableStateOf<String?>(null) }
    val view = GenresView(genres, unfolded, selected)
    val open = view.open
    val off = settings != null && LabelSource.entries.none { settings.givesLabels(it) }
    val select = { label: PlacedLabel -> selected = if (open?.key == label.key) null else label.key }
    val fold = {
        unfolded = false
        if (view.folded.none { it.key == selected }) selected = null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            genres.isEmpty && off -> Small(stringResource(R.string.home_genres_off))
            genres.isEmpty -> Small(stringResource(R.string.home_genres_none))
            genres.oneLevel -> Chips(view.folded, open, select)
            view.table -> {
                Fold(unfolded = true, toggle = fold)
                Table(genres, open, select)
            }
            else -> Chips(view.folded, open, select) { Fold(unfolded = false) { unfolded = true } }
        }
        open?.let { Credits(it, several = track.normalized.artists.size > 1) }
    }
}

// The fold's control: `Genres` and its disclosure triangle, a button a screen reader says is collapsed or expanded.
@Composable
private fun Fold(
    unfolded: Boolean,
    toggle: () -> Unit,
) {
    val name = stringResource(R.string.home_genres)
    val state = stringResource(if (unfolded) R.string.home_genres_expanded else R.string.home_genres_collapsed)
    val symbol = stringResource(if (unfolded) R.string.home_fold_unfolded else R.string.home_fold_folded)
    Text(
        "$name $symbol",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .testTag(GENRES_FOLD_TAG)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClick = toggle)
                .clearAndSetSemantics {
                    contentDescription = name
                    stateDescription = state
                }.wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 4.dp),
    )
}

// [labels] as chips, after [before], the fold's control when there is one.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(
    labels: List<PlacedLabel>,
    open: PlacedLabel?,
    select: (PlacedLabel) -> Unit,
    before: (@Composable () -> Unit)? = null,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        before?.invoke()
        for (label in labels) GenreChip(label, selected = open?.key == label.key) { select(label) }
    }
}

/**
 * The unfolded table: a row per level with labels, in [GenreLevel] order, its name right-aligned
 * in a column as wide as the widest, its chips left-aligned beside it; an artist's row names the
 * artist first. A screen reader reads each row as one, "Track: Baroque Pop, Ballad", then its chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Table(
    genres: TrackGenres,
    open: PlacedLabel?,
    select: (PlacedLabel) -> Unit,
) {
    val style = MaterialTheme.typography.bodySmall
    val names = genres.rows.map { levelName(it.level) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = with(density) { names.maxOf { measurer.measure(it, style).size.width }.toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        genres.rows.forEachIndexed { index, row ->
            val said = rowWords(names[index], row)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    names[index],
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier =
                        Modifier
                            .testTag(GENRES_ROW_PREFIX + index)
                            .width(width)
                            .heightIn(min = ROW_HEIGHT)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .semantics { contentDescription = said },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    row.artist?.let { artist ->
                        Text(
                            stringResource(R.string.home_genres_artist, artist),
                            style = style,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // The row's name says it.
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    for (label in row.labels) {
                        val placed = PlacedLabel(row, label)
                        GenreChip(placed, selected = open?.key == placed.key) { select(placed) }
                    }
                }
            }
        }
    }
}

@Composable
private fun levelName(level: GenreLevel): String =
    stringResource(
        when (level) {
            GenreLevel.TRACK -> R.string.home_genres_track
            GenreLevel.ARTIST -> R.string.home_genres_artist_row
            GenreLevel.ALBUM -> R.string.home_genres_album
        },
    )

// What a screen reader says for a row: "Track: Baroque Pop, Ballad", "Artist, The Beatles: Rock, Pop".
@Composable
private fun rowWords(
    name: String,
    row: GenreRow,
): String {
    val labels = row.labels.joinToString(", ") { it.genre.label }
    return row.artist?.let { stringResource(R.string.home_genres_row_artist_words, name, it, labels) }
        ?: stringResource(R.string.home_genres_row_words, name, labels)
}

/** A plain chip (#439): a light fill in the text's colour, darker while its credits show. */
@Composable
private fun GenreChip(
    placed: PlacedLabel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content = MaterialTheme.colorScheme.onSurface
    val fill = content.copy(alpha = if (selected) SELECTED_FILL else FILL)
    val shape = RoundedCornerShape(16.dp)
    Text(
        placed.label.genre.label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier =
            Modifier
                .testTag(tagOf(placed))
                .minimumInteractiveComponentSize()
                .clip(shape)
                .background(fill, shape)
                .selectable(selected = selected, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Credits(
    placed: PlacedLabel,
    several: Boolean,
) {
    val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    val licence = stringResource(R.string.home_genre_licence)
    val label = placed.label
    val from =
        when (placed.row.level) {
            GenreLevel.TRACK -> R.string.home_genre_from_track
            GenreLevel.ARTIST -> R.string.home_genre_from
            GenreLevel.ALBUM -> R.string.home_genre_from_album
        }
    // With several credited artists, an artist's label names whose tags gave it.
    val artist = placed.row.artist?.takeIf { several }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Small(
            artist?.let { stringResource(R.string.home_genre_from_named, label.genre.label, it) }
                ?: stringResource(from, label.genre.label),
        )
        for (credit in label.credits) {
            val text =
                buildAnnotatedString {
                    val url = credit.url
                    if (url != null) {
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

/**
 * What the genres show: the [table] or the [folded] chips, and the chip whose credits are [open].
 * A selected chip that a later level would fold away (Last.fm's album tags landing under an
 * artist's chip the listener opened) keeps the table showing, until the listener folds it; a
 * selection whose chip is not shown (the listener folded it away) shows no credits.
 */
private class GenresView(
    genres: TrackGenres,
    unfolded: Boolean,
    selected: String?,
) {
    val folded: List<PlacedLabel> = genres.folded
    private val every = genres.rows.flatMap { row -> row.labels.map { PlacedLabel(row, it) } }
    private val kept = selected != null && folded.none { it.key == selected } && every.any { it.key == selected }
    val table: Boolean = (unfolded || kept) && !genres.oneLevel
    val open: PlacedLabel? = (if (table) every else folded).firstOrNull { it.key == selected }
}

// What a selection is kept by: the chip's level, artist and genre, not its row's other labels.
private val PlacedLabel.key: String get() = "${row.level}|${row.artist.orEmpty()}|${label.genre.identity}"

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The test tag of a genre label, before its level (`track`, `artist`, `album`), a colon, for an
 * artist's the artist and a colon, and its identity: `genre:artist:The Beatles:rock`.
 */
const val GENRE_PREFIX: String = "genre:"

private fun tagOf(placed: PlacedLabel): String {
    val artist =
        placed.row.artist
            ?.let { "$it:" }
            .orEmpty()
    return GENRE_PREFIX +
        placed.row.level.name
            .lowercase() + ":" + artist + placed.label.genre.identity
}

/** The test tag of the genres' fold control. */
const val GENRES_FOLD_TAG: String = "genres-fold"

/** The test tag of a row's name in the unfolded table, before its index. */
const val GENRES_ROW_PREFIX: String = "genres-row:"

// As tall as a chip's touch target, so a row's name sits level with its first line of chips.
private val ROW_HEIGHT = 48.dp
private const val FILL = 0.08f
private const val SELECTED_FILL = 0.24f
