package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.TracklistState
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.CardEntity
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
     * again for the album's tracklist, [group], or with none, for the track (as *Dates*' *Retry*).
     */
    data class Unreachable(
        val group: String?,
    ) : CoverBack

    /**
     * MusicBrainz has no tracklist for the album: it did not find the song, the album the player
     * names is none of its releases', or the album has no release with tracks to show.
     */
    data object NotFound : CoverBack

    /** The album's [discs], each with its tracks, and the track [playing] among them, when found. */
    data class Found(
        val discs: List<BackDisc>,
        val playing: TrackAt?,
    ) : CoverBack
}

/** A disc of the album, one with tracks: its [tracks] in order. */
internal data class BackDisc(
    val tracks: List<MbTrack>,
)

/** Where a track is in [CoverBack.Found.discs]: the disc's index, and the track's on it. */
internal data class TrackAt(
    val disc: Int,
    val track: Int,
)

/**
 * The album [header] names, by its release group, as the album's insight keys it
 * (`ItemInsights.entity`, [CardEntity.Album.of]): the one group of the track's releases the album's
 * name names (of several, the one album among them); from the run's resolution, else MusicBrainz's
 * kept result. Null with no album named, nothing resolved, or no one group named.
 */
internal fun albumGroup(
    track: TrackState?,
    header: NowPlaying,
): String? {
    val album = header.album?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val dates = track?.resolution?.releaseDates?.ifEmpty { null } ?: keptResult(track)?.releaseDates.orEmpty()
    val primary = track?.normalized?.primary.orEmpty()
    return CardEntity.Album.of(album, primary, dates, NAMES::albumKey).group
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
 * What the back shows for [track] beside [header] (#984), with [musicBrainzOn] as *Sources* has it,
 * and the tracklists looked up, [states]: MusicBrainz off first; then the track's own MusicBrainz state,
 * as *Dates* reads it ([NoDates.of]), until it is found; then the album's group ([albumGroup]), and its
 * tracklist as looked up, looking until it is asked.
 */
internal fun coverBack(
    track: TrackState?,
    header: NowPlaying,
    musicBrainzOn: Boolean,
    states: Map<String, TracklistState>,
): CoverBack =
    if (!musicBrainzOn) {
        CoverBack.Off
    } else {
        when (NoDates.of(track)) {
            NoDates.LOOKING -> CoverBack.Looking
            NoDates.OFF -> CoverBack.Off
            NoDates.UNREACHABLE -> CoverBack.Unreachable(group = null)
            NoDates.NOT_FOUND -> CoverBack.NotFound
            NoDates.NO_DATES ->
                albumGroup(track, header)?.let { group -> tracklistBack(group, states[group], track, header) }
                    ?: CoverBack.NotFound
        }
    }

// What the back shows for the album [group] once the track is found: its tracklist as looked up, [state].
private fun tracklistBack(
    group: String,
    state: TracklistState?,
    track: TrackState?,
    header: NowPlaying,
): CoverBack =
    when (state) {
        null, TracklistState.Looking -> CoverBack.Looking
        TracklistState.Failed -> CoverBack.Unreachable(group)
        TracklistState.NotFound -> CoverBack.NotFound
        is TracklistState.Found -> {
            val discs =
                state.tracklist.media
                    .filter { it.tracks.isNotEmpty() }
                    .map { BackDisc(it.tracks) }
            if (discs.isEmpty()) CoverBack.NotFound else CoverBack.Found(discs, playingIn(discs, track, header))
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
    val recording = track?.resolution?.recordingMbid ?: keptResult(track)?.recordingTags?.mbid
    val title = NAMES.key(track?.normalized?.title?.ifEmpty { null } ?: header.title)
    return find(discs) { recording != null && it.recordingMbid == recording }
        ?: find(discs) { title.isNotEmpty() && NAMES.key(it.title) == title }
}

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
