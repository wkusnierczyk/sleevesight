package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.ComposerPrefix
import app.sleevesight.core.pipeline.ArtistSplit
import app.sleevesight.core.pipeline.AuthorsName
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.artistSplit
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.sources.ArtistRoles

// The song's, the artist's and the album's insights under *Key insights* (#522): a row each, under
// the key insight and before *Sources*, unfolding in place under *Expandable boxes* or opening a page
// of its own under *Separate pages*.

/**
 * An item's row: the [item], and the [name] it goes by on the track playing; for the song, the
 * [version] the player's title names after it and the normalizer dropped (`2024 Remaster`, #1160),
 * said in its panel's heading and on its page, or null for none. [kind] is what its button and its
 * panel's title call it, [action] its button's action as a screen reader names it, each a string's
 * id: the item's own ([itemKindOf], [itemShowAction]), but for the Composer row and the Artist row
 * of a track whose roles tell a composer apart (#1199, [itemRows]; *Performer*, #1276), and the
 * song's row on a classical one (*Music*, #1331).
 */
internal class ItemRow(
    val item: InsightItem,
    val name: String,
    val version: String? = null,
    val kind: Int = itemKindOf(item),
    val action: Int = itemShowAction(item),
)

/**
 * The rows of [header], the track shown, as [normalized] reads it: the song by its title as under the
 * cover (the normalized title, the version suffix dropped, #1160; the player's whole title when that
 * leaves nothing), with the [ItemRow.version] dropped, and without the title's composer lead when
 * the Composer or Authors row names that composer (#1356, [songRow]); the artist by the primary artist (as the
 * normalizer reads the artists); the album by the player's album name; no row for one with no name
 * (an album the player does not name, a blank title).
 *
 * With [split], the roles telling a composer apart from the performers (#1199): a Composer row
 * before the artist's, by the composer, or with more than one author by all of them (*Composer*,
 * *Author* or *Authors*, [AuthorsName]); and the artist's by the lead performer, called *Performer*
 * (#1276), one performer even when others are known: the panel is about the lead, the others its
 * chips.
 */
internal fun itemRows(
    header: NowPlaying,
    normalized: NormalizedTrack,
    split: ArtistSplit? = null,
): List<ItemRow> =
    listOfNotNull(
        songRow(header, normalized, split),
        split?.let(::composerRow),
        split?.let(::performerRow)
            ?: normalized.primary.takeIf { it.isNotBlank() }?.let { ItemRow(InsightItem.ARTIST, it) },
        header.album
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { ItemRow(InsightItem.ALBUM, it) },
    )

// The Composer row of [split] (#1199): by the composer, or by every author, as its name says.
private fun composerRow(split: ArtistSplit): ItemRow {
    val (kind, action) =
        when (split.name) {
            AuthorsName.COMPOSER -> R.string.home_item_composer to R.string.home_item_show_composer
            AuthorsName.AUTHOR -> R.string.home_item_author to R.string.home_item_show_author
            AuthorsName.AUTHORS -> R.string.home_item_authors to R.string.home_item_show_authors
        }
    val name = if (split.name == AuthorsName.AUTHORS) split.authors.joinToString(", ") else split.composer
    return ItemRow(InsightItem.COMPOSER, name, kind = kind, action = action)
}

// The Artist row of [split] (#1199): *Performer*, by the lead performer, whoever else performs (#1276).
private fun performerRow(split: ArtistSplit): ItemRow =
    ItemRow(
        InsightItem.ARTIST,
        split.lead,
        kind = R.string.home_item_performer,
        action = R.string.home_item_show_performer,
    )

/**
 * [track]'s artists split between a Composer row and an Artist row (#1199, [ArtistSplit]) while it is
 * the track [normalized] reads, by the roles it knows ([TrackState.roles]); before then, by the
 * title's composer lead alone ([ArtistRoles.of]). Null for one Artist row.
 */
internal fun splitOf(
    track: TrackState?,
    normalized: NormalizedTrack,
): ArtistSplit? =
    // The track as the player names it (#1293: not a spelling the sources were asked in).
    if (track != null && track.played == normalized) {
        track.artistSplit(NAMES::sameName)
    } else {
        ArtistSplit.of(normalized, ArtistRoles.of(normalized), null, NAMES::sameName)
    }

// Names compared as the resolution compares them.
private val NAMES = NameMatcher(JvmAccentFolder)

// The song's row: its title as under the cover ([KeyData]), with the version dropped from it, and
// beside a Composer or Authors row ([split]) without the title's composer lead ("Barber: ",
// [NormalizedTrack.composer]) when that row names the composer it leads with (#1356), as searched
// ([NormalizedTrack.searchTitle]: the whole title when the lead is all there is); a lead naming a
// performer (`Gould: Interview`, Bach the composer) stays. So for a lead naming a composer the player
// does not credit whom MusicBrainz names for the work (#1376, [ArtistSplit.composers] from
// `ArtistRoles.uncredited`: "Barber: Adagio for Strings" with only the orchestra credited, which the
// normalizer finds no lead in). The player's whole title, and no version,
// when normalizing leaves no title. Called *Music* on a classical track ([ArtistSplit.classical], #1331:
// beside a Composer row, or an Authors row on a classical work), *Song* otherwise.
private fun songRow(
    header: NowPlaying,
    normalized: NormalizedTrack,
    split: ArtistSplit?,
): ItemRow? {
    if (header.title.isBlank()) return null
    val composers = split?.composers.orEmpty()
    val lead =
        normalized.composer ?: composers
            .filter { composer -> normalized.artists.none { NAMES.sameName(it, composer) } }
            .let { ComposerPrefix.leadNaming(normalized.title, it, NAMES::key) }
    val told = lead != null && composers.any { NAMES.sameName(it, lead.name) }
    val searched = if (told) normalized.copy(composer = lead).searchTitle else normalized.title
    val title = searched.takeIf { it.isNotEmpty() }
    val version = normalized.dropped.takeIf { title != null }
    return if (split?.classical == true) {
        ItemRow(
            InsightItem.SONG,
            title ?: header.title,
            version,
            kind = R.string.home_item_song_classical,
            action = R.string.home_item_show_song_classical,
        )
    } else {
        ItemRow(InsightItem.SONG, title ?: header.title, version)
    }
}

/** An item's kind, as its row and its page's title name it: [row]'s when given, else the item's own. */
@Composable
internal fun itemKind(
    item: InsightItem,
    row: ItemRow? = null,
): String = stringResource(row?.kind ?: itemKindOf(item))

/** The string of [item]'s own kind: *Song*, *Composer* (#1199), *Artist* (a named artist's too), *Album*. */
internal fun itemKindOf(item: InsightItem): Int =
    when (item) {
        InsightItem.SONG -> R.string.home_item_song
        InsightItem.COMPOSER -> R.string.home_item_composer
        InsightItem.ALBUM -> R.string.home_item_album
        else -> R.string.home_item_artist
    }

/** The action of [item]'s button as a screen reader names it under *Expandable boxes* (#894). */
internal fun itemShowAction(item: InsightItem): Int =
    when (item) {
        InsightItem.SONG -> R.string.home_item_show_song
        InsightItem.COMPOSER -> R.string.home_item_show_composer
        InsightItem.ALBUM -> R.string.home_item_show_album
        InsightItem.ARTIST -> R.string.home_item_show_artist
        else -> R.string.home_item_show_named
    }

/**
 * Which rows are unfolded, kept across recompositions and a recreated screen, as *Sources*' fold is
 * ([FoldMemory]): the items [open] on the track last shown, [track], and those a tap opened on it
 * ([tapped], not saved), which write at once under an automatic mode whatever held the track's own
 * writing back; of those, the ones whose tap's write is still to be asked for ([due], #948, saved
 * with the track it is of, so a recreated screen still writes it once, and never on another track),
 * once their state is known; and the rows whose name a tap showed whole ([whole], #894, not
 * saved).
 */
@Stable
internal class ItemFoldMemory(
    open: Set<InsightItem> = emptySet(),
    track: NowPlaying? = null,
    due: Set<InsightItem> = emptySet(),
) {
    var open: Set<InsightItem> by mutableStateOf(open)
    var track: NowPlaying? by mutableStateOf(track)
    var tapped: Set<InsightItem> by mutableStateOf(emptySet())
    var due: Set<InsightItem> by mutableStateOf(due)
    var whole: Set<InsightItem> by mutableStateOf(emptySet())

    companion object {
        /**
         * Saved as plain values: the items open, by name, the track as [savedTrack] keeps it, then the
         * items whose tap's write is due on it (#948), last, so a state saved before them, which ends
         * with the track, restores with none due.
         */
        val Saver: Saver<ItemFoldMemory, Any> =
            listSaver(
                save = { memory ->
                    listOf<Any?>(names(memory.open)) + savedTrack(memory.track) + names(memory.due)
                },
                restore = { saved ->
                    val due = (saved.getOrNull(DUE_AT) as? String).orEmpty()
                    ItemFoldMemory(items(saved[0] as String), restoredTrack(saved.drop(1)), items(due))
                },
            )

        // Where the items due are saved: after the items open and the track's five values.
        private const val DUE_AT = 6

        private fun names(items: Set<InsightItem>): String = items.joinToString(",") { it.name }

        private fun items(names: String): Set<InsightItem> =
            names
                .split(',')
                .mapNotNull { name -> InsightItem.entries.firstOrNull { it.name == name } }
                .toSet()
    }
}

/** The rows' memory, remembered above whatever comes and goes with the track, as *Sources*' is. */
@Composable
internal fun rememberItemFoldMemory(): ItemFoldMemory =
    rememberSaveable(saver = ItemFoldMemory.Saver) {
        ItemFoldMemory()
    }

/**
 * Which rows are unfolded beside [playing]: as kept while it plays on the track last shown; for a
 * new track, as [start] says (*Song, Artist and Album on a new track*): none, all, or as they were
 * left; none while the settings are not read ([start] null). Worked out as it is read, so a new
 * track never shows the last one's for a frame; [follow] then keeps them as the new track's.
 */
internal class ItemFolds(
    private val memory: ItemFoldMemory,
    private val playing: NowPlaying,
    private val start: NewTrackFold?,
) {
    private val same: Boolean get() = memory.track?.let { TrackIdentity.playsOn(it, playing) } == true

    /** The items unfolded. */
    val open: Set<InsightItem>
        get() =
            when {
                same -> memory.open
                start == NewTrackFold.AS_LEFT -> memory.open
                start == NewTrackFold.UNFOLDED -> InsightItem.entries.toSet()
                else -> emptySet()
            }

    /** Whether [item]'s name is shown whole in its row, on the track playing (#894); a new track's are clipped. */
    fun whole(item: InsightItem): Boolean = same && item in memory.whole

    /** A tap on [item]'s name in its row (#894): shown whole, or clipped again. */
    fun toggleWhole(item: InsightItem) {
        val now = if (same) memory.whole else emptySet()
        keep()
        memory.whole = if (item in now) now - item else now + item
    }

    /** Whether [item] was opened by a tap on the track playing. */
    fun tapped(item: InsightItem): Boolean = same && item in memory.tapped

    /**
     * The write of [item]'s opening by a tap on the track playing (#948): due until its state is known,
     * and only while it stays open; then taken, not asked again.
     */
    fun opening(item: InsightItem): OpeningWrite =
        if (same && item in memory.due) OpeningWrite(due = true) { memory.due -= item } else OpeningWrite.NONE

    /** A tap on [item]'s row: unfolded, its write due (#948), or folded. */
    fun toggle(item: InsightItem) {
        val now = open
        val opening = item !in now
        keep()
        memory.open = if (opening) now + item else now - item
        memory.tapped = if (opening) memory.tapped + item else memory.tapped - item
        memory.due = if (opening) memory.due + item else memory.due - item
    }

    /** On a new track, keeps the rows it shows as its own; not until the settings are read. */
    fun follow() {
        if (start == null || same) return
        keep()
    }

    // The rows as shown, kept as the track playing's: a new track's start with no taps, no writes due,
    // and every name clipped.
    private fun keep() {
        if (same) return
        memory.open = open
        memory.tapped = emptySet()
        memory.due = emptySet()
        memory.whole = emptySet()
        memory.track = playing
    }
}

/**
 * The rows' folds beside [playing], from [memory], a new track's as [start] says; a new track's are
 * kept as its own.
 */
@Composable
internal fun itemFolds(
    memory: ItemFoldMemory,
    playing: NowPlaying,
    start: NewTrackFold?,
): ItemFolds {
    val folds = ItemFolds(memory, playing, start)
    SideEffect { folds.follow() }
    return folds
}
