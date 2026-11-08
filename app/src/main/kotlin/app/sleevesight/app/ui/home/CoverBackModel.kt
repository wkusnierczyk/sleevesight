package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.pipeline.TracklistState
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.MbTrack
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.SourceResult

// What the back of Home's cover shows (#984): the album's tracklist, or why it has none, worked out
// from what the pipeline publishes and the tracklists looked up, as plain functions.

/** What the back of the cover shows. */
internal sealed interface CoverBack {
    /** MusicBrainz is being asked, for the track or for the album's tracklist: "Looking on MusicBrainz…". */
    data object Looking : CoverBack

    /** MusicBrainz is switched off in Settings: "MusicBrainz is disabled.", and a link to *Settings → Sources*. */
    data object Off : CoverBack

    /**
     * MusicBrainz could not be reached: "MusicBrainz could not be reached.", with *Retry*, which asks
     * again for the album's tracklist, [key], or with none, for the track (as *Dates*' *Retry*).
     */
    data class Unreachable(
        val key: TracklistKey?,
    ) : CoverBack

    /**
     * MusicBrainz has no tracklist for the album: it did not find the song, the album the player
     * names is none of its releases', or the album has no release with tracks to show.
     */
    data object NotFound : CoverBack

    /**
     * MusicBrainz has several albums of the name the player gives, and nothing tells which one is
     * playing (#1062): "MusicBrainz reports several albums of this name for this artist. Sleevesight
     * could not decide which one this is." ([albumPick]).
     */
    data object Ambiguous : CoverBack

    /**
     * The album's [discs], each with its tracks, and the track [playing] among them, when found; of
     * [media] in the release, those with no track left out.
     */
    data class Found(
        val discs: List<BackDisc>,
        val playing: TrackAt?,
        val media: Int = discs.size,
    ) : CoverBack
}

/**
 * A disc of the album, one with tracks: its [position] in the release, as MusicBrainz numbers its
 * media (a disc with no track left out keeps the others' numbers), and its [tracks] in order.
 */
internal data class BackDisc(
    val position: Int,
    val tracks: List<MbTrack>,
)

/** Where a track is in [CoverBack.Found.discs]: the disc's index, and the track's on it. */
internal data class TrackAt(
    val disc: Int,
    val track: Int,
)

/**
 * The album [header] names, by its release groups ([AlbumPick.of]): from the run's resolution, else
 * MusicBrainz's kept result, with the player's year. Null with no album named, nothing resolved, or no
 * group named.
 */
internal fun albumPick(
    track: TrackState?,
    header: NowPlaying,
): AlbumPick? {
    val album = header.album?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val dates = track?.resolution?.releaseDates?.ifEmpty { null } ?: keptResult(track)?.releaseDates.orEmpty()
    val primary = track?.normalized?.primary.orEmpty()
    return AlbumPick.of(album, primary, dates, header.year?.toString(), NAMES::albumKey)
}

/**
 * The album [header] names as the back of the cover follows it: the back stays shown while this is
 * the same from one track to the next, and turns back to the cover when it changes (#984). By name,
 * as [NameMatcher.albumKey] reads it, so it does not change as the track resolves; null with no album.
 */
internal fun albumIdentity(header: NowPlaying): String? =
    header.album
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(NAMES::albumKey)

/**
 * The tracklist to ask for [track] beside [header] (#984): the album's group ([albumPick]), with the
 * others its name names, and the playing recording ([playingRecording]), whose release is shown; null
 * with no group, or several and none picked.
 */
internal fun tracklistKey(
    track: TrackState?,
    header: NowPlaying,
): TracklistKey? = (albumPick(track, header) as? AlbumPick.Groups)?.key(playingRecording(track))

/**
 * What the back shows for [track] beside [header] (#984), with [musicBrainzOn] as *Sources* has it,
 * and the tracklists looked up, [states]: MusicBrainz off first; then the track's own MusicBrainz state,
 * as *Dates* reads it ([NoDates.of]), until it is found; then the album's tracklist as looked up for
 * the playing recording ([tracklistKey]), looking until it is asked; when the album's name names several
 * groups and none is picked, [CoverBack.Ambiguous] ([albumPick]). While a new song of the album is
 * still looked up, the album's tracklist last found shows, its mark moved, rather than "Looking".
 */
internal fun coverBack(
    track: TrackState?,
    header: NowPlaying,
    musicBrainzOn: Boolean,
    states: Map<TracklistKey, TracklistState>,
): CoverBack {
    if (!musicBrainzOn) return CoverBack.Off
    return when (NoDates.of(track)) {
        NoDates.LOOKING -> CoverBack.Looking
        NoDates.OFF -> CoverBack.Off
        NoDates.UNREACHABLE -> CoverBack.Unreachable(key = null)
        NoDates.NOT_FOUND -> CoverBack.NotFound
        NoDates.NO_DATES ->
            when (val pick = albumPick(track, header)) {
                null -> CoverBack.NotFound
                AlbumPick.Ambiguous -> CoverBack.Ambiguous
                is AlbumPick.Groups -> tracklistBack(pick.key(playingRecording(track)), states, track, header)
            }
    }
}

// [key]'s state; while it is still looked up, the album's tracklist found last for another song of it.
private fun stateOf(
    key: TracklistKey,
    states: Map<TracklistKey, TracklistState>,
): TracklistState? {
    val state = states[key]
    return if (state == null || state == TracklistState.Looking) {
        states.entries.lastOrNull { it.key.group == key.group && it.value is TracklistState.Found }?.value ?: state
    } else {
        state
    }
}

// What the back shows for the album [key] once the track is found: its tracklist as looked up, of [states].
private fun tracklistBack(
    key: TracklistKey,
    states: Map<TracklistKey, TracklistState>,
    track: TrackState?,
    header: NowPlaying,
): CoverBack =
    when (val state = stateOf(key, states)) {
        null, TracklistState.Looking -> CoverBack.Looking
        TracklistState.Failed -> CoverBack.Unreachable(key)
        TracklistState.NotFound -> CoverBack.NotFound
        is TracklistState.Found -> {
            val discs =
                state.tracklist.media
                    .filter { it.tracks.isNotEmpty() }
                    .map { BackDisc(it.position, it.tracks) }
            val media = state.tracklist.media.size
            if (discs.isEmpty()) CoverBack.NotFound else CoverBack.Found(discs, playingIn(discs, track, header), media)
        }
    }

/**
 * Where the track playing is in [discs]: the track of its recording, as resolved (or kept with
 * MusicBrainz's result); else the first whose title is the track's, as [NameMatcher.key] reads titles.
 * Null when neither is on the album's release.
 */
private fun playingIn(
    discs: List<BackDisc>,
    track: TrackState?,
    header: NowPlaying,
): TrackAt? {
    val recording = playingRecording(track)
    val title = NAMES.key(track?.normalized?.title?.ifEmpty { null } ?: header.title)
    return find(discs) { recording != null && it.recordingMbid == recording }
        ?: find(discs) { title.isNotEmpty() && NAMES.key(it.title) == title }
}

// The playing recording, as resolved, else as kept with MusicBrainz's result; null when not known.
private fun playingRecording(track: TrackState?): String? =
    track?.resolution?.recordingMbid ?: keptResult(track)?.recordingTags?.mbid

private fun find(
    discs: List<BackDisc>,
    matches: (MbTrack) -> Boolean,
): TrackAt? {
    discs.forEachIndexed { d, disc ->
        val t = disc.tracks.indexOfFirst(matches)
        if (t >= 0) return TrackAt(d, t)
    }
    return null
}

// MusicBrainz's result for [track], as the run gave it or the retrieval cache kept it.
private fun keptResult(track: TrackState?): SourceResult? =
    (track?.sources?.get(SourceId.MUSICBRAINZ) as? SourceState.Done)?.result

// Names compared as the pipeline compares them.
private val NAMES = NameMatcher(JvmAccentFolder)
