package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.AlbumChoice
import app.sleevesight.app.pipeline.AlbumsKey
import app.sleevesight.app.pipeline.NamedAlbum
import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.pipeline.TracklistState
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.MbMedium
import app.sleevesight.core.resolution.MbReleaseGroup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.ReleaseDate

// Several albums of one name on the back of Home's cover (#1080): which they are, the listener's
// choice among them, and the list of them, as plain functions.

/**
 * The albums of the playing album's name, when it names several (#1080): the name's key ([name], by
 * which the listener's choice is kept), the [album]'s name as the player gives it, their release
 * [groups], the playing [recording] (null when not known), and the group the listener [chosen], when
 * it is one of them.
 */
internal data class SameName(
    val name: String,
    val album: String,
    val groups: List<String>,
    val recording: String?,
    val chosen: String? = null,
) {
    /** The albums to look up, each with the playing recording's release in it when it is on one. */
    val key: AlbumsKey get() = AlbumsKey(groups, recording)
}

/** Who picked the album shown of several of its name (#1080). */
internal enum class PickedBy {
    /** The listener: "Chosen by you from {n} albums of this name." */
    LISTENER,

    /** Sleevesight, by the player's year or the earliest date: "Picked by date from {n} albums of this name." */
    DATE,
}

/**
 * One of several albums of a name as the list on the back shows it (#1080), from its release's
 * tracklist ([NamedAlbum]): its [date] (null with none), the group's [type], how many [tracks], its
 * [labels], [country] and [format], each null or empty when MusicBrainz gives none, and where the
 * playing song is on it ([playingAt]: the track's number, with the disc's before it on a release of
 * several media, "2-3"; null when it is not on it). [back] is its tracklist, as the back shows one.
 */
internal data class AlbumRow(
    val group: String,
    val date: String?,
    val type: String?,
    val tracks: Int,
    val labels: List<String>,
    val country: String?,
    val format: String?,
    val playingAt: String?,
    val back: CoverBack.Found,
) {
    /** The year of [date], as MusicBrainz writes it first; null with no date. */
    val year: String? get() = date?.let(ReleaseDate::yearIn)
}

/**
 * The albums the name [header] gives names, when it names several (#1080), as [albumPick] reads the
 * track's releases: each release group once, in their order. With a choice kept for the name, of
 * [choices], the albums it was made among, the one chosen, whatever this song's releases name: it is
 * kept by the album's name and artist, and another song of the album may name fewer or others. Null
 * with no album, or with no choice and fewer than two groups named.
 */
internal fun sameName(
    track: TrackState?,
    header: NowPlaying,
    choices: Map<String, AlbumChoice> = emptyMap(),
): SameName? {
    val album = header.album?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val dates = track?.resolution?.releaseDates?.ifEmpty { null } ?: keptResult(track)?.releaseDates.orEmpty()
    val groups =
        ReleaseDate
            .named(album, dates, SAME_NAMES::albumKey)
            .map { it.group }
            .distinct()
    val name = choiceName(album, track?.normalized?.primary?.ifEmpty { null } ?: header.artists)
    val choice = choices[name]
    val among = choice?.let { (it.among.ifEmpty { groups } + it.group).distinct() } ?: groups
    return SameName(name, album, among, playingRecording(track), choice?.group)
        .takeIf { choice != null || groups.size > 1 }
}

/**
 * The key a choice among albums of one name is kept by (#1080): the [album]'s name and its [artist],
 * as [NameMatcher] reads them, so a choice holds for every song of the album however the player
 * writes it.
 */
internal fun choiceName(
    album: String,
    artist: String,
): String = SAME_NAMES.albumKey(album) + CHOICE_SEPARATOR + SAME_NAMES.key(artist)

/**
 * The albums of one name, [albums], as the list on the back shows them (#1080), with the song
 * [track] beside [header] marked on each it is on: those it is on first, then the earliest, an
 * undated one last, then in the order asked; one with no track left out.
 */
internal fun albumRows(
    albums: List<NamedAlbum>,
    track: TrackState?,
    header: NowPlaying,
): List<AlbumRow> =
    albums
        .mapNotNull { album -> albumRow(album, track, header) }
        .sortedWith(compareBy<AlbumRow>({ it.playingAt == null }, { it.date == null }, { it.date }))

// The row of [album], or null when its release has no track.
private fun albumRow(
    album: NamedAlbum,
    track: TrackState?,
    header: NowPlaying,
): AlbumRow? {
    val tracklist = album.tracklist
    val back = found(tracklist.media, track, header) ?: return null
    val at = back.playing
    val playingAt =
        at?.let {
            val number = back.discs[it.disc].tracks[it.track].position
            if (tracklist.media.size > 1) "${back.discs[it.disc].position}-$number" else "$number"
        }
    return AlbumRow(
        group = album.group,
        date = tracklist.date,
        type = typeOf(tracklist.group),
        tracks = back.discs.sumOf { it.tracks.size },
        labels = tracklist.labels,
        country = tracklist.country,
        format = formatOf(tracklist.media.mapNotNull { it.format }),
        playingAt = playingAt,
        back = back,
    )
}

// The group's types as MusicBrainz writes them together: "Album + Compilation", the primary first.
private fun typeOf(group: MbReleaseGroup?): String? =
    group
        ?.let { listOfNotNull(it.primaryType) + it.secondaryTypes }
        ?.distinct()
        ?.joinToString(" + ")
        ?.ifEmpty { null }

// The media's [formats] as one: "CD" for one, "2×CD" for several alike, else each kind once, joined.
private fun formatOf(formats: List<String>): String? =
    when {
        formats.isEmpty() -> null
        formats.distinct().size == 1 && formats.size > 1 -> "${formats.size}\u00d7${formats.first()}"
        else -> formats.distinct().joinToString(" + ")
    }

// [media]'s discs with tracks as the back shows them, the playing song marked; null with no track.
internal fun found(
    media: List<MbMedium>,
    track: TrackState?,
    header: NowPlaying,
): CoverBack.Found? {
    val discs = media.filter { it.tracks.isNotEmpty() }.map { BackDisc(it.position, it.tracks) }
    return if (discs.isEmpty()) null else CoverBack.Found(discs, playingIn(discs, track, header), media.size)
}

// The back once the track is found (#1080): the album the listener chose of several of its name, when
// they chose one; else the one [albumPick] picks, said to be picked by date when it picked one of
// several and that one shows; several not told apart say so.
internal fun pickedBack(
    track: TrackState?,
    header: NowPlaying,
    states: Map<TracklistKey, TracklistState>,
    choices: Map<String, AlbumChoice>,
): CoverBack {
    val same = sameName(track, header, choices)
    val recording = playingRecording(track)
    val chosen = same?.chosen
    if (chosen != null) {
        val back = tracklistBack(TracklistKey(chosen, recording), states, track, header, same)
        return (back as? CoverBack.Found)?.copy(albums = same, pickedBy = PickedBy.LISTENER) ?: back
    }
    return when (val pick = albumPick(track, header)) {
        null -> CoverBack.NotFound
        is AlbumPick.Ambiguous ->
            pick.key(recording)?.let { tracklistBack(it, states, track, header, same) }
                ?: CoverBack.Ambiguous(same)
        is AlbumPick.Groups -> {
            val key = pick.key(recording)
            val back = tracklistBack(key, states, track, header, same)
            val shownGroup = (stateOf(key, states) as? TracklistState.Found)?.tracklist?.group?.mbid
            // Picked by date only when it picked one of several, and that one shows.
            val byDate = same?.takeIf { pick.others.isNotEmpty() && shownGroup == pick.group }
            if (back is CoverBack.Found && byDate != null) {
                back.copy(albums = byDate, pickedBy = PickedBy.DATE)
            } else {
                back
            }
        }
    }
}

// Names compared as the pipeline compares them, as the back's are.
private val SAME_NAMES = NameMatcher(JvmAccentFolder)

// Between the album's name and its artist's in a choice's key: in neither.
private const val CHOICE_SEPARATOR = "\u001f"
