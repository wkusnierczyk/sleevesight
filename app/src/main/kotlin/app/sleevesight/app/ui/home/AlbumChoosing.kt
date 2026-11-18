package app.sleevesight.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.AlbumChoice
import app.sleevesight.app.pipeline.AlbumsKey
import app.sleevesight.app.pipeline.AlbumsState
import app.sleevesight.app.pipeline.NamedAlbum
import app.sleevesight.app.ui.theme.PillButton

// Several albums of one name on the back of Home's cover (#1080): the list of them, each opening its
// tracklist with *Select*, and the line above a tracklist picked among them, with *Change*; and the
// back's tracklist, under that line when there is one.

/**
 * What the back needs for the albums of one name (#1080): the albums looked up, by their key
 * ([states]), the way to [look] them up and to [retry] after a failure, the way to [choose] one (the
 * name's key and the group; null lets Sleevesight decide again), and the [rows] the list shows of the
 * albums found, in its order, with the playing [song]'s title for where it is on each.
 */
internal class AlbumChoosing(
    val states: Map<AlbumsKey, AlbumsState> = emptyMap(),
    val look: (AlbumsKey) -> Unit = {},
    val retry: (AlbumsKey) -> Unit = {},
    val choose: (String, AlbumChoice?) -> Unit = { _, _ -> },
    val rows: (List<NamedAlbum>) -> List<AlbumRow> = { emptyList() },
    val song: String = "",
)

/**
 * The note for several albums of one name not told apart (#1062), and under it *See the {n} albums*
 * ([see]), when the albums are known.
 */
@Composable
internal fun AmbiguousNote(
    albums: SameName?,
    see: () -> Unit,
) {
    Text(stringResource(R.string.home_tracklist_ambiguous), style = MaterialTheme.typography.bodyLarge)
    if (albums != null) {
        PillButton(stringResource(R.string.home_albums_see, albums.groups.size), see, Modifier.testTag(ALBUMS_SEE_TAG))
    }
}

/**
 * The line fixed above a tracklist picked of several albums of its name (#1080): who picked it
 * ([pickedBy]), of how many ([albums]), and *Change*, which opens the albums ([change]).
 */
@Composable
internal fun PickedLine(
    albums: SameName,
    pickedBy: PickedBy,
    change: () -> Unit,
) {
    val count = albums.groups.size
    val text =
        when (pickedBy) {
            PickedBy.LISTENER -> stringResource(R.string.home_albums_chosen, count)
            PickedBy.DATE -> stringResource(R.string.home_albums_by_date, count)
        }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp).testTag(ALBUMS_LINE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PillButton(stringResource(R.string.home_albums_change), change, Modifier.testTag(ALBUMS_CHANGE_TAG))
    }
}

/**
 * The albums of one name, [albums], on the back (#1080), as looked up when the listener opened them
 * (never here, so a song changing meanwhile asks nothing): the list of them ([opened] null), or the
 * one [opened], with *All albums* back to the list ([open] null) and *Select*, which keeps it as the
 * listener's choice and closes the albums ([done]); under the list, after a choice, *Let Sleevesight
 * decide*, which forgets it. While they are looked up, failed or none found, why there is no list, as
 * the back says it ([why]).
 */
@Suppress("LongParameterList") // The albums, the choosing, what is open, and the ways out.
@Composable
internal fun SameNamedAlbums(
    albums: SameName,
    choosing: AlbumChoosing,
    opened: String?,
    open: (String?) -> Unit,
    done: () -> Unit,
    why: @Composable (NoDates?, (() -> Unit)?) -> Unit,
) {
    val key = albums.key
    when (val state = choosing.states[key]) {
        null, AlbumsState.Looking -> why(NoDates.LOOKING, null)
        AlbumsState.Failed -> why(NoDates.UNREACHABLE) { choosing.retry(key) }
        AlbumsState.NotFound -> why(null, null)
        is AlbumsState.Found -> {
            val rows = choosing.rows(state.albums)
            val row = rows.firstOrNull { it.group == opened }
            if (row == null) {
                AlbumList(albums, rows, choosing, open, done)
            } else {
                OpenedAlbum(row, back = { open(null) }) {
                    choosing.choose(albums.name, AlbumChoice(row.group, albums.groups))
                    done()
                }
            }
        }
    }
}

// The list: its heading, a row per album, opening it, and, after a choice, *Let Sleevesight decide*.
@Composable
private fun AlbumList(
    albums: SameName,
    rows: List<AlbumRow>,
    choosing: AlbumChoosing,
    open: (String?) -> Unit,
    done: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().testTag(ALBUMS_LIST_TAG)) {
        Text(
            // Every album the name names, as the note and the line count them; one with no tracklist
            // has no row.
            stringResource(R.string.home_albums_heading, albums.groups.size, albums.album),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { it.group }) { row ->
                AlbumRowItem(row, mine = row.group == albums.chosen, song = choosing.song) { open(row.group) }
            }
            if (albums.chosen != null) {
                item {
                    PillButton(
                        stringResource(R.string.home_albums_decide),
                        onClick = {
                            choosing.choose(albums.name, null)
                            done()
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(ALBUMS_DECIDE_TAG),
                    )
                }
            }
        }
    }
}

/**
 * An album in the list: its year (or "No date"), type and track count; its label, country and
 * format; where the playing [song] is on it; "Your choice" when it is the listener's ([mine]). A
 * button that opens it, read as its lines in one, "2001, Compilation, 16 tracks, Capitol, United
 * States, CD, Eyes Without A Face: track 6, Your choice".
 */
@Composable
private fun AlbumRowItem(
    row: AlbumRow,
    mine: Boolean,
    song: String,
    open: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val first = firstLine(row)
    val second = listOfNotNull(row.labels.joinToString(", ").ifEmpty { null }, row.country, row.format)
    val third = row.playingAt?.let { stringResource(R.string.home_albums_playing_at, song, it) }
    val yours = stringResource(R.string.home_albums_yours)
    val action = stringResource(R.string.home_albums_open)
    val spoken = (first + second + listOfNotNull(third, yours.takeIf { mine })).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = open)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag(albumRowTag(row.group))
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(action) {
                    open()
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    first.joinToString(DOT),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (mine) {
                    Text(
                        yours,
                        Modifier
                            .background(colors.primaryContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp),
                        color = colors.onPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (second.isNotEmpty()) {
                Text(
                    second.joinToString(DOT),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            third?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.primary) }
        }
        Icon(
            painterResource(R.drawable.ic_keyboard_arrow_down),
            contentDescription = null,
            modifier = Modifier.size(20.dp).rotate(POINT_RIGHT),
            tint = colors.onSurfaceVariant,
        )
    }
}

// The year (or "No date"), the type and the track count.
@Composable
private fun firstLine(row: AlbumRow): List<String> =
    listOfNotNull(
        row.year ?: stringResource(R.string.home_albums_no_date),
        row.type,
        pluralStringResource(R.plurals.home_albums_tracks, row.tracks, row.tracks),
    )

/**
 * An album opened from the list: *All albums* ([back]) and *Select* ([select]) above its details
 * (year, type, label, country, format: a heading to a screen reader) and its tracklist, the playing
 * song marked, a page per disc as the back shows several.
 */
@Composable
private fun OpenedAlbum(
    row: AlbumRow,
    back: () -> Unit,
    select: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val details =
        listOfNotNull(
            row.year ?: stringResource(R.string.home_albums_no_date),
            row.type,
            row.labels.joinToString(", ").ifEmpty { null },
            row.country,
            row.format,
        ).joinToString(DOT)
    Column(Modifier.fillMaxSize().testTag(ALBUMS_OPENED_TAG)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(back, Modifier.padding(start = 12.dp).testTag(ALBUMS_ALL_TAG)) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.home_albums_all))
            }
            Spacer(Modifier.weight(1f))
            PillButton(
                stringResource(R.string.home_albums_select),
                select,
                Modifier.padding(end = 12.dp).testTag(ALBUMS_SELECT_TAG),
            )
        }
        Text(
            details,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp).semantics { heading() },
        )
        val found = row.back
        if (found.discs.size == 1) {
            DiscTracks(found.discs.single().tracks, found.playing?.track, Modifier.weight(1f).fillMaxWidth())
        } else {
            Column(Modifier.weight(1f).fillMaxWidth()) { DiscPages(found) }
        }
    }
}

// The album's tracklist, a single disc as one list, several a page each; picked of several albums of
// its name, under the line that says who picked it, whose *Change* opens them ([change], #1080). Where
// it was left kept in [tracklist] (#1097).
@Composable
internal fun FoundBack(
    back: CoverBack.Found,
    change: () -> Unit,
    tracklist: TracklistMemory,
) {
    Column(Modifier.fillMaxSize()) {
        val albums = back.albums
        val pickedBy = back.pickedBy
        if (albums != null && pickedBy != null) PickedLine(albums, pickedBy, change)
        val rest = Modifier.weight(1f).fillMaxWidth()
        if (back.discs.size == 1) {
            DiscTracks(back.discs.single().tracks, back.playing?.track, rest, 0, tracklist)
        } else {
            Column(rest) { DiscPages(back, tracklist) }
        }
    }
}

/**
 * The albums of one name the listener opened on the back (#1080): [albums], as they were when opened
 * (null while none are), and the one [opened] among them; [open] opens them and looks them up, then
 * and only then, [openAlbum] opens one (null: back to the list), and [close] closes them.
 */
internal class Browsing(
    val albums: SameName?,
    val opened: String?,
    val open: (SameName) -> Unit,
    val openAlbum: (String?) -> Unit,
    val close: () -> Unit,
)

/**
 * The albums opened on the back for the album and artist [albumArtist] (#1080, [choiceName]), kept
 * with the screen for them ([BrowsedAlbums]) and closed on another album or artist,
 * looked up through [choosing] on the tap that opens them; a list restored with the screen, whose
 * albums are no longer kept, asks once.
 */
@Composable
internal fun rememberBrowsing(
    albumArtist: String?,
    choosing: AlbumChoosing,
): Browsing {
    var saved by rememberSaveable(albumArtist, stateSaver = BrowsedAlbums) { mutableStateOf<SameName?>(null) }
    var opened by rememberSaveable(albumArtist) { mutableStateOf<String?>(null) }
    var asked by remember(albumArtist) { mutableStateOf(false) }
    // Only the albums of this album and artist: a state restored for another is not shown.
    val browsing = saved?.takeIf { it.name == albumArtist }
    LaunchedEffect(browsing?.key) {
        browsing?.key?.takeIf { !asked && it !in choosing.states }?.let(choosing.look)
        asked = true
    }
    return Browsing(
        albums = browsing,
        opened = opened,
        open = {
            saved = it
            asked = true
            choosing.look(it.key)
        },
        openAlbum = { opened = it },
        close = {
            saved = null
            opened = null
        },
    )
}

// The albums of one name opened (#1080), saved with the screen as their name, album, playing recording
// and chosen group (empty for none), then the groups.
private val BrowsedAlbums: Saver<SameName?, Any> =
    Saver(
        save = { it?.let { s -> arrayListOf(s.name, s.album, s.recording.orEmpty(), s.chosen.orEmpty()) + s.groups } },
        restore = { saved ->
            @Suppress("UNCHECKED_CAST")
            val fields = saved as List<String>
            SameName(
                name = fields[0],
                album = fields[1],
                groups = fields.drop(SAVED_FIELDS),
                recording = fields[2].ifEmpty { null },
                chosen = fields[SAVED_FIELDS - 1].ifEmpty { null },
            )
        },
    )

// The fields [BrowsedAlbums] saves before the groups.
private const val SAVED_FIELDS = 4

/** The test tag of an album's row in the list of several of one name (#1080). */
internal fun albumRowTag(group: String): String = "album_row_$group"

/** The test tag of *See the {n} albums*. */
internal const val ALBUMS_SEE_TAG: String = "albums_see"

/** The test tag of the line above a tracklist picked of several albums. */
internal const val ALBUMS_LINE_TAG: String = "albums_line"

/** The test tag of the line's *Change*. */
internal const val ALBUMS_CHANGE_TAG: String = "albums_change"

/** The test tag of the list of albums. */
internal const val ALBUMS_LIST_TAG: String = "albums_list"

/** The test tag of an album opened from the list. */
internal const val ALBUMS_OPENED_TAG: String = "albums_opened"

/** The test tag of *All albums*. */
internal const val ALBUMS_ALL_TAG: String = "albums_all"

/** The test tag of *Select*. */
internal const val ALBUMS_SELECT_TAG: String = "albums_select"

/** The test tag of *Let Sleevesight decide*. */
internal const val ALBUMS_DECIDE_TAG: String = "albums_decide"

private const val DOT = " · "

// The arrow that points down, turned to point right, at the end of an album's row.
private const val POINT_RIGHT = -90f
